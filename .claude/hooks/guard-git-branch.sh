#!/usr/bin/env bash
#
# PreToolUse guard for Bash commands that would create a git branch.
#
# CLAUDE.md: all work happens directly on master, and a branch is created only with explicit permission. The
# prohibition is worth a hook rather than prose because the cost is asymmetric - work committed onto an
# unexpected branch is invisible on master and easy to lose track of, while the guard costs nothing when the
# user has genuinely approved a branch (they can create it themselves, or approve this call).
#
# Deliberately narrow: it matches only the branch-CREATING forms. Switching to an existing branch, listing
# branches, and deleting one are all left alone.
#
# Contract: reads the hook payload on stdin, exits 2 to block (stderr is fed back to the model), 0 to allow.

set -euo pipefail

readonly BLOCK=2

payload="$(cat)"
command_text="$(jq -r '.tool_input.command // ""' <<<"${payload}")"

if [[ -z "${command_text}" ]]; then
    exit 0
fi

# `git checkout -b`/`-B`, `git switch -c`/`-C`, and `git branch <name>` (creation, not -d/-D/-l/-a/-r/--list).
readonly CREATE_PATTERN='(^|[;&|]|&&|\|\|)[[:space:]]*git[[:space:]]+(checkout[[:space:]]+(-[[:alnum:]]*[bB])|switch[[:space:]]+(-[[:alnum:]]*[cC])|branch[[:space:]]+(--track[[:space:]]+)?[^-[:space:]])'

if [[ "${command_text}" =~ ${CREATE_PATTERN} ]]; then
    cat >&2 <<MSG
BLOCKED: this command creates a git branch, which needs explicit permission in this project.

All work happens directly on master - no feature branches, no working branches, regardless of how large the
change is. If a branch genuinely seems necessary, ask the user first and proceed only on an explicit yes.

Blocked command: ${command_text}
MSG
    exit "${BLOCK}"
fi

exit 0
