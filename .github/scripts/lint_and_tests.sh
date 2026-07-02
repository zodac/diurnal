#!/bin/bash
# ------------------------------------------------------------------------------
# Script Name:     lint_and_tests.sh
#
# Description:     Lints and tests the project using Docker.
#
# Usage:           ./lint_and_tests.sh [steps]
#
#                  [steps] is an optional comma-separated list of steps to run.
#                  If omitted, only steps whose relevant files have changed since
#                  the most recent semver git tag are run. If no tag exists, all
#                  steps are run. Pass explicit steps to override auto-detection.
#
#                  Valid steps:
#                    docker      - Lint the Dockerfiles with hadolint
#                    java         - Run Java lints and unit tests with Maven
#                    javascript  - Lint JavaScript files with eslint
#                    markdown    - Lint Markdown files with markdownlint-cli2
#                    typescript  - Lint TypeScript files with eslint
#
#                  Examples:
#                    ./lint_and_tests.sh
#                    ./lint_and_tests.sh docker
#                    ./lint_and_tests.sh docker,javascript
#
# Requirements:
#   - Docker must be installed and available on the system PATH
#   - The code-quality-config submodule must be checked out
#     (git submodule update --init) - it holds every linter's CI config
#
# Exit Codes:
#   - 0: All linting and tests passed successfully
#   - Non-zero: One or more linting errors or test failures occurred
# ------------------------------------------------------------------------------

set -uo pipefail

trap 'echo; echo "❌ Interrupted"; exit 130' INT

ESLINT_BUILD_IMAGE="local/diurnal-eslint:latest"
ESLINT_NODE_IMAGE="node:26.4.0-alpine"
HADOLINT_DOCKER_IMAGE="hadolint/hadolint:v2.14.0-alpine"
MARKDOWNLINT_DOCKER_IMAGE="davidanson/markdownlint-cli2:v0.23.0"
MAVEN_DOCKER_IMAGE="maven:3.9.16-eclipse-temurin-26"

VALID_STEPS=("docker" "java" "javascript" "markdown" "typescript")

overall_exit_code=0

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
        echo "✅ Dockerfile lint passed"
    else
        echo "${output}"
        echo "❌ Dockerfile lint failed"
        overall_exit_code=1
    fi
}

