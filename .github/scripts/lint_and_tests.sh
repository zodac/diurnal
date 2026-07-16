#!/bin/bash
# ------------------------------------------------------------------------------
# Script Name:     lint_and_tests.sh
#
# Description:     Lints and tests the project using Docker.
#
# Usage:           ./lint_and_tests.sh [-v|--verbose] [-f|--force] [steps]
#
#                  [steps] is an optional comma-separated list of steps to run.
#                  If omitted, only steps whose relevant files have changed since
#                  the most recent semver git tag are run. If no tag exists, all
#                  steps are run. Pass explicit steps to override auto-detection.
#
#                  -v, --verbose
#                    Show each step's full output. Off by default: steps print only a
#                    per-substep progress line and a pass/fail summary (the long-running
#                    substeps' own output is hidden until one fails). Turn it on to stream
#                    every substep's output live — useful when a run fails and you need the detail.
#
#                  -f, --force
#                    Run ALL steps, skipping the git-diff auto-detection (equivalent to passing
#                    the full step list). Cannot be combined with an explicit [steps] argument.
#
#                  Valid steps:
#                    - docker      Lint the Dockerfiles with hadolint
#                    - grype       Build the runtime image and scan it for CVEs with grype
#                    - java        The full JVM gate: mvn clean install -Dall (unit + *IT + linters),
#                                  then the Playwright E2E/UI suite, then the deployment-smoke suite
#                    - javascript  Lint JavaScript files with eslint
#                    - markdown    Lint Markdown files with markdownlint-cli2
#                    - perf        k6 load/performance suite against the real prod image
#                                  (tests/run-perf.sh). Auto-detected on the SAME file set as `java`
#                                  (app/runtime/deps changes) plus the perf suite's own files.
#                    - shellcheck  Lint shell scripts (*.sh) with shellcheck
#                    - typescript  Lint TypeScript files with eslint
#
#                  Examples:
#                    ./lint_and_tests.sh
#                    ./lint_and_tests.sh -f
#                    ./lint_and_tests.sh docker
#                    ./lint_and_tests.sh docker,javascript
#                    ./lint_and_tests.sh -v java
#                    ./lint_and_tests.sh grype
#
# Requirements:
#   - Docker must be installed and available on the system PATH
#   - The code-quality-config submodule must be checked out
#     (git submodule update --init) - it holds every linter's CI config
#   - The `java` step needs the host toolchain the Docker steps don't: a JDK + Maven, Node/npm (the POM
#     css-build exec) and Playwright browsers (cd tests && npx playwright install --with-deps). It runs
#     `mvn clean install -Dall` directly on the host (because -Dall drives Docker itself for the managed
#     IT test DB), then — only if that passed — the E2E suite (tests/run-e2e.sh, reusing the fast-jar the
#     build just produced) and the deployment-smoke suite (tests/run-smoke.sh, which builds the REAL
#     production image and brings up an isolated app+DB stack). The `mvn` gate itself stays unit + ITs +
#     linters; the E2E and smoke tiers are deliberately NOT in the Maven build — they are chained into
#     this step instead, so a single `java` run is the whole JVM-side gate.
#   - The `grype` step builds the production runtime image and scans it, so it needs a Docker daemon
#     the current user can build with; the scan runs from the grype image with the Docker
#     socket mounted so it can read that just-built local image.
#
# Exit Codes:
#   - 0: All linting and tests passed successfully
#   - Non-zero: One or more linting errors or test failures occurred
# ------------------------------------------------------------------------------

set -uo pipefail

trap 'echo; echo "❌ Interrupted"; exit 130' INT

# Absolute path to this script (basedir + filename), so the "re-run ..." hints printed on failure are
# copy/paste-ready from any working directory rather than a bare filename. Each command substitution is
# assigned on its own line so shellcheck (SC2312) doesn't flag a masked return value.
SCRIPT_SOURCE="${BASH_SOURCE[0]}"
SCRIPT_DIR="$(dirname -- "${SCRIPT_SOURCE}")"
SCRIPT_DIR="$(cd -- "${SCRIPT_DIR}" > /dev/null 2>&1 && pwd)"
SCRIPT_NAME="$(basename -- "${SCRIPT_SOURCE}")"
SCRIPT_PATH="${SCRIPT_DIR}/${SCRIPT_NAME}"

ESLINT_BUILD_IMAGE="local/diurnal-eslint:latest"
ESLINT_NODE_IMAGE="node:26.5.0-alpine"
GRYPE_DOCKER_IMAGE="anchore/grype:v0.115.0"
HADOLINT_DOCKER_IMAGE="hadolint/hadolint:v2.14.0-alpine"
MARKDOWNLINT_DOCKER_IMAGE="davidanson/markdownlint-cli2:v0.23.0"
SHELLCHECK_DOCKER_IMAGE="koalaman/shellcheck:v0.11.0"

# The runtime image the grype step builds and scans (the same final stage the published image uses).
DIURNAL_RUNTIME_IMAGE="zodac/diurnal:latest"

