#!/usr/bin/env bash
# Launch the isolated project sandbox (every docker name is derived from the project directory).
#
#   ./sandbox.sh build          # (re)build the image
#   ./sandbox.sh                # start an interactive Claude session in the sandbox
#   ./sandbox.sh shell          # drop into a bash shell instead of Claude
#   ./sandbox.sh run <cmd...>   # run an arbitrary command in the sandbox
#   ./sandbox.sh stop           # stop & remove a running sandbox (one-click teardown)
#   ./sandbox.sh prune          # reclaim disk in the nested docker (build cache, images, volumes)
#
# A launch REPLACES any sandbox that is already running (they cannot coexist — same name, same port,
# same ~/.claude volume), stopping it only once the new image has been built. Every docker name is derived
# from the project directory (see SLUG below), so a DIFFERENT project's sandbox shares none of that state.
#
# Only the project directory is mounted from the host. No $HOME, no SSH keys,
# no other projects, and NOT the host Docker socket. The sandbox runs its own
# nested Docker daemon, so everything the project spins up (dev DB, Testcontainers,
# Playwright) lives and dies inside this disposable container.
set -euo pipefail

# This script lives in <project>/sandbox/, so the project root is its parent dir.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${PROJECT_DIR:-$(dirname "${HERE}")}"
# EVERY docker name below is DERIVED from the project directory, never hardcoded - the container, the
# image, the hostname and all four volumes. This launcher gets copied into other repos, and a hardcoded
# name travels with the copy: the copy mounts ITS OWN project at /work but attaches THE ORIGINAL
# project's volumes, so two unrelated repos end up sharing one ~/.claude - one prompt history (visible
# on the up-arrow), one memory directory, one set of transcripts offered by /resume, one allowedTools.
# Nothing warns you, because the project KEY Claude derives from the mount point ("-work") is identical
# for every project by construction. Deriving the names makes a copy self-scoping on its first launch.
# Set SANDBOX_NAME to pin one explicitly (e.g. two checkouts of the SAME repo that must not share).
SLUG="$(basename "${PROJECT_DIR}")"
SLUG="${SLUG,,}"                     # docker image names must be lowercase
SLUG="${SLUG//[^a-z0-9_.-]/-}"       # ... and hold only [a-z0-9_.-]
[[ "${SLUG}" =~ ^[a-z0-9] ]] || SLUG="sandbox${SLUG}"  # ... and start alphanumeric
NAME="${SANDBOX_NAME:-${SLUG}}-sandbox"
IMAGE="${NAME}"
CONTAINER="${NAME}"
# Ports published to the HOST, space-separated `host:container` pairs - the other resource a copied
# launcher would collide on (two sandboxes cannot both hold host :8071). Unlike the volume names this
# one fails LOUDLY ("port is already allocated"), so it is a default rather than a derivation.
# `SANDBOX_PORTS=` (empty) publishes nothing, which is the right setting for a project with no server.
read -ra PUBLISH_PORTS <<< "${SANDBOX_PORTS-8071:8081}"
PUBLISH=()
for port in ${PUBLISH_PORTS+"${PUBLISH_PORTS[@]}"}; do PUBLISH+=(-p "${port}"); done
# Git Bash on Windows resolves paths as /c/Users/... but Docker Desktop needs C:\Users\...
if command -v cygpath &>/dev/null; then
  HERE="$(cygpath -w "${HERE}")"
  PROJECT_DIR="$(cygpath -w "${PROJECT_DIR}")"
fi

build() {
  local uid gid
  uid="$(id -u)"
  gid="$(id -g)"
  docker build -t "${IMAGE}" \
    --build-arg UID="${uid}" \
    --build-arg GID="${gid}" \
    "${HERE}"
}

# Stop and remove whatever container is already holding our name, and wait until the name is actually
# free again. Reports what it did in REMOVED_EXISTING (1 = removed something, 0 = there was nothing) —
# a variable rather than an exit status, because a function called as an `if`/`||` condition runs with
# `set -e` disabled, which the docker calls in here should not.
#
# Two sandboxes CANNOT coexist: they share the container name, the published port and — worst of all —
# the named volumes, including /home/dev/.claude, whose login/session state Claude rewrites in place.
# Starting a second one while the first is up therefore takes BOTH down. So a launch does not compete
# with the running sandbox, it replaces it: the old one is killed here, deliberately AFTER build() has
# finished, so the outgoing session stays usable for the whole rebuild and the gap between the two is
# only the teardown itself.
REMOVED_EXISTING=0
remove_existing() {
  local existing waited=0
  REMOVED_EXISTING=0
  existing="$(docker ps -aq -f "name=^${CONTAINER}$")"
  if [[ -z "${existing}" ]]; then
    return 0
  fi
  REMOVED_EXISTING=1

  echo "[sandbox] stopping the existing ${CONTAINER} container..." >&2
  # `stop` first, with the same -t 10 grace as the teardown trap below (so the outgoing Claude still gets
  # to flush ~/.claude/.claude.json cleanly), then `rm -f` for a container that was NOT started with --rm
  # and would otherwise linger in `exited` state, still owning the name.
  docker stop -t 10 "${existing}" >/dev/null 2>&1 || true
  docker rm -f "${existing}" >/dev/null 2>&1 || true

  # `--rm` removal is asynchronous in the daemon: the name can stay taken for a moment after the client
  # exits, and `docker run --name` fails outright ("name is already in use") if we race it. Poll until the
  # name really is free rather than guessing at a sleep.
  while true; do
    existing="$(docker ps -aq -f "name=^${CONTAINER}$")"
    if [[ -z "${existing}" ]]; then
      return 0
    fi
    if (( waited >= 30 )); then
      echo "Timed out waiting for the existing ${CONTAINER} container to be removed." >&2
      exit 1
    fi
    sleep 1
    waited=$(( waited + 1 ))
  done
}

