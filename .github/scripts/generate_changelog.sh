#!/bin/sh
set -eu

# Repository base URL for commit links. Provided by GitHub Actions; empty when run locally (the links
# are then just relative-looking, but the script still runs so it can be tested by hand).
GIT_REPO_URL="${GITHUB_SERVER_URL:-}/${GITHUB_REPOSITORY:-}"
previous_git_tag="${1:-}"

# Field separator for the intermediate category file: an ASCII unit separator (0x1f). It cannot occur
# in a commit subject, so it never collides with the commit text (unlike a literal '|').
US=$(printf '\037')
UNCATEGORISED="Uncategorised"

# Commit range: everything since the previous tag, or the full history on the very first release.
if [ -z "${previous_git_tag}" ]; then
    log_range="HEAD"
else
    log_range="${previous_git_tag}..HEAD"
fi

categories_tmp=$(mktemp)

# One line per commit: "<short-hash> <subject>". Using %s (the subject) rather than %B means a
# multi-line commit body can never bleed into the changelog entry. `read` peels off the short hash
# (the first whitespace-delimited field) and keeps the rest as the subject. The `|| [ -n ... ]` guard
# processes the final line too: `git log --pretty=format:` writes no trailing newline, so `read`
# returns non-zero on the last (oldest) commit even though it populated the variables.
git log "${log_range}" --pretty=format:"%h %s" | while read -r commit_hash subject || [ -n "${commit_hash}" ]; do
    [ -z "${commit_hash}" ] && continue

    case "${subject}" in
        \[*\]\ *)
            # "[Category] message" — extract both halves.
            category=$(printf '%s' "${subject}" | sed -n 's/^\[\([A-Za-z0-9_.-]*\)\] .*/\1/p')
            message=$(printf '%s' "${subject}" | sed -n 's/^\[[^]]*\] \(.*\)/\1/p')
            ;;
        *)
            category=""
            message="${subject}"
            ;;
    esac

    # Commits with no (or an unparseable) "[Category]" prefix are grouped rather than silently dropped.
    [ -z "${category}" ] && category="${UNCATEGORISED}"

    printf '%s%s%s%s%s\n' "${category}" "${US}" "${commit_hash}" "${US}" "${message}" >>"${categories_tmp}"
done

# Build the changelog: one section per category, categories in alphabetical order.
tmp_output=$(mktemp)
categories_sorted=$(awk -F"${US}" '{ print $1 }' "${categories_tmp}" | sort -u)

if [ -z "${categories_sorted}" ]; then
    printf -- '- No notable changes\n' >"${tmp_output}"
else
    {
        for category in ${categories_sorted}; do
            printf '**[%s]**\n' "${category}"
            awk -F"${US}" -v cat="${category}" -v repo="${GIT_REPO_URL}" '
                $1 == cat {
                    printf "- [[%s](%s/commit/%s)] %s\n", $2, repo, $2, $3
                }
            ' "${categories_tmp}"
            printf '\n'
        done
    } >"${tmp_output}"
fi

# Expose the changelog to later workflow steps (multi-line value via the heredoc form). Falls back to
# /dev/null outside Actions so a local run doesn't fail on the unset variable.
{
    echo "changelog_content<<CHANGELOG_EOF"
    cat "${tmp_output}"
    echo "CHANGELOG_EOF"
} >>"${GITHUB_ENV:-/dev/null}"

# Echo to stdout too, so a manual/local run shows the generated changelog.
cat "${tmp_output}"

# Cleanup
rm -f "${categories_tmp}" "${tmp_output}"