run_java() {
    echo
    echo "Running Java lints and unit tests using [${MAVEN_DOCKER_IMAGE}]"
    docker pull "${MAVEN_DOCKER_IMAGE}" >/dev/null
    # Ensure the shared Maven cache exists and is owned by the invoking user before the bind mount,
    # otherwise Docker auto-creates it as root and the non-root container user cannot write to it.
    mkdir -p "${HOME}/.m2"
    # -Dcss.build.skip=true: the Node-less Maven image cannot run the css-build exec (npm run css).
    # -Dlint -Dtests: linters + surefire unit tests only (no *IT / E2E / deployment-smoke / DB / Docker).
    if output=$(docker run --rm \
        -u "$(id -u):$(id -g)" \
        -v "${PWD}":/app \
        -v "${HOME}/.m2":/var/maven/.m2 \
        -w /app \
        --entrypoint mvn \
        "${MAVEN_DOCKER_IMAGE}" \
        -Duser.home=/var/maven clean install -Dlint -Dtests -Dcss.build.skip=true 2>&1); then
        echo "✅ Java lints and unit tests passed"
    else
        echo "${output}"
        echo "❌ Java lints and unit tests failed"
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
        -u "$(id -u):$(id -g)" \
        -e HOME=/tmp \
        -v "${PWD}":/app \
        -w /app \
        --entrypoint eslint \
        "${ESLINT_BUILD_IMAGE}" \
        --config code-quality-config/javascript/eslint.config.cjs \
        "tailwind.config.js" "scripts/*.cjs" 2>&1); then
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
        -u "$(id -u):$(id -g)" \
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
        "!.claude/**" "!RELEASE_NOTES.md" 2>&1); then
        echo "✅ Markdown lint passed"
    else
        echo "${output}"
        echo "❌ Markdown lint failed"
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

    local run_docker=false run_java=false run_javascript=false run_markdown=false run_typescript=false
    local java_changed_files=()
    local file

    while IFS= read -r file; do
        [[ -z "${file}" ]] && continue
        [[ "${file}" == "Dockerfile" || "${file}" =~ ^sandbox/Dockerfile || "${file}" == ".dockerignore" || "${file}" =~ ^docker-compose || "${file}" =~ ^code-quality-config/docker/ ]] && run_docker=true
        if [[ "${file}" =~ ^src/ || "${file}" == "pom.xml" || "${file}" =~ ^code-quality-config/java/ ]]; then
            run_java=true
            java_changed_files+=("${file}")
        fi
        [[ "${file}" == "tailwind.config.js" || "${file}" =~ ^scripts/.*\.cjs$ || "${file}" =~ ^code-quality-config/javascript/ ]] && run_javascript=true
        [[ "${file}" =~ ^e2e/.*\.ts$ || "${file}" =~ ^e2e/tsconfig\.json$ || "${file}" =~ ^e2e/package(-lock)?\.json$ || "${file}" =~ ^code-quality-config/typescript/ ]] && run_typescript=true
        [[ "${file}" =~ \.md$ || "${file}" =~ ^code-quality-config/markdown/ ]] && run_markdown=true
    done < <(
        {
            git diff --name-only "${latest_tag}..HEAD"
            git diff --name-only
            git diff --name-only --cached
            git ls-files --others --exclude-standard
        } | sort -u
    )

    # Suppress the java step if the only changes are comments
    if [[ "${run_java}" == true ]]; then
        local non_comment_diff
        non_comment_diff=$(
            {
                git diff "${latest_tag}..HEAD" -- "${java_changed_files[@]}" 2>/dev/null
                git diff -- "${java_changed_files[@]}" 2>/dev/null
                git diff --cached -- "${java_changed_files[@]}" 2>/dev/null
            } | grep '^[+-]' | grep -v '^---\|^+++' \
              | grep -vE '^[+-][[:space:]]*(//|/\*\*?|\*|<!--|-->)' \
              | grep -vE '^[+-][[:space:]]*$'
        )
        if [[ -z "${non_comment_diff}" ]]; then
            run_java=false
            echo "Skipping java: only comment changes detected" >&2
        fi
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
        grep -qE '^[+-][[:space:]]*(ESLINT_BUILD_IMAGE|ESLINT_NODE_IMAGE)='       <<<"${script_diff}" && run_javascript=true
        grep -qE '^[+-][[:space:]]*(ESLINT_BUILD_IMAGE|ESLINT_NODE_IMAGE)='       <<<"${script_diff}" && run_typescript=true
        grep -qE '^[+-][[:space:]]*MAVEN_DOCKER_IMAGE='                           <<<"${script_diff}" && run_java=true
        grep -qE '^[+-][[:space:]]*MARKDOWNLINT_DOCKER_IMAGE='                    <<<"${script_diff}" && run_markdown=true
    fi

    [[ "${run_docker}"     == true ]] && echo "docker"
    [[ "${run_java}"       == true ]] && echo "java"
    [[ "${run_javascript}" == true ]] && echo "javascript"
    [[ "${run_markdown}"   == true ]] && echo "markdown"
    [[ "${run_typescript}" == true ]] && echo "typescript"
}

# Parse and validate steps
if [[ $# -eq 0 ]]; then
    mapfile -t steps < <(detect_changed_steps)
    if [[ ${#steps[@]} -eq 0 ]]; then
        echo "No relevant changes detected since last tag; nothing to run"
        exit 0
    fi
    echo "Running steps: $(IFS=', '; echo "${steps[*]}")"
else
    IFS=',' read -ra steps <<<"${1}"
    for step in "${steps[@]}"; do
        pattern=" ${step} "
        if [[ ! " ${VALID_STEPS[*]} " =~ $pattern ]]; then
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
    java) run_java ;;
    javascript) run_javascript ;;
    markdown) run_markdown ;;
    typescript) run_typescript ;;
    esac
done

if [[ "${overall_exit_code}" -ne 0 ]]; then
    echo
    echo "❌ One or more steps failed"
    exit 1
fi
