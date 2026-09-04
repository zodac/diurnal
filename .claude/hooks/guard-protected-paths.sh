#!/usr/bin/env bash
#
# PreToolUse guard for edits against files this project treats as immutable or hand-authored.
#
# Blocks two classes of edit that CLAUDE.md states as absolute prohibitions, both of which fail late and
# expensively when they slip through:
#
#   1. An EXISTING Flyway migration. Flyway checksums every applied script and revalidates at every startup,
#      so changing one byte breaks the boot of every database that already ran it - including the author's own
#      dev database, which is the copy that makes the mistake look like an unrelated failure hours later.
#      Creating a NEW V{n+1} file is allowed and is the sanctioned way to express any change, including a
#      reversion.
#   2. RELEASE_NOTES.md / VERSION. Hand-authored release artefacts owned by the maintainer; the pom's version
#      is CI-owned separately.
#
# It guards BOTH ways a file gets written: the Edit/Write/NotebookEdit tools, and a shell command. The shell
# path is not belt-and-braces - `sed -i`/`cat >` is the first-choice editing route in some permission modes,
# and `Bash(sed *)` is on the allowlist, so a file-path-only guard leaves the sanctioned route unguarded.
#
# Contract: reads the hook payload on stdin, exits 2 to block (stderr is fed back to the model), 0 to allow.

set -euo pipefail

readonly BLOCK=2

payload="$(cat)"

tool_name="$(jq -r '.tool_name // ""' <<<"${payload}")"
file_path="$(jq -r '.tool_input.file_path // ""' <<<"${payload}")"
command_text="$(jq -r '.tool_input.command // ""' <<<"${payload}")"

# Resolve a relative path in the payload against the project root so it compares like an absolute one.
project_dir="${CLAUDE_PROJECT_DIR:-$(pwd)}"

readonly MIGRATION_DIR='src/main/resources/db/migration'

migration_block_message() {
    cat >&2 <<MSG
BLOCKED: ${1} is an existing Flyway migration, and migrations are immutable.

Flyway stores a checksum of every applied script and revalidates it at every startup, so changing any byte -
SQL, a comment, or whitespace - makes every database that already ran it fail to boot with "Migration checksum
mismatch", recoverable only by a manual 'flyway repair' or by hand-editing flyway_schema_history. This applies
to uncommitted migrations too: a local dev database has already run them.

Express the change as a NEW migration instead. To alter something V{n} shipped, add V{n+1} with the ALTER; to
undo V{n}, add V{n+1} that reverses it. See .claude/DATABASE.md.
MSG
}

release_artefact_block_message() {
    cat >&2 <<MSG
BLOCKED: ${1} is a hand-authored release artefact owned by the maintainer.

Leave it untouched - including when it already looks modified in the working tree - unless the user's request
explicitly says to update this file. If the user did ask for it, they can confirm and re-run, or edit it
themselves. Note the pom's <version> is CI-owned and is bumped separately by .github/scripts/bump_version.sh.
MSG
}

# ---------------------------------------------------------------------------------------------------------
# Edit / Write / NotebookEdit: the target is a single explicit path.
# ---------------------------------------------------------------------------------------------------------

