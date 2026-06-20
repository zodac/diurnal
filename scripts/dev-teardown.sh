#!/usr/bin/env bash
#
# Tear down the local dev environment used for manual testing / preview generation:
#   - the `mvn quarkus:dev` server on the testing port 8081 (NEVER the production container on 8080),
#   - the ephemeral `diurnal-db-dev` Postgres container (tmpfs — nothing persisted is lost).
#
# Safe to run repeatedly and safe to run when nothing is up: it only touches dev resources, leaving
# any running production `docker compose up` stack (app on 8080, its DB) untouched. See CLAUDE.md
# ("Always tear down the dev environment once testing/verification is finished").
#
# Usage:  scripts/dev-teardown.sh
set -uo pipefail
cd "$(dirname "$0")/.."

echo "→ Stopping quarkus:dev (testing port 8081)…"
pkill -f "quarkus:dev" 2>/dev/null || true
# The forked dev JVM may outlive the Maven process; kill whatever still LISTENs on 8081 specifically,
# so the production server on 8080 is never affected.
port_pids=$(lsof -ti tcp:8081 -sTCP:LISTEN 2>/dev/null || true)
if [ -n "${port_pids}" ]; then
  kill -9 ${port_pids} 2>/dev/null || true
fi

echo "→ Removing the dev database container (diurnal-db-dev)…"
docker compose -f docker-compose.dev.yml rm -sf diurnal-db-dev >/dev/null 2>&1 || true

# Verify and report.
sleep 1
ok=0
if lsof -i tcp:8081 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "✗ port 8081 is still in use"; ok=1
else
  echo "✓ port 8081 is free"
fi
if docker ps --format '{{.Names}}' | grep -qx 'diurnal-db-dev'; then
  echo "✗ diurnal-db-dev is still running"; ok=1
else
  echo "✓ diurnal-db-dev is stopped"
fi

[ "${ok}" -eq 0 ] && echo "Dev teardown complete." || echo "Dev teardown finished with warnings."
exit "${ok}"
