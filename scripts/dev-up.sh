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
#         OIDC_PREVIEW=1 scripts/dev-up.sh   # additionally enable OIDC against a DUMMY IdP
#
# OIDC_PREVIEW exists only to generate the login screenshot (scripts/generate-screenshots.cjs
# documentation): its committed image shows the "Log in with <provider>" button beside the password
# form. The issuer below is a placeholder that is NEVER contacted - the button is server-rendered from
# the OIDC_ENABLED flag alone - so no real identity provider is needed. Off by default so a normal dev
# session shows the password-only login page.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PORT=8081
LOG=/tmp/quarkus-dev.log

if curl -sf "http://localhost:${PORT}/login" >/dev/null 2>&1; then
  echo "✓ dev server already up on ${PORT}"
  exit 0
fi

echo "→ Starting dev database (diurnal-db-dev)…"
docker compose -f docker-compose.dev.yml up -d diurnal-db-dev

# Dummy-IdP OIDC config, only when OIDC_PREVIEW=1 (see the header). These -D system properties are
# forwarded by quarkus:dev to the running app exactly like -Dquarkus.http.port below.
oidc_args=()
if [[ "${OIDC_PREVIEW:-0}" == "1" ]]; then
  echo "→ OIDC_PREVIEW=1 - enabling OIDC against a dummy IdP (login-screenshot preview only)…"
  oidc_args=(
    "-DOIDC_ENABLED=true"
    "-DOIDC_ISSUER_URL=http://127.0.0.1:8080"
    "-DOIDC_CLIENT_ID=diurnal"
    "-DOIDC_CLIENT_SECRET=preview-dummy-secret"
    "-DOIDC_PROVIDER_NAME=Authelia"
    "-DOIDC_SCOPES=email,profile"
    # The dummy issuer is never contacted, so skip the startup discovery probe that would otherwise
    # fail the boot (OIDC_VERIFY_ON_STARTUP defaults to true - see application.properties).
    "-DOIDC_VERIFY_ON_STARTUP=false"
  )
fi

echo "→ Starting quarkus:dev on ${PORT} (logs: ${LOG})…"
nohup mvn quarkus:dev -Dquarkus.http.port="${PORT}" "${oidc_args[@]}" >"${LOG}" 2>&1 &
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
