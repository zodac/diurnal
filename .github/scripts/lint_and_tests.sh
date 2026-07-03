#!/bin/bash
# ------------------------------------------------------------------------------
# Script Name:     lint_and_tests.sh
#
# Description:     Lints and tests the project using Docker.
#
# Usage:           ./lint_and_tests.sh [-v|--verbose] [steps]
#
#                  [steps] is an optional comma-separated list of steps to run.
#                  If omitted, only steps whose relevant files have changed since
#                  the most recent semver git tag are run. If no tag exists, all
#                  steps are run. Pass explicit steps to override auto-detection.
#
#                  -v, --verbose
#                    Show each step's full output. Off by default: steps print only a
#                    pass/fail summary (the long-running java/Maven gate is hidden
#                    entirely). Turn it on to stream the Maven build live and see every
#                    linter's output — useful when a run fails and you need the detail.
#
#                  Valid steps:
#                    - docker      Lint the Dockerfiles with hadolint
#                    - grype       Build the runtime image and scan it for CVEs with grype
#                    - java        Run the full Maven gate (mvn clean install -Dall)
#                    - javascript  Lint JavaScript files with eslint
#                    - markdown    Lint Markdown files with markdownlint-cli2
#                    - shellcheck  Lint shell scripts (*.sh) with shellcheck
#                    - typescript  Lint TypeScript files with eslint
#
#                  Examples:
#                    ./lint_and_tests.sh
#                    ./lint_and_tests.sh docker
#                    ./lint_and_tests.sh docker,javascript
#                    ./lint_and_tests.sh -v java
#                    ./lint_and_tests.sh grype
#
# Requirements:
#   - Docker must be installed and available on the system PATH
#   - The code-quality-config submodule must be checked out
#     (git submodule update --init) - it holds every linter's CI config
#   - The `java` step additionally needs the host toolchain the Docker steps don't: a JDK + Maven,
#     Node/npm (the POM css-build exec and the E2E deps), and Playwright browsers
#     (cd e2e && npx playwright install --with-deps). It runs `mvn clean install -Dall` directly on the
#     host, not in the Maven Docker image, because -Dall drives Docker itself (the managed test DB, the
#     E2E stack, and the deployment-smoke image build).
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

ESLINT_BUILD_IMAGE="local/diurnal-eslint:latest"
ESLINT_NODE_IMAGE="node:26.4.0-alpine"
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

VALID_STEPS=("docker" "grype" "java" "javascript" "markdown" "shellcheck" "typescript")

# Verbose off by default: steps print only a pass/fail summary and the java/Maven gate is hidden.
# The -v/--verbose flag (parsed below) flips this to stream/echo every step's full output.
VERBOSE=false

overall_exit_code=0

