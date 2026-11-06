#!/usr/bin/env bash
# Download the Chromium that the project's installed Playwright expects (runs as `dev` on an interactive
# open, with CWD = /work and the step/skip/warn/none helpers from ai-sandbox's setup.sh).
#
# Only the browser BINARY is handled here: it is cached in the persistent `pw` volume (sandbox.conf), and
# its OS shared libraries are baked into the image (this directory's Dockerfile). Each Playwright release
# expects its own browser revision, so the marker records the VERSION it last installed for: an upgrade
# downloads the new revision, and an unchanged version with a non-empty cache is a no-op.
#
# Gated on the project DECLARING Playwright, not on a directory existing. `--no-install` because a bare
# `npx playwright` with no node_modules would fetch whatever is newest, and with it a browser revision the
# project's own version cannot use: the dir has to be in SANDBOX_NPM_DIRS, which setup.sh runs first.
# Override the dirs with SANDBOX_PW_DIRS, in the ENV of this directory's Dockerfile.
set -uo pipefail

# shellcheck disable=SC2154  # STATE_DIR is exported by ai-sandbox's setup.sh, which runs this hook
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
  exit 0
fi

pw_version="$(cd "${pw_dir}" && npx --no-install playwright --version 2>/dev/null)"
pw_version="${pw_version#Version }"
if [[ -z "${pw_version}" ]]; then
  warn "${pw_dir} declares Playwright but has not installed it - is ${pw_dir} in SANDBOX_NPM_DIRS?"
  exit 0
fi

installed_for=""
if [[ -f "${PW_MARKER}" ]]; then
  installed_for="$(<"${PW_MARKER}")"
fi
cached="$(ls -A "${PW_CACHE}" 2>/dev/null || true)"
if [[ "${installed_for}" == "${pw_version}" && -n "${cached}" ]]; then
  skip "Playwright browser (${pw_version})"
else
  step "downloading the Chromium for Playwright ${pw_version}..."
  if (cd "${pw_dir}" && npx --no-install playwright install chromium); then
    printf '%s\n' "${pw_version}" > "${PW_MARKER}"
  else
    warn "playwright browser download failed"
  fi
fi