# Stop the container THIS launcher started, identified by the id docker wrote to the cidfile — never by
# name. Because remove_existing hands the name from an outgoing sandbox to an incoming one, a name-based
# teardown would let a departing launcher stop the container that replaced it.
#
# The path is a GLOBAL, set by run(). An EXIT trap fires in whatever scope the shell is in when it leaves:
# on the signal paths that is still inside run() (the INT/TERM trap's `exit`), but on a normal return it is
# the top level, where a `local` of run()'s is long out of scope — under `set -u` the trap body then dies
# with "cidfile: unbound variable" before it can stop anything or clean the file up.
CIDFILE=""
stop_own() {
  local cidfile="$1" cid=""
  if [[ -z "${cidfile}" ]]; then
    return 0
  fi
  if [[ -s "${cidfile}" ]]; then
    cid="$(<"${cidfile}")"
  fi
  if [[ -n "${cid}" ]]; then
    docker stop -t 10 "${cid}" >/dev/null 2>&1 || true
  fi
  rm -f "${cidfile}"
}

run() {
  if [[ ! -d "${PROJECT_DIR}" ]]; then
    echo "Project directory not found: ${PROJECT_DIR}" >&2
    exit 1
  fi

  # Always (re)build before launching so every session runs the latest image. Docker's layer
  # cache makes this a near-instant no-op when nothing in the build context has changed.
  echo "[sandbox] building ${IMAGE} before launch..." >&2
  build

  # Only now (image ready, downtime minimised) take the name off any sandbox that is already running.
  remove_existing

  # Allocate a TTY only when attached to one (so scripted `run` invocations work too).
  local tty=()
  if [[ -t 0 ]] && [[ -t 1 ]]; then tty=(-it); else tty=(-i); fi

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
  #
  # The container is identified for teardown by the id docker writes to --cidfile (see stop_own), not by
  # name. `mktemp -u` because docker refuses to start if the cidfile already exists.
  CIDFILE="$(mktemp -u "${TMPDIR:-/tmp}/${CONTAINER}.cid.XXXXXX")"
  exec 3<&0
  trap 'stop_own "${CIDFILE}"' EXIT
  trap 'exit' INT TERM HUP

  # Publish the in-sandbox dev server (it runs on container :8081, e.g. scripts/dev-up.sh) to host
  # :8071 — deliberately NOT host :8081, so the host's own 8081 stays free for host-native dev/tests.
  # See SANDBOX_PORTS at the top to repoint or disable this.
  #
  # The Maven local repository gets a named volume for the same reason the Docker data dir and the
  # Playwright browser cache do: without one it lives in the container's writable layer, which --rm
  # deletes on teardown, so every fresh sandbox re-downloads the project's whole dependency set
  # (~124MB / 365 jars, i.e. minutes added to the first `mvn` run). A named volume — rather than a bind
  # to the host's ~/.m2 — keeps the "no $HOME from the host" rule above intact while still persisting
  # across sessions. The image pre-creates /home/dev/.m2 dev-owned so the volume is writable (see the
  # Dockerfile's user-creation block).
  docker run "${tty[@]}" --rm \
    --name "${CONTAINER}" \
    --cidfile "${CIDFILE}" \
    --privileged \
    --hostname "${NAME}" \
    -v "${PROJECT_DIR}":/work \
    -v "${NAME}-docker":/var/lib/docker \
    -v "${NAME}-claude":/home/dev/.claude \
    -v "${NAME}-m2":/home/dev/.m2 \
    -v "${NAME}-pw":/home/dev/.cache/ms-playwright \
    ${PUBLISH[@]+"${PUBLISH[@]}"} \
    "${IMAGE}" "$@" <&3 &
  wait $!
}

# Reclaim disk inside the NESTED docker daemon - the `-docker` volume, by far the largest of the four
# (image layers and BuildKit cache from every gate run land there). The GC policy baked into the image
# (`daemon.json`) caps the build cache on its own; this is the manual, immediate version, and it also
# clears the images/containers/volumes that no policy covers.
#
# Prefers `docker exec` into a RUNNING sandbox so a live Claude session is never interrupted; only
# starts a container when none is up. Runs as root inside, since `dev` is not in the container's
# docker group.
PRUNE_CMD='docker system df; docker builder prune -af --max-used-space=20GB; docker image prune -af --filter until=336h; docker container prune -f; docker volume prune -f; echo; docker system df'
prune() {
  if docker inspect "${CONTAINER}" >/dev/null 2>&1; then
    echo "[sandbox] pruning inside the running ${CONTAINER} (session untouched)..."
    docker exec -u root "${CONTAINER}" bash -c "${PRUNE_CMD}"
  else
    echo "[sandbox] no ${CONTAINER} running - starting one to prune..."
    run sudo bash -c "${PRUNE_CMD}"
  fi
}

stop() {
  # Stopping the container triggers the running launcher's --rm + trap teardown (nested dockerd and
  # everything it spun up dies with it). A no-op if nothing is there.
  remove_existing
  if (( REMOVED_EXISTING == 1 )); then
    echo "Stopped ${CONTAINER}."
  else
    echo "No ${CONTAINER} container to stop."
  fi
}

case "${1:-}" in
  build) build ;;
  stop)  stop ;;
  prune) prune ;;
  shell) shift; run bash ;;
  run)   shift; run "$@" ;;
  "")    run ;;            # default: interactive claude (entrypoint default)
  *)     run "$@" ;;
esac
