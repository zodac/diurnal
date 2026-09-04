#!/usr/bin/env bash
#
# PreToolUse guard for Bash commands that bypass a project command convention.
#
# Both rules below are stated as absolutes in CLAUDE.md and were previously enforced by nothing, so the only
# feedback was a confusing failure much later:
#
#   1. `mvn ... -Dall` run directly. The wrapper IS the gate: `-Dall` is only its first tier, so a direct run
#      silently skips the E2E, deployment-smoke and Qodana tiers and reports green on a third of the checks.
#      It also captures output and manages the test database's lifecycle. Note this hook sees only the model's
#      own commands, never the wrapper's own child processes, so the wrapper is free to run `-Dall` itself.
#   2. `docker-compose` (the v1 hyphenated binary) instead of the `docker compose` v2 plugin. Only the
#      FILENAMES keep the hyphen, which is exactly why this is easy to get wrong.
#
# Both tests require the command to be what a segment actually RUNS, not merely a string it mentions - writing
# documentation that quotes `mvn clean install -Dall`, or passing `-f docker-compose.dev.yml`, is not an
# attempt to run either and must not be blocked.
#
# Contract: reads the hook payload on stdin, exits 2 to block (stderr is fed back to the model), 0 to allow.

set -euo pipefail

readonly BLOCK=2

payload="$(cat)"
command_text="$(jq -r '.tool_input.command // ""' <<<"${payload}")"

if [[ -z "${command_text}" ]]; then
    exit 0
fi

# `-Dall` as a whole flag. `-Dlint`, `-Dtests` and every other scoped profile are left alone.
readonly ALL_PROFILE='(^|[[:space:]])-Dall([[:space:]]|=|$)'

# Leading noise between the start of a segment and the real command: environment assignments and the wrappers
# that commonly precede a long build.
readonly LEADING_NOISE='^[[:space:]]*(([A-Za-z_][A-Za-z0-9_]*=[^[:space:]]*|nohup|time|command|exec)[[:space:]]+)*'

# The first word a segment actually runs, or the empty string.
segment_command() {
    local segment="${1}"

    if [[ "${segment}" =~ ${LEADING_NOISE} ]]; then
        segment="${segment:${#BASH_REMATCH[0]}}"
    fi

    printf '%s' "${segment%%[[:space:]]*}"
}

while IFS= read -r segment; do
    [[ -z "${segment}" ]] && continue

    case "$(segment_command "${segment}")" in
        mvn)
            if [[ "${segment}" =~ ${ALL_PROFILE} ]]; then
                cat >&2 <<MSG
BLOCKED: run the full gate through the wrapper, not 'mvn -Dall' directly.

'mvn clean install -Dall' is only the FIRST tier of the java gate (unit + *IT + linters). Running it directly
skips the E2E, deployment-smoke and Qodana tiers entirely, so it reports green having run a fraction of the
checks CI runs - and it does not manage the test database's lifecycle.

Use instead:
  .github/scripts/lint_and_tests.sh java          # the whole JVM gate
  .github/scripts/lint_and_tests.sh java:mvn      # just this tier, if that is genuinely what you want

Scoped Maven runs are unaffected: 'mvn test -Dtests', 'mvn -o clean compile -Dlint' and 'mvn package' are all
fine. See the 'gate' skill.

Blocked command: ${segment}
MSG
                exit "${BLOCK}"
            fi
            ;;
        docker-compose)
            cat >&2 <<MSG
BLOCKED: use 'docker compose' (the v2 plugin), never 'docker-compose' (hyphenated).

Only the FILENAMES keep the hyphen - 'docker compose -f docker-compose.dev.yml up -d' is the correct shape.

Blocked command: ${segment}
MSG
            exit "${BLOCK}"
            ;;
        *)
            ;;
    esac
done < <(sed -E 's/(;|&&|\|\||\|)/\n/g' <<<"${command_text}" || true)

exit 0
