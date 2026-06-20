#!/usr/bin/env bash
#
# Bring up the local dev environment for manual testing / preview generation:
#   - the ephemeral `diurnal-db-dev` Postgres container (tmpfs, port 5432),
#   - `mvn quarkus:dev` on the testing port 8081, in the background,
# then block until the server answers, so callers can immediately hit http://localhost:8081.
#
# Logs go to /tmp/quarkus-dev.log. Tear it all down again with scripts/dev-teardown.sh.
# Safe to re-run: if 8081 already serves, it skips starting a second server.
#
# Usage:  scripts/dev-up.sh
set -uo pipefail
cd "$(dirname "$0")/.."

PORT=8081
LOG=/tmp/quarkus-dev.log

if curl -sf "http://localhost:${PORT}/login" >/dev/null 2>&1; then
  echo "✓ dev server already up on ${PORT}"
  exit 0
fi

echo "→ Starting dev database (diurnal-db-dev)…"
docker compose -f docker-compose.dev.yml up -d diurnal-db-dev

echo "→ Starting quarkus:dev on ${PORT} (logs: ${LOG})…"
nohup mvn quarkus:dev -Dquarkus.http.port="${PORT}" >"${LOG}" 2>&1 &
dev_pid=$!
echo "  pid ${dev_pid}"

echo "→ Waiting for the server to answer…"
until curl -sf "http://localhost:${PORT}/login" >/dev/null 2>&1; do
  sleep 2
  if ! kill -0 "${dev_pid}" 2>/dev/null; then
    echo "✗ dev server process exited before becoming ready — last log lines:"
    tail -30 "${LOG}"
    exit 1
  fi
done

echo "✓ dev server up on http://localhost:${PORT}"
