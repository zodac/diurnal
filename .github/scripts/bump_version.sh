#!/bin/bash
# ------------------------------------------------------------------------------
# Script Name:     bump_version.sh
#
# Description:     Increments the patch version from a given semantic version,
#                  updates the `VERSION` file, and sets the new Maven project
#                  version to a `-SNAPSHOT`. Stages and optionally commits changes.
#
# Usage:           ./bump_version.sh <current_version>
#
# Requirements:
#   - Bash 4+ with support for string manipulation and arrays
#   - `mvn` (Maven) must be installed and accessible in the system PATH
#   - Git repository initialised and containing a `VERSION` file and pom.xml files
#   - GitHub Actions environment variable `GITHUB_ENV` must be defined for CI integration
#
# Behavior:
#   - Parses the version string (e.g., 1.2.3), increments the patch number
#   - Writes the new version to the `VERSION` file
#   - Updates all Maven modules to the new `-SNAPSHOT` version
#   - Stages the updated files (`VERSION`, all `pom.xml` files)
#   - Clears the value of RELEASE_NOTES.md, since the Github release has been generated
#   - Commits the changes if any were made, along with any updates to README.md and RELEASE_NOTES.md
#   - Outputs a GitHub Actions environment variable (`has_changes=true`) if changes were committed
#
# Exit Codes:
#   - 0: Success
#   - Non-zero: Failure due to invalid arguments or command errors
# ------------------------------------------------------------------------------

set -euo pipefail

current_version="${1}"

IFS='.' read -r major minor patch <<<"${current_version}"
new_patch=$((patch + 1))
next_version="${major}.${minor}.${new_patch}"

echo "Bumping version to ${next_version}"
echo "${next_version}" >VERSION

# Set Maven project version to next development SNAPSHOT version
mvn versions:set -DnewVersion="${next_version}-SNAPSHOT" -DgenerateBackupPoms=false -DprocessAllModules

# Clear RELEASE_NOTES.md for next version
: >RELEASE_NOTES.md

# Push changes
git add -- RELEASE_NOTES.md VERSION pom.xml

if git diff --cached --quiet; then
    echo "No changes to commit"
else
    git commit -m "[CI] Prepare next version: ${next_version}"
    echo "has_changes=true" >>"${GITHUB_ENV}"
fi
