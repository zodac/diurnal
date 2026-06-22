#!/usr/bin/env bash
# Tiny wrapper run as the `dev` user by the entrypoint: run the per-open setup
# (only for interactive sessions, gated via RUN_SETUP), then exec the real command.
set -uo pipefail

if [ "${RUN_SETUP:-0}" = "1" ]; then
  /usr/local/bin/sandbox-setup.sh || true
fi

exec "$@"
