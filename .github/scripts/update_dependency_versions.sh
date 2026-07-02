#!/bin/bash
# ------------------------------------------------------------------------------
# Script Name:  update_dependency_versions.sh
#
# Description:  Updates pinned tool and package versions across the project:
#                 - parent-pom version in pom.xml
#                 - Java (major) in pom.xml, Dockerfile, sandbox/Dockerfile, workflows
#                 - Maven (full) in pom.xml, Dockerfile, sandbox/Dockerfile, workflows
#                 - Node (full/major) in Dockerfile, sandbox/Dockerfile
#                 - Docker image pins in .github/scripts/lint_and_tests.sh
#                   (node when confirmed; hadolint + markdownlint-cli2 best-effort)
#                 - Ubuntu packages (# BEGIN/END UBUNTU PACKAGES blocks)
#                 - Debian packages (# BEGIN/END DEBIAN PACKAGES blocks)
#                 - Alpine packages (# BEGIN/END ALPINE PACKAGES blocks)
#                 - Git submodules (latest tag, best-effort)
#                 - GitHub Actions (latest release tag, best-effort)
#
# Usage:        .github/scripts/update_dependency_versions.sh
#               (Run from the repository root.)
#
# Requirements: bash, awk, curl, docker, git, jq, sed
#
# Exit codes:   0 — success (including best-effort partial updates)
#               1 — hard failure (missing required file)
# ------------------------------------------------------------------------------

set -euo pipefail

DOCKERFILE="./Dockerfile"
SANDBOX_DOCKERFILE="./sandbox/Dockerfile"
POM_XML="./pom.xml"
WORKFLOWS_DIR=".github/workflows"
GITMODULES_FILE=".gitmodules"
LINT_SCRIPT=".github/scripts/lint_and_tests.sh"

# Node version resolved (and confirmed to exist) by update_node, consumed by update_lint_script for
# the ESLINT_NODE_IMAGE bump. Empty = not confirmed this run. (The lint script no longer pins a Maven
# image — its `java` step runs `mvn clean install -Dall` on the host toolchain — so java/maven versions
# are no longer exposed here; only the Dockerfiles' maven image is bumped, from update_{java,maven}.)
LINT_NODE_ALPINE=""

# ── Output helpers ────────────────────────────────────────────────────────────

ok()   { echo "  ✅ ${*}"; }
warn() { echo "  ⚠️  ${*}" >&2; }

# ── curl wrappers ─────────────────────────────────────────────────────────────

curl_get() { curl -fsSL "${@}"; }

if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    github_curl() { curl -fsSL -H "Authorization: Bearer ${GITHUB_TOKEN}" "${@}"; }
else
    github_curl() { curl -fsSL "${@}"; }
fi

# Returns 0 if the Docker Hub tag exists, 1 otherwise.
hub_tag_exists() {
    local repo="${1}" tag="${2}"
    local status
    status=$(curl -fsSL -o /dev/null -w "%{http_code}" \
        "https://hub.docker.com/v2/repositories/${repo}/tags/${tag}")
    [[ "${status}" == "200" ]]
}

# ── 1. Java ───────────────────────────────────────────────────────────────────
# Updates: pom.xml <java-release>, Dockerfile maven stage (major),
#          Dockerfile jre stage (full tag), sandbox/Dockerfile jdk stage (full tag)
#          + maven stage (major), all workflow java-version fields.
# Pre-condition: eclipse-temurin jdk tag exists on Docker Hub,
#                AND maven:{current_maven}-eclipse-temurin-{new_major} exists.

