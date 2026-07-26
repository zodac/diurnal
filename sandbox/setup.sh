#!/usr/bin/env bash
# First-run / per-open setup for the diurnal sandbox. Runs as the `dev` user with
# CWD = /work. Every step is GUARDED so re-opening the environment is a fast no-op
# when nothing has changed; real work happens only on a fresh sandbox or after a
# relevant file changes. Failures are non-fatal (warn + continue) so the session
# still opens if you're offline.
set -uo pipefail

STATE_DIR="${HOME}/.claude/.sandbox-state"
mkdir -p "${STATE_DIR}"

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

# ── 2. npm install (frontend/) — only when package-lock.json changed or node_modules gone ─
LOCK_HASH_FILE="${STATE_DIR}/package-lock.sha"
CURRENT_LOCK_HASH="$(sha256sum frontend/package-lock.json 2>/dev/null | cut -d' ' -f1)"
if [[ ! -d frontend/node_modules ]] || [[ "${CURRENT_LOCK_HASH}" != "$(cat "${LOCK_HASH_FILE}" 2>/dev/null || true)" ]]; then
  step "running npm install..."
  if npm --prefix frontend install --no-audit --no-fund; then
    echo "${CURRENT_LOCK_HASH}" > "${LOCK_HASH_FILE}"
  else
    warn "npm install failed"
  fi
else
  skip "npm dependencies"
fi

# ── 3. Playwright Chromium browser binary ────────────────────────────────────
# Only the browser BINARY is handled here: it is cached in the persistent
# `ms-playwright` volume, so it just needs downloading once (guarded by a marker +
# a non-empty cache). The OS shared libs (libnspr4, libnss3, …) it depends on are
# NOT handled here anymore — they are baked into the image via `playwright
# install-deps` in the Dockerfile, so as an image layer they survive container
# recreation and need no per-open re-assertion.
PW_MARKER="${STATE_DIR}/playwright-installed"
PW_CACHE="${PLAYWRIGHT_BROWSERS_PATH:-${HOME}/.cache/ms-playwright}"
if [[ ! -f "${PW_MARKER}" ]] || [[ -z "$(ls -A "${PW_CACHE}" 2>/dev/null || true)" ]]; then
  step "downloading Playwright Chromium (first run only)..."
  if (cd tests && npx --yes playwright install chromium); then
    touch "${PW_MARKER}"
  else
    warn "playwright browser download failed"
  fi
else
  skip "Playwright browser"
fi

step "ready."