if [[ -n "${file_path}" ]]; then
    if [[ "${file_path}" != /* ]]; then
        file_path="${project_dir}/${file_path}"
    fi
    relative_path="${file_path#"${project_dir}"/}"

    case "${relative_path}" in
        "${MIGRATION_DIR}"/*.sql)
            # Only an EXISTING script is protected - a new V{n+1} file is exactly what the rule asks for, and
            # only `.sql` is Flyway's business, so a README or a CLAUDE.md sharing the directory is not caught.
            if [[ -f "${file_path}" ]]; then
                migration_block_message "${relative_path}"
                exit "${BLOCK}"
            fi
            ;;
        RELEASE_NOTES.md | VERSION)
            release_artefact_block_message "${relative_path}"
            exit "${BLOCK}"
            ;;
        *)
            ;;
    esac

    exit 0
fi

# ---------------------------------------------------------------------------------------------------------
# Bash: the target is buried in a command line, so look for a protected path in a WRITING position.
#
# Reading a migration is routine (`cat`, `grep`, `diff`), and creating the next one with a heredoc is the
# sanctioned way to change the schema - so a bare mention is not enough to block on. The test is a protected
# path appearing in the same command segment as something that writes.
# ---------------------------------------------------------------------------------------------------------

if [[ -z "${command_text}" ]]; then
    exit 0
fi

# A mutating command. `sed -i` covers `-i`, `-i.bak` and `--in-place`; the rest are matched as whole words so
# a path containing "install" or a flag like `--remove` does not trip them.
readonly WRITE_VERB='(^|[;&|[:space:]])(sed[[:space:]]+(-[^[:space:]]*i|--in-place)|(tee|cp|mv|rm|truncate|dd|patch|shred|sponge|install|ln|chmod|touch)([[:space:]]|$))'

# `RELEASE_NOTES.md`/`VERSION` as a standalone shell word, optionally with a directory prefix, so `./VERSION`
# and "${CLAUDE_PROJECT_DIR}/VERSION" match while `s/OLD_VERSION/new/` and `sed 's|VERSION|x|'` do not (in
# both of those the token is followed by the substitution delimiter rather than by whitespace or end).
readonly RELEASE_ARTEFACT=$'(^|[[:space:]"\'=])([.]{0,2}/|[^[:space:]"\';|&<>]*/)?(RELEASE_NOTES\\.md|VERSION)([[:space:]"\';)]|$)'

# Splitting on `;`, `&&`, `||` and newlines keeps a verb from matching a path in an unrelated segment of a
# compound command (`rm -rf target && cat VERSION` is a read). A bare `|` is deliberately NOT a separator: it
# is far more often a `sed` delimiter than a pipe in the commands this needs to catch.
readonly SEGMENT_SEPARATORS='(;|&&|\|\|)'

# Prints the first EXISTING migration file named by this command segment, or nothing. A glob is expanded, so
# `V39__*.sql` is caught and a not-yet-created `V44__foo.sql` is not. It always succeeds - "no match" is the
# empty string rather than a non-zero exit, so no caller has to disable `set -e` to ask the question.
segment_touches_existing_migration() {
    local segment="${1}"
    local -a candidates=()
    local candidate resolved expanded

    mapfile -t candidates < <(grep -oE "[^[:space:]\"';|&<>]*${MIGRATION_DIR}/[^[:space:]\"';|&<>]*\.sql" <<<"${segment}" || true)

    for candidate in "${candidates[@]}"; do
        [[ -z "${candidate}" ]] && continue
        resolved="${candidate}"
        if [[ "${resolved}" != /* ]]; then
            resolved="${project_dir}/${resolved}"
        fi

        # Unquoted for pathname expansion; an unmatched glob stays literal and fails the -f test.
        for expanded in ${resolved}; do
            if [[ -f "${expanded}" ]]; then
                printf '%s' "${expanded#"${project_dir}"/}"
                return 0
            fi
        done
    done

    return 0
}

# A redirection names its target directly, so it is a write on its own without any verb.
readonly REDIRECT_TO_MIGRATION=">>?[[:space:]]*[^[:space:]]*${MIGRATION_DIR}/[^[:space:]]*\.sql"
readonly REDIRECT_TO_RELEASE_ARTEFACT='>>?[[:space:]]*[^[:space:]]*(RELEASE_NOTES\.md|VERSION)'

while IFS= read -r segment; do
    [[ -z "${segment}" ]] && continue

    if [[ "${segment}" =~ ${WRITE_VERB} ]]; then
        writes=true
    else
        writes=false
    fi

    migration=""
    if [[ "${writes}" == true || "${segment}" =~ ${REDIRECT_TO_MIGRATION} ]]; then
        migration="$(segment_touches_existing_migration "${segment}")"
    fi

    if [[ -n "${migration}" ]]; then
        migration_block_message "${migration}"
        exit "${BLOCK}"
    fi

    if [[ "${segment}" =~ ${RELEASE_ARTEFACT} ]]; then
        artefact="${BASH_REMATCH[3]}"
        if [[ "${writes}" == true || "${segment}" =~ ${REDIRECT_TO_RELEASE_ARTEFACT} ]]; then
            release_artefact_block_message "${artefact}"
            exit "${BLOCK}"
        fi
    fi
done < <(sed -E "s/${SEGMENT_SEPARATORS}/\n/g" <<<"${command_text}" || true)

# tool_name is read for completeness; every tool this hook matches carries one of the two inputs above.
: "${tool_name}"

exit 0
