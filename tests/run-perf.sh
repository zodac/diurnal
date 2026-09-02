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

# cd into tests/ and reference the compose file by a bare relative name rather than an absolute path
# built from BASEDIR. On Windows/Git Bash, BASEDIR is POSIX-style (e.g. "/c/Users/..."); passed as an
# absolute "-f" path, the Go-based docker compose CLI mis-resolves the leading "/" as "root of the
# current drive" instead of translating "/c" to "C:", producing "C:\c\Users\...\docker-compose.perf.yml"
# and failing to open it. A relative filename has no leading "/" to mangle, so it works unchanged on
# every platform. (Build context inside the compose file is resolved relative to the compose file's own
# location, not the CWD, so this cd doesn't affect it.)
cd "${BASEDIR}/tests"
COMPOSE_FILE="docker-compose.perf.yml"
PROJECT="diurnal-perf"
K6_IMAGE="grafana/k6:2.2.0"

# Load-shape + seed knobs (overridable from the environment). Kept modest by default so a local run
# finishes in a couple of minutes; bump them in CI for a heavier sweep.
SEED_ACTIONS="${PERF_SEED_ACTIONS:-50}"
SEED_LOG_DAYS="${PERF_SEED_LOG_DAYS:-90}"
# How many days carry a note. Its own knob rather than following SEED_LOG_DAYS, because journal LENGTH is
# the variable the notes-search scenarios exist to measure: that search cannot use an index (the content is
# ciphertext) and its cost is linear in the number of notes, so raising this is how the tier is pointed at
# a many-year journal. The default matches seed.mjs's own.
SEED_NOTE_DAYS="${PERF_SEED_NOTE_DAYS:-60}"
BOOT_BUDGET_S="${PERF_BOOT_BUDGET_S:-20}"
# Post-boot RSS ceiling. DERIVED, not picked. Native memory tracking on the real image puts the JVM at
# ~899MiB COMMITTED once booted - 686MiB of heap plus 213MiB of non-heap (metaspace 78, GC structures 74,
# code cache 23, symbols 19, classes 13) - of which ~545MiB is actually resident. Three measurements
# across two machines agreed within 3% (528, 535, 545).
#
# The heap dominates because -Xms is only the INITIAL size: G1 expands toward -Xmx during the Quarkus /
# Flyway / Hibernate startup burst and does not hand it back. This ceiling is therefore a CONSEQUENCE of
# the -Xmx1330m tests/docker-compose.perf.yml pins to match production, and moves with it - the previous
# 512 was arithmetically unreachable, since the heap alone commits more than that.
#
# 700 leaves ~28% over the observed high-water mark for host variance (GC and JIT thread counts scale
# with core count) while still catching the step change a jlink or runtime regression would produce.
# RE-DERIVE it the same way if the pinned heap changes - do not nudge it until it passes.
RSS_MAX_MB="${PERF_RSS_MAX_MB:-700}"

# Per-run scratch dir for the k6 seed->load handover state file. Created now, removed by cleanup.
# Deliberately placed INSIDE the project tree (tests/), not the system temp dir plain `mktemp -d`
# would use: on Windows/Git Bash, `mktemp -d`'s default location is under MSYS's internal "/tmp"
# mapping, which Docker Desktop's path translation does NOT recognise (unlike a drive-letter-rooted
# path such as "/c/Users/..." — see the `${BASEDIR}/tests/perf` mount below, which already works). A
# bare "/tmp/..." bind-mount source is forwarded as-is and silently resolved INSIDE the daemon's own
# Linux VM instead — no error, just an unrelated empty directory — so the load container fails with
# "no such file" even though the seed step wrote the file correctly on the host. `mktemp -d` still
# supplies the random-suffix template so the dir stays unique per run; only its location changes.
# `chmod 777`: the k6 container runs as a non-root UID and must be able to write the state file into
# the bind mount. The dir is ephemeral (removed by cleanup) and holds only throwaway test credentials,
# so 777 is harmless.
STATE_DIR="$(mktemp -d "${BASEDIR}/tests/.perf-state.XXXXXX")"
chmod 777 "${STATE_DIR}"
STATE_FILE="${STATE_DIR}/perf-state.json"

# Best-effort + idempotent (mirrors run-smoke.sh): `down -v` removes the tracked stack and the
# label-based sweep reaps anything an interrupted `up` left half-created. Both quiet so they never mask
# the real (k6) exit code.
sweep_stack() {
  docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" down -v --remove-orphans --timeout 10 >/dev/null 2>&1 || true
  local leftovers
  leftovers="$(docker ps -aq --filter "label=com.docker.compose.project=${PROJECT}" 2>/dev/null || true)"
  [[ -n "${leftovers}" ]] && echo "${leftovers}" | xargs -r docker rm -f >/dev/null 2>&1 || true
}

