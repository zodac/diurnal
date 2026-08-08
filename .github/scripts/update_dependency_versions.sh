#!/bin/bash
# ------------------------------------------------------------------------------
# Script Name:  update_dependency_versions.sh
#
# Description:  Updates pinned tool and package versions across the project:
#                 - parent-pom version in pom.xml
#                 - Java (major) in pom.xml, Dockerfile, sandbox/Dockerfile, workflows
#                 - Maven (full) in pom.xml, Dockerfile, sandbox/Dockerfile, workflows
#                 - Node (full/major), as ONE atomic "like" group kept in lockstep: Dockerfile
#                   (css/icons -alpine + the screenshots stage's -trixie node source), sandbox/Dockerfile,
#                   AND the workflows' setup-node node-version
#                 - PostgreSQL image (within the current major) as ONE atomic group: the compose files
#                   (postgres:X-alpine) and the Dockerfile screenshots stage (postgres:X, Debian variant)
#                 - A final verify step HARD-FAILS if either group (node / postgres) has drifted apart
#                 - npm packages in frontend/package.json + tests/package.json (exact pins, no ^/~ ranges),
#                   regenerating each package-lock.json so `npm ci` stays in sync
#                 - Debian packages in the main Dockerfile's screenshots stage (# BEGIN/END DEBIAN
#                   PACKAGES block — cwebp, on the Postgres/Debian base)
#                 - Docker image pins AND the pinned eslint toolchain (eslint, @eslint/js,
#                   @typescript-eslint, globals, typescript) in .github/scripts/lint_and_tests.sh
#                   (node when confirmed; hadolint + markdownlint-cli2 + shellcheck + grype best-effort)
#                   Note: shellcheck installed as an apt/apk package instead (pinned in a
#                   # BEGIN/END … PACKAGES block) is handled generically by the package updaters.
#                 - k6 Docker image pin in tests/run-perf.sh (the perf tier's load generator; best-effort)
#                 - Ubuntu packages (# BEGIN/END UBUNTU PACKAGES blocks)
#                 - Debian packages (# BEGIN/END DEBIAN PACKAGES blocks)
#                 - Alpine packages (# BEGIN/END ALPINE PACKAGES blocks)
#                 - Git submodules (latest tag, best-effort)
#                 - GitHub Actions (latest release tag, best-effort)
#
#               Every resolved version is filtered through is_prerelease() first: a release
#               candidate, alpha/beta, milestone, preview/early-access, nightly or snapshot is
#               NEVER a valid update, whatever the upstream calls "latest".
#
# Usage:        .github/scripts/update_dependency_versions.sh
#               (Run from the repository root.)
#
# Requirements: bash, awk, curl, docker, git, jq, sed
#               (npm is run via Docker, so no host npm is needed)
#
# Exit codes:   0 — success (including best-effort partial updates)
#               1 — hard failure (missing required file)
# ------------------------------------------------------------------------------

# This script is deliberately best-effort under `set -e`: every external step is a predicate
# (hub_tag_exists) or is guarded with `|| warn`/`|| true` so one failure never aborts the run
# (only a missing required file does). That intentional pattern — invoking functions in a
# condition while `set -e` is active — is exactly what SC2310 flags, so disable it file-wide here.
# `set -e` is kept on purpose: it still aborts on unexpected failures inside the apply sections.
# shellcheck disable=SC2310
set -euo pipefail

DOCKERFILE="./Dockerfile"
SANDBOX_DOCKERFILE="./sandbox/Dockerfile"
POM_XML="./pom.xml"
WORKFLOWS_DIR=".github/workflows"
GITMODULES_FILE=".gitmodules"
LINT_SCRIPT=".github/scripts/lint_and_tests.sh"
PERF_SCRIPT="./tests/run-perf.sh"

# Node version resolved (and confirmed to exist) by update_node, consumed by update_lint_script for
# the ESLINT_NODE_IMAGE bump. Empty = not confirmed this run. (The lint script no longer pins a Maven
# image — its `java` step runs `mvn clean install -Dall` on the host toolchain — so java/maven versions
# are no longer exposed here; only the Dockerfiles' maven image is bumped, from update_{java,maven}.)
LINT_NODE_ALPINE=""

# ── Output helpers ────────────────────────────────────────────────────────────

ok()   { echo "  ✅ ${*}"; }
warn() { echo "  ⚠️  ${*}" >&2; }

# ── curl wrappers ─────────────────────────────────────────────────────────────

curl_get() { curl -fsSL --connect-timeout 10 --max-time 60 "${@}"; }

