#!/bin/bash
# ------------------------------------------------------------------------------
# Runs the full lint/test gate before a commit is created.
#
# Deliberately a PRE-COMMIT (not pre-push) hook: git opens the SSH connection to
# the remote BEFORE running a pre-push hook and holds it open for the hook's
# duration, so a ~10-minute gate outlives GitHub's idle-SSH timeout — the server
# drops the connection and `git push` then dies with SIGPIPE (141) even though
# the hook passed. A pre-commit hook holds no network connection, so the gate can
# take as long as it needs. Do NOT move this back to pre-push.
# ------------------------------------------------------------------------------

trap 'echo; exit 130' INT

current_branch=$(git rev-parse --abbrev-ref HEAD)
if [[ "${current_branch}" != "master" ]]; then
  echo "Skipping pre-commit checks (branch: ${current_branch})"
  exit 0
fi

echo "Running full build with tests and lints"
if ! .github/scripts/lint_and_tests.sh; then
  exit 1
fi

exit 0