# Named Docker volume that persists grype's vulnerability DB between runs (mounted into the scanner
# and pointed at by GRYPE_DB_CACHE_DIR). Without it every `docker run --rm` re-downloads the ~1.6GB
# DB; grype only re-pulls when the cached copy is stale, so warm runs skip the download. A named
# volume (vs. a host bind mount) keeps the root-owned DB files out of the working tree entirely — so
# nothing to .gitignore — and lets Docker manage its lifecycle. It is auto-created on first use;
# remove it with `docker volume rm ${GRYPE_DB_CACHE_VOLUME}`.
GRYPE_DB_CACHE_VOLUME="${GRYPE_DB_CACHE_VOLUME:-diurnal-grype-db}"

VALID_STEPS=("docker" "grype" "java" "javascript" "markdown" "perf" "shellcheck" "typescript")

# HTTP ports for the E2E and deployment-smoke tiers the `java` step chains after the Maven gate
# (formerly Maven properties in the pom's `all` profile). The E2E jar reuses 8081 (the @QuarkusTest
# default, released by the time the jar starts); the smoke stack uses 8082 with an isolated compose
# project — both distinct from 8080 (production), so either can run alongside a live deployment.
# Override on a port conflict.
E2E_HTTP_PORT="${E2E_HTTP_PORT:-8081}"
SMOKE_HTTP_PORT="${SMOKE_HTTP_PORT:-8082}"

# HTTP port for the opt-in `perf` step's isolated stack (tests/run-perf.sh). 8083 keeps it disjoint
# from 8080 (prod), 8081 (dev/E2E) and 8082 (smoke), so a perf run coexists with all of them.
PERF_HTTP_PORT="${PERF_HTTP_PORT:-8083}"

# Opt-in SonarQube analysis, folded into the `java` step's Maven gate (NOT a separate build). Off by
# default so local/PR runs never attempt an upload; the release pipeline (publish.yml) sets it to
# "true" so each release is analysed. When on, `-Dsonarqube` is appended to `mvn clean install -Dall`,
# activating the parent-pom's `activate_sonarqube` profile — which flips `skip-sonarqube` to false so
# the sonar-maven-plugin (bound to `install`) runs, ingesting the Checkstyle/PMD/SpotBugs report XMLs
# the same -Dall build just produced. It needs the SONARQUBE_HOST_URL and SONARQUBE_PAT env vars that
# the parent-pom reads (sonar.host.url / sonar.token); without them the analysis step fails.
SONARQUBE_ANALYSIS="${SONARQUBE_ANALYSIS:-false}"

# Verbose off by default: steps print a per-substep progress line plus a pass/fail summary, and each
# substep's own output is hidden until it fails. The -v/--verbose flag (parsed below) flips this to
# stream/echo every substep's full output live.
VERBOSE=false

# Force off by default: with -f/--force (parsed below), the git-diff auto-detection is skipped and
# every step runs. Ignored when an explicit step list is passed (that combination is rejected).
FORCE=false

overall_exit_code=0

# Yellow highlight for the copy/paste-ready "re-run …" command in failure messages, so it stands out
# from the surrounding text. Only emit the ANSI codes when stdout is a real terminal — piped/redirected
# output (e.g. CI logs) stays plain so the escape sequences don't leak into it.
if [[ -t 1 ]]; then
    YELLOW=$'\033[1;33m'
    GREEN=$'\033[1;32m'
    RED=$'\033[1;31m'
    RESET=$'\033[0m'
else
    YELLOW=""
    GREEN=""
    RED=""
    RESET=""
fi

# Host UID/GID, resolved once, for the `-u` flag of the eslint-based steps (so files the container
# writes are owned by the host user). Assigned separately from use so the command's exit status is
# not masked (SC2312).
HOST_UID="$(id -u)"
HOST_GID="$(id -g)"

# Emit an indented progress line naming the substep about to run. Always printed — even in non-verbose
# mode — so a long multi-part step (java's mvn/e2e/smoke, grype's build/scan) shows what it is doing
# rather than sitting silent until the final summary.
substep() {
    echo "   → ${1}"
}

# Convert an elapsed nanosecond count into a human-readable "natural time" string with millisecond
# precision. The format adapts to the magnitude of the duration:
#   - 123ms          (< 1 second)
#   - 4s:037ms       (< 1 minute)
#   - 2m:04s         (< 1 hour)
#   - 1h:02m         (>= 1 hour)
# Milliseconds are dropped once the time exceeds 1 minute, and seconds once it exceeds 1 hour. The
# result is printed to stdout (no trailing newline) so callers can interpolate it inline.
to_natural_time() {
    local elapsed_ns="${1}"
    local total_millis=$((elapsed_ns / 1000000))

    if ((total_millis < 1000)); then
        printf '%dms' "${total_millis}"
        return
    fi

    local total_seconds=$((total_millis / 1000))
    local millis_part=$((total_millis % 1000))
    if ((total_seconds < 60)); then
        printf '%ds:%03dms' "${total_seconds}" "${millis_part}"
        return
    fi

    local minutes=$((total_seconds / 60))
    local seconds_part=$((total_seconds % 60))
    if ((total_seconds < 3600)); then
        printf '%dm:%02ds' "${minutes}" "${seconds_part}"
        return
    fi

    local hours=$((total_seconds / 3600))
    local minutes_part=$(((total_seconds % 3600) / 60))
    printf '%dh:%02dm' "${hours}" "${minutes_part}"
}

