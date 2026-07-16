#!/usr/bin/env bash
#
# Self-contained performance/load runner — the `perf` step of .github/scripts/lint_and_tests.sh. It is
# OPT-IN: unlike run-e2e.sh / run-smoke.sh it is NOT chained onto the `java` gate and NOT part of any
# `mvn` command (perf runs are long and environment-sensitive, so they must not fail every build). Run
# it explicitly: `.github/scripts/lint_and_tests.sh perf` (or invoke this script directly).
#
# Like run-smoke.sh it exercises the REAL production Docker image (built from the Dockerfile) rather
# than a fast-jar on a full JDK, because that is the runtime whose performance actually ships — its
# jlink JRE, JIT warm-up and cold-boot behaviour differ from a dev JDK. It measures three things:
#
#   1. Cold-boot performance      — container start -> first 200 /api/v1/status with readiness UP,
#                                    plus post-boot peak RSS. Asserted against BOOT_BUDGET_S / RSS_MAX_MB.
#   2. Steady-state API throughput — k6 drives one scenario per public-API use-case group
#                                    (= OpenApiSurfaceIT.PUBLIC_API_CONTRACT), each with its own latency
#                                    + error-rate threshold. A breached threshold fails k6 (exit != 0),
#                                    which fails this script.
#   3. Heavy-data edge cases       — the k6 seed populates a large account (SEED_ACTIONS × SEED_LOG_DAYS)
#                                    so the list / stats / calendar-feed scenarios surface N+1 / unindexed
#                                    / per-log-fan-out regressions rather than empty-DB best cases.
#
# Fully self-contained and namespaced so it never collides with a running prod stack, the smoke stack,
# or the dev/E2E jar: a dedicated compose project (`-p diurnal-perf`), an ephemeral tmpfs DB, and host
# port 8083 (!= 8080 prod, != 8081 dev/E2E, != 8082 smoke). An EXIT trap always tears the stack down —
# on success OR failure — and the script exits with k6's own exit code.
#
# k6 runs from its pinned Docker image (grafana/k6) on the host network, matching the containerised-tool
# pattern the lint steps already use (hadolint/grype/shellcheck). No host k6 install is required.
#
# Args (passed positionally by the perf step):
#   $1  host port to publish the app on (= k6 base-URL port)
#   $2  project root (holds the Dockerfile; the compose file and k6 scripts live in tests/)
#
# Readiness/k6 use 127.0.0.1 (not 'localhost') because Node/Go may otherwise resolve localhost to IPv6
# ::1 while the published port binds IPv4.

set -eu

PORT="$1"
BASEDIR="$2"

COMPOSE_FILE="${BASEDIR}/tests/docker-compose.perf.yml"
PROJECT="diurnal-perf"
K6_IMAGE="grafana/k6:2.1.0"

# Load-shape + seed knobs (overridable from the environment). Kept modest by default so a local run
# finishes in a couple of minutes; bump them in CI for a heavier sweep.
SEED_ACTIONS="${PERF_SEED_ACTIONS:-50}"
SEED_LOG_DAYS="${PERF_SEED_LOG_DAYS:-90}"
BOOT_BUDGET_S="${PERF_BOOT_BUDGET_S:-20}"
RSS_MAX_MB="${PERF_RSS_MAX_MB:-512}"

# Per-run scratch dir for the k6 seed->load handover state file. Created now, removed by cleanup.
# `mktemp -d` is mode 700 owned by the host user; the k6 container runs as a non-root UID, so it must
# be world-writable for the seed step to write the state file into the bind mount. The dir is ephemeral
# (removed by cleanup) and holds only throwaway test credentials, so 777 is harmless.
STATE_DIR="$(mktemp -d)"
chmod 777 "${STATE_DIR}"
STATE_FILE="${STATE_DIR}/perf-state.json"

cleanup() {
  # Best-effort + idempotent (mirrors run-smoke.sh): `down -v` removes the tracked stack, the
  # label-based sweep reaps anything an interrupted `up` left half-created, and the scratch dir goes
  # last. All quiet so they never mask the real (k6) exit code.
  docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" down -v --remove-orphans --timeout 10 >/dev/null 2>&1 || true
  local leftovers
  leftovers="$(docker ps -aq --filter "label=com.docker.compose.project=${PROJECT}" 2>/dev/null || true)"
  [[ -n "${leftovers}" ]] && echo "${leftovers}" | xargs -r docker rm -f >/dev/null 2>&1 || true
  rm -rf "${STATE_DIR}" >/dev/null 2>&1 || true
}
# EXIT alone is not enough: a SIGINT/SIGTERM (Ctrl-C, or CI killing the child) would skip an EXIT-only
# trap and leak the stack. Trap the signals to a plain `exit`, which fires the EXIT trap exactly once.
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

# ── 1. Cold-boot performance ────────────────────────────────────────────────────
# Build the real image and bring the DB + app up WITHOUT --wait, so we can time the app's boot to
# readiness ourselves. `up -d` returns once the containers are created; the app then boots behind its
# own HEALTHCHECK. Time from here to the first 200 / readiness UP on the exact status URL.
echo "[perf] building the production image and starting the stack…"
PERF_PORT="${PORT}" docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" up -d --build

