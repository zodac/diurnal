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
# DOC_SCREENSHOTS=true (the Dockerfile build arg, default false) additionally generates the 17 README
# screenshots from the SAME Postgres + app + Chromium boot, into /gen/docs/screenshots. Only the release
# workflow sets it: it exports them from the `docscreenshots` stage and replaces the assets on the
# standalone `screenshots` GitHub release, which the README and docs/dockerhub-overview.md embed by
# absolute URL. Doing it in this one boot is why the release regenerates both sets for the cost of one.
#
# The stage is based on the official Postgres image, so Postgres is started with initdb + pg_ctl (not
# the Debian pg_ctlcluster) against a throwaway data dir. Node (for the generator + a readiness probe)
# and a jlink JRE (for the app) are copied in by the Dockerfile.
#
# Expected layout in the stage (see the Dockerfile):
#   /gen/app                — the previewbuild quarkus-app (quarkus-run.jar + lib/app/quarkus)
#   /gen/scripts/…          — generate-screenshots.cjs
#   /gen/tests/node_modules — playwright + pg (npm ci from the committed tests/ manifest)
#   /gen/key                — the rendering inputs, copied in ONLY to be hashed (see the cache below)
# Output: /gen/src/main/resources/META-INF/resources/img/settings/*.webp
#         /gen/docs/screenshots/*.webp  (DOC_SCREENSHOTS=true only)
#
# PREVIEW_CACHE=true (the Dockerfile build arg, default false, local iteration only) short-circuits all
# of the above when a previous run's thumbnails for the same rendering inputs are still in the build
# cache mount. Read the risk note beside the build arg in the Dockerfile before switching it on: the key
# excludes src/main/java, so a Java change that alters the rendering restores stale thumbnails.
set -euo pipefail

APP_DIR=/gen/app
GEN_DIR=/gen
OUT_DIR="${GEN_DIR}/src/main/resources/META-INF/resources/img/settings"
DOCS_DIR="${GEN_DIR}/docs/screenshots"
KEY_DIR="${GEN_DIR}/key"
CACHE_DIR=/preview-cache
PREVIEW_CACHE="${PREVIEW_CACHE:-false}"
DOC_SCREENSHOTS="${DOC_SCREENSHOTS:-false}"
export PGDATA=/tmp/pgdata

# Always present, even when no README shots are generated, so the `docscreenshots` export stage's COPY
# resolves either way rather than failing the build on a missing path.
mkdir -p "${DOCS_DIR}"

# Every expected output must exist before a set is shipped OR stored in the cache. Each preview is
# written twice - the picker tile and, under full/, the lightbox image - so BOTH sets are checked:
# shipping tiles without their full-size counterparts would leave every preview button broken.
verify_outputs() {
  local count full_count
  count="$(find "${OUT_DIR}" -maxdepth 1 -name '*.webp' | wc -l)"
  if [[ "${count}" -lt 8 ]]; then
    echo "✗ expected 8 preview thumbnails in ${OUT_DIR}, found ${count}." >&2
    return 1
  fi
  full_count="$(find "${OUT_DIR}/full" -maxdepth 1 -name '*.webp' 2>/dev/null | wc -l)"
  if [[ "${full_count}" -lt 8 ]]; then
    echo "✗ expected 8 full-size previews in ${OUT_DIR}/full, found ${full_count}." >&2
    return 1
  fi
  echo "✓ ${count} preview thumbnails + ${full_count} full-size previews"
}

# The README set is all-or-nothing in the same way: a capture that silently failed would otherwise be
# published as a missing image on the README rather than failing the release here.
verify_doc_outputs() {
  local count
  count="$(find "${DOCS_DIR}" -maxdepth 1 -name '*.webp' | wc -l)"
  if [[ "${count}" -lt 17 ]]; then
    echo "✗ expected 17 README screenshots in ${DOCS_DIR}, found ${count}." >&2
    return 1
  fi
  echo "✓ ${count} README screenshots"
}

# On when the build arg says so AND the cache mount is actually there (it is absent for a hand-run of
# this script outside the build, which then simply always generates). Resolved once into a flag rather
# than a predicate function, which `set -e` would disable inside an `if` (SC2310).
# DOC_SCREENSHOTS disables it outright: a cache HIT restores the thumbnails and exits before the app is
# ever booted, so the README shots would silently not be generated. The release passes no PREVIEW_CACHE
# anyway, so this only guards a hand-run that sets both.
cache_enabled=false
if [[ "${PREVIEW_CACHE}" == "true" && -d "${CACHE_DIR}" && "${DOC_SCREENSHOTS}" != "true" ]]; then
  cache_enabled=true
fi