# Nanosecond wall-clock start of the step currently running, set in the execution loop before each
# step is dispatched. `step_time` reads it to render how long the step has taken so far as natural
# time, so each run_* function can fold the elapsed time straight into its own pass/fail summary line
# (assign it to a local first — a bare $(step_time) inside echo masks its return value, SC2312).
STEP_START_NS=0
step_time() {
    local now
    now="$(date +%s%N)"
    to_natural_time "$((now - STEP_START_NS))"
}

# Run one substep's command: in verbose mode stream its output live; otherwise capture combined
# stdout+stderr and echo it ONLY if the command fails (so a passing run stays quiet, a failing one is
# still fully reviewable without a re-run). Returns the command's own exit code so callers can chain
# and short-circuit on failure; it deliberately does NOT touch overall_exit_code — the calling step
# decides how a substep failure maps to its own pass/fail.
run_quietly() {
    if [[ "${VERBOSE}" == true ]]; then
        "$@"
        return $?
    fi
    local out rc
    out=$("$@" 2>&1)
    rc=$?
    if [[ "${rc}" -ne 0 && -n "${out}" ]]; then
        echo "${out}"
    fi
    return "${rc}"
}

# The eslint image is shared by the javascript and typescript steps. It installs eslint plus the
# TypeScript plugins referenced by code-quality-config/typescript/eslint.config.mjs into the root
# node_modules so ESM plugin resolution from the config file walks up and finds them.
ensure_eslint_image() {
    docker pull "${ESLINT_NODE_IMAGE}" >/dev/null
    build_output=$(
        docker build -t "${ESLINT_BUILD_IMAGE}" - 2>&1 <<EOF
FROM ${ESLINT_NODE_IMAGE}
RUN npm install -g --no-audit --no-fund \
    eslint@9 \
    @eslint/js@9 \
    @typescript-eslint/eslint-plugin@8 \
    @typescript-eslint/parser@8 \
    globals \
    typescript@5 \
    && ln -s /usr/local/lib/node_modules /node_modules
EOF
    )
    # shellcheck disable=SC2181
    if [[ $? -ne 0 ]]; then
        echo "❌ eslint image build failed"
        echo "${build_output}"
        return 1
    fi
}

run_docker() {
    echo
    echo "Running Dockerfile lint using [${HADOLINT_DOCKER_IMAGE}]"
    docker pull "${HADOLINT_DOCKER_IMAGE}" >/dev/null
    if output=$(docker run --rm \
        -v "${PWD}":/app \
        -w /app \
        "${HADOLINT_DOCKER_IMAGE}" \
        hadolint --config code-quality-config/docker/.hadolint.yaml \
        Dockerfile sandbox/Dockerfile 2>&1); then
        [[ "${VERBOSE}" == true && -n "${output}" ]] && echo "${output}"
        local done_in
        done_in="$(step_time)"
        echo "✅ Dockerfile lint passed, finished in ${GREEN}${done_in}${RESET}"
    else
        echo "${output}" | jq .
        local done_in
        done_in="$(step_time)"
        echo "❌ Dockerfile lint failed after ${RED}${done_in}${RESET}: re-run ${YELLOW}'${SCRIPT_PATH} -v docker'${RESET} for the full output"
        overall_exit_code=1
    fi
}

