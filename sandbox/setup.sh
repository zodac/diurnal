#!/usr/bin/env bash
# First-run / per-open setup for the diurnal sandbox. Runs as the `dev` user with
# CWD = /work. Every step is GUARDED so re-opening the environment is a fast no-op
# when nothing has changed; real work happens only on a fresh sandbox or after a
# relevant file changes. Failures are non-fatal (warn + continue) so the session
# still opens if you're offline.
set -uo pipefail

STATE_DIR="$HOME/.claude/.sandbox-state"
mkdir -p "$STATE_DIR"

step()  { printf '\033[36m[setup]\033[0m %s\n' "$*"; }
skip()  { printf '\033[2m[setup] %s (up to date)\033[0m\n' "$*"; }
warn()  { printf '\033[33m[setup] WARN: %s\033[0m\n' "$*" >&2; }

cd /work || { warn "no /work mount"; exit 0; }

# ── 1. Git submodule (code-quality-config) — linters need it ─────────────────
if git submodule status 2>/dev/null | grep -q '^-'; then
  step "initialising git submodules (code-quality-config)..."
  git submodule update --init || warn "submodule init failed"
else
  skip "git submodules"
fi

# ── 2. npm install — only when package-lock.json changed or node_modules gone ─
LOCK_HASH_FILE="$STATE_DIR/package-lock.sha"
CURRENT_LOCK_HASH="$(sha256sum package-lock.json 2>/dev/null | cut -d' ' -f1)"
if [ ! -d node_modules ] || [ "$CURRENT_LOCK_HASH" != "$(cat "$LOCK_HASH_FILE" 2>/dev/null)" ]; then
  step "running npm install..."
  if npm install --no-audit --no-fund; then
    echo "$CURRENT_LOCK_HASH" > "$LOCK_HASH_FILE"
  else
    warn "npm install failed"
  fi
else
  skip "npm dependencies"
fi

# ── 3. Playwright Chromium (+ OS deps) — cached in a persistent volume ───────
# --with-deps installs the right system libs for this distro (uses sudo, which
# `dev` has passwordless). Guarded by a marker so it only runs once per volume.
PW_MARKER="$STATE_DIR/playwright-installed"
if [ ! -f "$PW_MARKER" ] || [ -z "$(ls -A "${PLAYWRIGHT_BROWSERS_PATH:-$HOME/.cache/ms-playwright}" 2>/dev/null)" ]; then
  step "installing Playwright Chromium + OS deps (first run only)..."
  if (cd e2e && npx --yes playwright install --with-deps chromium); then
    touch "$PW_MARKER"
  else
    warn "playwright install failed"
  fi
else
  skip "Playwright browsers"
fi

step "ready."
