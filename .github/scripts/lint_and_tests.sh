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
#                  Any entry may be narrowed to one of its step's SUBSTEPS with
#                  `step:substep` (see "Substeps" below); a bare step name runs all of
#                  them. Entries combine, so `java:mvn,java:qodana` runs those two tiers
#                  and nothing else, and `java,markdown` is the whole gate plus markdown.
#                  Auto-detection narrows a step the same way when only one of its tiers is
#                  affected, so a docker-compose edit selects `docker:hadolint` rather than
#                  the whole Docker gate.
#
#                  -v, --verbose
#                    Show each step's full output. Off by default: steps print only a
#                    per-substep progress line, that substep's elapsed time, and a pass/fail
#                    summary carrying the per-substep breakdown (the long-running
#                    substeps' own output is hidden until one fails). Turn it on to stream
#                    every substep's output live — useful when a run fails and you need the detail.
#                    During the parallel phase only the `java` gate is streamed; the other lanes are
#                    captured and replayed in step order afterwards, because concurrent live streams
#                    interleave line-by-line into something neither readable nor greppable.
#
#                  -f, --force
#                    Run ALL steps, skipping the git-diff auto-detection (equivalent to passing
#                    the full step list, every one of them whole). Cannot be combined with an
#                    explicit [steps] argument.
#
#                  -e, --exit-on-failure
#                    Stop at the first failing step instead of running the remaining steps. Off by
#                    default (every step runs and all failures are reported together); used by the
#                    release pipeline (publish.yml) to fail fast rather than burn time on later steps.
#                    Steps still running when the first failure lands are signalled and reported as
#                    CANCELLED (their result is unknown), separately from the step that actually failed.
#
#                  Execution order (see LANE_STEPS):
#                    The lane-eligible steps - every linter (`docker` only while it is scoped to its
#                    hadolint tier) plus `java` - run CONCURRENTLY, so the
#                    linters' runtime disappears inside the multi-minute JVM gate. A `docker` step
#                    including its grype substep, and `perf`, then run serially afterwards: the scan builds
#                    the production runtime image that the java step's smoke tier also builds (serial = a
#                    cache hit, concurrent = built twice), and perf MEASURES the application, so anything
#                    else running invalidates its numbers. The Qodana scan is a TIER of `java`, not a step
#                    beside it, so it rides that step's own parallelism rather than the lane machinery -
#                    as do the two Docker checks, which run in parallel with each other inside `docker`.
#                    Selecting a single lane-eligible step skips the machinery and runs it inline.
#
#                  Valid steps:
#                    - docker      The Docker gate, in two tiers run CONCURRENTLY: hadolint over the
#                                  Dockerfiles (seconds) and a build-and-scan CVE pass over the production
#                                  runtime image with grype (minutes), so the lint costs nothing beside it
#                    - java        The full JVM gate: mvn clean install -Dall (unit + *IT + linters),
#                                  the Playwright E2E/UI suite, the deployment-smoke suite, and the Qodana
#                                  whole-program analysis — the smoke and Qodana tiers run in parallel with
#                                  the rest, and Qodana dominates the wall clock (tens of minutes)
#                    - javascript  Lint JavaScript files with eslint
#                    - markdown    Lint Markdown files with markdownlint-cli2
#                    - perf        k6 load/performance suite against the real prod image
#                                  (tests/run-perf.sh). Auto-detected on the SAME file set as `java`
#                                  (app/runtime/deps changes) plus the perf suite's own files.
#                    - shellcheck  Lint shell scripts (*.sh) with shellcheck
#                    - typescript  Lint TypeScript files with eslint
#
#                  Substeps (see STEP_SUBSTEPS):
#                    `docker` and `java` have them, being the steps made of separable tiers. Naming one
#                    runs THAT TIER ALONE, which is what you want when iterating on the thing it
#                    checks rather than re-paying for the whole gate:
#                    - docker:hadolint  lint Dockerfile + sandbox/Dockerfile with hadolint. Seconds, and
#                                       the only tier a docker-compose or hadolint-config change needs
#                    - docker:grype     build the production runtime image and scan it for CVEs. Minutes,
#                                       and the reason a whole `docker` step no longer runs in the lanes
#                    - java:mvn     mvn clean install -Dall (unit + *IT + linters); packages the fast-jar
#                    - java:e2e     the Playwright E2E/UI suite (tests/run-e2e.sh). REUSES the fast-jar,
#                                   so target/quarkus-app must already exist - run `java:mvn` first, or
#                                   `java:mvn,java:e2e` to do both
#                    - java:smoke   the deployment-smoke suite (tests/run-smoke.sh); self-contained
#                    - java:qodana  the whole-program analysis - the only check that can see an unused
#                                   PUBLIC declaration (PMD/ErrorProne/SpotBugs all stop at private).
#                                   Configured by code-quality-config-overrides/qodana.yaml, whose
#                                   inspection profile and IDE entry-point overrides live in the
#                                   code-quality-config submodule (code-quality-config/java/qodana/).
#                                   Name it alone when iterating on any of those rather than on the code.
#
#                  Examples:
#                    ./lint_and_tests.sh
#                    ./lint_and_tests.sh -f
#                    ./lint_and_tests.sh docker
#                    ./lint_and_tests.sh docker,javascript
#                    ./lint_and_tests.sh -v java
#                    ./lint_and_tests.sh java:qodana
#                    ./lint_and_tests.sh java:mvn,java:e2e
#                    ./lint_and_tests.sh docker:hadolint
#                    ./lint_and_tests.sh docker:grype
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
#   - The `docker` step's grype tier builds the production runtime image and scans it, so it needs a Docker
#     daemon the current user can build with; the scan runs from the grype image with the Docker
#     socket mounted so it can read that just-built local image. Its hadolint tier needs nothing but the
#     shared config in the submodule, and runs alongside it.
#
# Exit Codes:
#   - 0: All linting and tests passed successfully
#   - Non-zero: One or more linting errors or test failures occurred
# ------------------------------------------------------------------------------

set -uo pipefail

# State of the concurrently-running steps (see the "parallel lanes" section): PID, step name, captured-output
# file and exit code (empty while still running) per lane. Declared up here because the INT trap below reads
# them - a Ctrl-C during the parallel phase must signal every lane's process group, or the shell exits and
# leaves mvn, the smoke stack and the test DB running behind it.
LANE_PIDS=()
LANE_NAMES=()
LANE_LOGS=()
LANE_RCS=()

# Name of the in-flight Qodana container, set by qodana_prepare. Declared here so `set -u` is satisfied
# on the interrupt path, which removes it: the daemon owns that container, so aborting the script would
# otherwise leave a whole-project analysis running with nothing attached to it.
QODANA_CONTAINER=""

trap 'echo; echo "❌ Interrupted"; lane_abort_all; qodana_stop_container; exit 130' INT

# Absolute path to this script (basedir + filename), so the "re-run ..." hints printed on failure are
# copy/paste-ready from any working directory rather than a bare filename. Each command substitution is
# assigned on its own line so shellcheck (SC2312) doesn't flag a masked return value.
SCRIPT_SOURCE="${BASH_SOURCE[0]}"
SCRIPT_DIR="$(dirname -- "${SCRIPT_SOURCE}")"
SCRIPT_DIR="$(cd -- "${SCRIPT_DIR}" > /dev/null 2>&1 && pwd)"
SCRIPT_NAME="$(basename -- "${SCRIPT_SOURCE}")"
SCRIPT_PATH="${SCRIPT_DIR}/${SCRIPT_NAME}"

# This project's own linter configuration - the files that OVERRIDE or extend the shared rules in the
# code-quality-config submodule, rather than replacing them: grype's project-level ignore list and the
# whole of qodana.yaml. They sit in one tracked directory (named for the submodule it layers over) instead
# of as dotfiles in the repo root, so every piece of CI configuration is in a directory rather than
# scattered across the top level. Nothing here is found by convention any more - grype is pointed at its
# file with `-c` and Qodana with `--config`, both of which this script passes explicitly. Paths are
# relative to the repo root, which is also where each tool's container mounts it, so the same string works
# on the host and inside the container.
OVERRIDES_DIR="code-quality-config-overrides"
GRYPE_CONFIG_FILE="${OVERRIDES_DIR}/.grype.yaml"
QODANA_CONFIG_FILE="${OVERRIDES_DIR}/qodana.yaml"

ESLINT_BUILD_IMAGE="local/diurnal-eslint:latest"
ESLINT_NODE_IMAGE="node:26.7.0-alpine"

# Exact pins for the linting toolchain baked into ESLINT_BUILD_IMAGE. Pinned, rather than the floating
# majors this used to install, because the versions resolve when the IMAGE is built: a developer's image
# is cached indefinitely while CI builds a fresh one every run, so the same commit could be linted by two
# different rule sets - and a new minor release could turn the gate red with no change to this repo. The
# two @typescript-eslint packages share one pin because they are released in lockstep. All are bumped by
# .github/scripts/update_dependency_versions.sh, like every other pin in this file.
ESLINT_VERSION="10.8.1"
ESLINT_JS_VERSION="10.0.1"
TYPESCRIPT_ESLINT_VERSION="8.67.0"
ESLINT_GLOBALS_VERSION="17.11.0"
TYPESCRIPT_VERSION="7.0.2"
GRYPE_DOCKER_IMAGE="anchore/grype:v0.117.0"
HADOLINT_DOCKER_IMAGE="hadolint/hadolint:v2.15.1-alpine"
MARKDOWNLINT_DOCKER_IMAGE="davidanson/markdownlint-cli2:v0.23.2"
SHELLCHECK_DOCKER_IMAGE="koalaman/shellcheck:v0.11.0"
# The IntelliJ inspection engine, packaged for CI. Tags are IntelliJ release trains (YYYY.N); the
# `-eap` ones are pre-releases and are never pinned. KEEP IN SYNC with `linter:` in QODANA_CONFIG_FILE —
# update_dependency_versions.sh bumps both together, so neither is edited by hand.
QODANA_DOCKER_IMAGE="jetbrains/qodana-jvm-community:2026.2@sha256:8ff36b5cebc0a6d720f77dcf3e0a94a03c39b4c42c3724a99ce5f7e462e42f99"

# The runtime image the docker step's grype tier builds and scans (the same final stage the published
# image uses).
DIURNAL_RUNTIME_IMAGE="zodac/diurnal:latest"

# Named Docker volume that persists grype's vulnerability DB between runs (mounted into the scanner
# and pointed at by GRYPE_DB_CACHE_DIR). Without it every `docker run --rm` re-downloads the ~1.6GB
# DB; grype only re-pulls when the cached copy is stale, so warm runs skip the download. A named
# volume (vs. a host bind mount) keeps the root-owned DB files out of the working tree entirely — so
# nothing to .gitignore — and lets Docker manage its lifecycle. It is auto-created on first use;
# remove it with `docker volume rm ${GRYPE_DB_CACHE_VOLUME}`.
GRYPE_DB_CACHE_VOLUME="${GRYPE_DB_CACHE_VOLUME:-diurnal-grype-db}"

# Qodana's working directories, all under one gitignored top-level dir rather than in target/ (which
# `mvn clean` wipes, taking the index cache with it and making every run a cold one). The container runs
# as the invoking user, so nothing it writes here is root-owned and `rm -rf .qodana` always works.
# There is deliberately NO baseline: every run is judged fresh, and any finding at all breaks the build.
# NOTHING TRACKED lives here - the whole directory is gitignored, and the scan's configuration comes from
# the submodule paths below.
QODANA_WORK_DIR="${PWD}/.qodana"

# The IDE configuration the scan runs with, laid out exactly as `.idea/` (see qodana_prepare). It lives in
# the code-quality-config submodule beside every other linter's shared config, so the gate depends on no
# one's local IDE setup - and is copied into QODANA_IDEA_DIR, which is mounted over the project's `.idea`
# inside the container so the real one is never touched. The inspection PROFILE is a sibling of it and is
# reached by `imports:` in QODANA_CONFIG_FILE rather than by this script.
QODANA_CONFIG_DIR="${PWD}/code-quality-config/java/qodana"
QODANA_OVERRIDES_DIR="${QODANA_CONFIG_DIR}/overrides"
QODANA_PROFILE_FILE="${QODANA_CONFIG_DIR}/profiles/java.yaml"
QODANA_IDEA_DIR="${QODANA_WORK_DIR}/idea-config"

VALID_STEPS=("docker" "java" "javascript" "markdown" "perf" "shellcheck" "typescript")

