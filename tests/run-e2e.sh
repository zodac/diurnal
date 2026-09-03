#!/usr/bin/env bash
#
# Self-contained E2E runner, invoked by the `java` step of .github/scripts/lint_and_tests.sh — chained
# after `mvn clean install -Dall` (and only if it passed), so the fast-jar in $2/quarkus-app/ that this
# script starts is the one that build just produced. The E2E tier is deliberately NOT part of the Maven
# build itself (the `mvn` gate is unit + ITs + linters); the java step chains it on afterwards.
#
# It is fully self-contained: it brings up its own test DB, starts the packaged fast-jar, polls /login
# until ready (max ~120s), then runs the Playwright suite. Traps tear the jar and test DB down on
# success, on failure AND on interruption, and the script exits with Playwright's own exit code, so a
# failing E2E run fails the java step. A run killed outright (SIGKILL) can still leak the JVM, so the
# script also sweeps a previous run's leftovers on the way IN — see the pre-flight block below.
#
# Args (passed positionally by the java step):
#   $1  HTTP port for the app + E2E base URL
#   $2  the Maven `target` build directory (holds quarkus-app/quarkus-run.jar)
#   $3  project root (holds docker-compose.dev.yml and the tests/ dir)
#
# Readiness/E2E use 127.0.0.1 (not 'localhost') because the app binds IPv4 (0.0.0.0) and Node may
# otherwise resolve localhost to IPv6 ::1.

set -eu

PORT="$1"
TARGET_DIR="$2"
BASEDIR="$3"

# cd into BASEDIR and reference the compose file by a bare relative name rather than an absolute path.
# On Windows/Git Bash, BASEDIR is POSIX-style (e.g. "/c/Users/..."); passed as an absolute "-f" path,
# the Go-based docker compose CLI mis-resolves the leading "/" as "root of the current drive" instead
# of translating "/c" to "C:", producing "C:\c\Users\...\docker-compose.dev.yml" and failing to open it.
# A relative filename has no leading "/" to mangle, so it works unchanged on every platform. (TARGET_DIR
# below stays absolute, so it is unaffected by this cd.)
cd "${BASEDIR}"
COMPOSE_FILE="docker-compose.dev.yml"
# The dev DB's compose project. Namespaced rather than relying on a global container_name, so this tier
# cannot remove a database another build (or a `quarkus:dev` session) is using. Must match pom.xml,
# scripts/dev-up.sh and scripts/dev-teardown.sh.
DB_PROJECT="diurnal-dev"
APP_PID=""

# Records the running jar's PID so a LATER run can reap it if this one is killed outright (SIGKILL, a
# crashed shell, a lost session) and no trap gets to fire. Deliberately not under target/: the java step
# runs `mvn clean` before this script, which would delete the very evidence the sweep below needs.
PID_FILE="${BASEDIR}/tests/.e2e-app.pid"

cleanup() {
  if [[ -n "${APP_PID}" ]]; then kill -9 "${APP_PID}" 2>/dev/null || true; fi
  rm -f "${PID_FILE}"
  docker compose -p "${DB_PROJECT}" -f "${COMPOSE_FILE}" rm -sf diurnal-db-dev >/dev/null 2>&1 || true
}
# EXIT alone is not enough: a SIGINT (Ctrl-C), a SIGTERM (CI cancelling the job, or the java step's own
# stop_tier signalling this tier because a sibling failed first) or a SIGHUP would skip an EXIT-only trap
# and leak the app JVM on ${PORT} plus the test DB. Trap the signals to a plain `exit`, which then fires
# the EXIT trap exactly once - so cleanup runs on success, on failure, AND on interruption.
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

# Pre-flight sweep: reap the app JVM left behind by a previous run that was killed outright (where no
# trap could fire). Without this it still holds ${PORT}, and the readiness poll below would then bind to
# THAT stale app - silently running the whole suite against the previous build.
if [[ -f "${PID_FILE}" ]]; then
  stale_pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
  if [[ -n "${stale_pid}" ]] && kill -0 "${stale_pid}" 2>/dev/null; then
    echo "Reaping the app JVM left behind by a previous run (PID ${stale_pid})"
    kill -9 "${stale_pid}" 2>/dev/null || true
  fi
  rm -f "${PID_FILE}"
fi

# Anything still answering on ${PORT} is NOT ours (the sweep above cleared our own leftovers) - most
# likely a dev instance (scripts/dev-up.sh also uses 8081). Refuse rather than test an unknown app: the
# readiness poll cannot tell the difference, so the suite would otherwise pass or fail against whatever
# happens to be listening.
if curl -sf "http://127.0.0.1:${PORT}/login" >/dev/null 2>&1; then
  echo "Port ${PORT} is already serving an app that this script did not start (a dev instance?)."
  echo "Stop it, or re-run the java step with E2E_HTTP_PORT set to a free port."
  exit 1
fi

# Make sure the browser build this @playwright/test version wants is actually cached, BEFORE spinning
# anything up (a cold download must not hold the DB and app JVM open while it runs). Playwright pins its
# browser build per release, so a dependency bump silently invalidates the cache and every spec then dies
# with "Executable doesn't exist at .../chromium_headless_shell-<build>" - 200-odd identical failures for
# what is really one missing download. Installing here makes the run self-sufficient: it is a fast no-op
# (no network) when the pinned build is already present. `chromium` covers both projects (Desktop Chrome
# and the Galaxy S24 device preset are both Chromium) and pulls the headless shell with it. OS-level
# packages are deliberately NOT installed (`--with-deps` needs root); those are a one-off host setup step.
(cd "${BASEDIR}/tests" && npx playwright install chromium)

# Bring up the DB and block until its healthcheck passes.
docker compose -p "${DB_PROJECT}" -f "${COMPOSE_FILE}" up -d --wait diurnal-db-dev

# The -D pins outrank the repo-root .env (which Quarkus also reads at runtime, at a HIGHER priority
# than the bundled %test profile): a deployer flipping PASSWORD_AUTH_ENABLED/ENABLE_REGISTRATION there
# must not fail the E2E auth specs.
#
# OIDC is pinned off for the same reason, and it is not hypothetical: with OIDC_ENABLED=true in .env
# the suite ran against a live third-party issuer, which is neither what CI tests (no .env there) nor
# something the specs can depend on being up - and OIDC_AUTO_REDIRECT=true would send /login straight
# to the IdP and fail every auth spec. The two keys are pinned rather than the whole file ignored so a
# deliberate OIDC profile can still opt in.
java -Dquarkus.profile=test -Dquarkus.http.port="${PORT}" \
  -Dpassword.auth.enabled=true -Dregistration.enabled=true \
  -Dquarkus.oidc.tenant-enabled=false -Doidc.auto.redirect=false \
  -jar "${TARGET_DIR}/quarkus-app/quarkus-run.jar" >"${TARGET_DIR}/app.log" 2>&1 &
APP_PID=$!
echo "${APP_PID}" > "${PID_FILE}"

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
