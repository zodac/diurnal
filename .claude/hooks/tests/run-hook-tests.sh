#!/usr/bin/env bash
#
# Behaviour tests for the PreToolUse guards in .claude/hooks/.
#
# The guards are the only enforcement behind several rules CLAUDE.md states as absolute, and each of them has to
# draw a line that is genuinely narrow: block an edit to an EXISTING migration but not the creation of the next
# one; block `docker-compose up` but not `-f docker-compose.dev.yml`; block a command that RUNS `mvn -Dall` but
# not documentation that quotes it. Every one of those distinctions is a regression waiting to happen, in a place
# where a false negative is silent and a false positive blocks ordinary work - so they are pinned here.
#
# Run directly, or through the gate: .github/scripts/lint_and_tests.sh shellcheck:hooks
#
# Also run at session start by sandbox/setup.sh, which covers the half this cannot: whether the guards are
# actually REGISTERED and runnable in the environment, rather than whether their logic is right.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
HOOKS_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
readonly HOOKS_DIR
PROJECT_DIR="$(cd -- "${HOOKS_DIR}/../.." && pwd)"
readonly PROJECT_DIR

readonly BLOCK=2

passed=0
failed=0
current_hook=""

# Feeds one synthetic PreToolUse payload to the guard under test and compares the verdict. Failures are
# accumulated rather than returned, so no caller has to disable `set -e` to ask for a result.
check() {
    local expect="${1}" payload="${2}" label="${3}"
    local status=0 output="" verdict

    output="$(CLAUDE_PROJECT_DIR="${PROJECT_DIR}" bash "${HOOKS_DIR}/${current_hook}" <<<"${payload}" 2>&1)" || status=$?

    case "${status}" in
        0) verdict="ALLOW" ;;
        "${BLOCK}") verdict="BLOCK" ;;
        *) verdict="ERROR(${status})" ;;
    esac

    if [[ "${verdict}" == "${expect}" ]]; then
        passed=$((passed + 1))
    else
        failed=$((failed + 1))
        printf 'FAIL [%s] expected %s, got %s: %s\n' "${current_hook}" "${expect}" "${verdict}" "${label}"
        if [[ -n "${output}" ]]; then
            printf '     %s\n' "${output%%$'\n'*}"
        fi
    fi
}

bash_payload() {
    jq -nc --arg command "${1}" '{tool_name: "Bash", tool_input: {command: $command}}'
}

edit_payload() {
    jq -nc --arg path "${1}" '{tool_name: "Edit", tool_input: {file_path: $path}}'
}

blocks_command() {
    local payload
    payload="$(bash_payload "${1}")"
    check BLOCK "${payload}" "${1}"
}

allows_command() {
    local payload
    payload="$(bash_payload "${1}")"
    check ALLOW "${payload}" "${1}"
}

blocks_edit() {
    local payload
    payload="$(edit_payload "${1}")"
    check BLOCK "${payload}" "${1}"
}

allows_edit() {
    local payload
    payload="$(edit_payload "${1}")"
    check ALLOW "${payload}" "${1}"
}

