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
bake js/actions.js        app.assets.js-actions-file
bake js/admin-users.js    app.assets.js-admin-file
bake js/admin-api-docs.js app.assets.js-api-docs-file
bake js/settings.js       app.assets.js-settings-file

# Settings preview thumbnails — base-name-keyed map (AppConfig.settingsImages / AppInfo.settingsImage).
bake img/settings/cal-nova-full-dark.webp      app.assets.settings-images.cal-nova-full-dark
bake img/settings/cal-nova-minimal-dark.webp   app.assets.settings-images.cal-nova-minimal-dark
bake img/settings/cal-nova-stacked-dark.webp   app.assets.settings-images.cal-nova-stacked-dark
bake img/settings/page-dyslexic-full-dark.webp app.assets.settings-images.page-dyslexic-full-dark
bake img/settings/page-nova-full-dark.webp     app.assets.settings-images.page-nova-full-dark
bake img/settings/page-nova-full-light.webp    app.assets.settings-images.page-nova-full-light
bake img/settings/page-nova-full-system.webp   app.assets.settings-images.page-nova-full-system
bake img/settings/page-standard-full-dark.webp app.assets.settings-images.page-standard-full-dark

# Top-level vector marks — base-name-keyed map (AppConfig.hashedImages / AppInfo.image). footer-mark has
# no current reference but is hashed too so every /img/*.svg is hashed (keeps the immutable filter exact).
bake img/wordmark.svg        app.assets.hashed-images.wordmark
bake img/wordmark-readme.svg app.assets.hashed-images.wordmark-readme
bake img/footer-mark.svg     app.assets.hashed-images.footer-mark
bake img/favicon.svg         app.assets.hashed-images.favicon
