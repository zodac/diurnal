# shellcheck shell=bash
#
# Runs a Playwright suite inside the official Playwright image, so the E2E and deployment-smoke tiers need
# nothing on the host beyond Docker: no Node, no `npm ci`, no `npx playwright install`. SOURCED (not run)
# by tests/run-e2e.sh and tests/run-smoke.sh, which use it when lint_and_tests.sh sets
# PLAYWRIGHT_IN_DOCKER=true; a hand-run of either script without it keeps using the host's Playwright.
#
# The image tag is DERIVED from the @playwright/test pin in tests/package.json rather than pinned again
# here, so the browser build in the image always matches the library driving it, and the dependency
# updater's bump of package.json is the only edit a version change needs. Playwright publishes an image per
# release (mcr.microsoft.com/playwright:v<version>-noble).
#
# Three choices below are load-bearing:
#
#   network           the container joins the APP container's network namespace (--network
#                     container:<app>), so the browser reaches the app at 127.0.0.1 exactly as it did from
#                     the host. A service-name URL instead would make the origin non-localhost plain HTTP,
#                     which is not a secure context: Chromium drops `Secure` cookies there and the CSRF
#                     filter sees a different Origin. Anything the app can reach by name (the E2E database,
#                     for helpers/db.ts) the suite can too.
#   node_modules      installed into a per-tier Docker VOLUME, never the host's tests/node_modules. The host
#                     directory is shared with the typescript lint step, which can run at the same time, and
#                     `npm ci` deletes before it reinstalls (see run_lint_typescript in lint_and_tests.sh for
#                     the race that caused). The suite is mounted at /pw/tests with the volume at
#                     /pw/node_modules, where module resolution finds it by walking up; a host
#                     tests/node_modules, if any, is masked with an empty tmpfs so a stale local install can
#                     never shadow it. One volume per tier because the E2E and smoke tiers run in parallel
#                     and would otherwise race to install into the same one. `npm ci` runs only when
#                     package-lock.json changed since the volume's last install (a stamp file records its
#                     hash), so a warm run installs nothing.
#   host UID          everything runs as the invoking user, so the reports and test-results written back
#                     into tests/ are never root-owned. That UID has no passwd entry, hence HOME, and a fresh
#                     volume is root-owned, hence the one-off chown before the run.
#
# The caller must define BASEDIR (the project root). Fixed container names let a run's cleanup, and the
# NEXT run's pre-flight sweep, remove one a killed run left behind: killing the `docker run` client does not
# stop the container it started.

: "${BASEDIR:?tests/playwright-docker.sh needs BASEDIR (the project root) set before it is sourced}"
PLAYWRIGHT_NPM_CACHE_VOLUME="diurnal-gate-npm-cache"

# Prints the Playwright image matching tests/package.json's @playwright/test pin.
playwright_docker_image() {
  local version
  version="$(sed -n -E 's/^[[:space:]]*"@playwright\/test":[[:space:]]*"([0-9][^"]*)".*/\1/p' "${BASEDIR}/tests/package.json")"
  if [[ -z "${version}" ]]; then
    echo "No exact @playwright/test version in ${BASEDIR}/tests/package.json" >&2
    return 1
  fi
  echo "mcr.microsoft.com/playwright:v${version}-noble"
}

# Pulls the image and prepares the tier's volumes. Called BEFORE the tier stands its app up, so a cold
# ~2 GB pull or a fresh `npm ci` never holds a database and an app open, nor eats a readiness budget.
# $1 the tier's node_modules volume.
prepare_playwright_docker() {
  local modules_volume="$1" image host_uid host_gid
  image="$(playwright_docker_image)"
  host_uid="$(id -u)"
  host_gid="$(id -g)"
  docker pull -q "${image}" >/dev/null
  docker run --rm -v "${modules_volume}:/nm" -v "${PLAYWRIGHT_NPM_CACHE_VOLUME}:/npm-cache" "${image}" \
    chown "${host_uid}:${host_gid}" /nm /npm-cache
  run_in_playwright_docker "" bridge "${modules_volume}" -- true
}

# Runs a command in tests/ inside the Playwright image, installing the dependencies first if the lockfile
# changed. Its exit code is the command's.
#   $1  container name ("" for an anonymous one)
#   $2  the --network value, e.g. container:<app>
#   $3  the tier's node_modules volume
#   then NAME=value environment entries, a literal `--`, and the command
run_in_playwright_docker() {
  local name="$1" network="$2" modules_volume="$3" image host_uid host_gid var
  shift 3
  local -a docker_args=()
  while (( $# > 0 )) && [[ "$1" != "--" ]]; do
    docker_args+=(-e "$1")
    shift
  done
  shift
  # Pass-through tuning knobs from playwright.config.ts, forwarded by name and only when set.
  for var in PW_WORKERS PW_MOBILE_ALL; do
    if [[ -n "${!var:-}" ]]; then docker_args+=(-e "${var}"); fi
  done
  if [[ -n "${name}" ]]; then docker_args+=(--name "${name}"); fi
  if [[ -d "${BASEDIR}/tests/node_modules" ]]; then
    docker_args+=(--mount "type=tmpfs,destination=/pw/tests/node_modules")
  fi
  image="$(playwright_docker_image)"
  host_uid="$(id -u)"
  host_gid="$(id -g)"
  # --init reaps Chromium's child processes; --ipc=host is Playwright's own recommendation for Chromium,
  # whose default 64 MB /dev/shm in a container is small enough to crash renderers.
  # shellcheck disable=SC2016 # the sh -c script expands its own variables, inside the container
  docker run --rm --init --ipc=host --network "${network}" \
    -u "${host_uid}:${host_gid}" -e HOME=/tmp -e npm_config_cache=/npm-cache \
    --mount "type=tmpfs,destination=/pw,tmpfs-mode=1777" \
    -v "${modules_volume}:/pw/node_modules" \
    -v "${PLAYWRIGHT_NPM_CACHE_VOLUME}:/npm-cache" \
    -v "${BASEDIR}/tests:/pw/tests" \
    -w /pw "${docker_args[@]}" "${image}" \
    sh -c 'set -e
      cp tests/package.json tests/package-lock.json .
      want="$(sha256sum package-lock.json | cut -d" " -f1)"
      if [ "$(cat node_modules/.diurnal-lock-sha256 2>/dev/null)" != "${want}" ]; then
        npm ci --no-audit --no-fund --loglevel=error
        echo "${want}" > node_modules/.diurnal-lock-sha256
      fi
      cd tests
      exec "$@"' sh "$@"
}