# An existing migration and a name no migration uses, both resolved from the real tree so the tests keep
# testing something after the next migration lands.
MIGRATION_DIR="src/main/resources/db/migration/postgresql"
readonly MIGRATION_DIR
EXISTING_MIGRATION="$(cd -- "${PROJECT_DIR}" && git ls-files "${MIGRATION_DIR}/*.sql" | sort -V | tail -1)"
readonly EXISTING_MIGRATION
readonly NEW_MIGRATION="${MIGRATION_DIR}/V9999__not_yet_written.sql"

if [[ -z "${EXISTING_MIGRATION}" ]]; then
    echo "Cannot run: no migrations found under ${MIGRATION_DIR}" >&2
    exit 1
fi

echo "Testing the PreToolUse guards (existing migration: $(basename "${EXISTING_MIGRATION}"))"

# ---------------------------------------------------------------------------------------------------------
current_hook="guard-protected-paths.sh"
# ---------------------------------------------------------------------------------------------------------

blocks_edit "${EXISTING_MIGRATION}"
blocks_edit "${PROJECT_DIR}/${EXISTING_MIGRATION}"
blocks_edit "VERSION"
blocks_edit "RELEASE_NOTES.md"
allows_edit "${NEW_MIGRATION}"
allows_edit "README.md"
allows_edit "src/main/java/net/zodac/diurnal/action/Action.java"

blocks_command "sed -i 's/foo/bar/' ${EXISTING_MIGRATION}"
blocks_command "sed -i.bak 's/foo/bar/' ${EXISTING_MIGRATION}"
blocks_command "sed -i 's/foo/bar/' ${MIGRATION_DIR}/V*.sql"
blocks_command "cat > ${EXISTING_MIGRATION} <<'EOF'"
blocks_command "echo x >> ${PROJECT_DIR}/${EXISTING_MIGRATION}"
blocks_command "rm ${EXISTING_MIGRATION}"
blocks_command "tee ${EXISTING_MIGRATION} < /tmp/x"
blocks_command "mvn -q clean; sed -i 's/a/b/' ${EXISTING_MIGRATION}"

# Only `.sql` is Flyway's business; a doc sharing the directory is an ordinary file.
allows_edit "${MIGRATION_DIR}/README.md"
allows_command "rm ${MIGRATION_DIR}/README.md"

allows_command "cat ${EXISTING_MIGRATION}"
allows_command "grep -n CREATE ${MIGRATION_DIR}/*.sql"
allows_command "cat > ${NEW_MIGRATION} <<'EOF'"
allows_command "ls ${MIGRATION_DIR}/ | sort -V | tail -3"
allows_command "grep -c INDEX ${EXISTING_MIGRATION} > /tmp/out"
allows_command "rm -rf target && cat ${EXISTING_MIGRATION}"

blocks_command "echo '2.0.0' > VERSION"
blocks_command "echo '2.0.0' > ./VERSION"
# shellcheck disable=SC2016  # the payload is a literal command STRING for the guard to judge, not one to run
blocks_command 'echo x > "${CLAUDE_PROJECT_DIR}/VERSION"'
blocks_command "sed -i 's/1.0/2.0/' VERSION"
blocks_command "cat >> RELEASE_NOTES.md <<'EOF'"
blocks_command "rm VERSION"
blocks_command "cp /tmp/notes RELEASE_NOTES.md"

allows_command "cat VERSION"
allows_command "grep -n '2.0' RELEASE_NOTES.md"
allows_command "sed -i 's/OLD_VERSION/NEW/' README.md"
allows_command "sed -i 's|VERSION|x|' src/main/java/Foo.java"
allows_command 'sed -i "s/VERSION/x/" pom.xml'
allows_command "rm -rf target && cat VERSION"
allows_command "docker build --build-arg VERSION=1.2.3 ."
allows_command "grep -c VERSION README.md > /tmp/x"
# shellcheck disable=SC2016  # ditto: the guard must see the bare dollar-VERSION an unexpanded command carries
allows_command 'echo $VERSION'
allows_command "git commit -m 'Bump the VERSION'"
allows_command "mvn package -DskipTests"
allows_command "git status --short"

# ---------------------------------------------------------------------------------------------------------
current_hook="guard-command-conventions.sh"
# ---------------------------------------------------------------------------------------------------------

blocks_command "mvn clean install -Dall"
blocks_command "mvn -o clean install -Dall"
blocks_command "mvn clean install -Dall -Dverbose=true"
blocks_command "cd /work && mvn clean install -Dall"
blocks_command "mvn -o clean test-compile -Dall"
blocks_command "nohup mvn clean install -Dall"
blocks_command "MAVEN_OPTS=-Xmx2g mvn clean install -Dall"
blocks_command "time mvn clean install -Dall"

allows_command "mvn test -Dtests"
allows_command "mvn test -Dtests -Dtest=MyTestClass"
allows_command "mvn -o clean compile -Dlint"
allows_command "mvn package"
allows_command "mvn quarkus:dev"
allows_command ".github/scripts/lint_and_tests.sh java"
allows_command "echo 'the wrapper runs mvn clean install -Dall internally'"
allows_command "grep -rn 'mvn clean install -Dall' .claude/"

blocks_command "docker-compose up -d"
blocks_command "docker-compose -f docker-compose.dev.yml up -d"
blocks_command "cd /work && docker-compose down -v"

allows_command "docker compose up -d --build"
allows_command "docker compose -p diurnal-dev -f docker-compose.dev.yml up -d diurnal-db-dev"
allows_command "cat docker-compose.dev.yml"
allows_command "grep -n image docker-compose.yml"
allows_command "echo 'do not use docker-compose up'"

# ---------------------------------------------------------------------------------------------------------
current_hook="guard-git-branch.sh"
# ---------------------------------------------------------------------------------------------------------

blocks_command "git checkout -b feature/x"
blocks_command "git checkout -B feature/x"
blocks_command "git switch -c feature/x"
blocks_command "git switch -C feature/x"
blocks_command "git branch new-branch"
blocks_command "git branch --track other origin/other"
blocks_command "cd /work && git checkout -b tmp"

allows_command "git checkout master"
allows_command "git switch master"
allows_command "git checkout -- VERSION"
allows_command "git branch"
allows_command "git branch -a"
allows_command "git branch --list"
allows_command "git branch -d old-branch"
allows_command "git branch -D old-branch"
allows_command "git status --short"
allows_command "git log --oneline -5"

# ---------------------------------------------------------------------------------------------------------

echo
if [[ "${failed}" -eq 0 ]]; then
    echo "All ${passed} hook guard cases passed"
    exit 0
fi

echo "${failed} of $((passed + failed)) hook guard cases FAILED"
exit 1