# Host UID/GID, resolved once, for the `-u` flag of the eslint-based steps (so files the container
# writes are owned by the host user). Assigned separately from use so the command's exit status is
# not masked (SC2312).
HOST_UID="$(id -u)"
HOST_GID="$(id -g)"

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
        echo "✅ Dockerfile lint passed"
    else
        echo "${output}"
        echo "❌ Dockerfile lint failed"
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
    # working dir so the two -c config paths resolve. Order is load-bearing: across multiple -c files
    # grype lets the FIRST file win every scalar key (verified empirically — later files do NOT override
    # earlier scalars), while ignore lists from all files are appended. So the project-level .grype.yaml
    # is passed FIRST (its log level / fail-on-severity / etc. take precedence), and the shared
    # code-quality-config submodule config SECOND to supply the common won't-fix ignore list plus any
    # scalar the project doesn't set. The vulnerability DB is persisted in a named Docker volume (mounted
    # at /grype-db, pointed at by GRYPE_DB_CACHE_DIR) so it survives the --rm and is not re-downloaded
    # every run — grype only
    # re-pulls it when the cached copy is stale. The volume is auto-created by `docker run -v` on first
    # use; nothing lands in the working tree.
    local build_cmd=(docker build -t "${DIURNAL_RUNTIME_IMAGE}" -f Dockerfile .)
    local grype_cmd=(docker run --rm
        -v /var/run/docker.sock:/var/run/docker.sock
        -v "${PWD}":/app
        -v "${GRYPE_DB_CACHE_VOLUME}":/grype-db
        -e GRYPE_DB_CACHE_DIR=/grype-db
        -w /app
        "${GRYPE_DOCKER_IMAGE}"
        -c .grype.yaml
        -c code-quality-config/docker/.grype.yaml
        "${DIURNAL_RUNTIME_IMAGE}")

    # Verbose streams the build and scan live (the scan is slow and, on a cold DB, downloads ~1.6GB —
    # so live progress matters); non-verbose captures each and prints only on failure. Both paths share
    # the command arrays above so the invocation is defined once.
    if [[ "${VERBOSE}" == true ]]; then
        echo "→ Building ${DIURNAL_RUNTIME_IMAGE}"
        if ! "${build_cmd[@]}"; then
            echo "❌ Grype scan failed (could not build ${DIURNAL_RUNTIME_IMAGE})"
            overall_exit_code=1
            return
        fi
        docker pull "${GRYPE_DOCKER_IMAGE}" >/dev/null
        echo "→ Starting Grype scan on ${DIURNAL_RUNTIME_IMAGE}"
        if "${grype_cmd[@]}"; then
            echo "✅ Grype scan passed"
        else
            echo "❌ Grype scan failed"
            overall_exit_code=1
        fi
        return
    fi

    local build_output
    if ! build_output=$("${build_cmd[@]}" 2>&1); then
        echo "${build_output}" | tail -30
        echo "❌ Grype scan failed (could not build ${DIURNAL_RUNTIME_IMAGE})"
        overall_exit_code=1
        return
    fi

    docker pull "${GRYPE_DOCKER_IMAGE}" >/dev/null

    if output=$("${grype_cmd[@]}" 2>&1); then
        echo "✅ Grype scan passed"
    else
        echo "${output}"
        echo "❌ Grype scan failed"
        overall_exit_code=1
    fi
}

run_java() {
    echo
    echo "Running the full Java gate with [mvn clean install -Dall]"
    # -Dall drives the CSS build (Node), a managed test DB, the E2E stack and the deployment-smoke image
    # build (all via Docker), so — unlike the other steps — it runs against the host toolchain (JDK +
    # Maven + Node + Playwright + a Docker daemon) rather than the Node-less Maven Docker image.
    #
    # The build is long and its output is enormous, so by default it is hidden and only the pass/fail
    # summary is printed (matching the other steps). Re-run with -v to stream the full Maven output
    # live — or run `mvn clean install -Dall` yourself — when a failure needs investigating.
    if [[ "${VERBOSE}" == true ]]; then
        if mvn clean install -Dall; then
            echo "✅ Java lints and tests passed"
        else
            echo "❌ Java lints and tests failed"
            overall_exit_code=1
        fi
    elif mvn clean install -Dall >/dev/null 2>&1; then
        echo "✅ Java lints and tests passed"
    else
        echo "❌ Java lints and tests failed (re-run with -v, or 'mvn clean install -Dall', for the full output)"
        overall_exit_code=1
    fi
}

run_javascript() {
    echo
    echo "Running JavaScript lint using [${ESLINT_NODE_IMAGE}]"
    if ! ensure_eslint_image; then
        overall_exit_code=1
        return
    fi
    if output=$(docker run --rm \
        -u "${HOST_UID}:${HOST_GID}" \
        -e HOME=/tmp \
        -v "${PWD}":/app \
        -w /app \
        --entrypoint eslint \
        "${ESLINT_BUILD_IMAGE}" \
        --config code-quality-config/javascript/eslint.config.cjs \
        "tailwind.config.js" "scripts/*.cjs" 2>&1); then
        [[ "${VERBOSE}" == true && -n "${output}" ]] && echo "${output}"
        echo "✅ JavaScript lint passed"
    else
        echo "${output}"
        echo "❌ JavaScript lint failed"
        overall_exit_code=1
    fi
}

