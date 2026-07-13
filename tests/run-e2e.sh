#!/usr/bin/env bash
#
# Self-contained E2E runner, invoked from the `e2e-then-smoke` exec execution in pom.xml (the `-Dall`
# profile). It is bound to the `install` phase — strictly AFTER the `verify` phase where failsafe
# reports `*IT` failures — so it only runs once the integration tests have been confirmed green. If
# the ITs fail, the build stops at `verify` and this script is never reached.
#
# It is fully self-contained so its steps never share a Maven phase with another exec execution
# (which sortpom could reorder): it brings up its own test DB, starts the packaged fast-jar, polls
# /login until ready (max ~120s), then runs the Playwright suite. An EXIT trap always tears the jar
# and test DB down — on success OR failure — and the script exits with Playwright's own exit code, so
# a failing E2E run fails the Maven build.
#
# Args (passed positionally from the exec plugin so Maven properties resolve in the POM, not here):
#   $1  HTTP port for the app + E2E base URL
#   $2  Maven ${project.build.directory} (the `target` dir)
#   $3  Maven ${project.basedir} (project root; holds docker-compose.dev.yml and the tests/ dir)
#
# Readiness/E2E use 127.0.0.1 (not 'localhost') because the app binds IPv4 (0.0.0.0) and Node may
# otherwise resolve localhost to IPv6 ::1.

set -eu

PORT="$1"
TARGET_DIR="$2"
BASEDIR="$3"

COMPOSE_FILE="${BASEDIR}/docker-compose.dev.yml"
APP_PID=""

cleanup() {
  if [[ -n "${APP_PID}" ]]; then kill -9 "${APP_PID}" 2>/dev/null || true; fi
  docker compose -f "${COMPOSE_FILE}" rm -sf diurnal-db-dev >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Bring up the DB and block until its healthcheck passes.
docker compose -f "${COMPOSE_FILE}" up -d --wait diurnal-db-dev

java -Dquarkus.profile=test -Dquarkus.http.port="${PORT}" \
  -jar "${TARGET_DIR}/quarkus-app/quarkus-run.jar" >"${TARGET_DIR}/app.log" 2>&1 &
APP_PID=$!

READY=0
for _ in $(seq 1 60); do
  if curl -sf "http://127.0.0.1:${PORT}/login" >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 2
done

if [[ "${READY}" != 1 ]]; then
  echo "App failed to start — see ${TARGET_DIR}/app.log"
  exit 1
fi

# Run Playwright; its exit code becomes the script's (cleanup runs via the EXIT trap regardless),
# so a non-zero result fails the Maven build.
(cd "${BASEDIR}/tests" && BASE_URL="http://127.0.0.1:${PORT}" npm test)
