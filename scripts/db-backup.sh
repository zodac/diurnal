#!/usr/bin/env bash
#
# Back up the production Postgres database (docker-compose.yml's `diurnal-db` service) to a single
# file, whole-instance, admin-level — this is NOT the per-user Settings > Data export (TRANSFER.md),
# which stays the tool for a user's own notes/actions/logs.
#
# Uses `pg_dump -Fc` (the custom/binary format) rather than plain SQL, and passes -T to
# `docker compose exec` to disable the pseudo-TTY compose allocates by default. Both matter: without
# -T, a TTY session can silently mangle the byte stream when its stdout is redirected to a file, and a
# plain-SQL dump gives psql no way to notice — it does not stop on error, so a corrupted COPY block for
# one table is dropped with only an ERROR line printed to the terminal, not to the dump file. The
# custom format is immune to that mangling and is restored with `pg_restore`, which does stop on error
# (see db-restore.sh's --exit-on-error).
#
# `notes.content_encrypted` comes out as ciphertext (see V28__encrypt_notes.sql) — this backup is only
# ever readable again next to the NOTE_ENCRYPTION_KEY it was taken under.
#
# Usage:  scripts/db-backup.sh [output-file]
#         Defaults to backup_diurnal_<UTC timestamp>.dump in the current directory.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

DB_USER="${DIURNAL_DB_USER:-diurnal_user}"
DB_NAME="${DIURNAL_DB_NAME:-diurnal_db}"
OUT="${1:-backup_diurnal_$(date -u +%Y%m%dT%H%M%SZ).dump}"

running_pid="$(docker compose ps diurnal-db --status running -q 2>/dev/null)" || true
if [[ -z "${running_pid}" ]]; then
  echo "✗ diurnal-db is not running — start it first (e.g. docker compose up -d diurnal-db)" >&2
  exit 1
fi

echo "→ Dumping ${DB_NAME} (user ${DB_USER}) to ${OUT}…"
if ! docker compose exec -T diurnal-db pg_dump -U "${DB_USER}" -d "${DB_NAME}" -Fc > "${OUT}"; then
  echo "✗ pg_dump failed — removing partial output ${OUT}" >&2
  rm -f "${OUT}"
  exit 1
fi

out_size="$(du -h "${OUT}")" || true
echo "✓ backup written to ${OUT} (${out_size%%$'\t'*})"
echo "  Restore with: scripts/db-restore.sh ${OUT}"