run_grype() {
    echo
    echo "Building [${DIURNAL_RUNTIME_IMAGE}] and scanning it with Grype [${GRYPE_DOCKER_IMAGE}]"

    # Build the production runtime image from the same multi-stage Dockerfile the published image uses,
    # so Grype scans exactly what ships: the distroless base OS packages, the jlink JRE, the busybox
    # wget binary and the Quarkus app's bundled Java dependency jars. The css/icons build stages only
    # export app.css / the favicons — their Node and ImageMagick toolchains never reach this image — so
    # a package.json / JS-dependency change alters nothing here and is deliberately NOT a grype trigger.
    #
    # The grype run reads the just-built local image via the mounted Docker socket, and the repo as the
    # working dir so the -c config paths resolve. Order is load-bearing: across multiple -c files
    # grype lets the FIRST file win every scalar key (verified empirically — later files do NOT override
    # earlier scalars), while ignore lists from all files are appended. So the project-level .grype.yaml
    # is passed FIRST (its log level / fail-on-severity / etc. take precedence), and the shared
    # code-quality-config submodule config SECOND to supply the common won't-fix ignore list plus any
    # scalar the project doesn't set. The project-level .grype.yaml is OPTIONAL — a project that needs no
    # overrides can rely on the submodule config alone, so it is only added when the file exists (keeping
    # its FIRST position when present). The vulnerability DB is persisted in a named Docker volume (mounted
    # at /grype-db, pointed at by GRYPE_DB_CACHE_DIR) so it survives the --rm and is not re-downloaded
    # every run — grype only
    # re-pulls it when the cached copy is stale. The volume is auto-created by `docker run -v` on first
    # use; nothing lands in the working tree.
    local build_cmd=(docker build -t "${DIURNAL_RUNTIME_IMAGE}" -f Dockerfile .)
    local grype_config_args=()
    [[ -f .grype.yaml ]] && grype_config_args+=(-c .grype.yaml)
    grype_config_args+=(-c code-quality-config/docker/.grype.yaml)
    local grype_cmd=(docker run --rm
        -v /var/run/docker.sock:/var/run/docker.sock
        -v "${PWD}":/app
        -v "${GRYPE_DB_CACHE_VOLUME}":/grype-db
        -e GRYPE_DB_CACHE_DIR=/grype-db
        -w /app
        "${GRYPE_DOCKER_IMAGE}"
        "${grype_config_args[@]}"
        "${DIURNAL_RUNTIME_IMAGE}")

    # Two substeps — build then scan — each with a progress line (even non-verbose) and its output
    # captured until it fails (run_quietly). The scan is slow and, on a cold DB, downloads ~1.6GB, so
    # the progress lines matter; -v streams both live. Both paths share the command arrays above.
    local done_in
    substep "docker build ${DIURNAL_RUNTIME_IMAGE}  (production runtime image)"
    if ! run_quietly "${build_cmd[@]}"; then
        done_in="$(step_time)"
        echo "❌ Grype scan failed after ${RED}${done_in}${RESET} (could not build ${DIURNAL_RUNTIME_IMAGE}): re-run ${YELLOW}'${SCRIPT_PATH} -v grype'${RESET} for the full output"
        overall_exit_code=1
        return
    fi

    docker pull "${GRYPE_DOCKER_IMAGE}" >/dev/null

    substep "grype scan ${DIURNAL_RUNTIME_IMAGE}  (CVE scan via ${GRYPE_DOCKER_IMAGE})"
    if run_quietly "${grype_cmd[@]}"; then
        done_in="$(step_time)"
        echo "✅ Grype scan passed, finished in ${GREEN}${done_in}${RESET}"
    else
        done_in="$(step_time)"
        echo "❌ Grype scan failed after ${RED}${done_in}${RESET}: re-run ${YELLOW}'${SCRIPT_PATH} -v grype'${RESET} for the full output"
        overall_exit_code=1
    fi
}

run_java() {
    echo
    echo "Running the full JVM gate [java]: Maven build + E2E + deployment-smoke"
    # The java step is the whole JVM-side gate: three substeps, each run only if the previous passed
    # (so a failure short-circuits and the later, slower tiers are skipped). Each substep prints a
    # progress line first (even in non-verbose mode); run_quietly hides its output unless it fails.
    #   1. mvn clean install -Dall — unit tests + *IT + the full inherited lint suite. Drives the CSS
    #      build (Node) and a managed IT test DB (Docker), and packages the fast-jar the E2E run reuses;
    #      so it runs against the host toolchain (JDK + Maven + Node + Docker), not a Maven Docker image.
    #   2. tests/run-e2e.sh — Playwright UI suite against the packaged jar (its own DB, port 8081). No
    #      rebuild needed: it reuses ${PWD}/target/quarkus-app from the install above.
    #   3. tests/run-smoke.sh — deployment-smoke suite against the REAL production image (port 8082),
    #      fully self-contained (builds the image, isolated app+DB stack, readiness-gating check).
    # This mirrors the old -Dall pom wiring (E2E after the ITs are green, smoke after E2E).
    local failed_at=""

    # The Maven gate, optionally with SonarQube analysis appended (see SONARQUBE_ANALYSIS above). The
    # label mirrors the exact args so the progress + "re-run …" hints stay copy/paste-accurate.
    local -a mvn_args=(clean install -Dall)
    local mvn_label="mvn clean install -Dall"
    if [[ "${SONARQUBE_ANALYSIS}" == "true" ]]; then
        mvn_args+=(-Dsonarqube)
        mvn_label+=" -Dsonarqube"
    fi

    substep "${mvn_label}  (unit + *IT + linters; packages the fast-jar)"
    run_quietly mvn "${mvn_args[@]}" || failed_at="${mvn_label}"

    if [[ -z "${failed_at}" ]]; then
        substep "tests/run-e2e.sh  (Playwright E2E/UI suite on :${E2E_HTTP_PORT}, reusing the built jar)"
        run_quietly "${PWD}/tests/run-e2e.sh" "${E2E_HTTP_PORT}" "${PWD}/target" "${PWD}" \
            || failed_at="tests/run-e2e.sh"
    fi

    if [[ -z "${failed_at}" ]]; then
        substep "tests/run-smoke.sh  (deployment-smoke against the real prod image on :${SMOKE_HTTP_PORT})"
        run_quietly "${PWD}/tests/run-smoke.sh" "${SMOKE_HTTP_PORT}" "${PWD}" \
            || failed_at="tests/run-smoke.sh"
    fi

    local done_in
    done_in="$(step_time)"
    if [[ -z "${failed_at}" ]]; then
        echo "✅ Java gate passed (build + tests + E2E + smoke), finished in ${GREEN}${done_in}${RESET}"
    else
        echo "❌ Java gate failed at [${failed_at}] after ${RED}${done_in}${RESET}: re-run ${YELLOW}'${SCRIPT_PATH} -v java'${RESET} for the full output"
        overall_exit_code=1
    fi
}

