#!/usr/bin/env bash
# Tiny wrapper run as the `dev` user by the entrypoint: recover Claude's login if
# it was lost, run the per-open setup (only for interactive sessions, gated via
# RUN_SETUP), then exec the real command.
set -uo pipefail

 # ── Recover Claude's login/onboarding from a sandbox-managed snapshot ─────────
# Claude's login lives in TWO files under `$CLAUDE_CONFIG_DIR`:
#   • `.claude.json`        — onboarding + account (no `hasCompletedOnboarding`/
#                             account ⇒ login/onboarding prompt)
#   • `.credentials.json`   — the OAuth tokens (missing/empty ⇒ forced login; this
#                             is the file that ACTUALLY gates login)
# Both are rewritten atomically (write-temp + rename). The sandbox is torn down
# with a short `docker stop` grace, so a hard SIGKILL can interrupt a rename and
# leave a file MISSING — and there is otherwise NO fallback for `.credentials.json`
# at all, so that single lost file forces a fresh login no matter how healthy the
# rest of the state is. (Claude also keeps `.claude.json` copies under `backups/`,
# but those are themselves frequently truncated to a `{firstStartTime}` stub by the
# same interrupted-write, so they are NOT a reliable source.)
#
# So the sandbox keeps its OWN known-good snapshot of both files in
# `.sandbox-state/` (persisted on the same volume). Each launch we (1) restore
# either file from the snapshot when the live copy is absent/empty/stripped, then
# (2) once the live state is healthy, refresh the snapshot — so the next launch
# always has last-good login + tokens to fall back to. Cheap no-op when healthy.
# (Belt-and-braces with the longer teardown grace in sandbox.sh, which makes the
# loss less likely in the first place.)
CCD="${CLAUDE_CONFIG_DIR:-$HOME/.claude}"
CFG="$CCD/.claude.json"
CRED="$CCD/.credentials.json"
SNAP="$CCD/.sandbox-state"
CFG_SNAP="$SNAP/claude.json.bak"
CRED_SNAP="$SNAP/credentials.json.bak"
ONBOARDED='"hasCompletedOnboarding"[[:space:]]*:[[:space:]]*true'
mkdir -p "$SNAP"

config_ok()  { grep -Eq "$ONBOARDED" "$CFG"  2>/dev/null; }
# A healthy credentials file is present and non-trivial (the OAuth token blob is
# hundreds of bytes; treat an empty or near-empty file as lost).
cred_ok()    { [ -s "$CRED" ] && grep -q 'claudeAiOauth' "$CRED" 2>/dev/null; }

# (1a) Restore `.claude.json`: prefer our own (full) snapshot, then fall back to
#      Claude's own backups (skipping the stubs that lost the onboarding marker).
if ! config_ok; then
  if grep -Eq "$ONBOARDED" "$CFG_SNAP" 2>/dev/null; then
    cp -p "$CFG_SNAP" "$CFG" && echo "[sandbox] restored Claude onboarding from snapshot"
  else
    for bak in $(ls -t "$CCD"/backups/.claude.json.backup.* 2>/dev/null); do
      if grep -Eq "$ONBOARDED" "$bak"; then
        cp -p "$bak" "$CFG" && echo "[sandbox] restored Claude onboarding from $(basename "$bak")"
        break
      fi
    done
  fi
fi

# (1b) Restore `.credentials.json` (the login tokens) from our snapshot.
if ! cred_ok && [ -s "$CRED_SNAP" ]; then
  cp -p "$CRED_SNAP" "$CRED" && echo "[sandbox] restored Claude login tokens from snapshot"
fi

# (2) Snapshot helper: copy any healthy live file to its backup. Only snapshots
#     healthy files, so a broken live copy never overwrites a good snapshot.
snapshot_now() {
  config_ok && cp -p "$CFG"  "$CFG_SNAP"  2>/dev/null
  cred_ok   && cp -p "$CRED" "$CRED_SNAP" 2>/dev/null
  return 0
}
snapshot_now   # immediate: capture whatever good state we already have

# (3) Keep the snapshot fresh FOR THE DURATION of an interactive session. A
#     startup-only snapshot has a hole: a login made this session is only captured
#     on the NEXT clean launch, so a fresh login that is then hard-killed is lost —
#     forcing login again. A lightweight background poll closes that hole: seconds
#     after you log in (or Claude rotates the OAuth token) the good files are
#     copied to `.sandbox-state`, so ANY later kill — even an abrupt SIGKILL that
#     skips the teardown grace entirely — is recoverable on the next launch. The
#     watcher is a child of this session; it dies with the container. Gated on
#     RUN_SETUP (set only for interactive TTY sessions) so quick `run <cmd>`
#     invocations don't spawn it.
if [ "${RUN_SETUP:-0}" = "1" ]; then
  ( while sleep 15; do snapshot_now; done ) &
fi

if [ "${RUN_SETUP:-0}" = "1" ]; then
  /usr/local/bin/sandbox-setup.sh || true
fi

exec "$@"