update_java() {
    echo
    echo "🔍 Fetching latest Java version..."

    local latest_major
    latest_major=$(curl_get "https://api.adoptium.net/v3/info/available_releases" \
        | jq -r '.most_recent_feature_release // empty')
    if [[ -z "${latest_major}" ]]; then
        warn "Could not fetch Java version from Adoptium, skipping"
        return 0
    fi
    echo "  Latest Java major: ${latest_major}"

    # Fetch tags using both name-prefix formats Eclipse Temurin uses for a given major:
    #   {major}_<build>          (initial GA,         e.g. 26_35)
    #   {major}.<n>.<n>_<build>  (maintenance release, e.g. 26.0.1_8)
    # A single ?name={major} query with ordering=-last_updated risks burying the plain -jdk
    # tag behind 100+ Alpine/Noble/Windows variants that were pushed more recently.
    # Two targeted prefix queries cover both formats and keep each result set small.
    local raw_tags
    raw_tags=$(
        curl_get "https://hub.docker.com/v2/repositories/library/eclipse-temurin/tags?name=${latest_major}_&page_size=100&ordering=-last_updated" \
            | jq -r '.results[].name'
        curl_get "https://hub.docker.com/v2/repositories/library/eclipse-temurin/tags?name=${latest_major}.&page_size=100&ordering=-last_updated" \
            | jq -r '.results[].name'
    )

    local jdk_tag
    jdk_tag=$(echo "${raw_tags}" \
        | grep -E "^${latest_major}[_.][0-9].*-jdk$" \
        | sort -t_ -k1,1V -k2,2n \
        | tail -1)
    if [[ -z "${jdk_tag}" ]]; then
        warn "No eclipse-temurin:${latest_major}*-jdk tag on Docker Hub, skipping Java update"
        return 0
    fi
    echo "  eclipse-temurin jdk tag: ${jdk_tag}"

    # The Dockerfile maven build stage image combines both versions.
    # Verify the current Maven version still resolves against the new Java major.
    local current_maven
    current_maven=$(grep -oP '<maven-release>\K[^<]+' "${POM_XML}")
    local maven_java_tag="${current_maven}-eclipse-temurin-${latest_major}"
    if ! hub_tag_exists "library/maven" "${maven_java_tag}"; then
        warn "maven:${maven_java_tag} not on Docker Hub, skipping Java update"
        return 0
    fi
    echo "  maven:${maven_java_tag} confirmed on Docker Hub"

    # ── Apply ──────────────────────────────────────────────────────────────────

    # pom.xml
    sed -i "s|<java-release>[0-9]*</java-release>|<java-release>${latest_major}</java-release>|" "${POM_XML}"

    # Dockerfile: java major in the maven build stage image tag
    sed -i "s|eclipse-temurin-[0-9]* AS build|eclipse-temurin-${latest_major} AS build|" "${DOCKERFILE}"

    # Dockerfile: full tag in the jre stage (ends with "-jdk", then a space or end-of-line before "AS")
    sed -i "s|FROM eclipse-temurin:[^ ]*-jdk |FROM eclipse-temurin:${jdk_tag} |" "${DOCKERFILE}"

    # sandbox/Dockerfile: full tag in the jdk source stage (plain "-jdk", then "AS jdk")
    sed -i "s|FROM eclipse-temurin:[^ ]*-jdk AS jdk|FROM eclipse-temurin:${jdk_tag} AS jdk|" "${SANDBOX_DOCKERFILE}"

    # sandbox/Dockerfile: java major in the maven source stage image tag
    sed -i "s|eclipse-temurin-[0-9]* AS maven|eclipse-temurin-${latest_major} AS maven|" "${SANDBOX_DOCKERFILE}"

    # All workflow files
    for workflow in "${WORKFLOWS_DIR}"/*.yml; do
        sed -i "s|java-version: '[0-9]*'|java-version: '${latest_major}'|g" "${workflow}"
    done

    ok "Java updated → major ${latest_major} (jdk: ${jdk_tag})"
}

# ── 2. Maven ──────────────────────────────────────────────────────────────────
# Updates: pom.xml <maven-release>, Dockerfile maven stage,
#          sandbox/Dockerfile maven source stage, all workflow maven-version fields.
# Pre-condition: maven:{new_version}-eclipse-temurin-{java_major} exists on Docker Hub.

update_maven() {
    echo
    echo "🔍 Fetching latest Maven version..."

    local latest_version
    latest_version=$(github_curl "https://api.github.com/repos/apache/maven/releases/latest" \
        | jq -r '.tag_name // empty')
    if [[ -z "${latest_version}" ]]; then
        warn "Could not fetch Maven version from GitHub, skipping"
        return 0
    fi
    latest_version="${latest_version#maven-}"
    echo "  Latest Maven: ${latest_version}"

    # Read the java major currently in pom.xml (may have been updated by update_java above)
    local java_major
    java_major=$(grep -oP '<java-release>\K[^<]+' "${POM_XML}")
    local docker_tag="${latest_version}-eclipse-temurin-${java_major}"

    if ! hub_tag_exists "library/maven" "${docker_tag}"; then
        warn "maven:${docker_tag} not on Docker Hub, skipping Maven update"
        return 0
    fi
    echo "  maven:${docker_tag} confirmed on Docker Hub"

    # ── Apply ──────────────────────────────────────────────────────────────────

    # pom.xml
    sed -i "s|<maven-release>[^<]*</maven-release>|<maven-release>${latest_version}</maven-release>|" "${POM_XML}"

    # Dockerfile: maven version in the build stage image tag
    sed -i "s|FROM maven:[^-]*-eclipse-temurin|FROM maven:${latest_version}-eclipse-temurin|" "${DOCKERFILE}"

    # sandbox/Dockerfile: maven version in the maven source stage image tag
    sed -i "s|FROM maven:[^-]*-eclipse-temurin|FROM maven:${latest_version}-eclipse-temurin|" "${SANDBOX_DOCKERFILE}"

    # All workflow files
    for workflow in "${WORKFLOWS_DIR}"/*.yml; do
        sed -i "s|maven-version: '[0-9.]*'|maven-version: '${latest_version}'|g" "${workflow}"
    done

    ok "Maven updated → ${latest_version}"
}

# ── 3. Node ───────────────────────────────────────────────────────────────────
# Updates: Dockerfile node stages (full version-alpine tag),
#          sandbox/Dockerfile node source stage (full version-trixie tag).
# Pre-condition: node:{version}-alpine (Dockerfile) AND node:{version}-trixie
#                (sandbox/Dockerfile) both exist on Docker Hub.

update_node() {
    echo
    echo "🔍 Fetching latest Node.js version..."

    local latest_version
    latest_version=$(github_curl "https://api.github.com/repos/nodejs/node/releases/latest" \
        | jq -r '.tag_name // empty')
    if [[ -z "${latest_version}" ]]; then
        warn "Could not fetch Node.js version from GitHub, skipping"
        return 0
    fi
    latest_version="${latest_version#v}"
    local latest_major="${latest_version%%.*}"
    local alpine_tag="${latest_version}-alpine"
    local trixie_tag="${latest_version}-trixie"
    echo "  Latest Node: ${latest_version} (major: ${latest_major})"

    # Check node:{version}-alpine on Docker Hub (used in Dockerfile)
    if ! hub_tag_exists "library/node" "${alpine_tag}"; then
        warn "node:${alpine_tag} not on Docker Hub, skipping Node update"
        return 0
    fi
    echo "  node:${alpine_tag} confirmed on Docker Hub"

    # Check node:{version}-trixie on Docker Hub (the source stage in sandbox/Dockerfile;
    # -trixie matches the Debian 13 runtime base's glibc)
    if ! hub_tag_exists "library/node" "${trixie_tag}"; then
        warn "node:${trixie_tag} not on Docker Hub, skipping Node update"
        return 0
    fi
    echo "  node:${trixie_tag} confirmed on Docker Hub"

    # Node version confirmed (alpine + trixie both exist) → expose for the lint script (uses -alpine).
    LINT_NODE_ALPINE="${latest_version}"

    # ── Apply ──────────────────────────────────────────────────────────────────

    # Dockerfile: both node stages use the same alpine tag
    sed -i "s|FROM node:[0-9.]*-alpine|FROM node:${alpine_tag}|g" "${DOCKERFILE}"

    # sandbox/Dockerfile: node source stage uses the trixie tag
    sed -i "s|FROM node:[0-9.]*-trixie|FROM node:${trixie_tag}|g" "${SANDBOX_DOCKERFILE}"

    ok "Node updated → ${latest_version} (major: ${latest_major})"
}

# ── 3b. lint_and_tests.sh Docker image pins ───────────────────────────────────
# Keeps the pinned Docker images in .github/scripts/lint_and_tests.sh in sync:
#   - ESLINT_NODE_IMAGE (node) is bumped from the node version resolved above, only if it was
#     confirmed this run (an unconfirmed tag must never be pinned). The lint script's `java` step
#     runs `mvn clean install -Dall` on the host toolchain, so there is no Maven image pin to bump.
#   - HADOLINT_DOCKER_IMAGE and MARKDOWNLINT_DOCKER_IMAGE are independent best-effort: each is
#     bumped to the latest GitHub release whose corresponding Docker Hub tag is confirmed to exist.

update_lint_script() {
    echo
    echo "🔍 Updating Docker image pins in ${LINT_SCRIPT}..."

    if [[ ! -f "${LINT_SCRIPT}" ]]; then
        warn "No ${LINT_SCRIPT}, skipping"
        return 0
    fi

    # ── node (eslint image) ───────────────────────────────────────────────────
    if [[ -n "${LINT_NODE_ALPINE}" ]]; then
        local node_image="node:${LINT_NODE_ALPINE}-alpine"
        sed -i "s|ESLINT_NODE_IMAGE=\"node:[^\"]*\"|ESLINT_NODE_IMAGE=\"${node_image}\"|" "${LINT_SCRIPT}"
        ok "node image → ${node_image}"
    else
        warn "Skipping node image bump (node unresolved this run)"
    fi

    # ── hadolint (best-effort) ────────────────────────────────────────────────
    local hadolint_tag
    hadolint_tag=$(github_curl "https://api.github.com/repos/hadolint/hadolint/releases/latest" \
        | jq -r '.tag_name // empty')
    if [[ -z "${hadolint_tag}" ]]; then
        warn "Could not fetch hadolint version, skipping"
    elif ! hub_tag_exists "hadolint/hadolint" "${hadolint_tag}-alpine"; then
        warn "hadolint/hadolint:${hadolint_tag}-alpine not on Docker Hub, skipping"
    else
        sed -i "s|HADOLINT_DOCKER_IMAGE=\"hadolint/hadolint:[^\"]*\"|HADOLINT_DOCKER_IMAGE=\"hadolint/hadolint:${hadolint_tag}-alpine\"|" "${LINT_SCRIPT}"
        ok "hadolint image → hadolint/hadolint:${hadolint_tag}-alpine"
    fi

    # ── markdownlint-cli2 (best-effort) ───────────────────────────────────────
    # markdownlint-cli2 publishes NO GitHub Releases (only git tags), so releases/latest 404s.
    # Use the tags API and pick the highest vX.Y.Z via sort -V (the API's order is not guaranteed).
    local mdl_tag
    mdl_tag=$(github_curl "https://api.github.com/repos/DavidAnson/markdownlint-cli2/tags" \
        | jq -r '.[].name // empty' \
        | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' \
        | sort -V | tail -1)
    if [[ -z "${mdl_tag}" ]]; then
        warn "Could not fetch markdownlint-cli2 version, skipping"
    elif ! hub_tag_exists "davidanson/markdownlint-cli2" "${mdl_tag}"; then
        warn "davidanson/markdownlint-cli2:${mdl_tag} not on Docker Hub, skipping"
    else
        sed -i "s|MARKDOWNLINT_DOCKER_IMAGE=\"davidanson/markdownlint-cli2:[^\"]*\"|MARKDOWNLINT_DOCKER_IMAGE=\"davidanson/markdownlint-cli2:${mdl_tag}\"|" "${LINT_SCRIPT}"
        ok "markdownlint-cli2 image → davidanson/markdownlint-cli2:${mdl_tag}"
    fi

    ok "Lint script Docker images processed"
}

# ── 4. Debian/Ubuntu (apt) packages ───────────────────────────────────────────
# For each # BEGIN/END <LABEL> PACKAGES block (LABEL = UBUNTU or DEBIAN) in the given
# Dockerfile:
#   - Determines the preceding FROM image and queries apt-cache policy in a fresh container
#   - If packages aren't in the base image's default repos (e.g. Docker-engine packages that
#     need the Docker APT repo configured), falls back to building a temporary context image
#     from all Dockerfile content up to that section. Version pins in earlier <LABEL> PACKAGES
#     blocks are stripped so they install cleanly in the context image.
#   - Updates each package's pinned version in-place via sed
# Both labels share this logic (apt on both distros); the label only picks the markers and the
# base image (the Ubuntu-based eclipse-temurin jre stage vs. the Debian sandbox base).
# Best-effort per section.

_parse_apt_candidates() {
    awk '
        /^[a-z0-9]/ { pkg = $1; sub(/:$/, "", pkg) }
        /Candidate:/ { if ($2 != "(none)") print pkg "=" $2 }
    '
}

update_apt_packages() {
    local dockerfile="${1}"
    local label="${2}"
    local start_marker="# BEGIN ${label} PACKAGES"
    local end_marker="# END ${label} PACKAGES"

    if ! grep -q "${start_marker}" "${dockerfile}"; then
        return 0
    fi

    local section_count
    section_count=$(grep -c "${start_marker}" "${dockerfile}")

    echo
    echo "🔍 Updating ${label} packages in ${dockerfile} (${section_count} section(s))..."

    for ((section = 1; section <= section_count; section++)); do
        echo "  --- Section ${section}/${section_count}"

        # Resolve the FROM image that immediately precedes this section
        local base_image
        base_image=$(awk -v start="${start_marker}" -v n="${section}" '
            /^FROM / { img = $2 }
            $0 ~ start { count++; if (count == n) { print img; exit } }
        ' "${dockerfile}")

        if [[ -z "${base_image}" ]]; then
            warn "Could not determine base image for section ${section}, skipping"
            continue
        fi
        echo "    Base image: ${base_image}"

        if ! docker pull "${base_image}" >/dev/null 2>&1; then
            warn "Could not pull ${base_image}, skipping section ${section}"
            continue
        fi

        # Extract the package names from this section (part before '=')
        local package_block
        package_block=$(awk -v start="${start_marker}" -v end="${end_marker}" -v n="${section}" '
            $0 ~ start { count++; if (count == n) in_block = 1 }
            in_block
            $0 ~ end && in_block { in_block = 0 }
        ' "${dockerfile}")

        local package_names=()
        mapfile -t package_names < <(
            echo "${package_block}" | grep -oP '^\s*[a-z0-9.+-]+(?==)' | sed 's/^[[:space:]]*//'
        )

        if [[ "${#package_names[@]}" -eq 0 ]]; then
            warn "No packages found in section ${section}, skipping"
            continue
        fi

        # ── Quick path: query directly from the base image ────────────────────
        local versions_raw
        versions_raw=$(docker run --rm "${base_image}" sh -c \
            "apt-get update -qq -o Acquire::Languages=none 2>/dev/null \
             && apt-cache policy ${package_names[*]}")

        unset apt_versions
        declare -A apt_versions
        while IFS='=' read -r pkg ver; do
            [[ -n "${pkg}" && -n "${ver}" ]] && apt_versions["${pkg}"]="${ver}"
        done < <(echo "${versions_raw}" | _parse_apt_candidates)

        # Detect any package with no Candidate (repo not configured in base image)
        local first_missing=""
        for package in "${package_names[@]}"; do
            if [[ -z "${apt_versions["${package}"]:-}" ]]; then
                first_missing="${package}"
                break
            fi
        done

        # ── Fallback: build a context image that includes the repo setup ──────
        # Extracts all Dockerfile content up to (not including) this section's BEGIN
        # marker and builds a temporary image. Version pins in earlier <label> PACKAGES
        # blocks are stripped (gsub) so they install as latest-available rather than
        # requiring exact versions that may no longer be in the apt cache.
        local temp_image=""
        if [[ -n "${first_missing}" ]]; then
            echo "    '${first_missing}' not in default repos, building context image for section ${section}..."

            local tmpdir
            tmpdir=$(mktemp -d)
            temp_image="dep-update-ctx-$$-${section}"

            awk -v start="${start_marker}" -v end="${end_marker}" -v n="${section}" '
                BEGIN { count = 0; in_prev_block = 0 }
                $0 ~ start {
                    count++
                    if (count == n) exit        # stop before the nth BEGIN
                    in_prev_block = 1; print; next
                }
                $0 ~ end { in_prev_block = 0; print; next }
                in_prev_block {
                    gsub(/="[^"]*"/, "")        # strip version pins → install unpinned
                    print; next
                }
                { print }
            ' "${dockerfile}" > "${tmpdir}/Dockerfile"

            if ! docker build -t "${temp_image}" "${tmpdir}" >/dev/null 2>&1; then
                warn "Context image build failed for section ${section}, skipping"
                rm -rf "${tmpdir}"
                temp_image=""
                continue
            fi
            rm -rf "${tmpdir}"

            # Re-query from the context image (which has the extra repos configured)
            versions_raw=$(docker run --rm "${temp_image}" sh -c \
                "apt-get update -qq -o Acquire::Languages=none 2>/dev/null \
                 && apt-cache policy ${package_names[*]}")

            unset apt_versions
            declare -A apt_versions
            while IFS='=' read -r pkg ver; do
                [[ -n "${pkg}" && -n "${ver}" ]] && apt_versions["${pkg}"]="${ver}"
            done < <(echo "${versions_raw}" | _parse_apt_candidates)
        fi

        # Clean up temp image regardless of outcome
        if [[ -n "${temp_image}" ]]; then
            docker image rm "${temp_image}" >/dev/null 2>&1 || true
        fi

        # Verify all packages resolved after quick or fallback path
        local all_found=true
        for package in "${package_names[@]}"; do
            if [[ -z "${apt_versions["${package}"]:-}" ]]; then
                warn "Could not resolve version for '${package}', skipping section ${section}"
                all_found=false
                break
            fi
            echo "    ${package}=${apt_versions[${package}]}"
        done
        [[ "${all_found}" == "true" ]] || continue

        # Update each package version in-place
        for package in "${package_names[@]}"; do
            sed -i "s|${package}=\"[^\"]*\"|${package}=\"${apt_versions[${package}]}\"|g" "${dockerfile}"
        done
    done

    ok "${label} packages processed in ${dockerfile}"
}

