#!/usr/bin/env bash
#
# Intersect a REQUESTED gate selection with the steps ONE job owns, and print what that job should run.
#
# Usage:           ./gate_job_steps.sh <requested> <job-steps>
#
#                  Both arguments are comma-separated `step[:substep]` selections in exactly the form
#                  lint_and_tests.sh accepts. The result is printed on stdout in that same form, ready to
#                  pass straight back to it, and is EMPTY when the job owns nothing the request asked for.
#
# Why it exists:   publish.yml used to run the whole gate as a single job, so its `gate-steps` dispatch
#                  input could be handed to lint_and_tests.sh verbatim. The gate is now split across
#                  several jobs (one per tier group) so they run on separate runners in parallel, and each
#                  of them has to work out which PART of the request is its own. Doing that inline in YAML
#                  would be one copy per job, free to drift; doing it here keeps one answer.
#
#                  The substep table is READ OUT OF lint_and_tests.sh rather than repeated here. That
#                  table is the single place a step's tiers are declared (see STEP_SUBSTEPS), and a second
#                  copy would silently stop matching the moment a tier is added - the job owning it would
#                  quietly skip it, which is the failure a release gate can least afford.
#
# Examples:        ./gate_job_steps.sh 'docker,java' 'java:mvn,java:e2e'   -> java:mvn,java:e2e
#                  ./gate_job_steps.sh 'java:qodana' 'java:mvn,java:e2e'   -> (empty)
#                  ./gate_job_steps.sh 'java' 'docker,java:smoke'          -> java:smoke
#                  ./gate_job_steps.sh 'docker,java' 'docker,java:smoke'   -> docker,java:smoke

set -euo pipefail

if [[ "$#" -ne 2 ]]; then
    echo "Usage: $(basename "${0}") <requested> <job-steps>" >&2
    exit 2
fi

REQUESTED="${1}"
JOB_STEPS="${2}"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
GATE_SCRIPT="${SCRIPT_DIR}/lint_and_tests.sh"

if [[ ! -f "${GATE_SCRIPT}" ]]; then
    echo "❌ Cannot read the substep table: ${GATE_SCRIPT} does not exist" >&2
    exit 1
fi

# The declared tiers of every step that has them, lifted from lint_and_tests.sh's STEP_SUBSTEPS block.
# A step absent from the table has no substeps and is matched whole.
declare -A SUBSTEPS=()
substep_table="$(sed -n '/^declare -A STEP_SUBSTEPS=(/,/^)/p' "${GATE_SCRIPT}")"
if [[ -z "${substep_table}" ]]; then
    echo "❌ Could not find the STEP_SUBSTEPS table in ${GATE_SCRIPT}" >&2
    exit 1
fi

# The pattern is held in a variable rather than written inline: bash applies quote removal to an
# unquoted =~ operand first, so the backslashes an inline regex needs are eaten before the regex engine
# ever sees them and every line silently fails to match - leaving an EMPTY table, which this script
# cannot distinguish from a step that genuinely has no tiers.
table_entry_re='^[[:space:]]*\[([a-z]+)\]="([^"]+)"'
while IFS= read -r line; do
    [[ "${line}" =~ ${table_entry_re} ]] || continue
    SUBSTEPS["${BASH_REMATCH[1]}"]="${BASH_REMATCH[2]}"
done <<< "${substep_table}"

if [[ "${#SUBSTEPS[@]}" -eq 0 ]]; then
    echo "❌ The STEP_SUBSTEPS table in ${GATE_SCRIPT} parsed as empty - refusing to guess" >&2
    exit 1
fi

# Expand a selection into one `step:substep` atom per tier, so two selections written at different
# granularities ("java" and "java:smoke") can be compared directly. A step with no tiers becomes the
# single atom `step:` - the trailing colon is what keeps it from colliding with a step of the same name
# that later GAINS tiers.
expand_selection() {
    local selection="${1}"
    local -a tokens=()
    IFS=',' read -ra tokens <<< "${selection}"

    local token step substep tiers
    for token in "${tokens[@]}"; do
        token="${token//[[:space:]]/}"
        [[ -z "${token}" ]] && continue
        step="${token%%:*}"
        tiers="${SUBSTEPS[${step}]:-}"

        if [[ "${token}" == *:* ]]; then
            substep="${token#*:}"
            [[ -n "${substep}" ]] && printf '%s:%s\n' "${step}" "${substep}"
            continue
        fi

        if [[ -z "${tiers}" ]]; then
            printf '%s:\n' "${step}"
            continue
        fi
        for substep in ${tiers}; do
            printf '%s:%s\n' "${step}" "${substep}"
        done
    done
}

requested_atoms="$(expand_selection "${REQUESTED}" | sort -u)"
job_atoms="$(expand_selection "${JOB_STEPS}" | sort -u)"
kept_atoms="$(comm -12 <(printf '%s\n' "${requested_atoms}") <(printf '%s\n' "${job_atoms}") || true)"

[[ -z "${kept_atoms}" ]] && exit 0

# Collapse back to the shortest form that means the same thing: a step whose every declared tier survived
# the intersection is printed bare, so the gate's own "re-run ..." hints read the way they always did.
declare -A kept_by_step=()
declare -a step_order=()
while IFS= read -r atom; do
    [[ -z "${atom}" ]] && continue
    step="${atom%%:*}"
    substep="${atom#*:}"
    if [[ -z "${kept_by_step[${step}]+set}" ]]; then
        step_order+=("${step}")
        kept_by_step["${step}"]=""
    fi
    [[ -n "${substep}" ]] && kept_by_step["${step}"]+=" ${substep}"
done <<< "${kept_atoms}"

declare -a output=()
for step in "${step_order[@]}"; do
    tiers="${SUBSTEPS[${step}]:-}"
    if [[ -z "${tiers}" ]]; then
        output+=("${step}")
        continue
    fi

    # Printed in the table's own tier order, not the order they happened to be selected in, so the result
    # is stable whichever way round the two selections were written.
    declare -a all_tiers=()
    read -ra all_tiers <<< "${tiers}"
    declare -a kept_ordered=()
    for substep in "${all_tiers[@]}"; do
        [[ " ${kept_by_step[${step}]} " == *" ${substep} "* ]] && kept_ordered+=("${step}:${substep}")
    done
    if [[ "${#kept_ordered[@]}" -eq "${#all_tiers[@]}" ]]; then
        output+=("${step}")
    else
        output+=("${kept_ordered[@]}")
    fi
done

(IFS=','; printf '%s\n' "${output[*]}")
