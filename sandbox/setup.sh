#!/usr/bin/env bash
# First-run / per-open setup for the project sandbox. Runs as the `dev` user with
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
# A step this project has nothing for - distinct from "up to date", which implies it applies here.
none()  { printf '\033[2m[setup] %s (not applicable)\033[0m\n' "$*"; }

cd /work || { warn "no /work mount"; exit 0; }

# ── 1. Git submodule (code-quality-config) — linters need it ─────────────────
if git submodule status 2>/dev/null | grep -q '^-'; then
  step "initialising git submodules (code-quality-config)..."
  git submodule update --init || warn "submodule init failed"
else
  skip "git submodules"
fi

# ── 2. npm install — for every project dir that actually HAS a package.json ──
# The dirs are a DEFAULT to look for, never an assumption: this script is meant to be
# copy-pasted into other projects, and one of them will have no Node side at all.
# Detection matters more than tidiness here - `npm --prefix X install` against a
# missing X still WRITES `X/package-lock.json` before it errors, so an unguarded
# install litters an unrelated project with a stray lockfile on its first open.
# Override with SANDBOX_NPM_DIRS (space-separated, relative to /work; "." = the root).
read -ra npm_dirs <<< "${SANDBOX_NPM_DIRS:-frontend .}"
npm_found=0
for dir in "${npm_dirs[@]}"; do
  [[ -f "${dir}/package.json" ]] || continue
  npm_found=1
  hash_file="${STATE_DIR}/npm-${dir//[^a-zA-Z0-9]/-}.sha"
  # Carry over the pre-per-dir state file so an existing sandbox doesn't reinstall once.
  if [[ "${dir}" == "frontend" && -f "${STATE_DIR}/package-lock.sha" && ! -f "${hash_file}" ]]; then
    mv "${STATE_DIR}/package-lock.sha" "${hash_file}"
  fi
  current_hash="$(sha256sum "${dir}/package-lock.json" 2>/dev/null | cut -d' ' -f1)"
  if [[ ! -d "${dir}/node_modules" ]] || [[ "${current_hash}" != "$(cat "${hash_file}" 2>/dev/null || true)" ]]; then
    step "running npm install in ${dir}..."
    if npm --prefix "${dir}" install --no-audit --no-fund; then
      echo "${current_hash}" > "${hash_file}"
    else
      warn "npm install failed in ${dir}"
    fi
  else
    skip "npm dependencies (${dir})"
  fi
done
[[ "${npm_found}" -eq 1 ]] || none "npm (no package.json)"

# ── 3. Playwright Chromium browser binary ────────────────────────────────────
# Only the browser BINARY is handled here: it is cached in the persistent
# `ms-playwright` volume, so it just needs downloading once (guarded by a marker +
# a non-empty cache). The OS shared libs (libnspr4, libnss3, …) it depends on are
# NOT handled here anymore — they are baked into the image via `playwright
# install-deps` in the Dockerfile, so as an image layer they survive container
# recreation and need no per-open re-assertion.
#
# Gated on the project DECLARING Playwright, not on a directory existing: `npx
# playwright` in a project that doesn't use it would download the package itself
# just to run it. Override the dirs with SANDBOX_PW_DIRS.
PW_MARKER="${STATE_DIR}/playwright-installed"
PW_CACHE="${PLAYWRIGHT_BROWSERS_PATH:-${HOME}/.cache/ms-playwright}"
read -ra pw_dirs <<< "${SANDBOX_PW_DIRS:-tests .}"
pw_dir=""
for dir in "${pw_dirs[@]}"; do
  if [[ -f "${dir}/package.json" ]] && grep -qE '"(@playwright/|playwright)' "${dir}/package.json"; then
    pw_dir="${dir}"
    break
  fi
done
if [[ -z "${pw_dir}" ]]; then
  none "Playwright (not a dependency)"
elif [[ ! -f "${PW_MARKER}" ]] || [[ -z "$(ls -A "${PW_CACHE}" 2>/dev/null || true)" ]]; then
  step "downloading Playwright Chromium (first run only)..."
  if (cd "${pw_dir}" && npx --yes playwright install chromium); then
    touch "${PW_MARKER}"
  else
    warn "playwright browser download failed"
  fi
else
  skip "Playwright browser"
fi

step "ready."
