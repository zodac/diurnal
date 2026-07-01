#!/usr/bin/env bash
# Launch the isolated diurnal sandbox.
#
#   ./sandbox.sh build          # (re)build the image
#   ./sandbox.sh                # start an interactive Claude session in the sandbox
#   ./sandbox.sh shell          # drop into a bash shell instead of Claude
#   ./sandbox.sh run <cmd...>   # run an arbitrary command in the sandbox
#   ./sandbox.sh stop           # stop & remove a running sandbox (one-click teardown)
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
# Git Bash on Windows resolves paths as /c/Users/... but Docker Desktop needs C:\Users\...
if command -v cygpath &>/dev/null; then
  HERE="$(cygpath -w "$HERE")"
  PROJECT_DIR="$(cygpath -w "$PROJECT_DIR")"
fi

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

  # Always (re)build before launching so every session runs the latest image. Docker's layer
  # cache makes this a near-instant no-op when nothing in the build context has changed.
  echo "[sandbox] building $IMAGE before launch..." >&2
  build
  # Allocate a TTY only when attached to one (so scripted `run` invocations work too).
  local tty=()
  if [ -t 0 ] && [ -t 1 ]; then tty=(-it); else tty=(-i); fi

  # Tie the container's lifetime to THIS launcher. `docker run --rm` removes the container only when it
  # *exits*; if the client is killed — IntelliJ stops the run configuration, or the terminal/console
  # is closed — the container would otherwise keep running in the daemon. So stop it whenever we leave.
  # Running `docker run` as `… & wait` (not in the foreground) is what lets the trap fire *immediately*
  # on the signal: a foreground command defers traps until it returns, by which point IntelliJ may have
  # already escalated to SIGKILL. With job control off (a script), the background-ed client stays in the
  # foreground process group, so the interactive TTY keeps working.
  #
  # BUT: in a non-interactive shell (job control off — exactly how IntelliJ's Shell Script config and
  # `bash sandbox.sh` invoke us), POSIX reassigns a background-ed command's stdin to /dev/null *unless it
  # is explicitly redirected*. Without that explicit redirect, `docker run -it … &` would see a
  # non-terminal stdin and fail with "cannot attach stdin to a TTY-enabled container". So save the real
  # stdin on fd 3 and feed it back into the background-ed client with `<&3`, which suppresses the
  # /dev/null default and keeps the PTY attached.
  # `-t 10` (not a tighter grace) gives Claude time to finish its atomic rewrite of
  # ~/.claude/.claude.json on SIGTERM before docker SIGKILL it; too short a grace
  # interrupts that rename and loses the login/onboarding state (launch.sh restores
  # it from backup as a safety net, but a clean flush is better than relying on it).
  exec 3<&0
  trap 'docker stop -t 10 diurnal-sandbox >/dev/null 2>&1 || true' EXIT
  trap 'exit' INT TERM HUP

  # Publish the in-sandbox dev server (it runs on container :8081, e.g. scripts/dev-up.sh) to host
  # :8071 — deliberately NOT host :8081, so the host's own 8081 stays free for host-native dev/tests.
  docker run "${tty[@]}" --rm \
    --name diurnal-sandbox \
    --privileged \
    --hostname diurnal-sandbox \
    -v "$PROJECT_DIR":/work \
    -v diurnal-sandbox-docker:/var/lib/docker \
    -v diurnal-sandbox-claude:/home/dev/.claude \
    -v diurnal-sandbox-pw:/home/dev/.cache/ms-playwright \
    -p 8071:8081 \
    "$IMAGE" "$@" <&3 &
  wait $!
}

stop() {
  # `docker stop` triggers the running launcher's --rm + trap teardown (nested dockerd and everything it
  # spun up dies with the container). A no-op if nothing is running.
  if [ -n "$(docker ps -q -f name='^diurnal-sandbox$')" ]; then
    docker stop -t 10 diurnal-sandbox >/dev/null && echo "Stopped diurnal-sandbox."
  else
    echo "No running diurnal-sandbox container."
  fi
}

case "${1:-}" in
  build) build ;;
  stop)  stop ;;
  shell) shift; run bash ;;
  run)   shift; run "$@" ;;
  "")    run ;;            # default: interactive claude (entrypoint default)
  *)     run "$@" ;;
esac