if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    github_curl() { curl -fsSL --connect-timeout 10 --max-time 60 -H "Authorization: Bearer ${GITHUB_TOKEN}" "${@}"; }
else
    github_curl() { curl -fsSL --connect-timeout 10 --max-time 60 "${@}"; }
fi

# Returns 0 if the Docker Hub tag exists, 1 otherwise.
hub_tag_exists() {
    local repo="${1}" tag="${2}"
    local status
    # HEAD only (-I, no body): the response body is otherwise streamed to /dev/null, which on WSL2
    # can abort mid-transfer with "curl: (23) client returned ERROR on write of N bytes". A HEAD has
    # nothing to write. `-f` is deliberately omitted (a 404 is the expected "does not exist" answer,
    # not an error): with it, curl aborts a 404 as "curl: (22) ... 404" on stderr instead of handing
    # back the status code. stderr is suppressed and a hard curl failure (network) returns 1.
    status=$(curl -sIL -o /dev/null -w '%{http_code}' \
        --connect-timeout 10 --max-time 30 \
        "https://hub.docker.com/v2/repositories/${repo}/tags/${tag}" 2>/dev/null) || return 1
    [[ "${status}" == "200" ]]
}

# ── Pre-release filtering ─────────────────────────────────────────────────────
# Nothing that is not a final release may ever be pinned: a release candidate, alpha/beta,
# milestone, preview, early-access build, nightly or snapshot is unstable by definition, and a
# monthly unattended bump is exactly where one must not slip in. Upstreams cannot be trusted to
# say so themselves — Apache Maven published `maven-3.10.0-rc-1` WITHOUT GitHub's pre-release
# flag, so `releases/latest` served it as the latest release — hence the marker check below.
#
# A marker only counts as one when it is a whole token (delimited by a non-alphanumeric, or the
# start/end of the string) optionally followed by digits: `3.10.0-rc-1`, `1.0.0-alpha2` and
# `2.0_beta` are rejected, while `1.5.0-0.1`, `26.0.1_8` and `5:29.7.2-1~debian.13~trixie` are
# not. A bare `m` is deliberately NOT a marker (far too easy to hit by accident) — only the
# `M1`-style milestone form with digits is.
PRERELEASE_MARKERS='alpha|beta|rc|cr|snapshot|milestone|preview|pre|ea|nightly|canary|dev|unstable|m[0-9]+'

# Returns 0 if the given version string is a pre-release, 1 if it is a final release.
is_prerelease() {
    local version="${1,,}"
    [[ "${version}" =~ (^|[^a-z0-9])(${PRERELEASE_MARKERS})[0-9]*([^a-z0-9]|$) ]]
}

# Reads candidate versions on stdin, writes out only the final releases (order preserved).
filter_stable_versions() {
    local version
    while IFS= read -r version; do
        [[ -n "${version}" ]] || continue
        is_prerelease "${version}" || printf '%s\n' "${version}"
    done
}

