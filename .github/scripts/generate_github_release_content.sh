#!/usr/bin/env bash
# ------------------------------------------------------------------------------
# Script Name:     generate_github_release_content.sh
#
# Description:     Builds the body of the GitHub Release, written as a multi-line
#                  `body` value to ${GITHUB_OUTPUT}. Optionally prepends the
#                  contents of RELEASE_NOTES.md (when non-empty), then appends the
#                  Docker pull instructions and the categorised changelog.
#
# Usage:           ./generate_github_release_content.sh <version> <prev_tag>
#
# Requirements:
#   - GitHub Actions environment variables GITHUB_OUTPUT and CHANGELOG_CONTENT
#     must be defined (CHANGELOG_CONTENT is produced by generate_changelog.sh).
# ------------------------------------------------------------------------------

set -euo pipefail

VERSION="${1:?VERSION argument is required}"
PREV_TAG="${2:?PREV_TAG argument is required}"

: "${CHANGELOG_CONTENT:?CHANGELOG_CONTENT is required}"

{
    echo "body<<EOF"

    # Prepend RELEASE_NOTES.md if present and non-empty
    if [ -s RELEASE_NOTES.md ]; then
        echo "Including RELEASE_NOTES.md content" >&2
        cat RELEASE_NOTES.md
        echo
        echo "---"
        echo
    fi

    cat <<EOF2
Docker image pushed to Docker Hub:
[docker pull zodac/diurnal:${VERSION}](https://hub.docker.com/r/zodac/diurnal/tags)

## Changes since ${PREV_TAG}:

${CHANGELOG_CONTENT}
EOF2

    echo "EOF"
} >>"${GITHUB_OUTPUT}"