run_perf() {
    echo
    echo "Running the k6 performance/load suite [perf] against the real production image"
    # Auto-detected on the same file set as `java` (plus the perf suite's own files): tests/run-perf.sh
    # builds the prod image, brings up an isolated app+DB stack on :${PERF_HTTP_PORT}, measures cold-boot
    # latency +
    # post-boot RSS, seeds a heavy account, then drives one k6 scenario per public-API use-case group.
    # k6's threshold breaches make it exit non-zero, which the runner (and this substep) propagate as a
    # failure. Fully self-contained: an EXIT trap tears the stack down on success OR failure.
    local done_in
    substep "tests/run-perf.sh  (k6 boot + load suite on :${PERF_HTTP_PORT}, real prod image)"
    if run_quietly "${PWD}/tests/run-perf.sh" "${PERF_HTTP_PORT}" "${PWD}"; then
        done_in="$(step_time)"
        echo "✅ Performance suite passed (boot budget + all k6 thresholds), finished in ${GREEN}${done_in}${RESET}"
    else
        done_in="$(step_time)"
        echo "❌ Performance suite failed after ${RED}${done_in}${RESET}: re-run ${YELLOW}'${SCRIPT_PATH} -v perf'${RESET} for the full output"
        overall_exit_code=1
    fi
}

run_javascript() {
    echo
    echo "Running JavaScript lint using [${ESLINT_NODE_IMAGE}]"
    # Lint every tracked *.js/*.cjs file. `git ls-files` naturally excludes the vendored,
    # gitignored htmx.min.js (see scripts/vendor-assets.cjs), the code-quality-config submodule,
    # and any node_modules/target build output — same approach as the shellcheck step below.
    local files=()
    mapfile -t files < <(git ls-files '*.js' '*.cjs' || true)
    local done_in
    if [[ "${#files[@]}" -eq 0 ]]; then
        done_in="$(step_time)"
        echo "✅ JavaScript lint passed (no JavaScript files found), finished in ${GREEN}${done_in}${RESET}"
        return
    fi
    substep "preparing eslint image (${ESLINT_BUILD_IMAGE})"
    if ! ensure_eslint_image; then
        overall_exit_code=1
        return
    fi
    substep "eslint (${#files[@]} JavaScript file(s))"
    if output=$(docker run --rm \
        -u "${HOST_UID}:${HOST_GID}" \
        -e HOME=/tmp \
        -v "${PWD}":/app \
        -w /app \
        --entrypoint eslint \
        "${ESLINT_BUILD_IMAGE}" \
        --config code-quality-config/javascript/eslint.config.cjs \
        "${files[@]}" 2>&1); then
        [[ "${VERBOSE}" == true && -n "${output}" ]] && echo "${output}"
        done_in="$(step_time)"
        echo "✅ JavaScript lint passed, finished in ${GREEN}${done_in}${RESET}"
    else
        echo "${output}"
        done_in="$(step_time)"
        echo "❌ JavaScript lint failed after ${RED}${done_in}${RESET}: re-run ${YELLOW}'${SCRIPT_PATH} -v javascript'${RESET} for the full output"
        overall_exit_code=1
    fi
}

run_typescript() {
    echo
    echo "Running TypeScript lint using [${ESLINT_NODE_IMAGE}]"
    substep "preparing eslint image (${ESLINT_BUILD_IMAGE})"
    if ! ensure_eslint_image; then
        overall_exit_code=1
        return
    fi
    # The TS config is type-aware (parserOptions.project) so the tests/ dependencies must be installed
    # for the type-checker to resolve imports. eslint is run from within tests/ so it picks up
    # tests/tsconfig.json and tests/node_modules; the config path is relative to that dir.
    substep "npm ci + eslint (tests/**/*.ts)"
    if output=$(docker run --rm \
        -u "${HOST_UID}:${HOST_GID}" \
        -e HOME=/tmp \
        -e npm_config_cache=/tmp/.npm \
        -v "${PWD}":/app \
        -w /app/tests \
        --entrypoint sh \
        "${ESLINT_BUILD_IMAGE}" \
        -c "npm ci --no-audit --no-fund && \
            eslint \
            --config ../code-quality-config/typescript/eslint.config.mjs \
            '**/*.ts'" 2>&1); then
        [[ "${VERBOSE}" == true && -n "${output}" ]] && echo "${output}"
        local done_in
        done_in="$(step_time)"
        echo "✅ TypeScript lint passed, finished in ${GREEN}${done_in}${RESET}"
    else
        echo "${output}"
        local done_in
        done_in="$(step_time)"
        echo "❌ TypeScript lint failed after ${RED}${done_in}${RESET}: re-run ${YELLOW}'${SCRIPT_PATH} -v typescript'${RESET} for the full output"
        overall_exit_code=1
    fi
}