# The substeps each step can be narrowed to with `step:substep`, in the order they are reported. `docker`
# and `java` have them: they are the steps made of tiers that are separately meaningful (and separately
# expensive), so they are where scoping saves real minutes. Both follow the same rule - a tier that checks
# the same subject matter as the rest of the step belongs INSIDE it rather than beside it, so a bare step
# name is the whole gate for that subject and `step:substep` is how you run one tier of it. Hence the
# Qodana scan is a substep of `java` (a Java lint over the same sources as the rest of the gate) and the
# CVE scan is a substep of `docker` (it scans the image the linted Dockerfile builds).
#
# A step absent from here has no substeps, and `step:anything` is rejected for it. Adding some is this
# entry plus the branching inside that step's run_* function; everything else (parsing, validation, the
# re-run hints, the lane machinery) is driven from this table.
declare -A STEP_SUBSTEPS=(
    [docker]="hadolint grype"
    [java]="mvn e2e smoke qodana"
)

# The substeps actually selected for a step, space-separated. An ABSENT entry means "all of them", which
# is what every path that does not name a substep leaves behind: auto-detection, -f/--force, and a bare
# step name. So nothing but the explicit `step:substep` parsing ever writes to it.
declare -A SELECTED_SUBSTEPS=()

# True when ${2} is a selected substep of step ${1}. An unset selection means the whole step, so every
# substep answers yes - which is what keeps the branching inside a run_* function invisible on the
# ordinary paths.
runs_substep() {
    local selected="${SELECTED_SUBSTEPS[${1}]:-}"
    [[ -z "${selected}" ]] && return 0
    [[ " ${selected} " == *" ${2} "* ]]
}

# How the step currently selected would be typed back in, so the "re-run ..." hints and the failed-step
# summary name exactly what ran rather than the whole step. A fully-selected step is its bare name; a
# scoped one expands to one entry per selected substep ("java:mvn,java:e2e") - the same comma-separated
# form the [steps] argument accepts, so the suggestion is copy/paste-ready either way.
step_invocation() {
    local step="${1}"
    local selected="${SELECTED_SUBSTEPS[${step}]:-}"
    if [[ -z "${selected}" ]]; then
        printf '%s' "${step}"
        return
    fi

    local -a substeps=()
    read -ra substeps <<<"${selected}"
    local substep invocation=""
    for substep in "${substeps[@]}"; do
        invocation+=",${step}:${substep}"
    done
    printf '%s' "${invocation#,}"
}

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
#
# run_java pre-flight-checks the server (sonarqube_reachable, using this same host/token) before
# deciding whether to append -Dsonarqube at all: if it's unreachable, the analysis is skipped for this
# run instead of letting a bootstrap failure fail the whole release gate. If it IS reachable but the
# analysis then fails anyway, that still fails the build normally.
SONARQUBE_ANALYSIS="${SONARQUBE_ANALYSIS:-false}"

# Read by sonarqube_reachable/mvn (sonar.host.url / sonar.token) when SONARQUBE_ANALYSIS=true. Only
# publish.yml ever sets these (as secrets); defaulted to empty here purely so shellcheck (SC2154) sees
# them assigned and every other run doesn't reference an unset var.
SONARQUBE_HOST_URL="${SONARQUBE_HOST_URL:-}"
SONARQUBE_PAT="${SONARQUBE_PAT:-}"

# Verbose off by default: steps print a per-substep progress line plus a pass/fail summary, and each
# substep's own output is hidden until it fails. The -v/--verbose flag (parsed below) flips this to
# stream/echo every substep's full output live.
VERBOSE=false

# Force off by default: with -f/--force (parsed below), the git-diff auto-detection is skipped and
# every step runs. Ignored when an explicit step list is passed (that combination is rejected).
FORCE=false

# Fail-fast off by default: every step runs and all failures are reported together. With
# -e/--exit-on-failure (parsed below), the execution loop breaks as soon as a step fails, skipping
# the remaining steps (used by the release pipeline so a failing gate stops promptly).
FAIL_FAST=false

# Per-step failure signal: reset to 0 before each step in the execution loop, set to 1 by any run_*
# function that fails. Read straight after each step to tell whether THAT step failed (a plain
# monotonic accumulator couldn't — once it flipped to 1 it could no longer distinguish later steps).
overall_exit_code=0

# The names of every step that failed (in run order) and the final process exit code. `failed_steps`
# feeds both the end-of-run summary and the copy/paste re-run command (so it lists exactly the failed
# steps); `final_exit_code` is the real accumulator that survives the per-step reset of overall_exit_code.
failed_steps=()
final_exit_code=0

# Steps that were stopped, or never started, because -e/--exit-on-failure tripped on another step's failure.
# Kept apart from failed_steps on purpose: a cancelled step did not fail, its result is simply unknown, so it
# must not be named in the "re-run the failures" hint as though it were something to go and fix.
cancelled_steps=()

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

# Per-substep timings of the step currently running, as "<name> <natural-time>" entries in run order.
# Appended to by run_timed_substep and reset by the step that uses it; a step folds the joined list
# into its own summary line, so a multi-tier step reports where its time actually went.
TIMED_SUBSTEPS=()

# Natural time elapsed since the nanosecond stamp in ${1}, printed to stdout. Assign the `date` call to
# a local first so its return value is not masked (SC2312).
elapsed_since() {
    local now
    now="$(date +%s%N)"
    to_natural_time "$((now - ${1}))"
}

# Record one substep's outcome: print its elapsed time — green when it passed, red when it failed, so a
# glance at the run tells you which tier died — and append the same coloured figure to TIMED_SUBSTEPS for
# the step's summary breakdown. ${1} is the short name, ${2} the substep's nanosecond start stamp, ${3}
# its exit code.
#
# The printed line is NAMED, not a bare duration: a parallel tier finishing part-way through another one
# means two timings can land back-to-back, and an unlabelled pair gives no clue which figure is which.
record_substep_time() {
    local name="${1}"
    local start_ns="${2}"
    local rc="${3}"
    # OPTIONAL nanosecond END stamp, for a tier that finished before anything looked at it (the Qodana tier
    # - see run_java). Without one the figure is measured to NOW, which for such a tier is the moment it was
    # joined rather than the moment it exited, and so can never read shorter than whatever was joined ahead
    # of it. Every other caller finishes in the foreground or is polled once a second, and omits it.
    local end_ns="${4:-}"

    # String comparison, not -eq: in bash's arithmetic context an EMPTY value compares equal to 0, so a
    # tier whose exit code was somehow never captured would be painted green and pass silently.
    local colour done_in
    if [[ "${rc}" == "0" ]]; then colour="${GREEN}"; else colour="${RED}"; fi
    if [[ -n "${end_ns}" ]]; then
        done_in="$(to_natural_time "$((end_ns - start_ns))")"
    else
        done_in="$(elapsed_since "${start_ns}")"
    fi

    TIMED_SUBSTEPS+=("${name} ${colour}${done_in}${RESET}")
    echo "     ${name}: ${colour}${done_in}${RESET}"
}

# Join TIMED_SUBSTEPS into a bracketed one-line breakdown for a step's summary, e.g.
# "  [mvn 14m:23s | e2e 7m:11s | smoke 9m:28s]". Empty (not an empty bracket pair) when the step
# recorded no substeps, so a summary line can interpolate it unconditionally.
substep_breakdown() {
    if [[ "${#TIMED_SUBSTEPS[@]}" -eq 0 ]]; then
        return
    fi
    local joined
    joined="$(printf ' | %s' "${TIMED_SUBSTEPS[@]}")"
    printf '  [%s]' "${joined# | }"
}

# Pre-flight reachability check for the SonarQube server, hit with the SAME host/token the
# sonar-maven-plugin itself would use (SONARQUBE_HOST_URL / SONARQUBE_PAT). run_java calls this BEFORE
# deciding whether to append -Dsonarqube: if the server doesn't answer (down, unreachable, any non-200 —
# including the 502 that prompted this check), the analysis is skipped for this run entirely rather than
# letting a bootstrap failure fail the whole gate. If the server IS reachable but the analysis itself
# then fails for some other reason (bad token, quality-gate failure, ...), that still fails the build
# normally — this only gates whether the analysis is attempted at all.
sonarqube_reachable() {
    local http_code
    http_code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
        -u "${SONARQUBE_PAT}:" \
        "${SONARQUBE_HOST_URL%/}/api/server/version" 2>/dev/null) || return 1
    [[ "${http_code}" == "200" ]]
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
    eslint@${ESLINT_VERSION} \
    @eslint/js@${ESLINT_JS_VERSION} \
    @typescript-eslint/eslint-plugin@${TYPESCRIPT_ESLINT_VERSION} \
    @typescript-eslint/parser@${TYPESCRIPT_ESLINT_VERSION} \
    globals@${ESLINT_GLOBALS_VERSION} \
    typescript@${TYPESCRIPT_VERSION} \
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

# Tier 1 of the `docker` step: hadolint over both Dockerfiles. Prints its findings and returns its exit
# code, leaving the timing and the pass/fail summary to run_docker - which is what lets it be run in the
# background beside the scan without either of them reporting on the step as a whole.
run_hadolint_tier() {
    local output
    if output=$(docker run --rm \
        -v "${PWD}":/app \
        -w /app \
        "${HADOLINT_DOCKER_IMAGE}" \
        hadolint --config code-quality-config/docker/.hadolint.yaml \
        Dockerfile sandbox/Dockerfile 2>&1); then
        [[ "${VERBOSE}" == true && -n "${output}" ]] && echo "${output}"
        return 0
    fi

    # hadolint reports findings as JSON, so pretty-print them - but fall back to the raw text when
    # the output is not JSON at all. It often isn't: a docker-level failure (daemon down, image pull
    # refused) writes a plain error here, and piping that straight into jq replaced the one line
    # explaining the failure with a parse error about it.
    echo "${output}" | jq . 2>/dev/null || echo "${output}"
    return 1
}

# Tier 2 of the `docker` step: build the production runtime image and scan it for CVEs. Returns its exit
# code (0 = clean), same contract as the hadolint tier above.
run_grype_tier() {
    # Build the production runtime image from the same multi-stage Dockerfile the published image uses,
    # so Grype scans exactly what ships: the distroless base OS packages, the jlink JRE, the busybox
    # wget binary and the Quarkus app's bundled Java dependency jars. The css/icons build stages only
    # export app.css / the favicons — their Node and ImageMagick toolchains never reach this image — so
    # a package.json / JS-dependency change alters nothing here and is deliberately NOT a grype trigger.
    #
    # The grype run reads the just-built local image via the mounted Docker socket, and the repo as the
    # working dir so the -c config paths resolve. Order is load-bearing: across multiple -c files
    # grype lets the FIRST file win every scalar key (verified empirically — later files do NOT override
    # earlier scalars), while ignore lists from all files are appended. So the project-level config
    # (GRYPE_CONFIG_FILE) is passed FIRST (its log level / fail-on-severity / etc. take precedence), and the
    # shared code-quality-config submodule config SECOND to supply the common won't-fix ignore list plus any
    # scalar the project doesn't set. The project-level config is OPTIONAL — a project that needs no
    # overrides can rely on the submodule config alone, so it is only added when the file exists (keeping
    # its FIRST position when present). The vulnerability DB is persisted in a named Docker volume (mounted
    # at /grype-db, pointed at by GRYPE_DB_CACHE_DIR) so it survives the --rm and is not re-downloaded
    # every run — grype only
    # re-pulls it when the cached copy is stale. The volume is auto-created by `docker run -v` on first
    # use; nothing lands in the working tree.
    # GENERATE_PREVIEWS=false matches what tests/docker-compose.smoke.yml passes, so this build and the
    # smoke tier's build share every layer - whichever runs second is a cache hit instead of a second
    # full multi-stage build (and in a `-f` run they now overlap, see run_java). It also skips the preview
    # generation itself here (an extra Quarkus build plus Postgres and Chromium), which this step has no
    # use for. Scan fidelity is unaffected: the previews are static PNGs under the app's resources, so
    # they add no OS package and no jar to the final image - nothing grype looks at.
    local build_cmd=(docker build -t "${DIURNAL_RUNTIME_IMAGE}" --build-arg GENERATE_PREVIEWS=false -f Dockerfile .)
    local grype_config_args=()
    [[ -f "${GRYPE_CONFIG_FILE}" ]] && grype_config_args+=(-c "${GRYPE_CONFIG_FILE}")
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

    # Two phases — build then scan — each with a progress line (even non-verbose) and its output
    # captured until it fails (run_quietly). The scan is slow and, on a cold DB, downloads ~1.6GB, so
    # the progress lines matter; -v streams both live. Both paths share the command arrays above.
    substep "docker build ${DIURNAL_RUNTIME_IMAGE}  (production runtime image)"
    if ! run_quietly "${build_cmd[@]}"; then
        echo "   The production runtime image could not be built, so nothing was scanned."
        return 1
    fi

    docker pull "${GRYPE_DOCKER_IMAGE}" >/dev/null

    substep "grype scan ${DIURNAL_RUNTIME_IMAGE}  (CVE scan via ${GRYPE_DOCKER_IMAGE})"
    run_quietly "${grype_cmd[@]}"
}

