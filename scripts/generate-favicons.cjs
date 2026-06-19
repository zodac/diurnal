#!/usr/bin/env node
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
 * WHAT IT PRODUCES (all in the served img/ dir)
 * ---------------------------------------------
 *   favicon-16.png       16x16  — explicit small favicon PNG
 *   favicon-32.png       32x32  — explicit standard favicon PNG
 *   favicon.ico          16/32/48 multi-resolution ICO — legacy browser fallback
 *   apple-touch-icon.png 180x180 — iOS "add to home screen" / bookmark thumbnail
 * The transparent background of the SVG is preserved in every output.
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
const path = require('path');
const fs = require('fs');
const { execFileSync, execSync } = require('child_process');

const REPO = path.join(__dirname, '..');
const IMG = path.join(REPO, 'src', 'main', 'resources', 'META-INF', 'resources', 'img');
// Source SVG (the committed favicon.svg) and the served web root the rasters go into — the same dir,
// since favicon.svg is itself a generated, served asset (owned by generate-brand.py).
const SOURCE = process.env.SOURCE || path.join(IMG, 'favicon.svg');
const OUT = process.env.OUT || IMG;

// Target raster assets. The 48px PNG is an intermediate that only lives inside favicon.ico.
const PNGS = [
  { name: 'favicon-16.png', size: 16 },
  { name: 'favicon-32.png', size: 32 },
  { name: 'apple-touch-icon.png', size: 180 },
];
const ICO_SIZES = [16, 32, 48]; // packed into the multi-resolution favicon.ico

// ── Tool detection ─────────────────────────────────────────────────────────────────────────────

function has(cmd, args) {
  try { execFileSync(cmd, args, { stdio: 'ignore' }); return true; }
  catch { return false; }
}

// Prefer rsvg-convert (exact-size, no policy quirks); else fall back to ImageMagick (magick → convert).
const RSVG = has('rsvg-convert', ['--version']);
const IM = has('magick', ['--version']) ? 'magick' : (has('convert', ['--version']) ? 'convert' : null);
if (!RSVG && !IM) {
  throw new Error('Need rsvg-convert or ImageMagick to rasterise the SVG (install librsvg or imagemagick).');
}
if (!IM) {
  throw new Error('ImageMagick (magick/convert) is required to assemble favicon.ico (install imagemagick).');
}

// ── Rendering ────────────────────────────────────────────────────────────────────────────────────

// Rasterise the SVG to an exact size, preserving its transparent background.
function renderPng(size, outPath) {
  if (RSVG) {
    execFileSync('rsvg-convert', ['-w', String(size), '-h', String(size), '-o', outPath, SOURCE]);
  } else {
    // -background none keeps transparency; a high -density renders the 1254px artwork well above the
    // target so the -resize downscale is cleanly antialiased. -depth 8 keeps the output 8-bit (some
    // ImageMagick builds default to 16-bit here), matching rsvg-convert so both renderers agree.
    execFileSync(IM, ['-background', 'none', '-density', '384', SOURCE, '-resize', `${size}x${size}`, '-depth', '8', outPath]);
  }
  console.log(`rendered ${path.basename(outPath)} (${size}x${size})`);
}

function buildIco() {
  // Render each ICO size, pack them into one multi-resolution .ico, then drop the temp PNGs that are
  // not first-class outputs (16/32 are kept as standalone favicons; 48 only exists inside the .ico).
  const temps = ICO_SIZES.map(size => {
    const p = path.join(OUT, `favicon-${size}.png`);
    if (!fs.existsSync(p)) renderPng(size, p);
    return p;
  });
  const ico = path.join(OUT, 'favicon.ico');
  execFileSync(IM, [...temps, ico]);
  console.log(`packed favicon.ico (${ICO_SIZES.join('/')})`);
  // 48 is ICO-only — remove its standalone PNG.
  fs.rmSync(path.join(OUT, 'favicon-48.png'), { force: true });
}

// ── Lossless optimisation ──────────────────────────────────────────────────────────────────────

function hasOptipng() {
  try { execFileSync('optipng', ['--version'], { stdio: 'ignore' }); return true; }
  catch { return false; }
}

// Install optipng on demand (Alpine via apk, Debian/Ubuntu via apt-get) so it need not be preinstalled.
function installOptipng() {
  const sudo = process.getuid && process.getuid() !== 0 ? 'sudo ' : '';
  console.log('optipng not found — installing it…');
  if (has('apk', ['--version'])) execSync(`${sudo}apk add --no-cache optipng`, { stdio: 'inherit' });
  else execSync(`${sudo}apt-get update && ${sudo}apt-get install -y optipng`, { stdio: 'inherit' });
}

// Shrink every PNG losslessly (-o2 typically saves ~10%). Pixels are untouched, so appearance is identical.
function optimise() {
  if (!hasOptipng()) installOptipng();
  const files = fs.readdirSync(OUT).filter(f => f.endsWith('.png')).map(f => path.join(OUT, f));
  execFileSync('optipng', ['-quiet', '-o2', ...files], { stdio: 'inherit' });
  console.log(`optimised ${files.length} PNGs`);
}

// ── Main ───────────────────────────────────────────────────────────────────────────────────────

(() => {
  if (!fs.existsSync(SOURCE)) throw new Error(`favicon source not found: ${SOURCE} (run generate-brand.py first)`);
  fs.mkdirSync(OUT, { recursive: true });
  console.log(`source: ${SOURCE}\nout:    ${OUT}\nrenderer: ${RSVG ? 'rsvg-convert' : IM}\n`);

  for (const { name, size } of PNGS) renderPng(size, path.join(OUT, name));
  buildIco();
  optimise();

  console.log('\nDone — review and commit the assets in\n  ' + path.relative(path.join(__dirname, '..'), OUT));
})();
