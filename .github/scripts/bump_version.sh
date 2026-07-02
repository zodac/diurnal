#!/bin/sh
set -eu

current_version="${1}"

# Split version into major, minor, patch
OLD_IFS="${IFS}"
IFS=.
# shellcheck disable=SC2086
set -- ${current_version}
major="${1}"
minor="${2}"
patch="${3}"
IFS="${OLD_IFS}"

# Increment the patch component for the next development cycle.
new_patch=$((patch + 1))
next_version="${major}.${minor}.${new_patch}"

echo "Bumping version to ${next_version}"
echo "${next_version}" > VERSION

# The patch always changes, so there is always exactly one thing to commit. Only VERSION is staged
# here, so an unrelated dirty working tree (e.g. the pom edited by `mvn versions:set`) stays out of
# this commit. The caller handles pushing.
git add VERSION
git commit -m "[CI] Prepare next version: ${next_version}"