# The Docker gate, in two tiers - each of them also a SUBSTEP, so either can be selected on its own
# (`docker:hadolint`, `docker:grype`):
#   1. hadolint over Dockerfile + sandbox/Dockerfile. Seconds, and the tier a docker-compose or
#      hadolint-config change needs on its own.
#   2. build the production runtime image and scan it for CVEs with grype. Minutes, and on a cold
#      vulnerability DB several more.
#
# They share the subject matter - the Dockerfile one lints is the Dockerfile the other builds - and
# NOTHING else: neither reads the other's output, they pull different images and the scan builds into the
# daemon rather than the working tree. So they run CONCURRENTLY and the step costs max(hadolint, grype)
# rather than their sum, which in practice is the scan's runtime with the lint free inside it.
#
# The scan runs in the FOREGROUND and the lint in the background, not the other way round: the scan is the
# long, chatty tier whose per-phase progress lines (and, under -v, whose whole output) are the only
# feedback the step gives while it runs, and two live streams interleave line-by-line into something
# neither readable nor greppable (the same reason run_supervised_tier never streams the smoke tier). The
# lint's output is a handful of lines, captured and replayed at the join.
#
# Unlike the java gate's tiers, neither tier stops the other when it fails: both results are reported. The
# asymmetry is the point - by the time a hadolint finding could stop the scan the lint is already over, so
# stopping saves nothing, while killing a `docker build`/`docker run` client mid-flight leaves the daemon
# doing the work (the problem qodana_stop_container exists to solve) for no gain. Two findings reported
# together also cost one re-run rather than two.
run_docker() {
    # Which of the two tiers this invocation covers. Both unless it was narrowed to a substep; every
    # launch, join and report below is gated on these, so a scoped run pays for nothing it did not ask for.
    local run_hadolint=false run_grype=false
    runs_substep docker hadolint && run_hadolint=true
    runs_substep docker grype && run_grype=true

    # Named in tier order for the opening and summary lines, so a scoped run says which tiers it is.
    local invocation tier_list=""
    invocation="$(step_invocation docker)"
    [[ "${run_hadolint}" == true ]] && tier_list+=" + hadolint"
    [[ "${run_grype}" == true ]] && tier_list+=" + grype"
    tier_list="${tier_list# + }"

    echo
    echo "Running the Docker gate [${invocation}]: ${tier_list}"

    TIMED_SUBSTEPS=()

    # Tier 1, launched first and joined at the end. It stamps its OWN finish time into an end-stamp file,
    # because nothing polls for it: timed at the join it would be credited with every minute the scan was
    # still running, and a two-second lint would report as the longer of the two tiers (the same trap the
    # java gate's Qodana tier documents at its launch).
    local hadolint_pid="" hadolint_log="" hadolint_end_file="" hadolint_start_ns="" hadolint_rc=""
    if [[ "${run_hadolint}" == true ]]; then
        hadolint_log="$(mktemp)"
        hadolint_end_file="$(mktemp)"
        hadolint_start_ns="$(date +%s%N)"
        substep "hadolint  (Dockerfile, sandbox/Dockerfile via ${HADOLINT_DOCKER_IMAGE})"
        # The pull is inside the background tier so the scan is never held behind a download that has
        # nothing to do with it, and its result is ignored exactly as it always was: an image already
        # present locally lints fine with no registry at all.
        (
            {
                docker pull "${HADOLINT_DOCKER_IMAGE}" >/dev/null || true
                run_hadolint_tier
            } > "${hadolint_log}" 2>&1
            hadolint_tier_rc=$?
            date +%s%N > "${hadolint_end_file}"
            exit "${hadolint_tier_rc}"
        ) &
        hadolint_pid=$!
    fi

    # Tier 2, in the foreground so its progress lines (and, under -v, its output) stream live.
    local grype_rc="" grype_start_ns
    if [[ "${run_grype}" == true ]]; then
        grype_start_ns="$(date +%s%N)"
        grype_rc=0
        run_grype_tier || grype_rc=$?
        record_substep_time "grype" "${grype_start_ns}" "${grype_rc}"
    fi

    # Join tier 1. Its captured output is printed when it failed (or under -v), and only then - a clean
    # lint says nothing, like every other quiet step.
    if [[ -n "${hadolint_pid}" ]]; then
        hadolint_rc=0
        wait "${hadolint_pid}" || hadolint_rc=$?
        # The tier's own stamp (see its launch), not this moment. Empty only if it died before it could
        # write one, in which case the join time is used - an upper bound, but a figure rather than a blank.
        local hadolint_end_ns=""
        [[ -s "${hadolint_end_file}" ]] && hadolint_end_ns="$(< "${hadolint_end_file}")"
        record_substep_time "hadolint" "${hadolint_start_ns}" "${hadolint_rc}" "${hadolint_end_ns}"
        if [[ "${hadolint_rc}" != "0" || "${VERBOSE}" == true ]] && [[ -s "${hadolint_log}" ]]; then
            cat "${hadolint_log}"
        fi
        rm -f "${hadolint_log}" "${hadolint_end_file}"
    fi

    # Every failing tier is named, not just the first: both ran to completion, so both answers are known.
    # String comparisons, not -eq: an empty value compares equal to 0 in bash's arithmetic context, so a
    # tier whose exit code was somehow never captured would be reported as a pass.
    local failed_tiers=""
    [[ -n "${hadolint_rc}" && "${hadolint_rc}" != "0" ]] && failed_tiers+=" + hadolint"
    [[ -n "${grype_rc}" && "${grype_rc}" != "0" ]] && failed_tiers+=" + grype"
    failed_tiers="${failed_tiers# + }"

    local done_in breakdown
    done_in="$(step_time)"
    breakdown="$(substep_breakdown)"
    if [[ -z "${failed_tiers}" ]]; then
        echo "✅ Docker gate [${invocation}] passed (${tier_list}), finished in ${GREEN}${done_in}${RESET}${breakdown}"
    else
        echo "❌ Docker gate [${invocation}] failed at [${failed_tiers}] after ${RED}${done_in}${RESET}${breakdown}: re-run ${YELLOW}'${SCRIPT_PATH} -v ${invocation}'${RESET} for the full output"
        overall_exit_code=1
    fi
}

# --- parallel-tier supervision (the java step) --------------------------------------------------------
# The deployment-smoke tier runs alongside the Maven build and then the E2E suite (see run_java). These
# globals carry its state so run_supervised_tier can watch it while another tier is the one being waited
# on: PID, exit code once known (empty while still running), start stamp and captured-output file.
SMOKE_PID=""
SMOKE_RC=""
SMOKE_START_NS=""
SMOKE_LOG=""

# Set by run_supervised_tier when it aborted its tier because the parallel smoke tier failed first, so
# the caller knows the tier's own exit code is meaningless and smoke is the failure to report.
TIER_STOPPED_EARLY=false

# Exit code of the tier most recently reaped by tier_reap.
TIER_RC=""

# Reap a background PID without blocking: sets TIER_RC to its exit code and returns 0 once it has exited,
# or returns 1 (leaving TIER_RC alone) while it is still running. Bash retains a child's status until it is
# waited for, so `wait` after the process is gone still yields the real code. Call at most once per PID (a
# second `wait` on an already-reaped child reports 127, not the original status).
#
# The result comes back in a GLOBAL rather than on stdout on purpose: `$(...)` would run `wait` in a
# subshell, which cannot reap the calling shell's children and quietly yields the wrong status - it reports
# success for a tier that actually failed.
tier_reap() {
    if kill -0 "${1}" 2>/dev/null; then
        return 1
    fi
    TIER_RC=0
    wait "${1}" 2>/dev/null || TIER_RC=$?
    return 0
}

# Signal a still-running background tier and wait for it to tear itself down. Both run-smoke.sh and
# run-e2e.sh trap the signal and remove their containers/JVM, so waiting is what keeps the teardown from
# being skipped; abandoning the child would leak its stack. A no-op once the tier has already exited.
stop_tier() {
    kill "${1}" 2>/dev/null || true
    wait "${1}" 2>/dev/null || true
}

# Remove the Maven gate's managed IT database. Only needed on the abort path: the `all` profile starts it
# in pre-integration-test and removes it in post-integration-test, and failsafe defers its failure to
# `verify` precisely so that teardown still runs when an IT fails. But a SIGNALLED Maven - which is what
# happens when the parallel smoke tier fails first - never reaches post-integration-test at all, and
# leaves the container standing.
#
# Signalling Maven's process GROUP instead (the way lane_stop does) would not help: the tiers are
# deliberately started inside the lane's own group so a Ctrl-C reaches all of them at once, and giving
# mvn a group of its own would put it out of reach of that. Maven has no teardown hook to fire either
# way, so the container is swept here explicitly instead.
sweep_test_db() {
    docker compose -f "${PWD}/docker-compose.dev.yml" rm -sf diurnal-db-dev >/dev/null 2>&1 || true
}

# Print the parallel smoke tier's completion line and record its (coloured) duration. Called the moment
# it is seen to have exited - which may be part-way through another tier - so the breakdown lists tiers
# in COMPLETION order rather than start order.
report_smoke_completion() {
    substep "tests/run-smoke.sh finished (parallel tier)"
    record_substep_time "smoke" "${SMOKE_START_NS}" "${SMOKE_RC}"
}

# Run one tier in the background and supervise it against the parallel smoke tier: block until this tier
# finishes, OR until smoke is seen to have failed first - in which case this tier is stopped immediately
# instead of being allowed to run to completion, and TIER_STOPPED_EARLY is set. That is what makes a smoke
# failure abort the Maven build (rather than being discovered only after it finishes) so the step can move
# on to the next one. ${1} short name, ${2} progress label, the rest the command.
#
# Output goes to a temp file, because run_quietly buffers in a variable in THIS shell, which a background
# child cannot write to. It is printed if the tier failed; in verbose mode it is streamed live instead
# (`tail -f`), which is why it is not re-printed at the end. Smoke's own output is never streamed - two
# live streams would interleave line-by-line and make both unreadable.
#
# Returns the tier's own exit code, or 0 when it was stopped early (the caller reports smoke instead).
run_supervised_tier() {
    local name="${1}"
    local label="${2}"
    shift 2

    local log start_ns pid tail_pid="" rc=""
    log="$(mktemp)"
    substep "${label}"
    start_ns="$(date +%s%N)"
    "$@" > "${log}" 2>&1 &
    pid=$!
    if [[ "${VERBOSE}" == true ]]; then
        tail -f "${log}" 2>/dev/null &
        tail_pid=$!
    fi

    TIER_STOPPED_EARLY=false
    while [[ -z "${rc}" ]]; do
        if tier_reap "${pid}"; then
            rc="${TIER_RC}"
            break
        fi
        # Smoke may finish (either way) while this tier is still going. A pass is just recorded; a failure
        # stops this tier here and now, which is the whole point of supervising rather than joining later.
        # SMOKE_PID is empty when the smoke tier was not selected at all (`java:mvn`, say), and there is
        # then nothing to supervise against - reaping an empty pid would report a phantom exit.
        if [[ -n "${SMOKE_PID}" && -z "${SMOKE_RC}" ]] && tier_reap "${SMOKE_PID}"; then
            SMOKE_RC="${TIER_RC}"
            report_smoke_completion
            if [[ "${SMOKE_RC}" != "0" ]]; then
                stop_tier "${pid}"
                TIER_STOPPED_EARLY=true
                rc=0
                break
            fi
        fi
        # 1s poll: coarse enough to cost nothing over a multi-minute tier, and the reason a reported
        # duration can read up to a second longer than the tier actually took.
        sleep 1
    done

    if [[ -n "${tail_pid}" ]]; then
        kill "${tail_pid}" 2>/dev/null || true
        wait "${tail_pid}" 2>/dev/null || true
    fi

    if [[ "${TIER_STOPPED_EARLY}" == true ]]; then
        substep "${name} stopped early (tests/run-smoke.sh failed)"
    else
        record_substep_time "${name}" "${start_ns}" "${rc}"
        if [[ "${rc}" != "0" && "${VERBOSE}" != true && -s "${log}" ]]; then
            cat "${log}"
        fi
    fi

    rm -f "${log}"
    return "${rc}"
}

