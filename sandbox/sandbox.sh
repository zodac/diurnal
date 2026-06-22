#!/usr/bin/env bash
# Launch the isolated diurnal sandbox.
#
#   ./sandbox.sh build          # (re)build the image
#   ./sandbox.sh                # start an interactive Claude session in the sandbox
#   ./sandbox.sh shell          # drop into a bash shell instead of Claude
#   ./sandbox.sh run <cmd...>   # run an arbitrary command in the sandbox
#
# Only the project directory is mounted from the host. No $HOME, no SSH keys,
# no other projects, and NOT the host Docker socket. The sandbox runs its own
# nested Docker daemon, so everything the project spins up (dev DB, Testcontainers,
# Playwright) lives and dies inside this disposable container.
set -euo pipefail

IMAGE="diurnal-sandbox"
# This script lives in <project>/sandbox/, so the project root is its parent dir.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${PROJECT_DIR:-$(dirname "$HERE")}"

build() {
  docker build -t "$IMAGE" \
    --build-arg UID="$(id -u)" \
    --build-arg GID="$(id -g)" \
    "$HERE"
}

run() {
  if [ ! -d "$PROJECT_DIR" ]; then
    echo "Project directory not found: $PROJECT_DIR" >&2
    exit 1
  fi
  # Allocate a TTY only when attached to one (so scripted `run` invocations work too).
  local tty=()
  if [ -t 0 ] && [ -t 1 ]; then tty=(-it); else tty=(-i); fi
  docker run "${tty[@]}" --rm \
    --name diurnal-sandbox \
    --privileged \
    --hostname diurnal-sandbox \
    -v "$PROJECT_DIR":/work \
    -v diurnal-sandbox-docker:/var/lib/docker \
    -v diurnal-sandbox-claude:/home/dev/.claude \
    -v diurnal-sandbox-pw:/home/dev/.cache/ms-playwright \
    -p 8081:8081 \
    "$IMAGE" "$@"
}

case "${1:-}" in
  build) build ;;
  shell) shift; run bash ;;
  run)   shift; run "$@" ;;
  "")    run ;;            # default: interactive claude (entrypoint default)
  *)     run "$@" ;;
esac
