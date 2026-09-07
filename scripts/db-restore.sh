#!/usr/bin/env bash
#
# Restore a backup made by scripts/db-backup.sh into the running `diurnal-db` service, REPLACING
# every table's contents (--clean --if-exists drops each object before recreating it — same
# database, not a fresh volume). Pairs with db-backup.sh; see that script's header for why this goes
# through `pg_restore` on a custom-format dump rather than `psql` on plain SQL.
#
# --exit-on-error makes a corrupted or partial dump fail loudly instead of silently restoring only
# some tables — the failure mode that motivated this pair of scripts in the first place.
#
# Usage:  scripts/db-restore.sh <backup-file> [--yes]
#         --yes skips the confirmation prompt (for non-interactive use).
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

DB_USER="${DIURNAL_DB_USER:-diurnal_user}"
DB_NAME="${DIURNAL_DB_NAME:-diurnal_db}"

FILE=""
ASSUME_YES=0
for arg in "$@"; do
  case "${arg}" in
    --yes) ASSUME_YES=1 ;;
    *) FILE="${arg}" ;;
  esac
done

if [[ -z "${FILE}" ]]; then
  echo "Usage: scripts/db-restore.sh <backup-file> [--yes]" >&2
  exit 1
fi
if [[ ! -f "${FILE}" ]]; then
  echo "✗ ${FILE} not found" >&2
  exit 1
fi

running_pid="$(docker compose ps diurnal-db --status running -q 2>/dev/null)" || true
if [[ -z "${running_pid}" ]]; then
  echo "✗ diurnal-db is not running — start it first (e.g. docker compose up -d diurnal-db)" >&2
  exit 1
fi

if [[ "${ASSUME_YES}" -ne 1 ]]; then
  echo "This REPLACES every table in ${DB_NAME} with the contents of ${FILE}."
  read -r -p "Continue? [y/N] " reply
  [[ "${reply}" =~ ^[Yy]$ ]] || { echo "Aborted."; exit 1; }
fi

echo "→ Restoring ${FILE} into ${DB_NAME} (user ${DB_USER})…"
if docker compose exec -T diurnal-db pg_restore -U "${DB_USER}" -d "${DB_NAME}" \
     --clean --if-exists --exit-on-error < "${FILE}"; then
  echo "✓ restore complete"
else
  echo "✗ pg_restore reported an error — the database may be partially restored, check the output above" >&2
  exit 1
fi