# The hash of every input that can change a pixel: the copied rendering inputs plus the generator, this
# runner and the pinned Playwright version that paints them. Sorted before hashing so the digest does
# not depend on directory order; the paths are fixed inside the stage, so including them is stable.
cache_key() {
  find "${KEY_DIR}" "${GEN_DIR}/scripts" "${GEN_DIR}/tests/package-lock.json" -type f -print0 \
    | sort -z \
    | xargs -0 sha256sum \
    | sha256sum \
    | cut -d' ' -f1
}

CACHE_ENTRY=""
if [[ "${cache_enabled}" == "true" ]]; then
  CACHE_KEY="$(cache_key)"
  CACHE_ENTRY="${CACHE_DIR}/${CACHE_KEY}"
  if [[ -d "${CACHE_ENTRY}" ]]; then
    echo "→ Preview cache HIT (${CACHE_KEY:0:12}) - restoring, skipping Postgres/Chromium…"
    mkdir -p "${OUT_DIR}"
    cp -a "${CACHE_ENTRY}/." "${OUT_DIR}/"
    verify_outputs
    exit 0
  fi
  echo "→ Preview cache MISS (${CACHE_KEY:0:12}) - generating…"
fi

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
# Notes are encrypted at rest and the app refuses to boot without a key, so this stage needs one even
# though it never looks at a note: the generator seeds notes to give the calendar its day markers. Fixed
# and in plain sight on purpose - the database it protects is created, used and destroyed inside this one
# build layer, so there is nothing here worth a real secret. A deployment sets NOTE_ENCRYPTION_KEY itself.
PREVIEW_NOTE_KEY='ZGl1cm5hbC1wcmV2aWV3LWJ1aWxkLWtleS0zMmJ5dGU='

# DB_HOST=127.0.0.1 matches the trust host rule; the throttle is disabled so seeding is never limited.
boot_env=(
  DB_HOST=127.0.0.1
  AUTH_IP_THROTTLE_ENABLED=false
  "NOTE_ENCRYPTION_KEY=${PREVIEW_NOTE_KEY}"
)

# The README login shot shows BOTH sign-in methods, and the generator throws rather than capture a
# password-only one, so the app has to boot with OIDC enabled. The issuer is a DUMMY that is never
# contacted - the button renders from OIDC_ENABLED alone - and OIDC_VERIFY_ON_STARTUP=false stops the
# startup discovery probe failing the boot against it. Same throwaway config scripts/dev-up.sh applies
# under OIDC_PREVIEW=1; keep the two in step.
if [[ "${DOC_SCREENSHOTS}" == "true" ]]; then
  boot_env+=(
    OIDC_ENABLED=true
    OIDC_ISSUER_URL=http://127.0.0.1:8080
    OIDC_CLIENT_ID=diurnal
    OIDC_CLIENT_SECRET=preview-dummy-secret
    OIDC_PROVIDER_NAME=Authelia
    "OIDC_SCOPES=email,profile"
    OIDC_VERIFY_ON_STARTUP=false
  )
fi

cd "${APP_DIR}"
env "${boot_env[@]}" java -jar quarkus-run.jar >/tmp/app.log 2>&1 &
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
# `all` rather than a second `documentation` run: one process, one browser launch and one seeding of the
# demo account produce BOTH sets. TEST_DB_* is set unconditionally but only `all` uses it - that mode
# reaches the database directly to grant the demo user the administrator role for the Admin-page shot,
# there being no HTTP endpoint for it. 127.0.0.1 again matches the trust host rule.
gen_mode=app
if [[ "${DOC_SCREENSHOTS}" == "true" ]]; then
  gen_mode=all
fi

echo "→ Generating the screenshots (${gen_mode} mode)…"
cd "${GEN_DIR}"
TEST_DB_HOST=127.0.0.1 PW_CHROMIUM_ARGS="--no-sandbox" BASE_URL="http://127.0.0.1:8080" \
  node scripts/generate-screenshots.cjs "${gen_mode}"

# Sanity-check the expected outputs exist so a silent capture failure fails the build here.
verify_outputs
if [[ "${DOC_SCREENSHOTS}" == "true" ]]; then
  verify_doc_outputs
fi

# Store the verified set for the next build with these same rendering inputs. Written to a temporary
# directory and moved into place, so an interrupted build can never leave a half-populated entry that a
# later run would restore as complete. Entries are ~1MB and are never pruned - `docker builder prune`
# clears them along with the rest of the build cache.
if [[ "${cache_enabled}" == "true" ]]; then
  cache_tmp="${CACHE_DIR}/.tmp-$$"
  rm -rf "${cache_tmp}" "${CACHE_ENTRY}"
  mkdir -p "${cache_tmp}"
  cp -a "${OUT_DIR}/." "${cache_tmp}/"
  mv "${cache_tmp}" "${CACHE_ENTRY}"
  echo "✓ cached the generated previews (${CACHE_KEY:0:12})"
fi