run_typescript() {
    echo
    echo "Running TypeScript lint using [${ESLINT_NODE_IMAGE}]"
    if ! ensure_eslint_image; then
        overall_exit_code=1
        return
    fi
    # The TS config is type-aware (parserOptions.project) so the e2e dependencies must be installed
    # for the type-checker to resolve imports. eslint is run from within e2e so it picks up
    # e2e/tsconfig.json and e2e/node_modules; the config path is relative to that dir.
    if output=$(docker run --rm \
        -u "${HOST_UID}:${HOST_GID}" \
        -e HOME=/tmp \
        -e npm_config_cache=/tmp/.npm \
        -v "${PWD}":/app \
        -w /app/e2e \
        --entrypoint sh \
        "${ESLINT_BUILD_IMAGE}" \
        -c "npm ci --no-audit --no-fund && \
            eslint \
            --config ../code-quality-config/typescript/eslint.config.mjs \
            '**/*.ts'" 2>&1); then
        [[ "${VERBOSE}" == true && -n "${output}" ]] && echo "${output}"
        echo "✅ TypeScript lint passed"
    else
        echo "${output}"
        echo "❌ TypeScript lint failed"
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
        "!.claude/**" "!RELEASE_NOTES.md" "!e2e/playwright-report/**" "!e2e/test-results/**" 2>&1); then
        [[ "${VERBOSE}" == true && -n "${output}" ]] && echo "${output}"
        echo "✅ Markdown lint passed"
    else
        echo "${output}"
        echo "❌ Markdown lint failed"
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
    if [[ "${#files[@]}" -eq 0 ]]; then
        echo "✅ Shell script lint passed (no shell scripts found)"
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
        echo "✅ Shell script lint passed"
    else
        echo "${output}"
        echo "❌ Shell script lint failed"
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
    local java_changed_files=()
    local file

    while IFS= read -r file; do
        [[ -z "${file}" ]] && continue
        [[ "${file}" == "Dockerfile" || "${file}" =~ ^sandbox/Dockerfile || "${file}" == ".dockerignore" || "${file}" =~ ^docker-compose || "${file}" =~ ^code-quality-config/docker/ ]] && run_docker=true
        # Grype re-scans when the contents of the runtime image change: the runtime Dockerfile (base
        # images, package pins, copy layout), the build context filter, or the ignore list itself. The
        # pom.xml path (bundled Java deps) is handled below with the same version-bump suppression as
        # java; sandbox/Dockerfile and docker-compose don't affect the published runtime image.
        [[ "${file}" == "Dockerfile" || "${file}" == ".dockerignore" || "${file}" == ".grype.yaml" ]] && run_grype=true
        if [[ "${file}" =~ ^src/ || "${file}" == "pom.xml" || "${file}" =~ ^code-quality-config/java/ ]]; then
            run_java=true
            java_changed_files+=("${file}")
        fi
        [[ "${file}" == "tailwind.config.js" || "${file}" =~ ^scripts/.*\.cjs$ || "${file}" =~ ^code-quality-config/javascript/ ]] && run_javascript=true
        [[ "${file}" =~ ^e2e/.*\.ts$ || "${file}" =~ ^e2e/tsconfig\.json$ || "${file}" =~ ^e2e/package(-lock)?\.json$ || "${file}" =~ ^code-quality-config/typescript/ ]] && run_typescript=true
        [[ "${file}" =~ \.md$ || "${file}" =~ ^code-quality-config/markdown/ ]] && run_markdown=true
        [[ "${file}" =~ \.sh$ || "${file}" =~ ^code-quality-config/shellscript/ ]] && run_shellcheck=true
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
    [[ "${run_shellcheck}" == true ]] && echo "shellcheck"
    [[ "${run_typescript}" == true ]] && echo "typescript"
}

# Parse flags (accepted in any position); everything else is collected as the positional steps arg.
positional=()
while [[ $# -gt 0 ]]; do
    case "${1}" in
    -v | --verbose) VERBOSE=true ;;
    --) shift; positional+=("$@"); break ;;
    -*)
        echo "❌ Unknown option: '${1}'. Supported: -v, --verbose"
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

# Parse and validate steps
if [[ $# -eq 0 ]]; then
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

# Execute steps
for step in "${steps[@]}"; do
    case "${step}" in
    docker) run_docker ;;
    grype) run_grype ;;
    java) run_java ;;
    javascript) run_javascript ;;
    markdown) run_markdown ;;
    shellcheck) run_shellcheck ;;
    typescript) run_typescript ;;
    *) ;; # unreachable: steps are validated against VALID_STEPS above
    esac
done

if [[ "${overall_exit_code}" -ne 0 ]]; then
    echo
    echo "❌ One or more steps failed"
    exit 1
fi