# Echoes the latest STABLE release tag of a GitHub repo (empty if none could be resolved).
# `releases/latest` alone is not enough: GitHub only skips releases the project remembered to
# FLAG as a pre-release, so an unflagged one is served as latest. When that happens, fall back to
# the HIGHEST-VERSIONED unflagged, non-pre-release entry in the release list.
#
# The fallback deliberately sorts by version (sort -V) rather than taking the API's newest-first
# order: release dates do not run in version order, so a patch cut on an older branch AFTER a
# newer release (a backport, a security fix on an LTS line) is the most recent release while
# being the lower version, and picking it would silently walk the pin BACKWARDS.
latest_stable_github_release() {
    local repo="${1}"

    local tag
    tag=$(github_curl "https://api.github.com/repos/${repo}/releases/latest" | jq -r '.tag_name // empty')
    if [[ -n "${tag}" ]] && ! is_prerelease "${tag}"; then
        printf '%s\n' "${tag}"
        return 0
    fi
    [[ -n "${tag}" ]] && warn "Ignoring pre-release '${tag}' offered as ${repo}'s latest release"

    # sort -V compares the numeric runs as numbers, so a shared tag prefix is harmless
    # (`maven-3.9.16` > `maven-3.9.9`) — and `tail` drains its input, so no SIGPIPE under pipefail.
    github_curl "https://api.github.com/repos/${repo}/releases?per_page=100" \
        | jq -r '.[] | select(.draft == false and .prerelease == false) | .tag_name // empty' \
        | filter_stable_versions \
        | sort -V | tail -1
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
        | filter_stable_versions \
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
    latest_version=$(latest_stable_github_release "apache/maven")
    if [[ -z "${latest_version}" ]]; then
        warn "Could not fetch a stable Maven version from GitHub, skipping"
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
# Updates the WHOLE node "like" group to one version, atomically (all together, or — if the pre-condition
# below fails — none): the Dockerfile node stages (css/icons -alpine + the screenshots stage's -trixie
# source, Debian/glibc so Playwright's Chromium runs), the sandbox/Dockerfile node source stage, AND the
# workflows' setup-node `node-version` (the CI toolchain node, kept identical to the image node). The
# post-run verify_version_sync guard hard-fails if any of these ever drift apart.
# Pre-condition: node:{version}-alpine AND node:{version}-trixie both exist on Docker Hub (the -trixie
#                tag now backs both the Dockerfile screenshots source and the sandbox source). If either
#                is missing the whole group is left unchanged, so it never half-updates.

update_node() {
    echo
    echo "🔍 Fetching latest Node.js version..."

    local latest_version
    latest_version=$(latest_stable_github_release "nodejs/node")
    if [[ -z "${latest_version}" ]]; then
        warn "Could not fetch a stable Node.js version from GitHub, skipping"
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

    # Dockerfile: the css + icons stages use the same alpine tag
    sed -i "s|FROM node:[0-9.]*-alpine|FROM node:${alpine_tag}|g" "${DOCKERFILE}"

    # Dockerfile: the screenshots stage's node source uses the trixie (Debian/glibc) tag
    sed -i "s|FROM node:[0-9.]*-trixie|FROM node:${trixie_tag}|g" "${DOCKERFILE}"

    # sandbox/Dockerfile: node source stage uses the trixie tag
    sed -i "s|FROM node:[0-9.]*-trixie|FROM node:${trixie_tag}|g" "${SANDBOX_DOCKERFILE}"

    # All workflow files: the setup-node toolchain version (full), kept identical to the image node so
    # the whole node group moves in lockstep.
    for workflow in "${WORKFLOWS_DIR}"/*.yml; do
        sed -i "s|node-version: '[0-9.]*'|node-version: '${latest_version}'|g" "${workflow}"
    done

    ok "Node updated → ${latest_version} (major: ${latest_major})"
}

# ── 3a. PostgreSQL ─────────────────────────────────────────────────────────────
# Updates the pinned Postgres image everywhere it appears — the runtime/dev/test/docs compose files
# (postgres:X-alpine) AND the Dockerfile screenshots stage (postgres:X, the Debian variant used because
# Playwright's Chromium needs glibc). Deliberately stays WITHIN the current major: a Postgres major
# upgrade needs a manual data migration for existing deployments, so it is not automated here. Bumps to
# the highest minor/patch whose -alpine (compose) AND Debian (Dockerfile) tags both exist. Best-effort.

update_postgres() {
    echo
    echo "🔍 Fetching latest PostgreSQL version (within the current major)..."

    local compose_files=(
        "./docker-compose.yml" "./docker-compose.dev.yml" "./docs/docker-compose.example.yml"
        "./tests/docker-compose.smoke.yml" "./tests/docker-compose.perf.yml"
    )

    # Current pinned version (major.minor) from the primary compose file.
    local current major
    current=$(grep -hoP 'postgres:\K[0-9]+\.[0-9]+(?=-alpine)' "./docker-compose.yml" 2>/dev/null | head -1)
    if [[ -z "${current}" ]]; then
        warn "No postgres:*-alpine pin found in docker-compose.yml, skipping"
        return 0
    fi
    major="${current%%.*}"
    echo "  Current: ${current} (major ${major})"

    # Highest ${major}.x-alpine tag on Docker Hub (sorted semver-aware; API order is not guaranteed).
    # The anchored X.Y regex admits no pre-release suffix (a `${major}beta1-alpine` never matches),
    # so no further pre-release filtering is needed here.
    local latest
    latest=$(curl_get "https://hub.docker.com/v2/repositories/library/postgres/tags?name=${major}.&page_size=100" \
        | jq -r '.results[].name' \
        | grep -E "^${major}\.[0-9]+-alpine$" \
        | sed 's/-alpine$//' \
        | sort -V | tail -1)
    if [[ -z "${latest}" ]]; then
        warn "Could not resolve latest postgres ${major}.x from Docker Hub, skipping"
        return 0
    fi

    # Both variants must exist: -alpine (compose) and the plain Debian tag (Dockerfile screenshots stage).
    if ! hub_tag_exists "library/postgres" "${latest}-alpine"; then
        warn "postgres:${latest}-alpine not on Docker Hub, skipping"
        return 0
    fi
    if ! hub_tag_exists "library/postgres" "${latest}"; then
        warn "postgres:${latest} (Debian) not on Docker Hub, skipping"
        return 0
    fi
    echo "  postgres:${latest}-alpine + postgres:${latest} confirmed on Docker Hub"

    if [[ "${current}" == "${latest}" ]]; then
        echo "  postgres=${latest} (already up-to-date)"
    else
        echo "  postgres: ${current} → ${latest}"
    fi

    # ── Apply ──────────────────────────────────────────────────────────────────
    # Compose files: the -alpine pin.
    local f
    for f in "${compose_files[@]}"; do
        [[ -f "${f}" ]] || continue
        sed -i -E "s|postgres:[0-9]+\.[0-9]+-alpine|postgres:${latest}-alpine|g" "${f}"
    done
    # Dockerfile: the plain Debian tag in the screenshots stage FROM (require a following " AS " so the
    # -alpine form is never matched).
    sed -i -E "s|postgres:[0-9]+\.[0-9]+( +AS )|postgres:${latest}\1|g" "${DOCKERFILE}"

    ok "PostgreSQL updated → ${latest}"
}

# ── 3b. lint_and_tests.sh Docker image pins ───────────────────────────────────
# Keeps the pinned Docker images in .github/scripts/lint_and_tests.sh in sync:
#   - ESLINT_NODE_IMAGE (node) is bumped from the node version resolved above, only if it was
#     confirmed this run (an unconfirmed tag must never be pinned). The lint script's `java` step
#     runs `mvn clean install -Dall` on the host toolchain, so there is no Maven image pin to bump.
#   - HADOLINT_DOCKER_IMAGE, MARKDOWNLINT_DOCKER_IMAGE, SHELLCHECK_DOCKER_IMAGE and GRYPE_DOCKER_IMAGE
#     are independent best-effort: each is bumped to the latest GitHub release whose corresponding
#     Docker Hub tag is confirmed to exist.
#
# If shellcheck is instead pinned as an apt/apk package (a `shellcheck="<ver>"` line inside a
# # BEGIN/END … PACKAGES block in a Dockerfile), no work is needed here — update_apt_packages /
# update_alpine_packages already resolve and bump every package in those blocks generically.

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
    hadolint_tag=$(latest_stable_github_release "hadolint/hadolint")
    if [[ -z "${hadolint_tag}" ]]; then
        warn "Could not fetch a stable hadolint version, skipping"
    elif ! hub_tag_exists "hadolint/hadolint" "${hadolint_tag}-alpine"; then
        warn "hadolint/hadolint:${hadolint_tag}-alpine not on Docker Hub, skipping"
    else
        sed -i "s|HADOLINT_DOCKER_IMAGE=\"hadolint/hadolint:[^\"]*\"|HADOLINT_DOCKER_IMAGE=\"hadolint/hadolint:${hadolint_tag}-alpine\"|" "${LINT_SCRIPT}"
        ok "hadolint image → hadolint/hadolint:${hadolint_tag}-alpine"
    fi

    # ── markdownlint-cli2 (best-effort) ───────────────────────────────────────
    # markdownlint-cli2 publishes NO GitHub Releases (only git tags), so releases/latest 404s.
    # Use the tags API and pick the highest vX.Y.Z via sort -V (the API's order is not guaranteed).
    # The anchored X.Y.Z regex already admits no pre-release suffix, so no further filtering.
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

    # ── shellcheck (best-effort) ──────────────────────────────────────────────
    local shellcheck_tag
    shellcheck_tag=$(latest_stable_github_release "koalaman/shellcheck")
    if [[ -z "${shellcheck_tag}" ]]; then
        warn "Could not fetch a stable shellcheck version, skipping"
    elif ! hub_tag_exists "koalaman/shellcheck" "${shellcheck_tag}"; then
        warn "koalaman/shellcheck:${shellcheck_tag} not on Docker Hub, skipping"
    else
        sed -i "s|SHELLCHECK_DOCKER_IMAGE=\"koalaman/shellcheck:[^\"]*\"|SHELLCHECK_DOCKER_IMAGE=\"koalaman/shellcheck:${shellcheck_tag}\"|" "${LINT_SCRIPT}"
        ok "shellcheck image → koalaman/shellcheck:${shellcheck_tag}"
    fi

    # ── grype (best-effort) ───────────────────────────────────────────────────
    # The grype step scans the built runtime image; keep the scanner pinned to its latest release
    # whose Docker Hub tag is confirmed. DIURNAL_RUNTIME_IMAGE is our own build tag, not a pin — it
    # is never bumped here.
    local grype_tag
    grype_tag=$(latest_stable_github_release "anchore/grype")
    if [[ -z "${grype_tag}" ]]; then
        warn "Could not fetch a stable grype version, skipping"
    elif ! hub_tag_exists "anchore/grype" "${grype_tag}"; then
        warn "anchore/grype:${grype_tag} not on Docker Hub, skipping"
    else
        sed -i "s|GRYPE_DOCKER_IMAGE=\"anchore/grype:[^\"]*\"|GRYPE_DOCKER_IMAGE=\"anchore/grype:${grype_tag}\"|" "${LINT_SCRIPT}"
        ok "grype image → anchore/grype:${grype_tag}"
    fi

    # ── the eslint toolchain baked into ESLINT_BUILD_IMAGE (best-effort) ──────
    # These are npm packages rather than images, but they live in the same file and are pinned for the
    # same reason: they used to be installed as floating majors resolved when the image was built, so a
    # cached local image and a freshly built CI one could lint the same commit under different rules.
    # Each is bumped to the registry's latest, exactly as the package.json pins below are — the full
    # gate runs immediately afterwards in the monthly workflow, which is what catches a breaking bump.
    # The two @typescript-eslint packages share one variable because they release in lockstep, so the
    # plugin's version is the one resolved.
    local pkg var latest
    for pkg in "eslint:ESLINT_VERSION" \
               "@eslint/js:ESLINT_JS_VERSION" \
               "@typescript-eslint/eslint-plugin:TYPESCRIPT_ESLINT_VERSION" \
               "globals:ESLINT_GLOBALS_VERSION" \
               "typescript:TYPESCRIPT_VERSION"; do
        var="${pkg##*:}"
        pkg="${pkg%:*}"
        latest=$(curl_get "https://registry.npmjs.org/${pkg/\//%2f}/latest" | jq -r '.version // empty')
        if [[ -z "${latest}" ]]; then
            warn "Could not fetch latest version for '${pkg}', skipping"
            continue
        fi
        # The `latest` dist-tag is a convention, not a guarantee — a package may point it at a
        # pre-release, which must never be pinned.
        if is_prerelease "${latest}"; then
            warn "Latest '${pkg}' (${latest}) is a pre-release, skipping"
            continue
        fi
        sed -i "s|^${var}=\"[^\"]*\"|${var}=\"${latest}\"|" "${LINT_SCRIPT}"
        ok "${pkg} → ${latest}"
    done

    ok "Lint script Docker images processed"
}

# ── 3b-perf. k6 image pin (tests/run-perf.sh) ─────────────────────────────────
# Keeps the pinned k6 Docker image in tests/run-perf.sh (K6_IMAGE) in sync — the perf tier's load
# generator, run from the grafana/k6 image (its JS API modules — k6, k6/http, k6/encoding — are built
# into that binary, so there is no npm package to bump; the image tag IS the dependency). Best-effort,
# mirroring the grype/hadolint/shellcheck blocks in update_lint_script: bump to the latest GitHub
# release whose Docker Hub tag is confirmed. k6's release tags carry a leading 'v' (v0.54.0) but the
# Docker Hub tag omits it (0.54.0), so strip it before checking/pinning.

update_perf_script() {
    echo
    echo "🔍 Updating the k6 Docker image pin in ${PERF_SCRIPT}..."

    if [[ ! -f "${PERF_SCRIPT}" ]]; then
        warn "No ${PERF_SCRIPT}, skipping"
        return 0
    fi

    local k6_tag k6_ver
    k6_tag=$(latest_stable_github_release "grafana/k6")
    if [[ -z "${k6_tag}" ]]; then
        warn "Could not fetch a stable k6 version, skipping"
        return 0
    fi
    k6_ver="${k6_tag#v}"
    if ! hub_tag_exists "grafana/k6" "${k6_ver}"; then
        warn "grafana/k6:${k6_ver} not on Docker Hub, skipping"
        return 0
    fi
    sed -i "s|K6_IMAGE=\"grafana/k6:[^\"]*\"|K6_IMAGE=\"grafana/k6:${k6_ver}\"|" "${PERF_SCRIPT}"
    ok "k6 image → grafana/k6:${k6_ver}"
}

# ── 3c. npm packages ──────────────────────────────────────────────────────────
# For each package.json manifest, bumps every dependency/devDependency to the
# latest version published on the npm registry and writes it as an EXACT pin
# (no "^"/"~" ranges — explicit versions only). Best-effort per package: a name
# whose latest version cannot be fetched is warned and left unchanged.
#
# After any bump in a manifest, its package-lock.json is regenerated so the two
# stay in sync — otherwise `npm ci` (Dockerfile css stage, lint_and_tests.sh)
# aborts with "package.json and package-lock.json … are in sync". The lock is
# refreshed with `npm install --package-lock-only`, which recomputes the lockfile
# without touching node_modules or running install scripts.
#
# npm is NOT required on the host: the refresh runs inside the same node image
# the Dockerfile's css stage uses for `npm ci`, so the lock is written by the
# exact npm version that later consumes it.

# Resolve the node image `npm ci` runs in (the Dockerfile css build stage), so the
# lockfile is regenerated with a matching npm. Falls back to node:alpine.
npm_docker_image() {
    local image
    image=$(grep -oP '^FROM \Knode:\S+(?= AS css)' "${DOCKERFILE}" 2>/dev/null | head -1)
    echo "${image:-node:alpine}"
}

# Regenerate a manifest's package-lock.json to match its (just-bumped) package.json,
# running npm inside Docker (no host npm needed). Best-effort: skipped (with a
# warning) if Docker is unavailable or there is no lockfile.
regenerate_npm_lockfile() {
    local manifest_dir="${1}"

    if ! command -v docker >/dev/null 2>&1; then
        warn "docker not available, cannot regenerate lockfile in ${manifest_dir} (run 'npm install' there before building)"
        return 0
    fi
    if [[ ! -f "${manifest_dir}/package-lock.json" ]]; then
        echo "    (no package-lock.json in ${manifest_dir}, nothing to regenerate)"
        return 0
    fi

    local image abs_dir host_uid host_gid
    image=$(npm_docker_image)
    abs_dir=$(cd "${manifest_dir}" && pwd)
    # Resolve UID/GID separately from use so their exit status isn't masked (SC2312).
    host_uid=$(id -u)
    host_gid=$(id -g)

    # Mount only the manifest dir; run as the host user so the rewritten lockfile
    # isn't root-owned, and point npm's cache at a writable tmp path (that user has
    # no home in the image).
    if docker run --rm \
        -u "${host_uid}:${host_gid}" \
        -e npm_config_cache=/tmp/.npm \
        -v "${abs_dir}:/app" \
        -w /app \
        "${image}" \
        npm install --package-lock-only --no-audit --no-fund >/dev/null 2>&1; then
        ok "package-lock.json regenerated in ${manifest_dir} (via ${image})"
    else
        warn "Failed to regenerate package-lock.json in ${manifest_dir} (run 'npm install' there before building)"
    fi
}

update_npm_packages() {
    echo
    echo "🔍 Updating npm packages (exact pinned versions)..."

    local manifests=("./frontend/package.json" "./tests/package.json")

    for manifest in "${manifests[@]}"; do
        if [[ ! -f "${manifest}" ]]; then
            warn "No ${manifest}, skipping"
            continue
        fi
        echo "  --- ${manifest}"

        local manifest_changed=false

        for section in dependencies devDependencies; do
            # `tr -d '\r'` strips any carriage return the jq output may carry (e.g. a Windows jq.exe
            # under WSL, or a CRLF manifest): an unstripped CR rides into the registry URL below and
            # curl rejects it with "curl: (3) URL rejected: Malformed input to a URL function".
            local names=()
            mapfile -t names < <(jq -r --arg s "${section}" '.[$s] // {} | keys[]' "${manifest}" 2>/dev/null | tr -d '\r' || true)
            [[ "${#names[@]}" -eq 0 ]] && continue

            for pkg in "${names[@]}"; do
                # Skip any blank entry a sanitised-away line could leave behind.
                [[ -z "${pkg}" ]] && continue
                # Scoped names (@scope/name) must have the internal '/' encoded as %2f
                # for the registry path; unscoped names are unaffected.
                local encoded="${pkg/\//%2f}"

                local latest
                latest=$(curl_get "https://registry.npmjs.org/${encoded}/latest" | jq -r '.version // empty')
                if [[ -z "${latest}" ]]; then
                    warn "Could not fetch latest version for '${pkg}', skipping"
                    continue
                fi
                # A package is free to point its `latest` dist-tag at a pre-release; never pin one.
                if is_prerelease "${latest}"; then
                    warn "Latest '${pkg}' (${latest}) is a pre-release, skipping"
                    continue
                fi

                local current
                current=$(jq -r --arg s "${section}" --arg p "${pkg}" '.[$s][$p]' "${manifest}")

                if [[ "${current}" == "${latest}" ]]; then
                    echo "    ${pkg}=${latest} (already pinned, up-to-date)"
                    continue
                fi

                echo "    ${pkg}: ${current} → ${latest} (exact pin)"
                jq --arg s "${section}" --arg p "${pkg}" --arg v "${latest}" \
                    '.[$s][$p] = $v' "${manifest}" > "${manifest}.tmp" \
                    && mv "${manifest}.tmp" "${manifest}"
                manifest_changed=true
            done
        done

        # Keep the lockfile in sync with the bumped manifest so `npm ci` succeeds.
        if [[ "${manifest_changed}" == "true" ]]; then
            regenerate_npm_lockfile "$(dirname "${manifest}")"
        fi

        ok "npm packages processed in ${manifest}"
    done
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
            echo "${package_block}" | grep -oP '^\s*[a-z0-9.+-]+(?==)' | sed 's/^[[:space:]]*//' || true
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
        done < <(echo "${versions_raw}" | _parse_apt_candidates || true)

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
            done < <(echo "${versions_raw}" | _parse_apt_candidates || true)
        fi

        # Clean up temp image regardless of outcome
        if [[ -n "${temp_image}" ]]; then
            docker image rm "${temp_image}" >/dev/null 2>&1 || true
        fi

        # Verify all packages resolved to a final release after the quick or fallback path
        local all_found=true
        for package in "${package_names[@]}"; do
            if [[ -z "${apt_versions["${package}"]:-}" ]]; then
                warn "Could not resolve version for '${package}', skipping section ${section}"
                all_found=false
                break
            fi
            if is_prerelease "${apt_versions[${package}]}"; then
                warn "Candidate '${package}=${apt_versions[${package}]}' is a pre-release, skipping section ${section}"
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
            echo "${package_block}" | grep -oP '^\s*[a-z0-9.+-]+(?==)' | sed 's/^[[:space:]]*//' || true
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

        # Verify all packages resolved to a final release
        local all_found=true
        for package in "${package_names[@]}"; do
            if [[ -z "${alpine_versions["${package}"]:-}" ]]; then
                warn "Could not resolve version for '${package}', skipping section ${section}"
                all_found=false
                break
            fi
            # Alpine spells pre-releases in the version itself (`1.2.0_rc1-r0`, `..._beta2-r0`).
            if is_prerelease "${alpine_versions[${package}]}"; then
                warn "Candidate '${package}=${alpine_versions[${package}]}' is a pre-release, skipping section ${section}"
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
    mapfile -t paths < <(git config -f "${GITMODULES_FILE}" --get-regexp '\.path$' | awk '{print $2}' || true)

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

            # The most recently tagged commit may carry a pre-release tag; fall back to the
            # HIGHEST-VERSIONED tag that is a final release. By version, not by tag date, for the
            # same reason as latest_stable_github_release: a backport tagged after a newer release
            # is the most recent tag while being the lower version.
            if [[ -n "${latest_tag}" ]] && is_prerelease "${latest_tag}"; then
                warn "Ignoring pre-release tag '${latest_tag}' in submodule '${path}'"
                latest_tag="$(git tag | filter_stable_versions | sort -V | tail -1)"
            fi

            if [[ -z "${latest_tag}" ]]; then
                warn "No tags in submodule '${path}', skipping"
                exit 0
            fi

            echo "    ${path}: current=${current_tag} → latest=${latest_tag}"

            if [[ "${latest_tag}" != "${current_tag}" ]]; then
                git checkout --quiet "${latest_tag}"
                echo "    Updated '${path}' to ${latest_tag}"
                # Sync this submodule's OWN nested submodules to the newly checked-out tag (the new
                # revision may add/move/remove them). Scoped here inside the submodule dir on purpose:
                # a top-level `git submodule update` would instead reset '${path}' back to the commit
                # in the superproject index, undoing the checkout above. Best-effort like the rest.
                git submodule update --init --recursive --quiet 2>/dev/null \
                    || warn "Could not sync nested submodules for '${path}'"
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
        | filter_stable_versions \
        | sort -V | tail -1)

    if [[ -z "${latest_version}" ]]; then
        warn "Could not fetch a stable parent-pom version from Maven Central, skipping"
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
            | sort -u || true
    )

    if [[ "${#action_refs[@]}" -eq 0 ]]; then
        echo "  No action references found."
        return 0
    fi

    for ref in "${action_refs[@]}"; do
        local action current_version latest_version
        action="${ref%@*}"
        current_version="${ref#*@}"

        latest_version=$(latest_stable_github_release "${action}")

        if [[ -z "${latest_version}" ]]; then
            warn "Could not fetch a stable version for ${action}, skipping"
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