# ── 5. Alpine packages ────────────────────────────────────────────────────────
# For each # BEGIN/END ALPINE PACKAGES block in the given Dockerfile:
#   - Determines the preceding FROM image
#   - Queries `apk search -e` inside a fresh container for each package
#   - Updates each package's pinned version in-place
# Best-effort per section.

update_alpine_packages() {
    local dockerfile="${1}"
    local start_marker="# BEGIN ALPINE PACKAGES"
    local end_marker="# END ALPINE PACKAGES"

    if ! grep -q "${start_marker}" "${dockerfile}"; then
        return 0
    fi

    local section_count
    section_count=$(grep -c "${start_marker}" "${dockerfile}")

    echo
    echo "🔍 Updating Alpine packages in ${dockerfile} (${section_count} section(s))..."

    for ((section = 1; section <= section_count; section++)); do
        echo "  --- Section ${section}/${section_count}"

        # Resolve the preceding FROM image
        local base_image
        base_image=$(awk -v start="${start_marker}" -v n="${section}" '
            /^FROM / { img = $2 }
            $0 ~ start { count++; if (count == n) { print img; exit } }
        ' "${dockerfile}")

        if [[ -z "${base_image}" ]]; then
            warn "Could not determine base image for section ${section}, skipping"
            continue
        fi
        echo "    Base image: ${base_image}"

        if ! docker pull "${base_image}" >/dev/null 2>&1; then
            warn "Could not pull ${base_image}, skipping section ${section}"
            continue
        fi

        # Extract package names
        local package_block
        package_block=$(awk -v start="${start_marker}" -v end="${end_marker}" -v n="${section}" '
            $0 ~ start { count++; if (count == n) in_block = 1 }
            in_block
            $0 ~ end && in_block { in_block = 0 }
        ' "${dockerfile}")

        local package_names=()
        mapfile -t package_names < <(
            echo "${package_block}" | grep -oP '^\s*[a-z0-9.+-]+(?==)' | sed 's/^[[:space:]]*//'
        )

        if [[ "${#package_names[@]}" -eq 0 ]]; then
            warn "No packages found in section ${section}, skipping"
            continue
        fi

        # Query each package version via `apk search -e` in a single container
        # Output format: pkgname-version-release (e.g. imagemagick-7.1.1.43-r0)
        # Strip the package name prefix to isolate the version.
        declare -A alpine_versions=()
        local search_result
        search_result=$(docker run --rm "${base_image}" sh -c \
            "apk update -q 2>/dev/null; apk search -e ${package_names[*]} 2>/dev/null" \
            || true)

        for package in "${package_names[@]}"; do
            local matched_line version
            matched_line=$(echo "${search_result}" | grep -E "^${package}-[0-9]" | head -1 || true)
            if [[ -z "${matched_line}" ]]; then
                continue
            fi
            # Strip the "pkgname-" prefix; everything after is the version string
            version="${matched_line#"${package}-"}"
            if [[ -n "${version}" ]]; then
                alpine_versions["${package}"]="${version}"
            fi
        done

        # Verify all packages resolved
        local all_found=true
        for package in "${package_names[@]}"; do
            if [[ -z "${alpine_versions["${package}"]:-}" ]]; then
                warn "Could not resolve version for '${package}', skipping section ${section}"
                all_found=false
                break
            fi
            echo "    ${package}=${alpine_versions[${package}]}"
        done
        [[ "${all_found}" == "true" ]] || continue

        # Update in-place
        for package in "${package_names[@]}"; do
            sed -i "s|${package}=\"[^\"]*\"|${package}=\"${alpine_versions[${package}]}\"|g" "${dockerfile}"
        done
    done

    ok "Alpine packages processed in ${dockerfile}"
}

# ── 6. Git submodules ─────────────────────────────────────────────────────────
# Fetches each submodule's latest tag and checks it out.
# Best-effort: a failing submodule is warned and skipped.

update_submodules() {
    echo
    echo "🔍 Updating git submodules..."

    if [[ ! -f "${GITMODULES_FILE}" ]]; then
        echo "  No ${GITMODULES_FILE}; nothing to update."
        return 0
    fi

    git submodule update --init --recursive >/dev/null 2>&1 || true

    local paths=()
    mapfile -t paths < <(git config -f "${GITMODULES_FILE}" --get-regexp '\.path$' | awk '{print $2}')

    if [[ "${#paths[@]}" -eq 0 ]]; then
        echo "  No submodule paths defined."
        return 0
    fi

    for path in "${paths[@]}"; do
        if [[ ! -d "${path}/.git" && ! -f "${path}/.git" ]]; then
            warn "Submodule '${path}' not initialised, skipping"
            continue
        fi

        (
            cd "${path}"
            git fetch --tags --quiet 2>/dev/null || { warn "fetch failed for '${path}', skipping"; exit 0; }

            local latest_tag current_tag
            latest_tag="$(git describe --tags "$(git rev-list --tags --max-count=1)" 2>/dev/null || true)"
            current_tag="$(git describe --tags --exact-match 2>/dev/null || echo 'detached')"

            if [[ -z "${latest_tag}" ]]; then
                warn "No tags in submodule '${path}', skipping"
                exit 0
            fi

            echo "    ${path}: current=${current_tag} → latest=${latest_tag}"

            if [[ "${latest_tag}" != "${current_tag}" ]]; then
                git checkout --quiet "${latest_tag}"
                echo "    Updated '${path}' to ${latest_tag}"
            fi
        ) || warn "Error processing submodule '${path}', skipping"
    done

    ok "Submodules processed"
}

# ── 7. Parent POM ────────────────────────────────────────────────────────────
# Fetches all published versions of net.zodac:parent-pom from Maven Central,
# picks the highest using sort -V (semver-aware), and updates pom.xml.

update_parent_pom() {
    echo
    echo "🔍 Fetching latest parent-pom version from Maven Central..."

    local current_version
    current_version=$(awk '
        /<parent>/ { in_parent=1 }
        /<\/parent>/ { in_parent=0 }
        in_parent && /<version>/ {
            gsub(/.*<version>|<\/version>.*/, "")
            print
            exit
        }
    ' "${POM_XML}")

    local latest_version
    latest_version=$(curl_get \
        "https://repo1.maven.org/maven2/net/zodac/parent-pom/maven-metadata.xml" \
        | grep -oP '(?<=<version>)[^<]+' \
        | sort -V | tail -1)

    if [[ -z "${latest_version}" ]]; then
        warn "Could not fetch parent-pom version from Maven Central, skipping"
        return 0
    fi

    if [[ "${current_version}" == "${latest_version}" ]]; then
        echo "  net.zodac:parent-pom=${latest_version} (already up-to-date)"
    else
        echo "  net.zodac:parent-pom: ${current_version} → ${latest_version}"
        awk -v version="${latest_version}" '
            /<parent>/ { in_parent=1 }
            /<\/parent>/ { in_parent=0 }
            in_parent && /<version>/ {
                sub(/<version>[^<]*<\/version>/, "<version>" version "</version>")
            }
            { print }
        ' "${POM_XML}" > "${POM_XML}.tmp" && mv "${POM_XML}.tmp" "${POM_XML}"
    fi

    ok "parent-pom version up-to-date (${latest_version})"
}

# ── 8. GitHub Actions ─────────────────────────────────────────────────────────
# Collects all `uses: owner/repo@version` references from workflow files and
# updates each to its latest GitHub release tag.
# Best-effort: actions that cannot be resolved are warned and left unchanged.

update_github_actions() {
    echo
    echo "🔍 Updating GitHub Actions versions..."

    if [[ ! -d "${WORKFLOWS_DIR}" ]]; then
        warn "No workflows directory at ${WORKFLOWS_DIR}, skipping"
        return 0
    fi

    # Collect unique action references
    local action_refs=()
    mapfile -t action_refs < <(
        grep -rh 'uses:' "${WORKFLOWS_DIR}"/*.yml \
            | grep -oP 'uses:\s+\K[a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+@\S+' \
            | sort -u
    )

    if [[ "${#action_refs[@]}" -eq 0 ]]; then
        echo "  No action references found."
        return 0
    fi

    for ref in "${action_refs[@]}"; do
        local action current_version latest_version
        action="${ref%@*}"
        current_version="${ref#*@}"

        latest_version=$(github_curl \
            "https://api.github.com/repos/${action}/releases/latest" \
            | jq -r '.tag_name // empty')

        if [[ -z "${latest_version}" ]]; then
            warn "Could not fetch latest version for ${action}, skipping"
            continue
        fi

        if [[ "${current_version}" == "${latest_version}" ]]; then
            echo "  ${action}@${latest_version} (already up-to-date)"
        else
            echo "  ${action}: ${current_version} → ${latest_version}"
            for workflow in "${WORKFLOWS_DIR}"/*.yml; do
                sed -i "s|${action}@${current_version}|${action}@${latest_version}|g" "${workflow}"
            done
        fi
    done

    ok "GitHub Actions updated in ${WORKFLOWS_DIR}"
}

# ── Entry point ───────────────────────────────────────────────────────────────

for f in "${DOCKERFILE}" "${SANDBOX_DOCKERFILE}" "${POM_XML}"; do
    if [[ ! -f "${f}" ]]; then
        echo "❌ Required file not found: ${f}"
        exit 1
    fi
done

# Java before Maven: the Maven check reads <java-release> from pom.xml, so
# updating Java first ensures the combined maven image tag is validated against
# the correct (already-updated) Java major.
update_parent_pom || warn "Parent POM update failed, continuing..."
update_java       || warn "Java update failed, continuing..."
update_maven      || warn "Maven update failed, continuing..."
update_node       || warn "Node update failed, continuing..."

# After java/maven/node: propagate the resolved (and confirmed) versions into the lint script.
update_lint_script || warn "Lint script image update failed, continuing..."

update_apt_packages "${DOCKERFILE}"         "UBUNTU" || warn "Ubuntu packages update failed for Dockerfile, continuing..."
update_apt_packages "${SANDBOX_DOCKERFILE}" "DEBIAN" || warn "Debian packages update failed for sandbox/Dockerfile, continuing..."
update_alpine_packages "${DOCKERFILE}"              || warn "Alpine packages update failed for Dockerfile, continuing..."

update_submodules      || warn "Submodules update failed, continuing..."
update_github_actions  || warn "GitHub Actions update failed, continuing..."

echo
echo "✅ Dependency version update complete."
