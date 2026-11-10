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

# The JDK image to boot the jar in. lint_and_tests.sh sets it to the same pinned image its Maven tier
# compiles with (MVN_JDK_IMAGE), so the JVM that runs the jar is the one that built it, whatever `java` the
# host has - or none. A host JDK older than the jar's class-file release cannot load it, and the Quarkus
# launcher swallows that error, exiting 1 with nothing written to app.log. Unset (a hand-run of this
# script), the jar boots on the host's `java` as before, behind check_java_release below.
JAVA_IMAGE="${E2E_JAVA_IMAGE:-}"
# The container joins the database's compose network and reaches it by SERVICE NAME, exactly as the Maven
# tier's container does (lint_and_tests.sh's DEV_DB_NETWORK/DEV_DB_SERVICE); compose derives the network
# name from the project, so the two move together. A FIXED container name lets the next run reap one a
# SIGKILLed run left behind: killing the `docker run` client does not stop the container it started.
DB_SERVICE="diurnal-db-dev"
DB_NETWORK="${DB_PROJECT}_default"
APP_CONTAINER="diurnal-gate-e2e-app"

# Whether the Playwright suite runs in the Playwright image too (tests/playwright-docker.sh), which
# lint_and_tests.sh sets alongside E2E_JAVA_IMAGE so the tier needs nothing on the host but Docker. It shares
# the APP container's network namespace, so it needs the containerised app.
PLAYWRIGHT_IN_DOCKER="${PLAYWRIGHT_IN_DOCKER:-false}"
PLAYWRIGHT_CONTAINER="diurnal-gate-e2e-playwright"
PLAYWRIGHT_MODULES_VOLUME="diurnal-gate-e2e-node-modules"
if [[ "${PLAYWRIGHT_IN_DOCKER}" == true ]]; then
  if [[ -z "${JAVA_IMAGE}" ]]; then
    echo "PLAYWRIGHT_IN_DOCKER=true needs E2E_JAVA_IMAGE: the suite joins the containerised app's network."
    exit 1
  fi
  # shellcheck source=tests/playwright-docker.sh
  source "${BASEDIR}/tests/playwright-docker.sh"
fi

# Records the running jar's PID so a LATER run can reap it if this one is killed outright (SIGKILL, a
# crashed shell, a lost session) and no trap gets to fire. Deliberately not under target/: the java step
# runs `mvn clean` before this script, which would delete the very evidence the sweep below needs.
PID_FILE="${BASEDIR}/tests/.e2e-app.pid"