# ── 9. Consistency guard: like-version groups must stay in sync ────────────────
# Node (every node:X.Y.Z-<variant> across the Dockerfile + sandbox/Dockerfile, PLUS the workflows'
# setup-node node-version) and Postgres (every postgres:X.Y across the compose files + the Dockerfile
# screenshots stage) are each ONE logical version. update_node / update_postgres already move each whole
# group together or not at all. This is the belt-and-braces check that they did NOT drift — a split
# (e.g. the dev compose on a newer Postgres than the Dockerfile, or the CI node ahead of the image node)
# is a broken, mismatched setup, so a divergence is a HARD failure, not a warning.

verify_version_sync() {
    echo
    echo "🔍 Verifying node/postgres version groups are in sync..."
    local failed=0

    # ── Node: image tags (both Dockerfiles) + the workflow setup-node version ──
    local node_versions node_count
    node_versions=$(
        {
            grep -hoE 'node:[0-9]+\.[0-9]+\.[0-9]+' "${DOCKERFILE}" "${SANDBOX_DOCKERFILE}" | sed 's|node:||'
            grep -rhoE "node-version: '[0-9]+\.[0-9]+\.[0-9]+'" "${WORKFLOWS_DIR}"/*.yml \
                | grep -oE '[0-9]+\.[0-9]+\.[0-9]+'
        } 2>/dev/null | sort -u
    )
    node_count=$(printf '%s\n' "${node_versions}" | grep -c . || true)
    if [[ "${node_count}" -gt 1 ]]; then
        warn "Node versions DIVERGED: $(printf '%s' "${node_versions}" | paste -sd',' - || true)"
        failed=1
    elif [[ "${node_count}" -eq 1 ]]; then
        ok "Node in sync → ${node_versions}"
    else
        warn "No node:X.Y.Z pins found to verify"
    fi

    # ── Postgres: every postgres:X.Y across the compose files + the Dockerfile ──
    local pg_files=() pg
    for pg in ./docker-compose.yml ./docker-compose.dev.yml ./docs/docker-compose.example.yml \
              ./tests/docker-compose.smoke.yml ./tests/docker-compose.perf.yml "${DOCKERFILE}"; do
        [[ -f "${pg}" ]] && pg_files+=("${pg}")
    done
    local pg_versions pg_count
    pg_versions=$(grep -hoE 'postgres:[0-9]+\.[0-9]+' "${pg_files[@]}" 2>/dev/null | sed 's|postgres:||' | sort -u)
    pg_count=$(printf '%s\n' "${pg_versions}" | grep -c . || true)
    if [[ "${pg_count}" -gt 1 ]]; then
        warn "Postgres versions DIVERGED: $(printf '%s' "${pg_versions}" | paste -sd',' - || true)"
        failed=1
    elif [[ "${pg_count}" -eq 1 ]]; then
        ok "Postgres in sync → ${pg_versions}"
    else
        warn "No postgres:X.Y pins found to verify"
    fi

    if [[ "${failed}" -ne 0 ]]; then
        echo "❌ A version 'like' group diverged — it must move together or not at all. Fix before committing." >&2
        exit 1
    fi
    ok "Version groups in sync"
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
update_postgres   || warn "PostgreSQL update failed, continuing..."

# After java/maven/node: propagate the resolved (and confirmed) versions into the lint script.
update_lint_script || warn "Lint script image update failed, continuing..."

# The perf tier's k6 image pin lives in tests/run-perf.sh (best-effort, independent of the above).
update_perf_script || warn "k6 image update failed, continuing..."

update_npm_packages || warn "npm package update failed, continuing..."

update_apt_packages "${DOCKERFILE}"         "UBUNTU" || warn "Ubuntu packages update failed for Dockerfile, continuing..."
update_apt_packages "${DOCKERFILE}"         "DEBIAN" || warn "Debian packages update failed for Dockerfile, continuing..."
update_apt_packages "${SANDBOX_DOCKERFILE}" "DEBIAN" || warn "Debian packages update failed for sandbox/Dockerfile, continuing..."
update_alpine_packages "${DOCKERFILE}"              || warn "Alpine packages update failed for Dockerfile, continuing..."

update_submodules      || warn "Submodules update failed, continuing..."
update_github_actions  || warn "GitHub Actions update failed, continuing..."

# Final guard (NOT best-effort): the node + postgres like-groups must not have drifted apart. A
# divergence here means a broken/mismatched build, so this hard-fails the whole run.
verify_version_sync

echo
echo "✅ Dependency version update complete."
