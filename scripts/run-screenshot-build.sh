#!/usr/bin/env bash
#
# Generate the in-app Settings preview thumbnails INSIDE the Docker build (the Dockerfile `screenshots`
# stage). Runs entirely inside one build RUN: boots a throwaway Postgres + the pre-built app (the
# `previewbuild` fast-jar) and drives the headless-Chromium generator against it. The 8 WebP land in the
# generator's output dir, which the real `build` stage then copies in + content-hashes.
#
# This is what makes a plain `docker build` / `docker compose up --build` produce fresh previews with
# nothing committed. Smoke/perf image builds skip this stage (GENERATE_PREVIEWS=false) — they don't use
# the previews and don't want the extra build cost.
#
# The stage is based on the official Postgres image, so Postgres is started with initdb + pg_ctl (not
# the Debian pg_ctlcluster) against a throwaway data dir. Node (for the generator + a readiness probe)
# and a jlink JRE (for the app) are copied in by the Dockerfile.
#
# Expected layout in the stage (see the Dockerfile):
#   /gen/app                — the previewbuild quarkus-app (quarkus-run.jar + lib/app/quarkus)
#   /gen/scripts/…          — generate-screenshots.cjs
#   /gen/tests/node_modules — playwright + pg (npm ci from the committed tests/ manifest)
# Output: /gen/src/main/resources/META-INF/resources/img/settings/*.webp
set -euo pipefail

APP_DIR=/gen/app
GEN_DIR=/gen
OUT_DIR="${GEN_DIR}/src/main/resources/META-INF/resources/img/settings"
export PGDATA=/tmp/pgdata

# ── Postgres (throwaway; official-image binaries via initdb/pg_ctl) ────────────────────────────────
initdb_path="$(command -v initdb)"
PG_BIN="$(dirname "${initdb_path}")"

postgres_version="$("${PG_BIN}/postgres" --version)"
echo "→ Initialising Postgres (${postgres_version})…"
install -d -o postgres -g postgres "${PGDATA}"
# -A trust: local/host connections need no password (throwaway DB), so the app connects with the
# datasource defaults unmodified.
su postgres -c "'${PG_BIN}/initdb' -A trust -D '${PGDATA}'" >/dev/null
su postgres -c "'${PG_BIN}/pg_ctl' -D '${PGDATA}' -o '-c listen_addresses=127.0.0.1' -w start"

echo "→ Creating role + database (diurnal_user / diurnal_db)…"
su postgres -c "'${PG_BIN}/psql' -v ON_ERROR_STOP=1" <<'SQL'
CREATE ROLE diurnal_user WITH LOGIN PASSWORD 'diurnal_password';
CREATE DATABASE diurnal_db OWNER diurnal_user;
SQL

# ── App (the previewbuild fast-jar; renders the styled dashboard the generator screenshots) ────────
echo "→ Booting the app…"
# DB_HOST=127.0.0.1 matches the trust host rule; the throttle is disabled so seeding is never limited.
cd "${APP_DIR}"
DB_HOST=127.0.0.1 AUTH_IP_THROTTLE_ENABLED=false java -jar quarkus-run.jar >/tmp/app.log 2>&1 &
app_pid=$!

cleanup() {
  kill "${app_pid}" 2>/dev/null || true
  su postgres -c "'${PG_BIN}/pg_ctl' -D '${PGDATA}' -m immediate stop" 2>/dev/null || true
}
trap cleanup EXIT

echo "→ Waiting for readiness (GET /api/v1/status)…"
# Readiness probed with Node (present for the generator) — no curl/wget dependency in this stage.
ready_probe='require("http").get("http://127.0.0.1:8080/api/v1/status",r=>process.exit(r.statusCode===200?0:1)).on("error",()=>process.exit(1))'
ready=0
for _ in $(seq 1 90); do
  if node -e "${ready_probe}" >/dev/null 2>&1; then ready=1; break; fi
  if ! kill -0 "${app_pid}" 2>/dev/null; then
    echo "✗ app exited before becoming ready — last log lines:" >&2
    tail -40 /tmp/app.log >&2 || true
    exit 1
  fi
  sleep 2
done
if [[ "${ready}" -ne 1 ]]; then
  echo "✗ app did not become ready in time — last log lines:" >&2
  tail -40 /tmp/app.log >&2 || true
  exit 1
fi

# ── Generate ───────────────────────────────────────────────────────────────────────────────────────
echo "→ Generating the in-app preview thumbnails…"
cd "${GEN_DIR}"
PW_CHROMIUM_ARGS="--no-sandbox" BASE_URL="http://127.0.0.1:8080" node scripts/generate-screenshots.cjs app

# Sanity-check the expected outputs exist so a silent capture failure fails the build here.
count="$(find "${OUT_DIR}" -maxdepth 1 -name '*.webp' | wc -l)"
if [[ "${count}" -lt 8 ]]; then
  echo "✗ expected 8 thumbnails in ${OUT_DIR}, found ${count}." >&2
  exit 1
fi
echo "✓ generated ${count} preview thumbnails"
