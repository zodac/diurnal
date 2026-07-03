#!/usr/bin/env bash
#
# Tear down the local dev environment used for manual testing / preview generation:
#   - the `mvn quarkus:dev` server on the testing port 8081 (NEVER the production container on 8080),
#   - the ephemeral `diurnal-db-dev` Postgres container (tmpfs — nothing persisted is lost),
#   - the `diurnal-smoke` stack on port 8082 (the deployment-smoke app + DB built from the Dockerfile).
#     run-smoke.sh tears its own stack down via an EXIT/signal trap, but an outright `kill -9` of the
#     runner (or a host reboot mid-run) can still leak it — so we sweep it here as a safety net. It is
#     a pure test artifact (project name `diurnal-smoke`, distinct from the prod `diurnal` stack), so
#     removing it is always safe; the production stack on 8080 is never touched.
#
# Port 8081 is freed ONLY when it is held by a process that belongs to THIS project — a `quarkus:dev`
# dev server or an `-Dall` E2E jar launched from this tree. Anything else on 8081 (another app, or a
# Docker-published port such as the `diurnal-sandbox` container's `-p 8081:8081`) is a legitimate use
# of the port: it is left untouched and reported as an error for you to resolve. We never kill a
# stranger's process — or a container — just because it occupies our dev port.
#
# Occupancy is detected from the world-readable /proc/net/tcp, so a root-owned holder (e.g. Docker's
# `docker-proxy`) is still seen even though we run unprivileged — the previous version used a non-root
# `lsof`, which cannot see root's sockets and so falsely reported the port "free".
#
# Safe to run repeatedly and safe to run when nothing is up: it only touches dev resources, leaving
# any running production `docker compose up` stack (app on 8080, its DB) untouched. See CLAUDE.md
# ("Always tear down the dev environment once testing/verification is finished").
#
# Usage:  scripts/dev-teardown.sh
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
PROJECT_DIR="$(pwd)"
PORT=8081
PORT_HEX="$(printf ':%04X' "${PORT}")"

# ── Is $PORT occupied at all? ─────────────────────────────────────────────────
# Reads /proc/net/tcp(6) directly: it lists EVERY listening socket (state 0A) regardless of owner, so
# this is true even for a root/`docker-proxy`-held port that an unprivileged `lsof` would miss.
port_occupied() {
  awk -v p="${PORT_HEX}" '$2 ~ p"$" && $4=="0A" {f=1} END{exit !f}' \
      /proc/net/tcp /proc/net/tcp6 2>/dev/null
}

