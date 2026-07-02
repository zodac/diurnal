#!/bin/bash

trap 'echo; exit 130' INT

current_branch=$(git rev-parse --abbrev-ref HEAD)
if [ "${current_branch}" != "master" ]; then
  echo "Skipping pre-commit checks (branch: ${current_branch})"
  exit 0
fi

echo "Running full build with tests and lints"
if ! .github/scripts/lint_and_tests.sh; then
  echo "Pre-commit build failed, commit aborted"
  exit 1
fi

exit 0