cleanup() {
  sweep_stack
  rm -rf "${STATE_DIR}" >/dev/null 2>&1 || true
}
# EXIT alone is not enough: a SIGINT/SIGTERM (Ctrl-C, or CI killing the child) would skip an EXIT-only
# trap and leak the stack. Trap the signals to a plain `exit`, which fires the EXIT trap exactly once.
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

# Pre-flight sweep: the traps cover every exit this script can observe, but a run killed outright
# (SIGKILL, a crashed shell) leaves its stack standing on port ${PORT} and its scratch dir behind. Clear
# both on the way IN as well, so each run starts from a known-clean host. The scratch dir created above
# is skipped - it belongs to THIS run.
sweep_stack
for stale_state in .perf-state.*; do
  if [[ -d "${stale_state}" && "${BASEDIR}/tests/${stale_state}" != "${STATE_DIR}" ]]; then
    rm -rf "${stale_state}" >/dev/null 2>&1 || true
  fi
done

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

# Post-boot RSS of the JVM ITSELF - jlink/runtime regressions show up here silently. Read from
# /proc/1/status inside the container (the entrypoint `exec`s java, so the JVM is PID 1), NOT from
# `docker stats`: its MemUsage is the cgroup's memory.current minus INACTIVE file pages, so it still
# counts ACTIVE page cache. That charges the app for the runtime image, jar and migrations it has just
# read off disk, and moves with the host's storage driver and cache pressure rather than with anything
# this tier could regress - which is the opposite of what a footprint guard is for.
app_cid="$(docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" ps -q app)"
rss_kib="$(docker exec "${app_cid}" /bin/busybox cat /proc/1/status 2>/dev/null | awk '/^VmRSS:/ {print $2}' || true)"

# A guard that cannot read its subject must SAY SO. This previously skipped silently whenever the value
# did not parse - and `docker stats` reports "0B" on some engines - so the ceiling went unenforced and
# the run passed regardless. A check that is absent is worse than one that is merely wrong, because
# nothing in the output says it did not happen.
if [[ ! "${rss_kib}" =~ ^[0-9]+$ ]]; then
  echo "[perf] FAIL: could not read the JVM's RSS from /proc/1/status in the app container"
  exit 1
fi
rss_mib=$((rss_kib / 1024))

# The container total is printed for context only, never asserted: it is the number an operator sees in
# `docker stats`, and the gap between the two is the page cache described above.
container_total="$(docker stats --no-stream --format '{{.MemUsage}}' "${app_cid}" 2>/dev/null | awk '{print $1}' || true)"
echo "[perf] post-boot JVM RSS: ${rss_mib}MiB (ceiling ${RSS_MAX_MB}MiB); container total ${container_total:-unknown}"
if [[ "${rss_mib}" -gt "${RSS_MAX_MB}" ]]; then
  echo "[perf] FAIL: post-boot RSS ${rss_mib}MiB exceeds ceiling ${RSS_MAX_MB}MiB"
  exit 1
fi

# ── 2. Seed the heavy account (k6, single iteration) ────────────────────────────
# The seed prints its handover state as a base64 PERFSTATE:…:ENDPERFSTATE stdout token (k6 can only
# write files from handleSummary, which can't see iteration state — see seed.mjs). Capture its output,
# echo it (so a direct run stays informative), then decode the token into the handover file for load.mjs.
echo "[perf] seeding ${SEED_ACTIONS} actions × ${SEED_LOG_DAYS} days and ${SEED_NOTE_DAYS} notes…"
seed_log="$(docker run --rm --network host \
  -v "${BASEDIR}/tests/perf":/scripts:ro \
  -e BASE_URL="http://127.0.0.1:${PORT}" \
  -e SEED_ACTIONS="${SEED_ACTIONS}" \
  -e SEED_LOG_DAYS="${SEED_LOG_DAYS}" \
  -e SEED_NOTE_DAYS="${SEED_NOTE_DAYS}" \
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
# Forward the load-shape + threshold knobs by NAME (no `=value`): docker passes each through from this
# process's environment only when it is actually set, so an unset knob falls through to load.mjs's own
# default rather than being clobbered with an empty string (Number("") === 0). This is how a caller —
# e.g. the CI step on a small shared runner — lowers the offered rate and relaxes the latency budget
# without editing load.mjs.
docker run --rm --network host \
  -v "${BASEDIR}/tests/perf":/scripts:ro \
  -v "${STATE_DIR}":/state:ro \
  -e PERF_STATE_FILE=/state/perf-state.json \
  -e PERF_RATE \
  -e PERF_VUS \
  -e PERF_DURATION \
  -e PERF_P95_TOLERANCE \
  -e PERF_DROPPED_MAX \
  "${K6_IMAGE}" run /scripts/load.mjs

echo "[perf] load suite passed all thresholds"