# ── Find the PID(s) LISTENing on $PORT that we can actually see ────────────────
# Prefer lsof, then ss (iproute2), then a pure-/proc fallback. Any of these can come up empty for a
# root-owned holder when we are unprivileged — that's fine, port_occupied() is the source of truth for
# whether the port is busy; this only finds PIDs we might be allowed to kill.
port_listener_pids() {
  if command -v lsof >/dev/null 2>&1; then
    lsof -tiTCP:"${PORT}" -sTCP:LISTEN 2>/dev/null | sort -u
    return
  fi
  if command -v ss >/dev/null 2>&1; then
    ss -lntpH "sport = :${PORT}" 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u
    return
  fi
  local inode fd
  while IFS= read -r inode; do
    [[ -z "${inode}" ]] && continue
    for fd in /proc/[0-9]*/fd/*; do
      [[ "$(readlink "${fd}" 2>/dev/null || true)" = "socket:[${inode}]" ]] && echo "${fd}" | cut -d/ -f3
    done
  done < <(awk -v p="${PORT_HEX}" '$2 ~ p"$" && $4=="0A" {print $10}' \
                /proc/net/tcp /proc/net/tcp6 2>/dev/null | sort -u || true) | sort -u || true
}

cmd_of() { tr '\0' ' ' < "/proc/$1/cmdline" 2>/dev/null; }

# ── Does a PID belong to THIS project? ────────────────────────────────────────
# It must look like a Quarkus process (our dev server / E2E jar — never a plain unrelated app) AND be
# rooted in this project tree (referenced on its command line, or running with its CWD inside it).
belongs_to_project() {
  local pid="$1" cmd cwd
  cmd="$(cmd_of "${pid}")"
  cwd="$(readlink "/proc/${pid}/cwd" 2>/dev/null)"
  case "${cmd}" in
    *quarkus*) ;;                       # quarkus:dev / quarkus-run.jar / -Dquarkus.* / io.quarkus.*
    *) return 1 ;;
  esac
  case "${cmd}" in *"${PROJECT_DIR}"*) return 0 ;; *) ;; esac
  case "${cwd}" in "${PROJECT_DIR}" | "${PROJECT_DIR}"/*) return 0 ;; *) ;; esac
  return 1
}

# ── If a Docker container publishes $PORT, name it (a common, easy-to-miss culprit) ──
docker_publisher() {
  command -v docker >/dev/null 2>&1 || return 0
  docker ps --format '{{.Names}}  ({{.Ports}})' 2>/dev/null | grep -E ":${PORT}->" || true
}

foreign=0

echo "→ Stopping this project's quarkus:dev wrapper(s) on port ${PORT}…"
# Kill the dev-mode wrapper(s) FIRST so dev mode can't relaunch the app after we free the port. Only
# this project's wrappers — belongs_to_project filters out any other project's quarkus:dev.
for pid in $(pgrep -f "quarkus:dev" 2>/dev/null || true); do
  [[ -e "/proc/${pid}" ]] && belongs_to_project "${pid}" && kill "${pid}" 2>/dev/null || true
done
sleep 1   # let a graceful wrapper take its forked app JVM down before we check the port

echo "→ Freeing port ${PORT} (only if it belongs to this project)…"
for pid in $(port_listener_pids); do
  [[ -e "/proc/${pid}" ]] || continue
  if belongs_to_project "${pid}"; then
    kill -9 "${pid}" 2>/dev/null || true
  else
    echo "✗ Port ${PORT} is held by an UNRELATED process (PID ${pid}) — NOT touching it:" >&2
    echo "    $(cmd_of "${pid}" || true)" >&2
    foreign=1
  fi
done

echo "→ Removing the dev database container (diurnal-db-dev)…"
docker compose -f docker-compose.dev.yml rm -sf diurnal-db-dev >/dev/null 2>&1 || true

echo "→ Removing any leftover deployment-smoke stack (diurnal-smoke)…"
# Namespaced project, so this only ever touches the smoke app + DB — never the prod `diurnal` stack.
# `down` first; then a label-based force-remove sweeps any container a killed run-smoke.sh left behind.
docker compose -p diurnal-smoke -f docker-compose.smoke.yml down -v --remove-orphans --timeout 10 >/dev/null 2>&1 || true
smoke_left="$(docker ps -aq --filter "label=com.docker.compose.project=diurnal-smoke" 2>/dev/null || true)"
[[ -n "${smoke_left}" ]] && echo "${smoke_left}" | xargs -r docker rm -f >/dev/null 2>&1 || true

# ── Verify and report ─────────────────────────────────────────────────────────
sleep 1
ok=0
if ! port_occupied; then
  echo "✓ port ${PORT} is free"
else
  ok=1
  if [[ "${foreign}" -eq 1 ]]; then
    echo "✗ port ${PORT} is still held by an unrelated process — stop that app yourself" >&2
  else
    # Busy, but we could not attribute it to a killable PID — almost always a Docker-published port
    # (root-owned docker-proxy, invisible to an unprivileged lsof). Name the container if we can.
    echo "✗ port ${PORT} is in use, but NOT by this project's dev server — leaving it alone." >&2
    pub="$(docker_publisher)"
    if [[ -n "${pub}" ]]; then
      echo "  A Docker container publishes :${PORT} — stop or remap it to free the port:" >&2
      echo "    ${pub}" >&2
    else
      echo "  It looks root-owned (e.g. a Docker-published port). Try: sudo lsof -i :${PORT}  /  docker ps" >&2
    fi
  fi
fi
if docker ps --format '{{.Names}}' | grep -qx 'diurnal-db-dev'; then
  echo "✗ diurnal-db-dev is still running"; ok=1
else
  echo "✓ diurnal-db-dev is stopped"
fi
if docker ps --format '{{.Names}}' | grep -q '^diurnal-smoke-'; then
  echo "✗ a diurnal-smoke container is still running"; ok=1
else
  echo "✓ deployment-smoke stack is stopped"
fi

[[ "${ok}" -eq 0 ]] && echo "Dev teardown complete." || echo "Dev teardown finished with warnings."
exit "${ok}"
