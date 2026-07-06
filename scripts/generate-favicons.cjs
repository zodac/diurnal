#!/usr/bin/env node
/* eslint-disable no-console -- CLI build script: progress output to the console is intended */
/**
 * Regenerates the raster favicon / icon assets from the favicon SVG (the single-letter "d" mark).
 *
 * WHEN TO RUN THIS
 * ----------------
 * The source is the served favicon.svg — itself generated from the brand font by
 * scripts/generate-brand.py (run that FIRST if the mark changed). This script only rasterises it:
 * it writes the PNG/ICO favicons next to it in the served static web root
 * (src/main/resources/META-INF/resources/img/), mirroring how the CSS is compiled from src/main/css
 * into resources/css. Re-run it whenever favicon.svg changes; then review and commit the regenerated
 * files. Nothing references them by anything but name. (It does NOT touch favicon.svg/wordmark.svg —
 * those committed SVGs are the source of truth, owned by generate-brand.py.)
 *
 * The Docker image regenerates the rasters on demand in a dedicated, non-runtime `icons` stage (see
 * the Dockerfile) from the committed favicon.svg, so the committed PNG/ICO copies exist only for
 * local/dev convenienc. They are visually identical but may differ by a few antialiasing pixels
 * across ImageMagick / librsvg versions, harmless since the build overwrites them.
 *
 * WHAT IT PRODUCES (minimal set — every file has a distinct purpose; no redundant sizes)
 * ----------------------------------------------------------------------------------------
 *   favicon.ico          16/32/48 multi-resolution ICO at the web ROOT (/favicon.ico, not /img/) — the
 *                        single legacy raster fallback + the conventional /favicon.ico probe path. The
 *                        16/32/48 PNGs are rendered only as throwaway temps to pack this; they are NOT
 *                        kept as standalone files (the SVG covers crisp desktop rendering already).
 *   img/apple-touch-icon.png 180x180 — iOS "add to home screen" / bookmark thumbnail
 *   img/icon-192.png     192x192 — load-bearing: Chromium-on-Android (Chrome/Opera) picks the widest PNG
 *                        <link rel=icon> ≤192 for the tab; without it Opera Mobile shows a globe.
 *   img/icon-512.png     512x512 — web-manifest PWA icon (paired with 192 per Chromium installability).
 * The favicon.svg itself (the vector icon, used by desktop browsers) is committed, not produced here.
 * The transparent background of the SVG is preserved in every raster output.
 *
 * PREREQUISITES
 * -------------
 * A renderer for SVG plus tools to assemble the ICO and optimise the PNGs. The script auto-detects
 * what is available and only needs ONE of the rasterisers:
 *   - rsvg-convert (librsvg) — preferred, exact-size and crisp; OR
 *   - ImageMagick (`magick`/`convert`) — fallback, renders the SVG at high density then downscales.
 * ImageMagick is also used to pack the multi-resolution .ico (from PNGs, so no SVG support needed for
 * that step), and optipng losslessly shrinks the PNGs. Missing optipng is installed on demand
 * (apk on Alpine, apt-get on Debian/Ubuntu) so neither the host nor the runtime image needs it.
 *
 * USAGE
 * -----
 *   node scripts/generate-favicons.cjs                 # uses the served favicon.svg
 *   SOURCE=/path/to/other.svg node scripts/generate-favicons.cjs
 *   OUT=/some/dir node scripts/generate-favicons.cjs   # override the served output dir
 */
const path = require('path')
const fs = require('fs')
const { execFileSync, execSync } = require('child_process')

const REPO = path.join(__dirname, '..')
const IMG = path.join(REPO, 'src', 'main', 'resources', 'META-INF', 'resources', 'img')
// Source SVG (the committed favicon.svg) and the served web root the rasters go into — the same dir,
// since favicon.svg is itself a generated, served asset (owned by generate-brand.py).
const SOURCE = process.env.SOURCE || path.join(IMG, 'favicon.svg')
const OUT = process.env.OUT || IMG

// First-class standalone raster outputs (each with a distinct purpose — see the header).
// The 16/32/48 sizes are NOT here: they exist only inside favicon.ico (rendered as temps in buildIco).
const PNGS = [
  { name: 'apple-touch-icon.png', size: 180 }, // iOS home-screen / bookmark thumbnail
  { name: 'icon-192.png', size: 192 },         // Android/Opera tab icon (the load-bearing one) + manifest 192
  { name: 'icon-512.png',  size: 512 },        // manifest PWA icon (paired with 192)
]
const ICO_SIZES = [16, 32, 48] // rendered as throwaway temps, packed into the multi-resolution favicon.ico