# ── Preflight: is the port every *IT needs actually free? ─────────────────────
# EVERY `*IT` in the Maven gate boots Quarkus on ${E2E_HTTP_PORT}, so anything already listening there
# makes ALL of them fail with "Port already bound" — dozens of identical boot stack traces that never
# mention the port, buried under a generic "There are test failures". The usual holder is a `quarkus:dev`
# left running by scripts/dev-up.sh. Checking once, up front, turns that into a single actionable line.
#
# Reads /proc/net/tcp(6) directly (state 0A = LISTEN) rather than using lsof: it lists every listening
# socket regardless of owner, so it also sees a root- or docker-proxy-held port that an unprivileged lsof
# would miss. Same probe as scripts/dev-teardown.sh, for the same reason.
assert_test_port_free() {
    local port_hex
    port_hex="$(printf ':%04X' "${E2E_HTTP_PORT}")"

    if ! awk -v p="${port_hex}" '$2 ~ p"$" && $4=="0A" {f=1} END{exit !f}' \
            /proc/net/tcp /proc/net/tcp6 2>/dev/null; then
        return 0
    fi

    echo "❌ Port ${E2E_HTTP_PORT} is already in use, and every *IT needs it to boot Quarkus."
    echo "   Without this check the whole gate fails with dozens of 'Port already bound' stack traces."
    echo "   A dev server is the usual cause - stop it with: ${YELLOW}scripts/dev-teardown.sh${RESET}"
    echo "   (or re-run with E2E_HTTP_PORT set to a free port)."
    return 1
}

# ── Preflight: is there a fast-jar for the E2E tier to boot? ──────────────────
# Only reachable when `java:e2e` was selected WITHOUT `java:mvn`: the E2E tier deliberately does not
# build anything, it boots the jar the Maven tier packaged. Run on its own after a `mvn clean` (or on a
# fresh clone) there is nothing to boot, and run-e2e.sh discovers that only after standing its database
# up. Say so here instead, and name the two ways out.
assert_fast_jar_present() {
    [[ -f "${PWD}/target/quarkus-app/quarkus-run.jar" ]] && return 0

    echo "❌ No packaged application at ${YELLOW}target/quarkus-app${RESET}, and the E2E tier boots it rather than building it."
    echo "   Run ${YELLOW}'${SCRIPT_PATH} java:mvn,java:e2e'${RESET} to package it first, or ${YELLOW}'mvn package'${RESET} by hand."
    return 1
}

