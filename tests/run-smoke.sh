#!/usr/bin/env bash
#
# Self-contained deployment-smoke runner, invoked by the `java` step of
# .github/scripts/lint_and_tests.sh — chained last, after `mvn clean install -Dall` and run-e2e.sh (and
# only if both passed). The smoke tier is deliberately NOT part of the Maven build (the `mvn` gate is
# unit + ITs + linters); the java step chains it on afterwards. Where run-e2e.sh exercises the packaged
# fast-jar on a full JDK, THIS script exercises the REAL production Docker image (built from the
# Dockerfile) so the suite catches bugs that live only in the distroless / jlink / non-root runtime —
# e.g. a missing jlink module (see the java.rmi incident), non-root write permissions, or a CSS-hash /
# favicon build-stage desync.
#
# It is fully self-contained and namespaced so it never collides with a running production stack
# (docker-compose.yml) or with run-e2e.sh: a dedicated compose project (`-p diurnal-smoke`), an
# ephemeral tmpfs DB, and host port 8082 (!= 8080 prod, != 8081 dev/E2E). An EXIT trap always tears
# the whole stack down — on success OR failure — and the script exits with Playwright's own exit
# code, so a failing smoke run fails the java step.
#
# It is fully independent of run-e2e.sh (disjoint ports, disjoint compose projects/DBs, each
# self-cleaning, each failing on its own), so running it alone is always safe.
#
# Args (passed positionally by the java step):
#   $1  host port to publish the app on (= Playwright base-URL port)
#   $2  project root (holds the Dockerfile; the compose file and suite live in tests/)
#
# Readiness/Playwright use 127.0.0.1 (not 'localhost') because Node may otherwise resolve localhost
# to IPv6 ::1 while the published port binds IPv4.

set -eu

PORT="$1"
BASEDIR="$2"

COMPOSE_FILE="${BASEDIR}/tests/docker-compose.smoke.yml"
PROJECT="diurnal-smoke"

cleanup() {
  # Best-effort + idempotent. `down` removes the tracked stack (containers, network, volumes); the
  # label-based sweep afterwards catches anything `down` missed — e.g. a container left half-created
  # by an interrupted `up`, which `down` won't always reap. Both are quiet so they never mask the
  # real (Playwright/Maven) exit code.
  docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" down -v --remove-orphans --timeout 10 >/dev/null 2>&1 || true
  local leftovers
  leftovers="$(docker ps -aq --filter "label=com.docker.compose.project=${PROJECT}" 2>/dev/null || true)"
  [[ -n "${leftovers}" ]] && echo "${leftovers}" | xargs -r docker rm -f >/dev/null 2>&1 || true
}
# EXIT alone is not enough: a SIGINT (Ctrl-C) or SIGTERM (Maven/CI killing the child when the build
# fails) would skip an EXIT-only trap and leak the stack. Trap the signals to a plain `exit`, which
# then fires the EXIT trap exactly once — so cleanup runs on normal exit, on test failure, AND on
# interruption.
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

# Build the real image and bring the stack up, blocking until the image's own HEALTHCHECK reports
# healthy (--wait). A boot failure (e.g. a missing jlink module) trips here and fails the script
# before Playwright even starts. --wait-timeout covers app start-period + retries, not the build.
SMOKE_PORT="${PORT}" docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" \
  up -d --build --wait --wait-timeout 240

# Run the smoke suite against the live container; a non-zero result trips `set -e` here (cleanup runs
# via the EXIT trap regardless), so a failing suite fails the Maven build before the checks below.
(cd "${BASEDIR}/tests" && BASE_URL="http://127.0.0.1:${PORT}" \
  npx playwright test --config playwright.smoke.config.ts)

# ── Readiness-gating check: the HEALTHCHECK is only valid if it reports UNHEALTHY when the app cannot
# serve traffic. Stop the database and assert (1) GET /api/v1/status (the exact URL the image's
# HEALTHCHECK probes) returns 503 with readiness DOWN, and (2) the container's own Docker health
# converges to `unhealthy`. A 200 — or a stuck `healthy` — here would mean the probe cannot detect an
# unready app. This runs only after the suite above passed, right before the EXIT-trap teardown, so
# leaving the stack degraded is harmless.
echo "[smoke] readiness-gating check: stopping the database…"
docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" stop db >/dev/null

STATUS_URL="http://127.0.0.1:${PORT}/api/v1/status"
http_code=""
body=""
# The connection pool may serve one last cached connection, so poll briefly until it observes the loss.
status_deadline=$((SECONDS + 30))
while (( SECONDS < status_deadline )); do
  response="$(curl -s -w $'\n%{http_code}' "${STATUS_URL}" 2>/dev/null || true)"
  http_code="${response##*$'\n'}"
  body="${response%$'\n'*}"
  if [[ "${http_code}" == "503" ]]; then
    break
  fi
  sleep 2
done
if [[ "${http_code}" != "503" ]]; then
  echo "[smoke] FAIL: /api/v1/status returned '${http_code}' (expected 503) with the DB stopped: ${body}"
  exit 1
fi
case "${body}" in
  *'"readiness":"DOWN"'*) : ;;
  *)
    echo "[smoke] FAIL: /api/v1/status did not report readiness DOWN with the DB stopped: ${body}"
    exit 1
    ;;
esac
echo "[smoke] /api/v1/status correctly reports 503 / readiness DOWN"

# The container's own HEALTHCHECK (interval 30s × 3 retries) must now converge to `unhealthy`.
app_cid="$(docker compose -p "${PROJECT}" -f "${COMPOSE_FILE}" ps -q app)"
health=""
health_deadline=$((SECONDS + 150))
while (( SECONDS < health_deadline )); do
  health="$(docker inspect -f '{{.State.Health.Status}}' "${app_cid}" 2>/dev/null || true)"
  if [[ "${health}" == "unhealthy" ]]; then
    break
  fi
  sleep 5
done
if [[ "${health}" != "unhealthy" ]]; then
  echo "[smoke] FAIL: container HEALTHCHECK did not report unhealthy after the DB stopped (last: '${health}')"
  exit 1
fi
echo "[smoke] container HEALTHCHECK correctly reports unhealthy — readiness gating verified"