STATUS_URL="http://127.0.0.1:${PORT}/api/v1/status"
boot_start="${SECONDS}"
ready=0
boot_deadline=$((SECONDS + BOOT_BUDGET_S))
while (( SECONDS <= boot_deadline )); do
  response="$(curl -s -w $'\n%{http_code}' "${STATUS_URL}" 2>/dev/null || true)"
  http_code="${response##*$'\n'}"
  body="${response%$'\n'*}"
  if [[ "${http_code}" == "200" && "${body}" == *'"readiness":"UP"'* ]]; then
    ready=1
    break
  fi
  sleep 1
done
boot_elapsed=$((SECONDS - boot_start))

if [[ "${ready}" != 1 ]]; then
  echo "[perf] FAIL: app did not reach readiness UP within ${BOOT_BUDGET_S}s (last: '${http_code:-none}')"
  exit 1
fi
echo "[perf] cold boot to readiness: ${boot_elapsed}s (budget ${BOOT_BUDGET_S}s)"

# Post-boot peak RSS of the app container — jlink/runtime regressions show up here silently. Read the
# raw byte value from docker stats and convert to MiB. Non-fatal to READ (some engines report 0 briefly).
app_cid="$(docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" ps -q app)"
mem_bytes="$(docker stats --no-stream --format '{{.MemUsage}}' "${app_cid}" 2>/dev/null | awk '{print $1}' || true)"
echo "[perf] post-boot memory usage: ${mem_bytes:-unknown} (ceiling ${RSS_MAX_MB}MiB)"
# Parse "NNNMiB" / "N.NGiB" -> integer MiB for the assertion; skip the check if the format is unexpected.
rss_mib="$(
  awk -v v="${mem_bytes}" 'BEGIN{
    if (v ~ /GiB$/) { sub(/GiB$/,"",v); printf("%d", v*1024) }
    else if (v ~ /MiB$/) { sub(/MiB$/,"",v); printf("%d", v) }
    else { print "" }
  }' 2>/dev/null || true
)"
if [[ -n "${rss_mib}" && "${rss_mib}" -gt "${RSS_MAX_MB}" ]]; then
  echo "[perf] FAIL: post-boot RSS ${rss_mib}MiB exceeds ceiling ${RSS_MAX_MB}MiB"
  exit 1
fi

# ── 2. Seed the heavy account (k6, single iteration) ────────────────────────────
# The seed prints its handover state as a base64 PERFSTATE:…:ENDPERFSTATE stdout token (k6 can only
# write files from handleSummary, which can't see iteration state — see seed.mjs). Capture its output,
# echo it (so a direct run stays informative), then decode the token into the handover file for load.mjs.
echo "[perf] seeding ${SEED_ACTIONS} actions × ${SEED_LOG_DAYS} days…"
seed_log="$(docker run --rm --network host \
  -v "${BASEDIR}/tests/perf":/scripts:ro \
  -e BASE_URL="http://127.0.0.1:${PORT}" \
  -e SEED_ACTIONS="${SEED_ACTIONS}" \
  -e SEED_LOG_DAYS="${SEED_LOG_DAYS}" \
  "${K6_IMAGE}" run /scripts/seed.mjs 2>&1)" || {
    echo "${seed_log}"
    echo "[perf] FAIL: seed run errored"
    exit 1
  }
echo "${seed_log}"

# Extract the base64 handover token (a '#' sed delimiter so the '/' in the base64 alphabet is literal).
state_b64="$(printf '%s\n' "${seed_log}" \
  | sed -n 's#.*PERFSTATE:\([A-Za-z0-9+/=]*\):ENDPERFSTATE.*#\1#p' | head -1 || true)"
if [[ -z "${state_b64}" ]]; then
  echo "[perf] FAIL: seed did not emit a PERFSTATE handover token"
  exit 1
fi
printf '%s' "${state_b64}" | base64 -d > "${STATE_FILE}" 2>/dev/null || {
    echo "[perf] FAIL: could not decode the PERFSTATE handover token"
    exit 1
  }
if [[ ! -s "${STATE_FILE}" ]]; then
  echo "[perf] FAIL: decoded handover state file is empty (${STATE_FILE})"
  exit 1
fi

# ── 3. Steady-state load (k6, thresholds = the gate) ────────────────────────────
# k6's exit code is non-zero iff a threshold was breached; `set -e` turns that into this script's exit
# code (cleanup runs via the EXIT trap regardless), so a perf regression fails the perf step.
echo "[perf] running the load suite…"
docker run --rm --network host \
  -v "${BASEDIR}/tests/perf":/scripts:ro \
  -v "${STATE_DIR}":/state:ro \
  -e PERF_STATE_FILE=/state/perf-state.json \
  "${K6_IMAGE}" run /scripts/load.mjs

echo "[perf] load suite passed all thresholds"