run_java() {
    # Which of the four tiers this invocation covers. All of them unless it was narrowed to a substep
    # (`java:qodana`, `java:mvn,java:e2e`, ...); every guard, launch and join below is gated on these, so
    # a scoped run pays for nothing it did not ask for and the full gate behaves exactly as it always has.
    local run_mvn=false run_e2e=false run_smoke=false run_qodana=false
    runs_substep java mvn && run_mvn=true
    runs_substep java e2e && run_e2e=true
    runs_substep java smoke && run_smoke=true
    runs_substep java qodana && run_qodana=true

    # Named in tier order for the opening and summary lines, so a scoped run says which tiers it is.
    local invocation tier_list=""
    invocation="$(step_invocation java)"
    [[ "${run_mvn}" == true ]] && tier_list+=" + Maven build"
    [[ "${run_e2e}" == true ]] && tier_list+=" + E2E"
    [[ "${run_smoke}" == true ]] && tier_list+=" + deployment-smoke"
    [[ "${run_qodana}" == true ]] && tier_list+=" + Qodana"
    tier_list="${tier_list# + }"

    echo
    echo "Running the JVM gate [${invocation}]: ${tier_list}"

    # Fail before the (multi-minute) Maven build rather than after every *IT has failed to boot. Both the
    # Maven and E2E tiers bind that port, so either one selected is reason enough to check.
    if [[ "${run_mvn}" == true || "${run_e2e}" == true ]] && ! assert_test_port_free; then
        overall_exit_code=1
        return
    fi
    # `java:e2e` without `java:mvn` boots a jar nothing in this run builds - check it exists before the
    # tier stands a database up to discover it.
    if [[ "${run_e2e}" == true && "${run_mvn}" == false ]] && ! assert_fast_jar_present; then
        overall_exit_code=1
        return
    fi
    # Likewise for the Qodana tier's config: check it here, before the first tier is launched in the
    # background, so the answer is a one-line message rather than an orphaned smoke stack.
    if [[ "${run_qodana}" == true ]] && ! qodana_config_guard; then
        overall_exit_code=1
        return
    fi
    # The java step is the whole JVM-side gate, in four tiers - each of them also a SUBSTEP, so any subset
    # of them can be selected on its own (`java:qodana`, `java:mvn,java:e2e`):
    #   1. mvn clean install -Dall — unit tests + *IT + the full inherited lint suite. Drives the CSS
    #      build (Node) and a managed IT test DB (Docker), and packages the fast-jar the E2E run reuses;
    #      so it runs against the host toolchain (JDK + Maven + Node + Docker), not a Maven Docker image.
    #   2. tests/run-e2e.sh — Playwright UI suite against the packaged jar (its own DB, port 8081). No
    #      rebuild needed: it reuses ${PWD}/target/quarkus-app from the install above.
    #   3. tests/run-smoke.sh — deployment-smoke suite against the REAL production image (port 8082),
    #      fully self-contained (builds the image, isolated app+DB stack, readiness-gating check).
    #   4. Qodana — whole-program static analysis (see qodana_prepare). A Java lint like the ones inside
    #      the Maven build, so it belongs to this gate rather than sitting beside it; it is simply the one
    #      that cannot run inside Maven, needing the IntelliJ engine and an index of the whole project.
    #
    # Tiers 1 and 2 are strictly ordered - E2E boots the jar the Maven build just packaged. Tiers 3 and 4
    # are NOT: neither shares anything with them (its own Docker build context - .dockerignore excludes target/ -
    # its own compose project, its own tmpfs DB, its own port), so it is STARTED FIRST, in the
    # background, and joined at the end. That takes the whole smoke tier (including a multi-stage image
    # build) off the critical path: the step now costs max(mvn + e2e, smoke, qodana) rather than their sum.
    # Tier 4 rides the same trick, but it does NOT dominate that max: warm, the scan is the SHORTER tier.
    # Measured on a 16-core box - 2m41s idle and ~7m under a load holding ~7 of the 16 cores, against the
    # Maven tier's 3m51s idle and 5m57s-7m loaded - so tier 1, and behind it tier 2, is what sets the gate's
    # wall clock. (Only a COLD scan, re-indexing from scratch, runs for tens of minutes; that is the case
    # the cache exists to avoid.)
    #
    # Running it alongside the others is still clearly worth it, and more so the busier the machine: under a
    # load saturating the box, the Maven and Qodana tiers together cost 436s in parallel against 581s run one
    # after the other (25% less), and parallel won in every load condition measured. The saving is well short
    # of the ideal max(...) because the two contend - each is stretched (mvn +25%, the scan +70%) until they
    # converge on near-identical finish times - but capping the scan's CPU only moves that cost around rather
    # than removing it: pinned to 4 cores the Maven tier gained 19s while the scan lost 65s and became the
    # critical path, for a net 47s WORSE. (On this Docker-in-Docker setup --cpus and --cpu-shares cannot be
    # applied at all - "cgroupv2 ... is in threaded mode" - so --cpuset-cpus is the only such knob available,
    # and it is not worth reaching for.) The first few minutes are genuinely contended (PITest, a multi-stage
    # image build, Chromium and the indexer at once); if that ever destabilises a tier, moving tier 4 after
    # the join is a one-line change.
    #
    # Tier 4 is supervised in ONE direction only: an earlier failure stops the scan (worth many minutes), but
    # a scan failure does not stop the others - nothing polls for it, so it is noticed only at the join below.
    # Being the shorter tier, it usually finishes FIRST, which is exactly why it has to stamp its own finish
    # time (see its launch) rather than be timed at that join.
    #
    # The old short-circuit (a failing tier skipped the slower ones that followed) becomes symmetric: the
    # FIRST tier to fail stops the others and the step gives up, in either direction. An earlier tier
    # failing signals smoke; smoke failing stops the Maven build or E2E run mid-flight (run_supervised_tier
    # polls for it rather than only joining at the end). Every stop is a signal-then-wait: run-smoke.sh and
    # run-e2e.sh trap it and tear their stacks down, so nothing leaks - though bash defers a trap until the
    # script's current foreground command returns, so a tier sitting in `docker compose up --wait` finishes
    # that one command first. The wait is deliberate: abandoning a child would leak its containers.
    #
    # A scoped run keeps every one of those rules and simply has fewer tiers to apply them to: the ordering,
    # the supervision and the early stops are all written against whichever tiers were selected, so
    # `java:mvn,java:smoke` still stops the build when smoke fails, and `java:qodana` is the scan alone.
    #
    # Each tier is timed individually and the durations - green when the tier passed, red when it failed -
    # are folded into the summary line below, because "the java step took 31 minutes" is not actionable on
    # its own; which tier owns those minutes, and which one died, is.
    local failed_at=""
    TIMED_SUBSTEPS=()

    # The Maven gate, optionally with SonarQube analysis appended (see SONARQUBE_ANALYSIS above). The
    # label mirrors the exact args so the progress + "re-run …" hints stay copy/paste-accurate.
    local -a mvn_args=(clean install -Dall)
    local mvn_label="mvn clean install -Dall"
    if [[ "${run_mvn}" == true && "${SONARQUBE_ANALYSIS}" == "true" ]]; then
        substep "checking SonarQube server reachability (${SONARQUBE_HOST_URL})"
        if sonarqube_reachable; then
            mvn_args+=(-Dsonarqube)
            mvn_label+=" -Dsonarqube"
        else
            echo "⚠️  SonarQube server at ${SONARQUBE_HOST_URL} is unreachable - skipping analysis for this run"
        fi
    fi

    # Tier 3, launched first and supervised by every later tier (see run_supervised_tier). SMOKE_PID is
    # left EMPTY when the tier was not selected, which is what tells run_supervised_tier there is nothing
    # to supervise against.
    SMOKE_PID=""
    SMOKE_RC=""
    SMOKE_LOG=""
    if [[ "${run_smoke}" == true ]]; then
        SMOKE_LOG="$(mktemp)"
        SMOKE_START_NS="$(date +%s%N)"
        substep "tests/run-smoke.sh  (on :${SMOKE_HTTP_PORT})"
        "${PWD}/tests/run-smoke.sh" "${SMOKE_HTTP_PORT}" "${PWD}" > "${SMOKE_LOG}" 2>&1 &
        SMOKE_PID=$!
    fi

    # Tier 4, launched alongside tier 3 and joined last (it is the longest by far).
    QODANA_RC=""
    QODANA_LOG=""
    QODANA_END_NS_FILE=""
    if [[ "${run_qodana}" == true ]]; then
        QODANA_LOG="$(mktemp)"
        QODANA_END_NS_FILE="$(mktemp)"
        QODANA_START_NS="$(date +%s%N)"
        qodana_prepare
        substep "qodana"
        # The pull happens INSIDE the background tier: a cold one is several GB, and pulling before the
        # launch would hold the Maven tier behind a download that has nothing to do with it.
        #
        # The tier stamps its OWN finish time into QODANA_END_NS_FILE on the way out, because nothing polls
        # for it the way run_supervised_tier polls for smoke - it is simply joined once the other tiers are
        # done. Timed at that join it would be credited with every minute they were still running, so its
        # figure could never read shorter than theirs and the scan would always look like the gate's longest
        # tier. Measured: a scan that really took 6m58s was reported as 9m10s, the exact length of the Maven
        # tier it was joined behind.
        (
            { docker pull "${QODANA_DOCKER_IMAGE}" >/dev/null && "${QODANA_CMD[@]}"; } > "${QODANA_LOG}" 2>&1
            qodana_rc=$?
            date +%s%N > "${QODANA_END_NS_FILE}"
            exit "${qodana_rc}"
        ) &
        QODANA_PID=$!
    fi

    if [[ "${run_mvn}" == true ]]; then
        run_supervised_tier "mvn" "${mvn_label}" \
            mvn "${mvn_args[@]}" || failed_at="${mvn_label}"
        if [[ "${TIER_STOPPED_EARLY}" == true ]]; then
            failed_at="tests/run-smoke.sh"
            # Maven was signalled mid-flight, so its post-integration-test teardown never ran (see
            # sweep_test_db); remove the IT database it may have left standing.
            sweep_test_db
        fi
    fi

    if [[ "${run_e2e}" == true && -z "${failed_at}" ]]; then
        run_supervised_tier "e2e" "tests/run-e2e.sh  (on :${E2E_HTTP_PORT}, reusing the mvn jar)" \
            "${PWD}/tests/run-e2e.sh" "${E2E_HTTP_PORT}" "${PWD}/target" "${PWD}" \
            || failed_at="tests/run-e2e.sh"
        if [[ "${TIER_STOPPED_EARLY}" == true ]]; then
            failed_at="tests/run-smoke.sh"
        fi
    fi

    # Join tier 3, unless a supervised tier already saw it finish. If an earlier tier failed, signal it and
    # wait for its teardown rather than letting it run on; that earlier failure is the one reported.
    if [[ -n "${SMOKE_PID}" ]]; then
        if [[ -z "${SMOKE_RC}" ]]; then
            if [[ -n "${failed_at}" ]]; then
                stop_tier "${SMOKE_PID}"
                substep "tests/run-smoke.sh stopped early (${failed_at} already failed)"
            else
                SMOKE_RC=0
                wait "${SMOKE_PID}" || SMOKE_RC=$?
                report_smoke_completion
            fi
        fi
        if [[ -n "${SMOKE_RC}" && "${SMOKE_RC}" != "0" ]]; then
            failed_at="tests/run-smoke.sh"
            if [[ -s "${SMOKE_LOG}" ]]; then
                cat "${SMOKE_LOG}"
            fi
        elif [[ "${VERBOSE}" == true && -s "${SMOKE_LOG}" ]]; then
            cat "${SMOKE_LOG}"
        fi
        rm -f "${SMOKE_LOG}"
    fi

    # Join tier 4. An earlier failure means the scan's answer is moot and its remaining minutes are pure
    # waste, so stop it - removing the container, not just signalling the client that started it.
    if [[ "${run_qodana}" == true ]]; then
        if [[ -n "${failed_at}" ]]; then
            stop_tier "${QODANA_PID}"
            qodana_stop_container
            substep "qodana stopped early (${failed_at} already failed)"
        else
            QODANA_RC=0
            wait "${QODANA_PID}" || QODANA_RC=$?
            substep "qodana finished (parallel tier)"
            # The tier's own stamp (see the launch above), not this moment. Empty only if it died before it
            # could write one - a killed container, a full disk - in which case the join time is used, which
            # is the old behaviour: an upper bound, but a figure rather than a blank.
            local qodana_end_ns=""
            [[ -s "${QODANA_END_NS_FILE}" ]] && qodana_end_ns="$(< "${QODANA_END_NS_FILE}")"
            record_substep_time "qodana" "${QODANA_START_NS}" "${QODANA_RC}" "${qodana_end_ns}"
            if [[ "${QODANA_RC}" != "0" ]]; then
                failed_at="qodana"
                [[ -s "${QODANA_LOG}" ]] && cat "${QODANA_LOG}"
                if qodana_dependency_guard; then
                    qodana_failure_hint
                fi
            elif ! qodana_dependency_guard; then
                # A pass the scan could have produced with no source on the classpath at all is not a pass.
                failed_at="qodana"
            elif [[ "${VERBOSE}" == true && -s "${QODANA_LOG}" ]]; then
                cat "${QODANA_LOG}"
            fi
        fi
        rm -f "${QODANA_LOG}" "${QODANA_END_NS_FILE}"
    fi

    local done_in breakdown
    done_in="$(step_time)"
    breakdown="$(substep_breakdown)"
    if [[ -z "${failed_at}" ]]; then
        echo "✅ Java gate [${invocation}] passed (${tier_list}), finished in ${GREEN}${done_in}${RESET}${breakdown}"
    else
        echo "❌ Java gate [${invocation}] failed at [${failed_at}] after ${RED}${done_in}${RESET}${breakdown}: re-run ${YELLOW}'${SCRIPT_PATH} -v ${invocation}'${RESET} for the full output"
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
    # Lint every tracked *.js/*.cjs file, PLUS any new one that is not gitignored. `--exclude-standard`
    # keeps out the vendored, gitignored htmx.min.js (see scripts/vendor-assets.cjs), the
    # code-quality-config submodule, and any node_modules/target build output — the same approach the
    # shell-script step below takes.
    #
    # (Wording note: a comment line must never BEGIN with the tool's own name, because such a line is
    # parsed as a directive rather than as prose, and the whole enclosing function then fails to parse.)
    #
    # The --others flag is load-bearing: a bare "git ls-files" lists only TRACKED files, so a brand-new
    # script was silently skipped until the moment it was committed. That is exactly when linting it is most
    # valuable, and it let a genuine syntax error through a passing gate once already.
    local files=() module_files=()
    mapfile -t files < <(git ls-files --cached --others --exclude-standard '*.js' '*.cjs' | sort -u || true)
    mapfile -t module_files < <(git ls-files --cached --others --exclude-standard '*.mjs' | sort -u || true)
    local done_in
    if [[ "${#files[@]}" -eq 0 && "${#module_files[@]}" -eq 0 ]]; then
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
    else
        echo "${output}"
        done_in="$(step_time)"
        echo "❌ JavaScript lint failed after ${RED}${done_in}${RESET}: re-run ${YELLOW}'${SCRIPT_PATH} -v javascript'${RESET} for the full output"
        overall_exit_code=1
        return
    fi

    # The *.mjs files (the k6 perf scripts and tests/measure.mjs) get a PARSE check rather than the full
    # eslint pass above. They are ES modules, and the shared config is `sourceType: 'script'` — which is
    # exactly why they were given the .mjs extension in the first place, so eslint would not try to parse
    # them. The side effect was that nothing checked them at all: tests/measure.mjs sat calling an
    # endpoint that had been renamed, and a syntax error in load.mjs would only surface when the perf
    # step actually ran it. `node --check` reads the module syntax per extension and costs milliseconds,
    # so the "does this file even parse" class is covered without editing the shared submodule config.
    if [[ "${#module_files[@]}" -eq 0 ]]; then
        done_in="$(step_time)"
        echo "✅ JavaScript lint passed, finished in ${GREEN}${done_in}${RESET}"
        return
    fi
    substep "node --check (${#module_files[@]} ES module file(s))"
    if output=$(docker run --rm \
        -u "${HOST_UID}:${HOST_GID}" \
        -e HOME=/tmp \
        -v "${PWD}":/app \
        -w /app \
        --entrypoint sh \
        "${ESLINT_BUILD_IMAGE}" \
        -c 'for f in "$@"; do node --check "${f}" || exit 1; done' _ "${module_files[@]}" 2>&1); then
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
    #
    # The install is CONDITIONAL, and that is a concurrency fix rather than a saving. tests/ is a bind
    # mount of the working tree, and `npm ci` DELETES node_modules before reinstalling it - on the host,
    # in the middle of a run where this lane is racing the java gate, whose smoke and E2E tiers resolve
    # Playwright out of that very directory (measured: it vanished for ~0.4-0.5s about 9s into every
    # full run). Installing only when the directory is absent removes the window entirely in the normal
    # case - the java step and CI both require those dependencies to exist already - while a standalone
    # `lint_and_tests.sh typescript` on a fresh clone still installs what the type-checker needs.
    substep "eslint (tests/**/*.ts; npm ci only if tests/node_modules is missing)"
    if output=$(docker run --rm \
        -u "${HOST_UID}:${HOST_GID}" \
        -e HOME=/tmp \
        -e npm_config_cache=/tmp/.npm \
        -v "${PWD}":/app \
        -w /app/tests \
        --entrypoint sh \
        "${ESLINT_BUILD_IMAGE}" \
        -c "if [ ! -d node_modules ]; then npm ci --no-audit --no-fund; fi && \
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
    # .qodana is excluded like target/ and node_modules: the scan's cache holds a downloaded JBR SDK whose
    # legal/ dir ships hundreds of third-party .md files. markdownlint globs the tree itself and does not
    # read .gitignore, so ignoring them in git is not enough - without this the step fails after any scan.
    echo "Running Markdown lint using [${MARKDOWNLINT_DOCKER_IMAGE}]"
    docker pull "${MARKDOWNLINT_DOCKER_IMAGE}" >/dev/null
    if output=$(docker run --rm \
        -v "${PWD}":/app \
        -w /app \
        "${MARKDOWNLINT_DOCKER_IMAGE}" \
        --config code-quality-config/markdown/.markdownlint.json \
        "**/*.md" "!code-quality-config/**" "!**/node_modules/**" "!**/target/**" \
        "!.claude/**" "!RELEASE_NOTES.md" "!tests/playwright-report/**" "!tests/test-results/**" \
        "!.qodana/**" 2>&1); then
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

# Answers "is the scan actually configured?" before a container is started. Two of the scan's three inputs
# - the inspection profile QODANA_CONFIG_FILE imports, and the entry-point overrides mounted over `.idea` -
# live in the code-quality-config submodule, so an uninitialised submodule leaves neither on disk. The
# third is QODANA_CONFIG_FILE itself, which is tracked here and so can only go missing by being moved.
#
# No absence is loud on its own, which is why this exists. A missing profile is the silent fall-back to the
# IDE Default profile documented in QODANA_CONFIG_FILE - that profile omits UnusedDeclaration, so the run
# goes GREEN having skipped the one inspection this step was added for. A config file the `--config` path
# does not resolve to is the same failure with one more step (no file, no `imports:`, Default profile), and
# is checked rather than trusted precisely because the path is now written out instead of found by
# convention. A missing overrides directory is the opposite and just as misleading: nothing teaches the
# community image what CDI or JAX-RS instantiate, and 238 framework-managed declarations report as unused,
# none of them real.
qodana_config_guard() {
    if [[ ! -f "${QODANA_CONFIG_FILE}" ]]; then
        echo "❌ Qodana's configuration is missing - expected ${YELLOW}${QODANA_CONFIG_FILE}${RESET}"
        echo "   That file is tracked in this repository; restore it (or fix the --config path) and try again."
        return 1
    fi

    [[ -f "${QODANA_PROFILE_FILE}" && -f "${QODANA_OVERRIDES_DIR}/misc.xml" ]] && return 0
    echo "❌ Qodana's configuration is missing - the code-quality-config submodule is not checked out."
    echo "   Expected ${YELLOW}${QODANA_PROFILE_FILE#"${PWD}/"}${RESET} and ${YELLOW}${QODANA_OVERRIDES_DIR#"${PWD}/"}/misc.xml${RESET}"
    echo "   Run ${YELLOW}'git submodule update --init'${RESET} and try again."
    return 1
}

# Prepares Qodana's working dirs, pulls the pinned image, and assembles the `docker run` argv into the
# QODANA_CMD global. There is one caller (the java gate's tier), which is the point: `java:qodana` scopes
# the gate down to that same tier rather than running a scan of its own, so a standalone run and a full
# gate cannot drift into analysing the project differently.
#
# This is the one analysis that sees the WHOLE project at once. PMD, ErrorProne and SpotBugs all report
# unused code, but only for private members: each reads one compilation unit, so a public method could be
# called from anywhere they cannot see. Dead public/package-private methods have reached master through
# that gap. Configuration (profile, exclusions, the no-baseline policy) lives in QODANA_CONFIG_FILE.
qodana_prepare() {
    local uid gid
    uid="$(id -u)"
    gid="$(id -g)"
    mkdir -p "${QODANA_WORK_DIR}/results" "${QODANA_WORK_DIR}/cache"

    # Named so the container can be removed outright when the tier is stopped early. Signalling the
    # `docker run` CLI is not enough - the daemon owns the container, so killing the client can leave a
    # multi-GB analysis running with nothing attached to it. PID-scoped so two runs never collide.
    QODANA_CONTAINER="diurnal-qodana-$$"

    # The scan's own IDE configuration, built fresh from the submodule's overrides/ (QODANA_OVERRIDES_DIR)
    # and mounted OVER /data/project/
    # .idea inside the container (see the mount below). The developer's own .idea/ is therefore invisible to
    # the scan and is never read, written or restored - which is the point. An earlier version swapped the
    # tracked file into .idea/misc.xml for the duration of a run and put the original back afterwards; that
    # works, but for those minutes the config an open IDE is watching has no ProjectRootManager in it, so
    # IntelliJ drops the project JDK and writes back a stripped file. Shadowing the whole directory keeps
    # the scan hermetic and leaves the working tree alone even if the run is killed.
    #
    # The cache's copy has to go too: the scan keeps its own `.idea` under /data/cache and restores it at
    # the start of every run, so a warm cache would analyse the PREVIOUS overrides and an edit to them would
    # appear to do nothing at all - a silent failure that costs an afternoon. Dropping just that directory
    # is cheap; the index and the downloaded JBR are what make the cache worth keeping, and neither moves.
    # Built FRESH every run, from the tracked overrides alone. Keeping the project model the scan writes
    # here between runs was tried as an optimisation and reverted: the second run then fails outright with
    # "Cannot configure project to run inspections", because what is left behind is a partial model that no
    # longer agrees with the overrides copied over it. It also bought nothing measurable - a warm scan costs
    # the same either way, since the index (the expensive part) lives in the cache below, not here.
    #
    # The cache's own copy of `.idea` goes too: the scan restores that over the project's at start-up, so a
    # stale one would silently reinstate the PREVIOUS overrides. That directory is the project model only -
    # the index and the downloaded JBR are siblings of it and are untouched, so this costs nothing.
    rm -rf "${QODANA_WORK_DIR:?}/cache/.idea" "${QODANA_IDEA_DIR:?}"

    # The IDE's own module model under cache/idea/*/projects/ is deliberately NOT touched here, though it is
    # what goes stale in a cache written before ExternalStorageConfigurationManager was removed from the
    # overrides (symptom: "This project contains no modules", on every run, forever). Deleting it per-run
    # was tried and is worse than the problem: the run doing the deleting fails outright, and the next one
    # re-indexes from scratch - measured at 9m57s against 2m30s warm, on every single run.
    #
    # The IDE's imported module model lives in cache/idea/*/projects/, NOT in `.idea` - a Maven project is
    # imported by the external-system integration, which always writes there (see the header of
    # code-quality-config/java/qodana/overrides/misc.xml for the two attempts to change that, and why
    # neither could). So the model
    # outlives the `.idea` rebuilt above, and when the two disagree every run fails with "This project
    # contains no modules". Because the bad state is in the CACHE, it survives a git pull and looks
    # machine-specific: whoever ran an older set of overrides keeps failing, and whoever did not, cannot
    # reproduce it.
    #
    # So it is dropped on EVERY run, unconditionally. `.idea` is rebuilt from the overrides each time, so a
    # model that outlives it is a model describing a project that no longer exists - and the second run is
    # where that bites, which is why a clean cache passes and the run straight after it fails.
    #
    # This costs nothing. Measured: the run that drops the model takes 2m34s against 2m36-2m38s for one
    # that keeps it, because the expensive artefact is the INDEX, and that is a sibling of this directory
    # (caches/ and index/, hundreds of MB, against ~280KB here) which is never touched. Re-importing a
    # single-module Maven project against a warm index is seconds.
    #
    # Two narrower forms were tried and are worse. Deleting only the external_build_system and
    # project-model-cache children fails the very run that does it - the state left behind is half a model.
    # Stamping the cache with a hash of the overrides and dropping it only on change looks tidier, but the
    # model goes stale WITHOUT the overrides changing, so the stamp simply never fires when it matters.
    if compgen -G "${QODANA_WORK_DIR}/cache/idea/*/projects" > /dev/null; then
        rm -rf "${QODANA_WORK_DIR:?}"/cache/idea/*/projects
    fi

    # A half-extracted JBR, on the other hand, IS worth healing here: the scan downloads its own JDK into
    # the cache, and an interrupted or truncated download leaves the directory in place with the runtime
    # missing from it. Nothing retries, so every later run dies before it reads a line of Java with
    #     Error: missing `server' JVM at .../qodana-jbr/.../lib/server/libjvm.so
    # which reads like a broken local toolchain rather than a bad download. The marker file below is the
    # one the scan itself looks for, so this fires ONLY when the runtime really is absent; a complete JBR
    # and a cold cache both leave it alone, and the ~72MB re-download costs nothing next to the index,
    # which lives elsewhere and is untouched.
    if [[ -d "${QODANA_WORK_DIR}/cache/qodana-jbr" ]] \
        && [[ -z "$(find "${QODANA_WORK_DIR}/cache/qodana-jbr" -name libjvm.so -print -quit 2>/dev/null || true)" ]]; then
        echo "   Cached Qodana JDK is incomplete (no libjvm.so) - removing it so the scan re-downloads"
        rm -rf "${QODANA_WORK_DIR:?}/cache/qodana-jbr"
    fi

    # The scan resolves the project's dependencies itself, into its OWN Maven repository at cache/.m2
    # (MAVEN_REPOSITORY=/data/cache/.m2 inside the container) and through JetBrains' cache-redirector
    # mirror rather than Maven Central. When that mirror answers 5xx for one artifact, Maven writes a
    # `.lastUpdated` marker recording the failure and then does NOT retry it - not on the next run, not
    # for the whole update interval - so a blip of a few seconds is inherited by every later scan from
    # the cache, and by CI from the cache it restores.
    #
    # The damage is out of all proportion to the one file. A single 502 on
    # org.hibernate.orm:hibernate-platform:pom - the BOM hibernate-core imports, so its descriptor
    # cannot be read without it - dropped the module from 813 resolved jars to 192, and with the
    # libraries went every third-party symbol: 36 JavadocReference "Cannot resolve symbol" on ordinary
    # imported types, 2,038 `unused` (nothing looks reachable once the JUnit and CDI annotations are
    # unresolved) and 475 sanity errors. NONE of it is real, and none of it looks unreal in the report -
    # the findings name our own files, and open as already-fixed in an IDE that resolves against a
    # complete ~/.m2. Only the run's own log says why, in `... were not resolved` lines nobody reads
    # unless they already suspect the classpath.
    #
    # So the markers go before every run: a failed resolution is re-attempted rather than inherited,
    # which is the behaviour `mvn -U` gives the Maven tier. This costs nothing when there are none (the
    # normal case) and re-downloads only the artifacts that actually failed when there are - the jars
    # that resolved are keyed by path and stay put. It deliberately does not touch anything else in
    # cache/.m2: the repository is the second-most expensive thing in the cache after the index.
    local stale_markers
    stale_markers="$(find "${QODANA_WORK_DIR}/cache/.m2" -name "*.lastUpdated" -print -delete 2>/dev/null | wc -l)"
    if [[ "${stale_markers}" -gt 0 ]]; then
        echo "   Cleared ${stale_markers} cached Maven resolution failure(s) so the scan re-attempts them"
    fi

    mkdir -p "${QODANA_IDEA_DIR}"
    cp -R "${QODANA_OVERRIDES_DIR}/." "${QODANA_IDEA_DIR}/"

    # Run as the invoking user so the report and index cache are not root-owned; --fail-threshold 0 turns
    # any finding at all into a non-zero exit, which is what makes this a gate rather than a report. No
    # --baseline is ever passed: the scan is judged fresh on every run, so a finding cannot be inherited
    # into an accepted set - it has to be fixed or switched off in the profile. The image resolves the
    # project's Maven dependencies itself - mount the host's ~/.m2 into the container's home if that
    # resolution ever becomes the slow part of a run.
    #
    # --config is what lets the configuration live outside the repo root: the CLI looks for `qodana.yaml`
    # beside the project only when it is not told otherwise. Unlike --profile-path it is honoured, and
    # qodana_config_guard checks the file is there first, because a path that resolves to nothing is the
    # same silent fall-back to the IDE Default profile.
    #
    # The two kinds of relative path inside that file do NOT resolve the same way, and `--config --help`
    # ("Relative paths in the configuration will be based on the project directory") is wrong about the
    # first: `imports:` is resolved against the CONFIG FILE's own directory, so the submodule profile is
    # reached through a `../` that was not needed while the file sat in the root, while `exclude:` and the
    # per-inspection `ignore:` globs really are project-relative and were left untouched. Only the import
    # can be got wrong silently in principle, and in practice it is not: the run fails outright with
    # "imports file not found" rather than quietly inspecting nothing.
    QODANA_CMD=(docker run --rm
        --name "${QODANA_CONTAINER}"
        -u "${uid}:${gid}"
        -v "${PWD}":/data/project
        -v "${QODANA_IDEA_DIR}":/data/project/.idea
        -v "${QODANA_WORK_DIR}/results":/data/results
        -v "${QODANA_WORK_DIR}/cache":/data/cache
        "${QODANA_DOCKER_IMAGE}"
        --config "${QODANA_CONFIG_FILE}"
        --fail-threshold 0)
}

# Remove the scan container outright. Used when the tier is stopped early: `stop_tier` signals the docker
# CLI, but the daemon keeps the container (and its CPU) unless it is explicitly removed.
#
# Written as an `if` rather than the shorter `[[ … ]] && docker rm … || true`: that form reads as an
# if-then-else but is not one (the `|| true` also fires when the test passes and the removal fails), which
# is what SC2015 warns about. ShellCheck itself stays quiet here - it suppresses SC2015 when the `||`
# branch is a harmless no-op like `true` - but an IDE pattern-matching the shape still flags it.
# Best-effort either way: a container that is already gone must not fail the cleanup, hence `|| true`.
qodana_stop_container() {
    if [[ -n "${QODANA_CONTAINER}" ]]; then
        docker rm -f "${QODANA_CONTAINER}" >/dev/null 2>&1 || true
    fi
}

# Answers the question every other message here assumes: were the project's dependencies actually on the
# classpath the scan analysed? Returns 0 when they were, and when they were not, explains it and returns 1.
#
# It matters because the failure is SILENT and the findings it produces are indistinguishable from real
# ones. The scan resolves dependencies itself (see the marker sweep in qodana_prepare), and when one
# artifact does not resolve, the module quietly loses the libraries below it - measured here, 813 jars
# down to 192 from a single 502 on a BOM pom. Nothing fails; the analysis simply proceeds against a
# project where `Response`, `SecurityIdentity` and `QuarkusTest` are unknown words. What lands in the
# report is 36 "Cannot resolve symbol" Javadoc findings on ordinary imported types and 2,038 `unused`
# declarations (nothing is reachable once the JUnit and CDI annotations are meaningless) - every one of
# them naming OUR file and OUR line, and every one of them opening as already-fixed in an IDE that
# resolves against a complete ~/.m2. Chasing them is a wasted afternoon that ends in a fix for nothing.
#
# So the run's own log is the thing to read, and this reads it: the IDE prints one `... were not resolved`
# line per library it could not attach. idea.log is APPENDED to across runs, so only the last run counts -
# hence the offset to its final start-up banner rather than a grep over the whole file.
qodana_dependency_guard() {
    local log="${QODANA_WORK_DIR}/results/log/idea.log"
    [[ -f "${log}" ]] || return 0

    local run_start unresolved
    run_start="$(grep -n "AppStarter - JVM:" "${log}" 2>/dev/null | tail -1 | cut -d: -f1 || true)"
    [[ -n "${run_start}" ]] || run_start=1
    unresolved="$(tail -n "+${run_start}" "${log}" 2>/dev/null | grep -c "were not resolved" || true)"
    [[ "${unresolved}" -gt 0 ]] || return 0

    echo "   ${RED}The scan ran against an INCOMPLETE classpath - ${unresolved} project libraries were not resolved.${RESET}"
    echo "   Its findings are NOT trustworthy and must not be fixed or suppressed: unresolved imports are"
    echo "   reported as 'Cannot resolve symbol' Javadoc findings, and code that uses them stops looking"
    echo "   reachable and is reported as 'unused'. The artifacts that failed to resolve:"
    tail -n "+${run_start}" "${log}" 2>/dev/null \
        | grep -oE "Failed to read artifact descriptor for [^[:space:]]+" \
        | sort -u | head -5 | sed 's/^/     /' || true
    echo "   Cached resolution failures are cleared before every run, so re-running is usually the whole fix"
    echo "   (the mirror is JetBrains' cache-redirector, not Maven Central, and answers 5xx of its own)."
    return 1
}

# Printed wherever a Qodana run fails, since the count alone is not actionable until the reader knows
# where to read the findings. Every finding counts - there is no baseline to absorb any of them - so the
# only two answers are to fix it or to switch the inspection off in the submodule's inspection profile.
qodana_failure_hint() {
    echo "   Full report in ${YELLOW}.qodana/results${RESET} (open report/index.html to browse it)."
    echo "   No baseline exists by design: fix the finding, or disable its inspection in ${YELLOW}${QODANA_PROFILE_FILE#"${PWD}/"}${RESET}."
}

run_shellcheck() {
    echo
    echo "Running shell script lint using [${SHELLCHECK_DOCKER_IMAGE}]"
    # Lint every tracked *.sh file, plus any new one that is not gitignored (see the --others note in
    # run_javascript: a bare "git ls-files" skips a brand-new script entirely). Excludes the code-quality-config
    # submodule (its files belong to that repo) and any node_modules/target build output.
    local files=()
    mapfile -t files < <(git ls-files --cached --others --exclude-standard '*.sh' | sort -u || true)
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

# --- parallel lanes (the linters alongside the java gate) ---------------------------------------------
# Which steps may run concurrently with one another. Every entry is either a self-contained `docker run`
# over the working tree (no host port, no database, no build output, seconds to a couple of minutes) or the
# multi-minute `java` gate. Run together, the linters' runtime disappears entirely inside the gate's.
#
# `perf` is deliberately NOT lane-eligible, and `docker` only conditionally so (see lane_eligible):
#   - perf MEASURES the application - cold-boot latency, post-boot RSS, k6 latency thresholds. Its ports are
#     disjoint from everything else, so the problem is not a clash but CONTENTION: a machine also running
#     PITest, Chromium and a multi-stage Docker build makes every threshold a coin flip. It runs alone.
#   - docker's grype tier builds the production runtime image, which the java step's smoke tier also builds
#     from the same Dockerfile with the same GENERATE_PREVIEWS=false. Run after it, that build is a cache
#     hit (the whole point of the note in run_grype_tier); run alongside it, both start cold and the image
#     is built twice. So the step joins the lanes only when that tier is not selected - lane_eligible, not
#     this list, is the final answer for it.
#   - qodana is not listed because it is not a step at all: the `java` gate runs it as a parallel tier (see
#     run_java), and `java:qodana` scopes that gate down to it. Lane eligibility is otherwise a property of
#     the STEP, so a substep-scoped `java` is lane-eligible exactly as the whole gate is.
LANE_STEPS=("docker" "java" "javascript" "markdown" "shellcheck" "typescript")

# True when ${1} appears in the remaining arguments.
lane_contains() {
    local needle="${1}"
    shift
    local item
    for item in "$@"; do
        [[ "${item}" == "${needle}" ]] && return 0
    done
    return 1
}

# True when ${1} may run in the parallel phase. LANE_STEPS is the base answer; `docker` qualifies it,
# because only half of that step is lane-safe. `docker:hadolint` is a seconds-long `docker run` over the
# working tree and rides the lanes exactly as the whole step used to, while its grype tier builds the image
# the java gate's smoke tier builds (see LANE_STEPS) and so belongs in the serial tail, where that build is
# a cache hit rather than a second cold one. Nothing is serialised that need not be: the hadolint tier still
# runs in parallel INSIDE the step, alongside the scan that put it there.
lane_eligible() {
    lane_contains "${1}" "${LANE_STEPS[@]}" || return 1
    [[ "${1}" == "docker" ]] && runs_substep docker grype && return 1
    return 0
}

# Signal a still-running lane's whole process group and wait for it to go. TERM to the GROUP (the leading
# "-" on the pid) is what reaches the descendants that matter - mvn, the docker CLI, run-smoke.sh,
# run-e2e.sh - so the tier runners' own traps fire and tear their Docker stacks down. Signalling only the
# subshell would kill the bookkeeping and orphan every one of them, leaving containers and the test DB
# standing. The wait is what gives those traps time to finish; abandoning the child would leak its stack.
lane_stop() {
    local pid="${1}"
    kill -TERM -- -"${pid}" 2>/dev/null || kill -TERM "${pid}" 2>/dev/null || true
    wait "${pid}" 2>/dev/null || true
}

# Stop every lane that has not yet been reaped. Called by the INT trap (Ctrl-C) and by the fail-fast abort.
lane_abort_all() {
    local i
    for i in "${!LANE_PIDS[@]}"; do
        [[ -n "${LANE_RCS[i]}" ]] && continue
        lane_stop "${LANE_PIDS[i]}"
        LANE_RCS[i]="cancelled"
    done
}

# Launch one step in the background with its output captured to a temp file. The caller enables job control
# (`set -m`) first, which is what places each launched job in its OWN process group so lane_stop can signal
# the whole tree rather than just the subshell. The monitor flag is inherited by the subshell but is turned
# off again inside it, so the step's own background tiers (run_java's smoke/e2e) stay in that same group and
# remain reachable by the group kill.
#
# A step reports failure by setting the global overall_exit_code, which a background subshell cannot write
# back to the parent - so the subshell re-emits it as its own exit status, which is what the reaper reads.
lane_start() {
    local step="${1}"
    local log
    log="$(mktemp)"
    (
        set +m
        overall_exit_code=0
        STEP_START_NS="$(date +%s%N)"
        "run_${step}"
        exit "${overall_exit_code}"
    ) > "${log}" 2>&1 &
    LANE_PIDS+=("$!")
    LANE_NAMES+=("${step}")
    LANE_LOGS+=("${log}")
    LANE_RCS+=("")
}

# Run every step named in the arguments concurrently and block until they have all finished - or, under
# -e/--exit-on-failure, until the first failure stops the rest. Results are folded into failed_steps /
# cancelled_steps / final_exit_code, and each step's captured output is replayed afterwards in the order the
# steps were given, so a parallel run reads like a serial one.
#
# The java gate's log is STREAMED live while the others stay captured: its per-tier progress lines are the
# only feedback a multi-minute step gives, and two live streams interleave line-by-line into something
# neither readable nor greppable (the same reason run_supervised_tier never streams the smoke tier).
run_lanes() {
    local lane_steps=("$@")
    LANE_PIDS=()
    LANE_NAMES=()
    LANE_LOGS=()
    LANE_RCS=()

    echo
    echo "Running ${#lane_steps[@]} steps in parallel: $(IFS=', '; echo "${lane_steps[*]}")"

    # The javascript and typescript steps share one locally-built eslint image. Started together they would
    # race to build the same tag and pay the npm install twice over, so build it once here; each step's own
    # ensure_eslint_image call is then a cache hit. A failure is not reported here - the steps themselves
    # re-run it and fail with the detail attributed to the right step.
    if lane_contains "javascript" "${lane_steps[@]}" || lane_contains "typescript" "${lane_steps[@]}"; then
        substep "preparing the shared eslint image (${ESLINT_BUILD_IMAGE})"
        ensure_eslint_image > /dev/null 2>&1 || true
    fi

    local step
    set -m
    for step in "${lane_steps[@]}"; do
        lane_start "${step}"
    done
    set +m

    local i tail_pid=""
    for i in "${!LANE_NAMES[@]}"; do
        if [[ "${LANE_NAMES[i]}" == "java" ]]; then
            tail -f "${LANE_LOGS[i]}" 2>/dev/null &
            tail_pid=$!
            break
        fi
    done

    local remaining="${#LANE_PIDS[@]}" aborted=false
    while [[ "${remaining}" -gt 0 ]]; do
        for i in "${!LANE_PIDS[@]}"; do
            [[ -n "${LANE_RCS[i]}" ]] && continue
            tier_reap "${LANE_PIDS[i]}" || continue
            LANE_RCS[i]="${TIER_RC}"
            remaining=$((remaining - 1))
            # String comparison, not -eq: an empty value compares equal to 0 in bash's arithmetic context,
            # so a lane whose exit code was somehow never captured would be treated as a pass (same reason
            # record_substep_time does it this way).
            if [[ "${LANE_RCS[i]}" != "0" && "${FAIL_FAST}" == true ]]; then
                aborted=true
            fi
        done
        [[ "${aborted}" == true ]] && break
        # 1s poll, matching run_supervised_tier: coarse enough to cost nothing over a multi-minute lane.
        [[ "${remaining}" -gt 0 ]] && sleep 1
    done

    # -e/--exit-on-failure: a lane failed, so stop the ones still running rather than paying for the rest of
    # their minutes. They are CANCELLED, not failed - nothing is known about their result.
    [[ "${aborted}" == true ]] && lane_abort_all

    if [[ -n "${tail_pid}" ]]; then
        kill "${tail_pid}" 2>/dev/null || true
        wait "${tail_pid}" 2>/dev/null || true
    fi

    # Replay in the caller's order. The java lane was streamed live, so only its outcome is noted here.
    local name rc
    for i in "${!LANE_NAMES[@]}"; do
        name="${LANE_NAMES[i]}"
        rc="${LANE_RCS[i]}"
        if [[ "${name}" != "java" && -s "${LANE_LOGS[i]}" ]]; then
            cat "${LANE_LOGS[i]}"
        fi
        rm -f "${LANE_LOGS[i]}"
        case "${rc}" in
        0) ;;
        cancelled)
            echo
            echo "⏹  [${name}] cancelled - another step failed first (-e/--exit-on-failure)"
            cancelled_steps+=("${name}")
            ;;
        *)
            # The invocation rather than the bare name, so a scoped lane's failure re-runs scoped (see the
            # serial loop's copy of this).
            failed_steps+=("$(step_invocation "${name}")")
            final_exit_code=1
            ;;
        esac
    done
}

# True when any of the given paths is UNTRACKED, i.e. a brand-new file. Load-bearing for
# detect_changed_steps: an untracked file produces NO `git diff` output at all, so any filter that
# judges a change by its diff CONTENT is blind to one and reads it as "nothing changed".
any_untracked() {
    (($# == 0)) && return 1
    local untracked_file
    while IFS= read -r untracked_file; do
        [[ -z "${untracked_file}" ]] && continue
        if printf '%s\n' "$@" | grep -qxF -- "${untracked_file}"; then
            return 0
        fi
    done < <(git ls-files --others --exclude-standard || true)
    return 1
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

    # The two `docker` tiers are detected SEPARATELY and the step is emitted scoped to whichever of them the
    # change actually touched (see the emission at the end). Their triggers overlap only on the runtime
    # Dockerfile itself: a docker-compose or hadolint-config edit needs the lint alone, and a dependency
    # bump the scan alone - so merging them into one trigger would make every hadolint-only change pay for
    # a multi-stage image build and a CVE scan, which is minutes for nothing.
    local run_hadolint=false run_grype=false run_java=false run_javascript=false run_markdown=false run_shellcheck=false run_typescript=false
    local run_perf=false
    local java_changed_files=()
    # Perf's own inputs (the k6 scripts, the runner, the perf compose stack). A change to any of these
    # triggers `perf` even when no `java`-set file changed — the suite's own definition changed.
    local perf_own_files=false
    local file

    while IFS= read -r file; do
        [[ -z "${file}" ]] && continue
        [[ "${file}" == "Dockerfile" || "${file}" =~ ^sandbox/Dockerfile || "${file}" == ".dockerignore" || "${file}" =~ ^(tests/)?docker-compose || "${file}" =~ ^code-quality-config/docker/ ]] && run_hadolint=true
        # Grype re-scans when the contents of the runtime image change: the runtime Dockerfile (base
        # images, package pins, copy layout), the build context filter, or the ignore list itself. The
        # pom.xml path (bundled Java deps) is handled below with the same version-bump suppression as
        # java; sandbox/Dockerfile and docker-compose don't affect the published runtime image.
        [[ "${file}" == "Dockerfile" || "${file}" == ".dockerignore" || "${file}" == "${GRYPE_CONFIG_FILE}" ]] && run_grype=true
        # The `java` step is the whole JVM gate (Maven build + ITs + the E2E and deployment-smoke
        # suites), so anything feeding ANY of those three tiers triggers it:
        #   - src/, frontend/ (Tailwind source feeding the compiled stylesheet), pom.xml, the java lint config
        #     (which since the move now covers Qodana's profile and overrides too, so an edit to either
        #     re-triggers the tier that reads them - it never did while they sat in .qodana/);
        #   - QODANA_CONFIG_FILE, the scan's own configuration. It never triggered anything while it was a
        #     root-level qodana.yaml, so editing the exclusions or a project-level inspection override left
        #     the tier that reads them unselected - the same gap the profile and overrides had;
        #   - the E2E suite's own files — the ui specs, shared helpers/global-setup, its Playwright config,
        #     the runner, and the tests/ npm/ts deps;
        #   - the deployment-smoke inputs — the runtime Dockerfile/.dockerignore, the smoke stack/suite,
        #     its Playwright config and runner.
        if [[ "${file}" =~ ^src/ || "${file}" =~ ^frontend/ || "${file}" == "pom.xml" || "${file}" =~ ^code-quality-config/java/ \
           || "${file}" == "${QODANA_CONFIG_FILE}" \
           || "${file}" =~ ^tests/ui/ || "${file}" =~ ^tests/helpers/ || "${file}" == "tests/global-setup.ts" \
           || "${file}" == "tests/playwright.config.ts" || "${file}" == "tests/run-e2e.sh" \
           || "${file}" =~ ^tests/package(-lock)?\.json$ || "${file}" == "tests/tsconfig.json" \
           || "${file}" == "Dockerfile" || "${file}" == ".dockerignore" || "${file}" =~ ^tests/smoke/ \
           || "${file}" == "tests/docker-compose.smoke.yml" || "${file}" == "tests/playwright.smoke.config.ts" \
           || "${file}" == "tests/run-smoke.sh" ]]; then
            run_java=true
            java_changed_files+=("${file}")
        fi
        # .mjs is included because the step now parse-checks ES modules too (see run_javascript).
        [[ "${file}" =~ \.(js|cjs|mjs)$ || "${file}" =~ ^code-quality-config/javascript/ ]] && run_javascript=true
        [[ "${file}" =~ ^tests/.*\.ts$ || "${file}" =~ ^tests/tsconfig\.json$ || "${file}" =~ ^tests/package(-lock)?\.json$ || "${file}" =~ ^code-quality-config/typescript/ ]] && run_typescript=true
        [[ "${file}" =~ \.md$ || "${file}" =~ ^code-quality-config/markdown/ ]] && run_markdown=true
        [[ "${file}" =~ \.sh$ || "${file}" =~ ^code-quality-config/shellscript/ ]] && run_shellcheck=true
        # The perf suite's own inputs — its k6 scripts (tests/perf/), the runner, and its compose stack.
        [[ "${file}" =~ ^tests/perf/ || "${file}" == "tests/run-perf.sh" || "${file}" == "tests/docker-compose.perf.yml" ]] && perf_own_files=true
        # A submodule bump is reported as the BARE gitlink path, with no trailing slash, so not one of
        # the `^code-quality-config/<tool>/` patterns above can match it - and the bump changes EVERY
        # linter's config at once. Select them all rather than guessing which tool's rules moved; the
        # alternative (what this fixes) is a config bump that silently runs nothing at all.
        if [[ "${file}" == "code-quality-config" ]]; then
            run_hadolint=true
            run_grype=true
            run_java=true
            run_javascript=true
            run_markdown=true
            run_shellcheck=true
            run_typescript=true
            java_changed_files+=("${file}")
        fi
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
    #
    # A brand-new file is UNTRACKED, so `git diff` reports NOTHING for it - the filter would then see an
    # empty diff and conclude "comments only", skipping the whole JVM gate for a newly added class or
    # spec. That is the same trap the linters' `git ls-files --others` flag exists to avoid, and it is
    # worst exactly when the gate matters most. So check first whether any java-triggering file is
    # untracked, and if so keep the step without consulting the diff at all.
    if [[ "${run_java}" == true ]] && ! any_untracked "${java_changed_files[@]}"; then
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
        # Each Docker image pin re-triggers the TIER that pulls it, not the whole step: the two are
        # separately selectable, so a hadolint bump has no reason to re-scan the runtime image.
        grep -qE '^[+-][[:space:]]*HADOLINT_DOCKER_IMAGE='                        <<<"${script_diff}" && run_hadolint=true
        grep -qE '^[+-][[:space:]]*(GRYPE_DOCKER_IMAGE|DIURNAL_RUNTIME_IMAGE)='   <<<"${script_diff}" && run_grype=true
        grep -qE '^[+-][[:space:]]*(ESLINT_BUILD_IMAGE|ESLINT_NODE_IMAGE)='       <<<"${script_diff}" && run_javascript=true
        grep -qE '^[+-][[:space:]]*(ESLINT_BUILD_IMAGE|ESLINT_NODE_IMAGE)='       <<<"${script_diff}" && run_typescript=true
        grep -qE '^[+-][[:space:]]*MARKDOWNLINT_DOCKER_IMAGE='                    <<<"${script_diff}" && run_markdown=true
        grep -qE '^[+-][[:space:]]*SHELLCHECK_DOCKER_IMAGE='                      <<<"${script_diff}" && run_shellcheck=true
        # The scan is a tier of `java`, so its image pin re-triggers that step rather than one of its own.
        grep -qE '^[+-][[:space:]]*QODANA_DOCKER_IMAGE='                          <<<"${script_diff}" && run_java=true
    fi

    # `docker` is emitted SCOPED unless both its tiers were triggered, so a change that touched only one
    # of them runs only that one - the same string a caller would type, and parsed by the same code (see
    # the auto-detection branch below, which feeds this list straight back through parse_step_selection).
    if [[ "${run_hadolint}" == true && "${run_grype}" == true ]]; then
        echo "docker"
    elif [[ "${run_hadolint}" == true ]]; then
        echo "docker:hadolint"
    elif [[ "${run_grype}" == true ]]; then
        echo "docker:grype"
    fi
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
    -e | --exit-on-failure) FAIL_FAST=true ;;
    --) shift; positional+=("$@"); break ;;
    -*)
        echo "❌ Unknown option: '${1}'. Supported: -v, --verbose, -f, --force, -e, --exit-on-failure"
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

# Parse the comma-separated [steps] argument into the global `steps` array, recording any `step:substep`
# narrowing in SELECTED_SUBSTEPS. Every rejection names what WOULD have been accepted, since the whole
# value of the syntax is that a typo is recoverable without opening this file.
#
# Entries ACCUMULATE rather than replace, so `java:mvn,java:e2e` selects both tiers - and a bare step name
# anywhere in the list widens it back to everything, because the wider selection is the safer one to
# resolve a contradictory list to (`java,java:qodana` is the whole gate). Step order follows first
# mention; each step's substeps are normalised into STEP_SUBSTEPS order, so the re-run hints read the same
# however the list was typed.
parse_step_selection() {
    local -a tokens=()
    IFS=',' read -ra tokens <<<"${1}"

    local -A whole=() scoped=()
    steps=()

    local token step substep scoping pattern valid_substeps
    for token in "${tokens[@]}"; do
        step="${token%%:*}"
        substep=""
        scoping=false
        # Tracked separately from a non-empty substep so a bare trailing colon can be rejected below as the
        # half-typed narrowing it is, rather than silently running the whole multi-minute step.
        [[ "${token}" == *:* ]] && scoping=true && substep="${token#*:}"

        pattern=" ${step} "
        if [[ ! " ${VALID_STEPS[*]} " =~ ${pattern} ]]; then
            echo "❌ Unknown step: '${step}'. Valid steps: $(
                IFS=', '
                echo "${VALID_STEPS[*]}"
            )"
            return 1
        fi
        if [[ -z "${whole[${step}]:-}" && -z "${scoped[${step}]:-}" ]]; then
            steps+=("${step}")
        fi

        if [[ "${scoping}" == false ]]; then
            whole["${step}"]=1
            continue
        fi

        valid_substeps="${STEP_SUBSTEPS[${step}]:-}"
        if [[ -z "${valid_substeps}" ]]; then
            echo "❌ Step '${step}' has no substeps, so '${token}' cannot be run. Run it as '${step}'."
            return 1
        fi
        if [[ -z "${substep}" ]]; then
            echo "❌ Missing substep after the ':' in '${token}'. Valid substeps of '${step}': ${valid_substeps// /, }"
            return 1
        fi
        if [[ " ${valid_substeps} " != *" ${substep} "* ]]; then
            echo "❌ Unknown substep: '${token}'. Valid substeps of '${step}': ${valid_substeps// /, }"
            return 1
        fi
        scoped["${step}"]+=" ${substep}"
    done

    local -a ordered=() all=()
    for step in "${steps[@]}"; do
        [[ -n "${whole[${step}]:-}" ]] && continue
        ordered=()
        read -ra all <<<"${STEP_SUBSTEPS[${step}]}"
        for substep in "${all[@]}"; do
            [[ " ${scoped[${step}]} " == *" ${substep} "* ]] && ordered+=("${substep}")
        done
        SELECTED_SUBSTEPS["${step}"]="${ordered[*]}"
    done
}

# The whole selection written as it would be typed back in ("java:qodana,markdown"), for the line that
# announces the run. A step selected whole is its bare name; see step_invocation.
selection_invocation() {
    local -a invocations=()
    local step invocation
    for step in "${steps[@]}"; do
        invocation="$(step_invocation "${step}")"
        invocations+=("${invocation}")
    done
    (IFS=', '; printf '%s' "${invocations[*]}")
}

# Parse and validate steps
if [[ "${FORCE}" == true ]]; then
    steps=("${VALID_STEPS[@]}")
    echo "Running ALL steps (forced): $(IFS=', '; echo "${steps[*]}")"
elif [[ $# -eq 0 ]]; then
    mapfile -t detected_steps < <(detect_changed_steps || true)
    if [[ ${#detected_steps[@]} -eq 0 ]]; then
        echo "No relevant changes detected since last tag; nothing to run"
        exit 0
    fi
    # Fed back through the same parser the [steps] argument uses, rather than assigned to `steps` directly:
    # auto-detection can now emit a SCOPED entry (`docker:hadolint`), and it must narrow the step exactly as
    # typing it would - which means SELECTED_SUBSTEPS, and so the one function that writes it.
    detected_list="$(IFS=','; echo "${detected_steps[*]}")"
    parse_step_selection "${detected_list}" || exit 1
    detected_invocation=""
    detected_invocation="$(selection_invocation)"
    echo "Running steps: ${detected_invocation}"
else
    parse_step_selection "${1}" || exit 1
    # Assigned on its own line so the command substitution's exit status isn't masked (SC2312).
    selected_list=""
    selected_list="$(selection_invocation)"
    echo "Running steps: ${selected_list}"
fi

# Execute steps, timing each one. `date +%s%N` (nanoseconds since epoch) fits a 64-bit shell integer,
# so the differences feed straight into to_natural_time. Each run_* function reads STEP_START_NS via
# step_time to fold its own elapsed time into its pass/fail summary line. Assigned on their own lines
# so the command substitution's exit status isn't masked (SC2312).
overall_start=""
overall_start="$(date +%s%N)"

# Split the selected steps into the parallel lane set and the serial tail, keeping the order they were
# selected in. See LANE_STEPS and lane_eligible for why perf, and a `docker` step including its scan, are
# excluded from the lanes; running them after the parallel phase is also what makes the scan's image build
# a cache hit off the java step's smoke tier.
parallel_selected=()
serial_selected=()
for step in "${steps[@]}"; do
    if lane_eligible "${step}"; then
        parallel_selected+=("${step}")
    else
        serial_selected+=("${step}")
    fi
done

# A lone lane-eligible step has nothing to run alongside, so run it inline instead: no supervision
# machinery, no captured-and-replayed output, and the everyday `lint_and_tests.sh java` (or `… shellcheck`)
# behaves exactly as it always has. It leads the serial tail so a scan-carrying `docker` step (and `perf`),
# if also selected, still follows it - which is what keeps that scan's image build a cache hit.
if [[ "${#parallel_selected[@]}" -eq 1 ]]; then
    serial_selected=("${parallel_selected[@]}" "${serial_selected[@]}")
    parallel_selected=()
fi

if [[ "${#parallel_selected[@]}" -gt 0 ]]; then
    run_lanes "${parallel_selected[@]}"
fi

# -e/--exit-on-failure: a failure in the parallel phase skips the serial tail entirely. Those steps never
# ran at all, so they are cancelled rather than failed.
if [[ "${FAIL_FAST}" == true && "${final_exit_code}" -ne 0 && "${#serial_selected[@]}" -gt 0 ]]; then
    echo
    echo "⏹  Stopping early after first failure (-e/--exit-on-failure)"
    cancelled_steps+=("${serial_selected[@]}")
    serial_selected=()
fi

for idx in "${!serial_selected[@]}"; do
    step="${serial_selected[idx]}"
    STEP_START_NS="$(date +%s%N)"
    # Reset the per-step signal so we can tell whether THIS step failed (each run_* sets it to 1 on
    # failure); the real cumulative result is kept in final_exit_code / failed_steps below.
    overall_exit_code=0
    case "${step}" in
    docker) run_docker ;;
    java) run_java ;;
    javascript) run_javascript ;;
    markdown) run_markdown ;;
    perf) run_perf ;;
    shellcheck) run_shellcheck ;;
    typescript) run_typescript ;;
    *) ;; # unreachable: steps are validated against VALID_STEPS above
    esac
    if [[ "${overall_exit_code}" -ne 0 ]]; then
        # Recorded as the INVOCATION, not the bare step name, so the re-run command at the end repeats the
        # scoping too - re-running the whole java gate to chase a `java:qodana` failure is minutes wasted.
        step_failure="$(step_invocation "${step}")"
        failed_steps+=("${step_failure}")
        final_exit_code=1
    fi
    # -e/--exit-on-failure: stop as soon as a step fails, skipping the remaining steps.
    if [[ "${FAIL_FAST}" == true && "${overall_exit_code}" -ne 0 ]]; then
        echo
        echo "⏹  Stopping early after first failure (-e/--exit-on-failure)"
        remaining_steps=("${serial_selected[@]:idx+1}")
        if [[ "${#remaining_steps[@]}" -gt 0 ]]; then
            cancelled_steps+=("${remaining_steps[@]}")
        fi
        break
    fi
done

overall_end=""
overall_end="$(date +%s%N)"
total_elapsed=""
total_elapsed="$(to_natural_time "$((overall_end - overall_start))")"
# Colour the total green when every step passed, red when any failed — matching the per-step timings.
if [[ "${final_exit_code}" -eq 0 ]]; then
    total_colour="${GREEN}"
else
    total_colour="${RED}"
fi
echo
echo "⏱  Total time: ${total_colour}${total_elapsed}${RESET}"

if [[ "${final_exit_code}" -ne 0 ]]; then
    # Name every failed step and fold them straight into the re-run command (comma-separated, the same
    # form the [steps] argument accepts), so the suggested command re-runs exactly what failed.
    failed_list="$(IFS=','; echo "${failed_steps[*]}")"
    echo
    echo "❌ Failed steps (${#failed_steps[@]}): ${RED}${failed_list}${RESET}"
    # Cancelled steps are listed separately and kept OUT of the re-run command: they did not fail, they were
    # stopped (or never started) by -e, so nothing about them is known and none of them is a thing to fix.
    if [[ "${#cancelled_steps[@]}" -gt 0 ]]; then
        cancelled_list="$(IFS=','; echo "${cancelled_steps[*]}")"
        echo "⏹  Cancelled steps (${#cancelled_steps[@]}): ${cancelled_list} - stopped by -e, result unknown"
    fi
    echo "   Re-run with: ${YELLOW}'${SCRIPT_PATH} -v ${failed_list}'${RESET} for the full output"
    exit 1
fi
