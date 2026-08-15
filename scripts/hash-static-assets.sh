#!/usr/bin/env bash
#
# Content-hash the served static assets for cache-busting. Renames each asset to insert a 12-char content
# hash after its first name segment (app.css -> app.<hash>.css, htmx.min.js -> htmx.<hash>.min.js,
# page-nova-full-dark.webp -> page-nova-full-dark.<hash>.webp, wordmark.svg -> wordmark.<hash>.svg) and
# bakes the resulting filename into the build-time MicroProfile config that AppConfig/AppInfo read — so
# every template reference / <link> and the served file always agree. Because a new build yields a fresh
# URL only when an asset's bytes change, every hashed asset is served `immutable` (application.properties).
#
# This is the SINGLE place the image build hashes assets: it is invoked once from the Dockerfile's build
# stage. A non-Docker `mvn package` / dev run never runs it, so the config keys stay unset and the
# un-hashed default filenames / base names are served instead (dev serves those `no-store`).
#
# Assets deliberately NOT hashed (stable URLs, served with a bounded ceiling): the woff2 fonts (referenced
# by @font-face inside the compiled CSS), the raster app-icons (icon-192/512, apple-touch — the first two
# are pinned by manifest.json), /favicon.ico (browsers probe that fixed root path) and manifest.json.
#
# Usage: hash-static-assets.sh <resources-root> <config-file>
#   <resources-root>  path to src/main/resources/META-INF/resources
#   <config-file>     path to src/main/resources/META-INF/microprofile-config.properties (appended to)
set -euo pipefail

RES="${1:?resources root required}"
CONF="${2:?config file required}"

# Rename "$RES/$1" to insert its content hash after the FIRST '.'-segment (preserving the existing naming,
# e.g. htmx.min.js -> htmx.<hash>.min.js), then append "$2=<hashed-filename>" to the config file.
bake() {
  local rel="$1" key="$2"
  local dir base stem rest hash hashed
  dir="$(dirname "${rel}")"
  base="$(basename "${rel}")"
  stem="${base%%.*}"
  rest="${base#*.}"
  hash="$(sha256sum "${RES}/${rel}" | cut -c1-12)"
  hashed="${stem}.${hash}.${rest}"
  mv "${RES}/${rel}" "${RES}/${dir}/${hashed}"
  printf '\n%s=%s\n' "${key}" "${hashed}" >> "${CONF}"
}

# Compiled stylesheet + vendored/extracted scripts (fixed, one-off config keys AppConfig reads directly).
bake css/app.css          app.assets.css-file
bake js/htmx.min.js       app.assets.js-file
bake js/app.js            app.assets.js-app-file
bake js/dashboard.js      app.assets.js-dashboard-file
bake js/note.js           app.assets.js-note-file
bake js/actions.js        app.assets.js-actions-file
bake js/admin-users.js    app.assets.js-admin-file
bake js/admin-api-docs.js app.assets.js-api-docs-file
bake js/settings.js       app.assets.js-settings-file
bake js/stats.js          app.assets.js-stats-file

# Settings previews — base-name-keyed maps. Each preview exists TWICE under the same base name: the
# picker tile thumbnail at img/settings/<base>.webp (AppConfig.settingsImages / AppInfo.settingsImage)
# and the lightbox image at img/settings/full/<base>.webp (AppConfig.settingsFullImages /
# AppInfo.settingsFullImage). See generate-screenshots.cjs `writeShot` for why they are separate files.
# These are NOT committed: the image build generates them (the Dockerfile `screenshots` stage) and drops
# them in via the `previews` stage just before this script runs. Bake them when present; when absent
# (a GENERATE_PREVIEWS=false build — smoke/perf — copies an empty dir) simply skip them, and AppInfo
# falls back to the un-hashed `<base>.webp` name at runtime.
if [[ -f "${RES}/img/settings/page-nova-full-system.webp" ]]; then
  # Both sets or neither. A tiles-only directory would hash and boot cleanly, then 404 on every preview
  # the reader opens, so say what is wrong here rather than failing later on a bare sha256sum error.
  if [[ ! -f "${RES}/img/settings/full/page-nova-full-system.webp" ]]; then
    echo "✗ settings preview tiles are present but ${RES}/img/settings/full/ is missing or incomplete." >&2
    echo "  Each preview must be written twice (see generate-screenshots.cjs writeShot). A stale local" >&2
    echo "  img/settings/ from before the tile/full split will do this - delete it and rebuild." >&2
    exit 1
  fi
  for preview in cal-nova-full-dark cal-nova-minimal-dark cal-nova-stacked-dark \
                 page-dyslexic-full-dark page-nova-full-dark page-nova-full-light \
                 page-nova-full-system page-standard-full-dark; do
    bake "img/settings/${preview}.webp"      "app.assets.settings-images.${preview}"
    bake "img/settings/full/${preview}.webp" "app.assets.settings-full-images.${preview}"
  done
else
  echo "ℹ settings previews absent — skipping (a GENERATE_PREVIEWS=false build, e.g. smoke/perf)." >&2
fi

# Top-level vector marks — base-name-keyed map (AppConfig.hashedImages / AppInfo.image). footer-mark has
# no current reference but is hashed too so every /img/*.svg is hashed (keeps the immutable filter exact).
bake img/wordmark.svg        app.assets.hashed-images.wordmark
bake img/wordmark-readme.svg app.assets.hashed-images.wordmark-readme
bake img/footer-mark.svg     app.assets.hashed-images.footer-mark
bake img/favicon.svg         app.assets.hashed-images.favicon