// ── Tool detection ─────────────────────────────────────────────────────────────────────────────

function has(cmd, args) {
  try { execFileSync(cmd, args, { stdio: 'ignore' }); return true }
  catch { return false }
}

// Prefer rsvg-convert (exact-size, no policy quirks); else fall back to ImageMagick (magick → convert).
const RSVG = has('rsvg-convert', ['--version'])
const IM = has('magick', ['--version']) ? 'magick' : (has('convert', ['--version']) ? 'convert' : null)
if (!RSVG && !IM) {
  throw new Error('Need rsvg-convert or ImageMagick to rasterise the SVG (install librsvg or imagemagick).')
}
if (!IM) {
  throw new Error('ImageMagick (magick/convert) is required to assemble favicon.ico (install imagemagick).')
}

// ── Rendering ────────────────────────────────────────────────────────────────────────────────────

// Rasterise the SVG to an exact size, preserving its transparent background.
function renderPng(size, outPath) {
  if (RSVG) {
    execFileSync('rsvg-convert', ['-w', String(size), '-h', String(size), '-o', outPath, SOURCE])
  } else {
    // -background none keeps transparency; a high -density renders the 1254px artwork well above the
    // target so the -resize downscale is cleanly antialiased. -depth 8 keeps the output 8-bit (some
    // ImageMagick builds default to 16-bit here), matching rsvg-convert so both renderers agree.
    execFileSync(IM, ['-background', 'none', '-density', '384', SOURCE, '-resize', `${size}x${size}`, '-depth', '8', outPath])
  }
  console.log(`rendered ${path.basename(outPath)} (${size}x${size})`)
}

function buildIco() {
  // Render the ICO sizes as throwaway temps (they are not standalone outputs), pack them into the
  // multi-resolution .ico, then delete the temps. Output to the web root (one dir above OUT/img/),
  // not /img/, so a conventional /favicon.ico probe (and legacy browsers) find it.
  const temps = ICO_SIZES.map(size => {
    const p = path.join(OUT, `favicon-${size}.png`)
    renderPng(size, p)
    return p
  })
  const ico = path.join(OUT, '..', 'favicon.ico')
  execFileSync(IM, [...temps, ico])
  console.log(`packed favicon.ico (${ICO_SIZES.join('/')})`)
  for (const p of temps) {fs.rmSync(p, { force: true })}
}

// ── Lossless optimisation ──────────────────────────────────────────────────────────────────────

function hasOptipng() {
  try { execFileSync('optipng', ['--version'], { stdio: 'ignore' }); return true }
  catch { return false }
}

// Install optipng on demand (Alpine via apk, Debian/Ubuntu via apt-get) so it need not be preinstalled.
function installOptipng() {
  const sudo = process.getuid && process.getuid() !== 0 ? 'sudo ' : ''
  console.log('optipng not found — installing it…')
  if (has('apk', ['--version'])) {execSync(`${sudo}apk add --no-cache optipng`, { stdio: 'inherit' })}
  else {execSync(`${sudo}apt-get update && ${sudo}apt-get install -y optipng`, { stdio: 'inherit' })}
}

// Shrink every PNG losslessly (-o2 typically saves ~10%). Pixels are untouched, so appearance is identical.
function optimise() {
  if (!hasOptipng()) {installOptipng()}
  const files = fs.readdirSync(OUT).filter(f => f.endsWith('.png')).map(f => path.join(OUT, f))
  execFileSync('optipng', ['-quiet', '-o2', ...files], { stdio: 'inherit' })
  console.log(`optimised ${files.length} PNGs`)
}

// ── Main ───────────────────────────────────────────────────────────────────────────────────────

(() => {
  if (!fs.existsSync(SOURCE)) {throw new Error(`favicon source not found: ${SOURCE} (run generate-brand.py first)`)}
  fs.mkdirSync(OUT, { recursive: true })
  console.log(`source: ${SOURCE}\nout:    ${OUT}\nrenderer: ${RSVG ? 'rsvg-convert' : IM}\n`)

  for (const { name, size } of PNGS) {renderPng(size, path.join(OUT, name))}
  buildIco()
  optimise()

  console.log(`\nDone — review and commit the assets in\n  ${  path.relative(path.join(__dirname, '..'), OUT)}`)
})()