run_markdown() {
    echo
    echo "Running Markdown lint using [${MARKDOWNLINT_DOCKER_IMAGE}]"
    docker pull "${MARKDOWNLINT_DOCKER_IMAGE}" >/dev/null
    if output=$(docker run --rm \
        -v "${PWD}":/app \
        -w /app \
        "${MARKDOWNLINT_DOCKER_IMAGE}" \
        --config code-quality-config/markdown/.markdownlint.json \
        "**/*.md" "!code-quality-config/**" "!**/node_modules/**" "!**/target/**" \
        "!.claude/**" "!RELEASE_NOTES.md" "!tests/playwright-report/**" "!tests/test-results/**" 2>&1); then
        [[ "${VERBOSE}" == true && -n "${output}" ]] && echo "${output}"
        local done_in
        done_in="$(step_time)"
        echo "✅ Markdown lint passed, finished in ${GREEN}${done_in}${RESET}"
    else
        echo "${output}"
        local done_in
        done_in="$(step_time)"
        echo "❌ Markdown lint failed after ${RED}${done_in}${RESET}: re-run ${YELLOW}'${SCRIPT_PATH} -v markdown'${RESET} for the full output"
        overall_exit_code=1
    fi
}

run_shellcheck() {
    echo
    echo "Running shell script lint using [${SHELLCHECK_DOCKER_IMAGE}]"
    # Lint every tracked *.sh file. `git ls-files` naturally excludes the code-quality-config
    # submodule (its files belong to that repo) and any node_modules/target build output.
    local files=()
    mapfile -t files < <(git ls-files '*.sh' || true)
    local done_in
    if [[ "${#files[@]}" -eq 0 ]]; then
        done_in="$(step_time)"
        echo "✅ Shell script lint passed (no shell scripts found), finished in ${GREEN}${done_in}${RESET}"
        return
    fi
    docker pull "${SHELLCHECK_DOCKER_IMAGE}" >/dev/null
    # Unlike the other linters, shellcheck has no `--config` flag: it auto-discovers a `.shellcheckrc`
    # by walking up from each checked file. Overlay the submodule's shared config so every file picks
    # it up — but mount the repo into a *subdirectory* (/app/repo) and place the config at its parent
    # (/app/.shellcheckrc). shellcheck walks up from /app/repo/<file> and finds it. Crucially, the
    # overlay's mount point (/app/.shellcheckrc) then lives in a container-internal dir, NOT inside a
    # host bind mount, so Docker no longer creates a stray empty .shellcheckrc in the host repo root.
    if output=$(docker run --rm \
        -v "${PWD}":/app/repo \
        -v "${PWD}/code-quality-config/shellscript/.shellcheckrc":/app/.shellcheckrc:ro \
        -w /app/repo \
        "${SHELLCHECK_DOCKER_IMAGE}" \
        "${files[@]}" 2>&1); then
        [[ "${VERBOSE}" == true && -n "${output}" ]] && echo "${output}"
        done_in="$(step_time)"
        echo "✅ Shell script lint passed, finished in ${GREEN}${done_in}${RESET}"
    else
        echo "${output}"
        done_in="$(step_time)"
        echo "❌ Shell script lint failed after ${RED}${done_in}${RESET}: re-run ${YELLOW}'${SCRIPT_PATH} -v shellcheck'${RESET} for the full output"
        overall_exit_code=1
    fi
}