cleanup() {
  if [[ -n "${APP_PID}" ]]; then kill -9 "${APP_PID}" 2>/dev/null || true; fi
  # Before the database: a container still attached to its network blocks the compose teardown.
  if [[ "${PLAYWRIGHT_IN_DOCKER}" == true ]]; then docker rm -f "${PLAYWRIGHT_CONTAINER}" >/dev/null 2>&1 || true; fi
  if [[ -n "${JAVA_IMAGE}" ]]; then docker rm -f "${APP_CONTAINER}" >/dev/null 2>&1 || true; fi
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
if [[ "${PLAYWRIGHT_IN_DOCKER}" == true ]]; then docker rm -f "${PLAYWRIGHT_CONTAINER}" >/dev/null 2>&1 || true; fi
if [[ -n "${JAVA_IMAGE}" ]]; then docker rm -f "${APP_CONTAINER}" >/dev/null 2>&1 || true; fi

# Anything still answering on ${PORT} is NOT ours (the sweep above cleared our own leftovers) - most
# likely a dev instance (scripts/dev-up.sh also uses 8081). Refuse rather than test an unknown app: the
# readiness poll cannot tell the difference, so the suite would otherwise pass or fail against whatever
# happens to be listening.
if curl -sf "http://127.0.0.1:${PORT}/login" >/dev/null 2>&1; then
  echo "Port ${PORT} is already serving an app that this script did not start (a dev instance?)."
  echo "Stop it, or re-run the java step with E2E_HTTP_PORT set to a free port."
  exit 1
fi

# Refuse a host `java` older than the release the jar was compiled for - only when booting on the host
# (JAVA_IMAGE unset), since the gate's container runs the very JDK that compiled the jar. An older JVM fails
# the boot SILENTLY: the UnsupportedClassVersionError is swallowed by the Quarkus launcher, which exits 1
# having written nothing at all to app.log. The class-file major is read from the app's own jar (major = feature release + 44);
# the check is skipped, rather than guessed at, when `unzip` is unavailable or nothing can be read.
check_java_release() {
  [[ -z "${JAVA_IMAGE}" ]] || return 0
  local app_jar class_name major="" required host_release java_path
  command -v unzip >/dev/null 2>&1 || return 0
  for app_jar in "${TARGET_DIR}"/quarkus-app/app/*.jar; do
    [[ -f "${app_jar}" ]] || return 0
    class_name="$(unzip -Z1 "${app_jar}" 2>/dev/null | grep -m 1 '\.class$' || true)"
    [[ -n "${class_name}" ]] || return 0
    major="$(unzip -p "${app_jar}" "${class_name}" 2>/dev/null | od -An -j6 -N2 -tu1 | awk '{ print $1 * 256 + $2 }' || true)"
    break
  done
  # Every read above tolerates failure; an unreadable result fails these shape checks and skips the check.
  [[ "${major}" =~ ^[0-9]+$ ]] && (( major > 44 )) || return 0
  required=$(( major - 44 ))
  host_release="$(java -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.specification.version/ { print $2 }' || true)"
  [[ "${host_release}" =~ ^[0-9]+$ ]] || return 0
  if (( host_release < required )); then
    java_path="$(command -v java)" || java_path="java"
    echo "The app was compiled for Java ${required}, but the host 'java' (${java_path}) is Java ${host_release}."
    echo "The Maven tier builds in the gate's own JDK ${required} container; this tier boots the jar on the host,"
    echo "where an older JVM exits silently with code 1. Install JDK ${required}+ and put it first on PATH."
    return 1
  fi
}
check_java_release

# Make sure the browser build this @playwright/test version wants is actually cached, BEFORE spinning
# anything up (a cold download must not hold the DB and app JVM open while it runs). Playwright pins its
# browser build per release, so a dependency bump silently invalidates the cache and every spec then dies
# with "Executable doesn't exist at .../chromium_headless_shell-<build>" - 200-odd identical failures for
# what is really one missing download. Installing here makes the run self-sufficient: it is a fast no-op
# (no network) when the pinned build is already present. `chromium` covers both projects (Desktop Chrome
# and the Galaxy S24 device preset are both Chromium) and pulls the headless shell with it. OS-level
# packages are deliberately NOT installed (`--with-deps` needs root); those are a one-off host setup step.
# In the Playwright image the browsers are already baked in at the matching build; what it needs instead is
# the image itself and the suite's dependencies, prepared here for the same reason.
if [[ "${PLAYWRIGHT_IN_DOCKER}" == true ]]; then
  prepare_playwright_docker "${PLAYWRIGHT_MODULES_VOLUME}"
else
  (cd "${BASEDIR}/tests" && npx playwright install chromium)
fi

# Pull the JDK image up front, so a cold download neither lands in app.log nor eats into the 120s readiness
# budget below. A no-op once cached.
if [[ -n "${JAVA_IMAGE}" ]]; then
  docker pull -q "${JAVA_IMAGE}" >/dev/null
fi

# Bring up the DB and block until its healthcheck passes.
docker compose -p "${DB_PROJECT}" -f "${COMPOSE_FILE}" up -d --wait diurnal-db-dev

# The -D pins outrank the repo-root .env (which Quarkus also reads at runtime, at a HIGHER priority
# than the bundled %test profile): a deployer flipping PASSWORD_AUTH_ENABLED/ENABLE_LOCAL_REGISTRATION
# there must not fail the E2E auth specs.
#
# OIDC is pinned off for the same reason, and it is not hypothetical: with OIDC_ENABLED=true in .env
# the suite ran against a live third-party issuer, which is neither what CI tests (no .env there) nor
# something the specs can depend on being up - and OIDC_AUTO_REDIRECT=true would send /login straight
# to the IdP and fail every auth spec. The two keys are pinned rather than the whole file ignored so a
# deliberate OIDC profile can still opt in.
#
# Console logging is pinned ON for the same reason: app.log below is the ONLY record of this JVM, and an
# override that silences the console (QUARKUS_LOG_CONSOLE_ENABLED=false in the environment or .env) leaves
# it at 0 bytes - a startup failure then explains nothing, which is exactly how one was once reported.
APP_LOG="${TARGET_DIR}/app.log"
JAVA_ARGS=(-Dquarkus.profile=test -Dquarkus.http.port="${PORT}"
  -Dpassword.auth.enabled=true -Dregistration.local.enabled=true
  -Dquarkus.oidc.tenant-enabled=false -Doidc.auto.redirect=false
  -Dquarkus.log.console.enabled=true
  -jar "${TARGET_DIR}/quarkus-app/quarkus-run.jar")
if [[ -n "${JAVA_IMAGE}" ]]; then
  # The repo is mounted AT ITS HOST PATH and is the working directory, so the jar path above and the
  # repo-root .env (which Quarkus reads from the working directory) resolve exactly as they do on the host.
  # The port is published on 127.0.0.1 only, which is all the readiness poll and Playwright need. It runs
  # as the host UID so nothing it writes is root-owned; that UID has no passwd entry, hence HOME.
  HOST_UID="$(id -u)"
  HOST_GID="$(id -g)"
  DOCKER_ARGS=(--rm --name "${APP_CONTAINER}" --network "${DB_NETWORK}"
    -p "127.0.0.1:${PORT}:${PORT}"
    -v "${BASEDIR}:${BASEDIR}" -w "${BASEDIR}"
    -e HOME=/tmp -e "DB_HOST=${DB_SERVICE}"
    -u "${HOST_UID}:${HOST_GID}")
  # A target directory outside the repo (a hand-run with a different $2) needs its own mount.
  if [[ "${TARGET_DIR}" != "${BASEDIR}"/* ]]; then DOCKER_ARGS+=(-v "${TARGET_DIR}:${TARGET_DIR}"); fi
  # Not detached: the client stays attached, so its output is the JVM's (into app.log), its exit code is
  # the JVM's, and APP_PID below tracks the app's lifetime just as it does for a host JVM.
  docker run "${DOCKER_ARGS[@]}" "${JAVA_IMAGE}" java "${JAVA_ARGS[@]}" >"${APP_LOG}" 2>&1 &
else
  java "${JAVA_ARGS[@]}" >"${APP_LOG}" 2>&1 &
fi
APP_PID=$!
echo "${APP_PID}" > "${PID_FILE}"

# Explains a dead or unready app IN THIS SCRIPT'S OUTPUT, which is what the gate captures and replays in its
# failure excerpt - a bare "see app.log" pointed at a file that could be empty and said nothing else. Prints
# the JVM's exit status (a signal is named, since SIGKILL with an empty log is the OOM killer's signature),
# which `java` ran, the tail of app.log (or an explicit note that it is empty), and the database's state.
# $1 is the one-line reason; $2 the JVM's exit code, empty while it is still running.
report_app_failure() {
  local reason="$1" app_rc="${2:-}" signal_name java_path java_version errors line
  echo ""
  echo "E2E app failure: ${reason}"
  if [[ -z "${app_rc}" ]]; then
    echo "  JVM (PID ${APP_PID}): still running, but never answered GET /login on 127.0.0.1:${PORT}"
  elif (( app_rc > 128 )); then
    signal_name="$(kill -l "$(( app_rc - 128 ))" 2>/dev/null)" || signal_name="?"
    echo "  JVM (PID ${APP_PID}): killed by signal $(( app_rc - 128 )) (${signal_name})" \
      "- SIGKILL with an empty log usually means the kernel OOM killer; check 'dmesg' / 'journalctl -k'"
  else
    echo "  JVM (PID ${APP_PID}): exited with code ${app_rc}"
  fi
  if [[ -n "${JAVA_IMAGE}" ]]; then
    echo "  java: in container ${APP_CONTAINER}, image ${JAVA_IMAGE} (the PID above is its 'docker run' client)"
  else
    java_path="$(command -v java)" || java_path="NOT ON PATH"
    java_version="$(java -version 2>&1)" || true
    echo "  java: ${java_path} (${java_version%%$'\n'*})"
  fi
  if [[ -s "${APP_LOG}" ]]; then
    # The cause sits at the TOP of a startup stack trace, which a tail scrolls away; pull the ERROR lines
    # and the `Caused by:` chain out first so the root cause is the first thing read.
    errors="$(sed -e 's/\x1b\[[0-9;]*m//g' "${APP_LOG}" \
      | grep -E '\[(ERROR|FATAL) *\]|^Caused by:|^Error:|Exception: ' | head -n 20)" || true
    echo "  Errors in ${APP_LOG}:"
    if [[ -n "${errors}" ]]; then
      while IFS= read -r line; do echo "    > ${line}"; done <<<"${errors}"
    else
      echo "    (none logged - see the tail below)"
    fi
    echo "  Last 60 lines of ${APP_LOG}:"
    # Quarkus colours its console output; strip the escapes so the excerpt reads cleanly in a captured log.
    sed -e 's/\x1b\[[0-9;]*m//g' -e 's/^/    | /' "${APP_LOG}" | tail -n 60 || true
  else
    echo "  ${APP_LOG} is EMPTY: the JVM wrote nothing at all before it stopped or stalled. It was killed"
    echo "  before logging, or a setting outside the jar (environment, repo-root .env) redirected its output."
  fi
  echo "  Database (${DB_PROJECT}/diurnal-db-dev):"
  docker compose -p "${DB_PROJECT}" -f "${COMPOSE_FILE}" ps -a diurnal-db-dev 2>&1 | sed 's/^/    | /' || true
  docker compose -p "${DB_PROJECT}" -f "${COMPOSE_FILE}" logs --tail 15 diurnal-db-dev 2>&1 | sed 's/^/    | /' || true
}

# Polls for readiness, but stops the moment the JVM exits: a startup failure (bad config, the database
# refusing it) kills it within seconds, and waiting out the rest of the 120s for it only delayed the report.
READY=0
APP_RC=""
for _ in $(seq 1 60); do
  if curl -sf "http://127.0.0.1:${PORT}/login" >/dev/null 2>&1; then
    READY=1
    break
  fi
  if ! kill -0 "${APP_PID}" 2>/dev/null; then
    APP_RC=0
    wait "${APP_PID}" || APP_RC=$?
    break
  fi
  sleep 2
done

if [[ "${READY}" != 1 ]]; then
  if [[ -n "${APP_RC}" ]]; then
    report_app_failure "the app exited during startup" "${APP_RC}"
  else
    report_app_failure "the app was not ready within 120s"
  fi
  APP_PID=""
  exit 1
fi

# Run Playwright; its exit code becomes the script's (cleanup runs via the EXIT trap regardless), so a
# non-zero result fails the java step. A failed run whose app has since DIED is reported as such: every
# spec after the crash fails with a connection error, and the cause is in app.log, not in the specs.
E2E_RC=0
if [[ "${PLAYWRIGHT_IN_DOCKER}" == true ]]; then
  # Inside the app's network namespace the app is 127.0.0.1:${PORT} and the database is reachable by its
  # service name, which helpers/db.ts reads from TEST_DB_HOST.
  # set -e is lifted around the call rather than the call put in an `||`: either way its failure must not
  # end the script before the report below, and this way the function body still runs under set -e.
  set +e
  run_in_playwright_docker "${PLAYWRIGHT_CONTAINER}" "container:${APP_CONTAINER}" "${PLAYWRIGHT_MODULES_VOLUME}" \
    "BASE_URL=http://127.0.0.1:${PORT}" "TEST_DB_HOST=${DB_SERVICE}" -- npx --no-install playwright test
  E2E_RC=$?
  set -e
else
  (cd "${BASEDIR}/tests" && BASE_URL="http://127.0.0.1:${PORT}" npm test) || E2E_RC=$?
fi
if [[ "${E2E_RC}" != 0 ]]; then
  if ! kill -0 "${APP_PID}" 2>/dev/null; then
    APP_RC=0
    wait "${APP_PID}" || APP_RC=$?
    report_app_failure "the app died while the suite was running" "${APP_RC}"
    APP_PID=""
  else
    echo "E2E suite failed with exit code ${E2E_RC}; the app was still running. App log: ${APP_LOG}"
  fi
  exit "${E2E_RC}"
fi