detect_changed_steps() {
    local latest_tag
    latest_tag=$(git tag --sort=-version:refname 2>/dev/null | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | head -1 || true)

    if [[ -z "${latest_tag}" ]]; then
        echo "No semver tag found; running all steps" >&2
        printf '%s\n' "${VALID_STEPS[@]}"
        return
    fi

    echo "Checking changes since tag [${latest_tag}]..." >&2

    local run_docker=false run_grype=false run_java=false run_javascript=false run_markdown=false run_shellcheck=false run_typescript=false
    local run_perf=false
    local java_changed_files=()
    # Perf's own inputs (the k6 scripts, the runner, the perf compose stack). A change to any of these
    # triggers `perf` even when no `java`-set file changed — the suite's own definition changed.
    local perf_own_files=false
    local file

    while IFS= read -r file; do
        [[ -z "${file}" ]] && continue
        [[ "${file}" == "Dockerfile" || "${file}" =~ ^sandbox/Dockerfile || "${file}" == ".dockerignore" || "${file}" =~ ^(tests/)?docker-compose || "${file}" =~ ^code-quality-config/docker/ ]] && run_docker=true
        # Grype re-scans when the contents of the runtime image change: the runtime Dockerfile (base
        # images, package pins, copy layout), the build context filter, or the ignore list itself. The
        # pom.xml path (bundled Java deps) is handled below with the same version-bump suppression as
        # java; sandbox/Dockerfile and docker-compose don't affect the published runtime image.
        [[ "${file}" == "Dockerfile" || "${file}" == ".dockerignore" || "${file}" == ".grype.yaml" ]] && run_grype=true
        # The `java` step is the whole JVM gate (Maven build + ITs + the E2E and deployment-smoke
        # suites), so anything feeding ANY of those three tiers triggers it:
        #   - src/, frontend/ (Tailwind source feeding the compiled stylesheet), pom.xml, the java lint config;
        #   - the E2E suite's own files — the ui specs, shared helpers/global-setup, its Playwright config,
        #     the runner, and the tests/ npm/ts deps;
        #   - the deployment-smoke inputs — the runtime Dockerfile/.dockerignore, the smoke stack/suite,
        #     its Playwright config and runner.
        if [[ "${file}" =~ ^src/ || "${file}" =~ ^frontend/ || "${file}" == "pom.xml" || "${file}" =~ ^code-quality-config/java/ \
           || "${file}" =~ ^tests/ui/ || "${file}" =~ ^tests/helpers/ || "${file}" == "tests/global-setup.ts" \
           || "${file}" == "tests/playwright.config.ts" || "${file}" == "tests/run-e2e.sh" \
           || "${file}" =~ ^tests/package(-lock)?\.json$ || "${file}" == "tests/tsconfig.json" \
           || "${file}" == "Dockerfile" || "${file}" == ".dockerignore" || "${file}" =~ ^tests/smoke/ \
           || "${file}" == "tests/docker-compose.smoke.yml" || "${file}" == "tests/playwright.smoke.config.ts" \
           || "${file}" == "tests/run-smoke.sh" ]]; then
            run_java=true
            java_changed_files+=("${file}")
        fi
        [[ "${file}" =~ \.(js|cjs)$ || "${file}" =~ ^code-quality-config/javascript/ ]] && run_javascript=true
        [[ "${file}" =~ ^tests/.*\.ts$ || "${file}" =~ ^tests/tsconfig\.json$ || "${file}" =~ ^tests/package(-lock)?\.json$ || "${file}" =~ ^code-quality-config/typescript/ ]] && run_typescript=true
        [[ "${file}" =~ \.md$ || "${file}" =~ ^code-quality-config/markdown/ ]] && run_markdown=true
        [[ "${file}" =~ \.sh$ || "${file}" =~ ^code-quality-config/shellscript/ ]] && run_shellcheck=true
        # The perf suite's own inputs — its k6 scripts (tests/perf/), the runner, and its compose stack.
        [[ "${file}" =~ ^tests/perf/ || "${file}" == "tests/run-perf.sh" || "${file}" == "tests/docker-compose.perf.yml" ]] && perf_own_files=true
    done < <(
        {
            git diff --name-only "${latest_tag}..HEAD" || true
            git diff --name-only || true
            git diff --name-only --cached || true
            git ls-files --others --exclude-standard || true
        } | sort -u || true
    )

    # Suppress the java step if the only changes are comments, or a bump of the project's own
    # <version> in pom.xml. The release pipeline always advances that version to the next -SNAPSHOT
    # right after a release, so an unfiltered pom.xml change would flag a build on every release.
    # The project version is the sole top-level (4-space-indented) <version> in pom.xml; the
    # 8-space-indented parent <version> and any dependency/property version are NOT stripped, so a
    # genuine dependency or parent bump still triggers the build.
    if [[ "${run_java}" == true ]]; then
        local non_comment_diff
        non_comment_diff=$(
            {
                git diff "${latest_tag}..HEAD" -- "${java_changed_files[@]}" 2>/dev/null
                git diff -- "${java_changed_files[@]}" 2>/dev/null
                git diff --cached -- "${java_changed_files[@]}" 2>/dev/null
            } | grep '^[+-]' | grep -v '^---\|^+++' \
              | grep -vE '^[+-][[:space:]]*(//|/\*\*?|\*|<!--|-->)' \
              | grep -vE '^[+-]    <version>[^<]*</version>[[:space:]]*$' \
              | grep -vE '^[+-][[:space:]]*$'
        )
        if [[ -z "${non_comment_diff}" ]]; then
            run_java=false
            echo "Skipping java: only comment or project version-bump changes detected" >&2
        fi
    fi

    # `perf` (the k6 load suite) runs on the SAME trigger as `java` — any change feeding the runtime,
    # app code or dependencies plausibly moves performance — PLUS the perf suite's own files. It keys
    # off run_java's FINAL value (computed above), so java's comment-only / project version-bump
    # suppression applies to perf too; a perf-only change (its scripts/stack) fires it independently.
    if [[ "${run_java}" == true || "${perf_own_files}" == true ]]; then
        run_perf=true
    fi

    # The runtime image bundles the app's Java dependency jars, so a genuine pom.xml dependency or
    # parent bump must re-scan the image. Reuse the java filter (scoped to pom.xml only, since src/
    # changes don't alter the dependency set): ignore comment-only edits and a project <version> bump
    # (which rebuilds the image but changes no dependency). Runs regardless of run_java's outcome.
    if printf '%s\n' "${java_changed_files[@]}" | grep -qxF 'pom.xml'; then
        local pom_diff
        pom_diff=$(
            {
                git diff "${latest_tag}..HEAD" -- pom.xml 2>/dev/null
                git diff -- pom.xml 2>/dev/null
                git diff --cached -- pom.xml 2>/dev/null
            } | grep '^[+-]' | grep -v '^---\|^+++' \
              | grep -vE '^[+-][[:space:]]*(//|/\*\*?|\*|<!--|-->)' \
              | grep -vE '^[+-]    <version>[^<]*</version>[[:space:]]*$' \
              | grep -vE '^[+-][[:space:]]*$'
        )
        [[ -n "${pom_diff}" ]] && run_grype=true
    fi

    # A bump to any docker image pinned in this script must re-trigger the matching step,
    # since the image change won't otherwise show up in the file-path detection above.
    # Runs after the comment-only suppress so an image change can re-enable a suppressed step.
    local script_path=".github/scripts/lint_and_tests.sh"
    local script_diff
    script_diff=$(
        {
            git diff "${latest_tag}..HEAD" -- "${script_path}" 2>/dev/null
            git diff -- "${script_path}" 2>/dev/null
            git diff --cached -- "${script_path}" 2>/dev/null
        } | grep -E '^[+-][^+-]' || true
    )
    if [[ -n "${script_diff}" ]]; then
        grep -qE '^[+-][[:space:]]*HADOLINT_DOCKER_IMAGE='                        <<<"${script_diff}" && run_docker=true
        grep -qE '^[+-][[:space:]]*(GRYPE_DOCKER_IMAGE|DIURNAL_RUNTIME_IMAGE)='   <<<"${script_diff}" && run_grype=true
        grep -qE '^[+-][[:space:]]*(ESLINT_BUILD_IMAGE|ESLINT_NODE_IMAGE)='       <<<"${script_diff}" && run_javascript=true
        grep -qE '^[+-][[:space:]]*(ESLINT_BUILD_IMAGE|ESLINT_NODE_IMAGE)='       <<<"${script_diff}" && run_typescript=true
        grep -qE '^[+-][[:space:]]*MARKDOWNLINT_DOCKER_IMAGE='                    <<<"${script_diff}" && run_markdown=true
        grep -qE '^[+-][[:space:]]*SHELLCHECK_DOCKER_IMAGE='                      <<<"${script_diff}" && run_shellcheck=true
    fi

    [[ "${run_docker}"     == true ]] && echo "docker"
    [[ "${run_grype}"      == true ]] && echo "grype"
    [[ "${run_java}"       == true ]] && echo "java"
    [[ "${run_javascript}" == true ]] && echo "javascript"
    [[ "${run_markdown}"   == true ]] && echo "markdown"
    [[ "${run_perf}"       == true ]] && echo "perf"
    [[ "${run_shellcheck}" == true ]] && echo "shellcheck"
    [[ "${run_typescript}" == true ]] && echo "typescript"
}

# Parse flags (accepted in any position); everything else is collected as the positional steps arg.
positional=()
while [[ $# -gt 0 ]]; do
    case "${1}" in
    -v | --verbose) VERBOSE=true ;;
    -f | --force) FORCE=true ;;
    --) shift; positional+=("$@"); break ;;
    -*)
        echo "❌ Unknown option: '${1}'. Supported: -v, --verbose, -f, --force"
        exit 1
        ;;
    *) positional+=("${1}") ;;
    esac
    shift
done
if [[ ${#positional[@]} -gt 0 ]]; then
    set -- "${positional[@]}"
else
    set --
fi

# -f/--force runs every step, so it is mutually exclusive with an explicit step list — reject the
# ambiguous combination rather than silently picking one.
if [[ "${FORCE}" == true && $# -gt 0 ]]; then
    echo "❌ -f/--force runs ALL steps and cannot be combined with an explicit step list ('${1}')"
    exit 1
fi

# Parse and validate steps
if [[ "${FORCE}" == true ]]; then
    steps=("${VALID_STEPS[@]}")
    echo "Running ALL steps (forced): $(IFS=', '; echo "${steps[*]}")"
elif [[ $# -eq 0 ]]; then
    mapfile -t steps < <(detect_changed_steps || true)
    if [[ ${#steps[@]} -eq 0 ]]; then
        echo "No relevant changes detected since last tag; nothing to run"
        exit 0
    fi
    echo "Running steps: $(IFS=', '; echo "${steps[*]}")"
else
    IFS=',' read -ra steps <<<"${1}"
    for step in "${steps[@]}"; do
        pattern=" ${step} "
        if [[ ! " ${VALID_STEPS[*]} " =~ ${pattern} ]]; then
            echo "❌ Unknown step: '${step}'. Valid steps: $(
                IFS=', '
                echo "${VALID_STEPS[*]}"
            )"
            exit 1
        fi
    done
fi

# Execute steps, timing each one. `date +%s%N` (nanoseconds since epoch) fits a 64-bit shell integer,
# so the differences feed straight into to_natural_time. Each run_* function reads STEP_START_NS via
# step_time to fold its own elapsed time into its pass/fail summary line. Assigned on their own lines
# so the command substitution's exit status isn't masked (SC2312).
overall_start=""
overall_start="$(date +%s%N)"
for step in "${steps[@]}"; do
    STEP_START_NS="$(date +%s%N)"
    case "${step}" in
    docker) run_docker ;;
    grype) run_grype ;;
    java) run_java ;;
    javascript) run_javascript ;;
    markdown) run_markdown ;;
    perf) run_perf ;;
    shellcheck) run_shellcheck ;;
    typescript) run_typescript ;;
    *) ;; # unreachable: steps are validated against VALID_STEPS above
    esac
done

overall_end=""
overall_end="$(date +%s%N)"
total_elapsed=""
total_elapsed="$(to_natural_time "$((overall_end - overall_start))")"
echo
echo "⏱  Total time: ${total_elapsed}"

if [[ "${overall_exit_code}" -ne 0 ]]; then
    echo
    echo "❌ One or more steps failed: re-run ${YELLOW}'${SCRIPT_PATH} -v [steps]'${RESET} for the full output"
    exit 1
fi
