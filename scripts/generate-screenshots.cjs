#!/usr/bin/env node
/* eslint-disable no-console -- CLI build script: progress output to the console is intended */
/**
 * Regenerates the project's screenshots from a seeded demo account. There are two independent sets,
 * selected by the mode argument (see USAGE), that used to be produced together but are now split:
 *
 *   1. `app`           — the Settings-page preview thumbnails (Theme / Calendar-style / Font pickers,
 *                        see settings.html -> partials/preview-option.html), written to
 *                        src/main/resources/META-INF/resources/img/settings/. These are the LIVE in-app
 *                        previews. They are NO LONGER COMMITTED: they are a build artifact, generated
 *                        INSIDE the Docker build (the Dockerfile `screenshots` stage runs this in `app`
 *                        mode via scripts/run-screenshot-build.sh) and baked into the image. A dev /
 *                        `mvn package` run simply has no thumbnails.
 *   2. `documentation` — the README screenshots, written to docs/screenshots/. These ARE committed and
 *                        are allowed to lag: regenerate them manually when a page's appearance changes.
 *
 * WHEN TO RUN THIS
 * ----------------
 *   - `app`: not by hand in the normal flow — the image build runs it for you. Run it manually only to
 *     eyeball the thumbnails locally (they land in img/settings/, gitignored).
 *   - `documentation`: whenever a README-visible page changes (dashboard/calendar styling, the
 *     Actions / Stats / Admin / Settings pages, the light/dark tokens, navbar/day-panel/layout). Then
 *     review and commit the WebP files under docs/screenshots/.
 *
 * WHAT IT PRODUCES
 * ----------------
 * `app` — 8 WebP files (web/desktop viewport) in img/settings/:
 *   page-nova-full-{light,dark,system}.webp       — Theme picker (Nova font, Full calendar)
 *   cal-nova-{full,minimal,stacked}-dark.webp      — Calendar picker (Nova font, dark)
 *   page-{nova,standard,dyslexic}-full-dark.webp   — Font picker (Full calendar, dark)
 *   (page-nova-full-dark is shared between the Theme-dark tile and the Font-nova tile.)
 *
 * `documentation` — 9 WebP files in docs/screenshots/, all captured dark / Full / Nova unless noted:
 *   dashboard-{system,dark,light}.webp             — dashboard banner + gallery (system = light/dark split)
 *   cal-{minimal,stacked}-dark.webp                — the two extra calendar styles shown in the README
 *   {actions,stats,admin,settings}-dark.webp       — the four page screenshots
 *
 * PREREQUISITES
 * -------------
 *   1. A running dev server (defaults to http://localhost:8081):
 *        scripts/dev-up.sh          # brings up diurnal-db-dev + quarkus:dev on 8081
 *   2. Playwright's browser binaries (already installed for the e2e suite):
 *        cd tests && npx playwright install
 *
 * USAGE
 * -----
 *   node scripts/generate-screenshots.cjs app             # in-app thumbnails only  (no DB access needed)
 *   node scripts/generate-screenshots.cjs documentation   # README screenshots only
 *   node scripts/generate-screenshots.cjs all             # both (default)
 *   BASE_URL=http://localhost:8080 node scripts/generate-screenshots.cjs app
 *
 * The script registers a dedicated demo user, seeds a fixed set of actions and logs over HTTP
 * (idempotent — safe to re-run), then drives a headless browser to capture each configuration. The
 * ONE exception to being HTTP-only: `documentation`/`all` connect to the dev DB (same config as
 * tests/helpers/db.ts, env-overridable) solely to grant the demo user the administrator role for the
 * Admin-page shot — there is no HTTP endpoint for that. `app` mode never touches the DB.
 */
const path = require('path')
const fs = require('fs')
const os = require('os')
const { execFileSync, execSync } = require('child_process')
// Reuse Playwright (and pg) from the tests/ workspace so this script needs no dependencies of its own.
const { chromium } = require(path.join(__dirname, '..', 'tests', 'node_modules', 'playwright'))
const { Client } = require(path.join(__dirname, '..', 'tests', 'node_modules', 'pg'))


const BASE = process.env.BASE_URL || 'http://localhost:8081'
// In-app preview thumbnails — served, content-hashed assets baked into the image (uncommitted).
const OUT = path.join(__dirname, '..', 'src', 'main', 'resources', 'META-INF', 'resources', 'img', 'settings')
// README page screenshots — NOT app-served assets, so they live under docs/ (committed), keeping the
// Docker image lean.
const SHOTS = path.join(__dirname, '..', 'docs', 'screenshots')

// Mode: which set(s) to generate. `app` = in-app thumbnails (OUT), `documentation` = README shots
// (SHOTS), `all` = both. Default `all` for a full local regen.
const MODE = (process.argv[2] || 'all').toLowerCase()
if (!['app', 'documentation', 'all'].includes(MODE)) {
  console.error(`Unknown mode "${MODE}". Use one of: app | documentation | all`)
  process.exit(2)
}
const wantApp = MODE === 'app' || MODE === 'all'
const wantDocs = MODE === 'documentation' || MODE === 'all'

// Direct DB access, used ONLY (in documentation/all mode) to promote the demo user to an administrator
// for the Admin-page screenshot (there is no HTTP endpoint to grant the admin role). Mirrors
// tests/helpers/db.ts — same dev DB, same env overrides. Everything else in this script is driven
// purely over HTTP.
const DB_CONFIG = {
  host: process.env.TEST_DB_HOST || 'localhost',
  port: Number(process.env.TEST_DB_PORT || 5432),
  user: process.env.TEST_DB_USER || 'diurnal_user',
  password: process.env.TEST_DB_PASSWORD || 'diurnal_password',
  database: process.env.TEST_DB_NAME || 'diurnal_db',
}
// Web capture viewport (full-page/element shots ignore the height). The width is deliberately wide
// enough that `.page-container` (width:75%, capped at --page-max-width = 1280px) reaches its cap, so
// the previews show the true widest desktop layout. At a narrower viewport the column shrinks and the
// day-panel (1/3 of the grid) gets cramped enough to ellipsis-truncate action names like "Exercise".
const VW = 1728, VH = 820

// Dedicated demo account — kept separate from real dev data.
const USER = { email: 'preview-demo@diurnal.local', password: 'preview_demo123', displayName: 'Test User' }

// The fixed seed: four colourful habits logged on days RELATIVE TO TODAY, so the captured calendar
// looks identical no matter which calendar date the script is run on (it was previously fixed
// days-of-month capped at today, which made the images depend on the run date). `daysAgo` are offsets
// back from today (0 = today, 1 = yesterday, …) — never future, so nothing is skipped; `count` is how
// many times each is incremented so the "full" calendar shows a representative "×N". The pattern spans
// the 15 days ending today (offsets 0..14): when today is mid-month this fills the visible month grid,
// and when today is early in the month the trail extends into the leading (previous-month) grid cells —
// either way the activity around the highlighted "today" cell is always the same.
const ACTIONS = [
  { name: 'Exercise', colour: '#ef4444', count: 1, daysAgo: [13, 11, 9, 6, 4, 2, 0] },
  { name: 'Read',     colour: '#3b82f6', count: 2, daysAgo: [14, 13, 12, 10, 7, 5, 3, 1] },
  { name: 'Meditate', colour: '#10b981', count: 1, daysAgo: [14, 13, 10, 8, 6, 3, 0] },
  { name: 'Water',    colour: '#f59e0b', count: 3, daysAgo: [14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0] },
]

// Extra accounts registered only to populate the Admin-page user table so its screenshot shows a
// realistic list (the demo user alone would be a one-row table). All plain 'user' role; the demo
// user above is the sole administrator (promoted via the DB below). Only seeded in documentation/all.
const ADMIN_DEMO_USERS = [
  { email: 'alex.rivera@diurnal.local',  password: 'preview_demo123', displayName: 'Alex Rivera' },
  { email: 'sam.chen@diurnal.local',     password: 'preview_demo123', displayName: 'Sam Chen' },
  { email: 'jordan.blake@diurnal.local', password: 'preview_demo123', displayName: 'Jordan Blake' },
  { email: 'priya.nair@diurnal.local',   password: 'preview_demo123', displayName: 'Priya Nair' },
]

const pad = n => String(n).padStart(2, '0')

// `base` minus `n` calendar days as a UTC `YYYY-MM-DD` string (n may be negative for future days).
// Date.UTC normalises an out-of-range day-of-month, so this rolls correctly across month/year edges.
//
// UTC is deliberate, and safe ONLY because the whole pipeline agrees on UTC: the seed dates here, the
// Playwright browser context (`timezoneId: 'UTC'`) and the seeded user's `timezone` preference (set to
// 'UTC' below) all match the app's own "today". The app computes "today" — the highlighted calendar
// cell (WebResource) and the future-date log guard (LogGuards) — via `clock.today(zoneFor(user.timezone))`,
// which falls back to `app.timezone` = `${TZ:UTC}`; that resolves to UTC by default and in the Docker
// `screenshots` stage (the app boots there with no TZ set), which is the path that produces the baked/
// committed previews. So script-UTC == app-UTC == browser-UTC and every seeded day lands where intended.
//
// The one way this desyncs is running the generator against an app whose `app.timezone`/`TZ` is a
// NON-UTC zone (e.g. a `dev-up.sh` box with `TZ` exported): the app's "today" becomes the local date
// while the seed keeps using the UTC date, so near midnight the newest day (offset 0 = UTC today) can
// be the app's "tomorrow". The increment API then 400s it as a future date and the seed silently skips
// it (the POST response is intentionally not checked), leaving the highlighted "today" cell unseeded.
// That only affects a manual non-UTC dev run — the CI/Docker previews are unaffected — so it is left
// as-is; if it ever needs fixing, derive "today" from the app (the dashboard's `data-today` attribute)
// rather than the local UTC instant.
const dateMinusDays = (base, n) => {
  const d = new Date(Date.UTC(base.getUTCFullYear(), base.getUTCMonth(), base.getUTCDate() - n))
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`
}

// ── Direct DB access (admin promotion only) ──────────────────────────────────────────────────────

// The users.role storage value for an administrator (net.zodac.diurnal.user.Role.Values.ADMIN),
// mirrored from tests/helpers/db.ts — a fixed schema contract, interpolated so the SQL string stays
// single-quoted.
const ROLE_ADMIN = 'admin'

async function withDb(fn) {
  const client = new Client(DB_CONFIG)
  await client.connect()
  try { return await fn(client) }
  finally { await client.end() }
}

// Grant the demo user the administrator role so the Admin page is reachable. Roles are read live per
// request (SessionIdentityProvider), so this takes effect on the already-authenticated session with no
// re-login. Deliberately does NOT demote any other admin — the least-invasive write against a dev DB.
async function promoteDemoUserToAdmin() {
  const res = await withDb(c => c.query(`UPDATE users SET role = '${ROLE_ADMIN}' WHERE email = $1`, [USER.email]))
  if (res.rowCount === 0) {throw new Error(`promoteDemoUserToAdmin: no user found with email ${USER.email}`)}
  console.log('promoted demo user to admin')
}

// Emails (lower-cased) already present in the users table, so re-runs don't re-POST /register for
// existing accounts — a duplicate registration is a *failed* attempt that feeds the per-IP throttle.
async function existingEmails(emails) {
  return withDb(async c => {
    const res = await c.query('SELECT email FROM users WHERE email = ANY($1)', [emails.map(e => e.toLowerCase())])
    return new Set(res.rows.map(r => r.email.toLowerCase()))
  })
}

// ── Seeding (over HTTP, via the logged-in browser-context cookies) ───────────────────────────────

async function registerDemoUser(ctx) {
  // The initial account MUST be created through the web setup flow (POST /register) — the API refuses
  // to register the first user until an account exists, so it can never claim the admin account. Once
  // this demo user exists the rest of the accounts can register via the API (see registerAdminDemoUsers).
  // Idempotent: on re-runs the account already exists, so the failure is expected and ignored.
  await ctx.request.post(`${BASE}/register`, {
    form: { email: USER.email, displayName: USER.displayName, password: USER.password, confirmPassword: USER.password }
  }).catch(() => {})
}

// Register the extra Admin-table demo accounts, skipping any that already exist (see existingEmails).
async function registerAdminDemoUsers(ctx) {
  const have = await existingEmails(ADMIN_DEMO_USERS.map(u => u.email))
  for (const user of ADMIN_DEMO_USERS) {
    if (have.has(user.email.toLowerCase())) {continue}
    await ctx.request.post(`${BASE}/api/v1/auth/register`, { data: user }).catch(() => {})
  }
}

async function login(ctx) {
  const page = await ctx.newPage()
  await page.goto(`${BASE}/login`)
  await page.fill('input[name="email"]', USER.email)
  await page.fill('input[name="password"]', USER.password)
  await Promise.all([page.waitForLoadState('networkidle'), page.click('button[type="submit"]')])
  await page.close()
}

// Map existing action name -> id by parsing the actions list (rows are `<tr id="action-{uuid}">`).
async function existingActions(ctx) {
  const html = await (await ctx.request.get(`${BASE}/internal/actions/list`)).text()
  const map = {}
  const re = /id="action-([0-9a-fA-F-]{36})"[\s\S]*?<span data-dt-view>([^<]+)<\/span>/g
  let m
  while ((m = re.exec(html))) {map[m[2].trim()] = m[1]}
  return map
}

async function ensureAction(ctx, existing, { name, colour }) {
  if (existing[name]) {return existing[name]}
  const res = await ctx.request.post(`${BASE}/internal/actions`, { form: { name, colour } })
  const id = (await res.text()).match(/id="action-([0-9a-fA-F-]{36})"/)
  if (!id) {throw new Error(`Could not create or locate action "${name}"`)}
  return id[1]
}

// Keys (`date|colour`) for logs already present in [start, end), so re-runs don't inflate counts.
async function existingLogKeys(ctx, start, end) {
  const events = await (await ctx.request.get(`${BASE}/api/v1/logs/events?start=${start}&end=${end}`)).json()
  return new Set(events.map(e => `${e.start}|${(e.backgroundColor || '').toLowerCase()}`))
}

async function seed(ctx) {
  await registerDemoUser(ctx)
  if (wantDocs) {
    // The Admin-page screenshot needs a populated user table and an admin session; app mode skips both
    // (and so needs no DB access at all).
    await registerAdminDemoUsers(ctx)
    await promoteDemoUserToAdmin()
  }
  await login(ctx)

  const now = new Date()
  // The seed window: from the oldest offset through today (the events `end` is exclusive, so pass
  // today + 1). Covers the whole range even when it straddles a month/year boundary.
  const maxAgo = Math.max(...ACTIONS.flatMap(a => a.daysAgo))
  const existing = await existingActions(ctx)
  const have = await existingLogKeys(ctx, dateMinusDays(now, maxAgo), dateMinusDays(now, -1))

  for (const action of ACTIONS) {
    const id = await ensureAction(ctx, existing, action)
    for (const daysAgo of action.daysAgo) {
      const date = dateMinusDays(now, daysAgo) // offsets are never future, so nothing is skipped
      if (have.has(`${date}|${action.colour.toLowerCase()}`)) {continue} // already logged
      for (let i = 0; i < action.count; i++) {
        await ctx.request.post(`${BASE}/api/v1/logs/${date}/${id}/increment`)
      }
    }
  }
  console.log('seeded demo data')
}

// ── Screenshot capture ───────────────────────────────────────────────────────────────────────────

async function setPrefs(ctx, theme, calendarView, font = 'nova') {
  // Preferences are updated in one shot via the consolidated PATCH /internal/settings endpoint (a
  // form-encoded body; returns 204). Only the fields this script cares about are sent — the resource
  // treats absent fields as "keep".
  const res = await ctx.request.fetch(`${BASE}/internal/settings`, {
    method: 'PATCH',
    form: { theme, font, calendarView, pageSize: '10', timezone: 'UTC' },
  })
  if (!res.ok()) {throw new Error(`setPrefs failed: ${res.status()}`)}
}

// Open the dashboard and wait until the chosen calendar style's activity markers are painted.
// Caller closes the page.
async function openDashboard(ctx, calendarView) {
  const page = await ctx.newPage()
  await page.goto(`${BASE}/`, { waitUntil: 'networkidle' })
  const sel = calendarView === 'full' ? '.d-full-event'
            : calendarView === 'stacked' ? '.d-stk-bar'
            : '.d-min-dot'
  await page.waitForSelector(sel, { timeout: 15000 })
  await page.waitForTimeout(600) // settle fonts/layout
  return page
}

// Playwright only supports PNG and JPEG screenshot types. We capture as PNG (lossless) then convert
// each buffer to lossless WebP via cwebp, which is typically 25-34% smaller than optipng PNG.
// cwebp is installed on demand (Debian/Ubuntu) the first time this function is called.
let _cwebpReady = false
function pngToLosslessWebp(pngBuf) {
  if (!_cwebpReady) {
    try { execFileSync('cwebp', ['-version'], { stdio: 'ignore' }) }
    catch {
      const sudo = process.getuid && process.getuid() !== 0 ? 'sudo ' : ''
      console.log('cwebp not found — installing via apt-get…')
      execSync(`${sudo}apt-get update -qq && ${sudo}apt-get install -y -qq webp`, { stdio: 'inherit' })
    }
    _cwebpReady = true
  }
  const tmp = path.join(os.tmpdir(), `diurnal-preview-${process.pid}-${Date.now()}.png`)
  fs.writeFileSync(tmp, pngBuf)
  try {
    // -lossless: pixel-perfect (no quality loss). -o -: write WebP to stdout.
    return execFileSync('cwebp', ['-lossless', '-quiet', tmp, '-o', '-'], { maxBuffer: 50 * 1024 * 1024 })
  } finally {
    fs.unlinkSync(tmp)
  }
}

// Read pixel dimensions from a PNG IHDR chunk (big-endian uint32 at offsets 16 and 20).
function pngSize(buf) {
  return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) }
}

// Theme preview: the WHOLE dashboard page (navbar, heading, calendar, day panel, stats) — fullPage
// captures the entire scroll height, not just the viewport. Returns the PNG buffer so the caller can
// pass it to compositeSystem for compositing.
async function shotFullPage(page, dir, file) {
  const pngBuf = await page.screenshot({ fullPage: true })
  fs.writeFileSync(path.join(dir, file), pngToLosslessWebp(pngBuf))
  console.log('wrote', file)
  return pngBuf // PNG for compositing — compositeSystem re-encodes the composite
}

// Calendar-style preview: ONLY the calendar. Every calendar style now shares the #calendar-wrap
// container (shared toolbar + the style's grid). We screenshot `#calendar-wrap` ITSELF, NOT its
// `.card` parent: the card adds a `rounded-2xl border` that would sit on the image edge and, inside
// the lightbox modal's own `rounded-lg border`, read as a spurious "double" rounded outline that the
// theme (full-page) shots don't have. Shooting the wrap keeps the calendar flush to the edge, so both
// pickers' full-size previews are framed identically. An element screenshot captures the whole element
// even where it overflows the viewport, so it is never cut off.
async function shotCalendar(page, dir, file) {
  const pngBuf = await page.locator('#calendar-wrap').screenshot()
  fs.writeFileSync(path.join(dir, file), pngToLosslessWebp(pngBuf))
  console.log('wrote', file)
}

// Full page screenshot at a URL (whole page), written to `dir`. Waits for the page's key content to
// render before the full-page capture.
async function shotPage(ctx, url, waitSelector, dir, file) {
  const page = await ctx.newPage()
  await page.goto(`${BASE}${url}`, { waitUntil: 'networkidle' })
  await page.waitForSelector(waitSelector, { timeout: 15000 })
  await page.waitForTimeout(600) // settle fonts/layout
  const pngBuf = await page.screenshot({ fullPage: true })
  fs.writeFileSync(path.join(dir, file), pngToLosslessWebp(pngBuf))
  console.log('wrote', file)
  await page.close()
}

// System theme = diagonal split of the light & dark dashboards (light upper-left, dark lower-right;
// divider runs corner-to-corner top-right → bottom-left). Receives PNG buffers (captured by
// shotFullPage) so no file reads are needed. The canvas matches the sources' actual pixel size.
async function compositeSystem(browser, { lightBuf, darkBuf, dir, out }) {
  const lightB64 = lightBuf.toString('base64')
  const darkB64  = darkBuf.toString('base64')
  const { w: PW, h: PH } = pngSize(lightBuf)
  const ctx = await browser.newContext({ deviceScaleFactor: 1 })
  const page = await ctx.newPage()
  await page.setViewportSize({ width: PW, height: PH })
  await page.setContent(`
    <body style="margin:0">
      <div id="cmp" style="position:relative;width:${PW}px;height:${PH}px;overflow:hidden">
        <img src="data:image/png;base64,${lightB64}" style="position:absolute;inset:0;width:${PW}px;height:${PH}px;display:block">
        <img src="data:image/png;base64,${darkB64}"  style="position:absolute;inset:0;width:${PW}px;height:${PH}px;display:block;
             clip-path:polygon(100% 0, 100% 100%, 0 100%)">
        <svg width="${PW}" height="${PH}" style="position:absolute;inset:0">
          <line x1="${PW}" y1="0" x2="0" y2="${PH}" stroke="#6366f1" stroke-width="3"/>
        </svg>
      </div>
    </body>`)
  await page.evaluate(() => Promise.all([...document.images].map(i => i.decode().catch(() => {}))))
  await page.waitForTimeout(200)
  const compositePng = await (await page.$('#cmp')).screenshot()
  fs.writeFileSync(path.join(dir, out), pngToLosslessWebp(compositePng))
  await ctx.close()
  console.log('wrote', out)
}

// ── Capture: in-app preview thumbnails (`app`) ─────────────────────────────────────────────────────

// Capture all 8 in-app thumbnails into OUT (img/settings/). Captures (in order):
//   page-nova-full-light    — Theme-light tile
//   page-nova-full-dark     — Theme-dark tile + Font-nova tile (shared image)
//   cal-nova-full-dark      — Calendar-full tile
//   cal-nova-minimal-dark   — Calendar-minimal tile
//   cal-nova-stacked-dark   — Calendar-stacked tile
//   page-standard-full-dark — Font-standard tile
//   page-dyslexic-full-dark — Font-OpenDyslexic tile
//   page-nova-full-system   — Theme-system tile (composite of light + dark PNGs)
async function captureAppPreviews(ctx, browser) {
  // Nova, full, light → Theme-light tile; store PNG for system composite
  await setPrefs(ctx, 'light', 'full', 'nova')
  const lightPage = await openDashboard(ctx, 'full')
  const lightBuf = await shotFullPage(lightPage, OUT, 'page-nova-full-light.webp')
  await lightPage.close()

  // Nova, full, dark → Theme-dark + Font-nova tiles; store PNG for system composite; cal-full-dark
  await setPrefs(ctx, 'dark', 'full', 'nova')
  const darkFullPage = await openDashboard(ctx, 'full')
  const darkBuf = await shotFullPage(darkFullPage, OUT, 'page-nova-full-dark.webp')
  await shotCalendar(darkFullPage, OUT, 'cal-nova-full-dark.webp')
  await darkFullPage.close()

  // Nova, minimal, dark → Calendar-minimal tile
  await setPrefs(ctx, 'dark', 'minimal', 'nova')
  const minPage = await openDashboard(ctx, 'minimal')
  await shotCalendar(minPage, OUT, 'cal-nova-minimal-dark.webp')
  await minPage.close()

  // Nova, stacked, dark → Calendar-stacked tile
  await setPrefs(ctx, 'dark', 'stacked', 'nova')
  const stkPage = await openDashboard(ctx, 'stacked')
  await shotCalendar(stkPage, OUT, 'cal-nova-stacked-dark.webp')
  await stkPage.close()

  // Standard, full, dark → Font-standard tile
  await setPrefs(ctx, 'dark', 'full', 'standard')
  const stdPage = await openDashboard(ctx, 'full')
  await shotFullPage(stdPage, OUT, 'page-standard-full-dark.webp')
  await stdPage.close()

  // OpenDyslexic, full, dark → Font-dyslexic tile
  await setPrefs(ctx, 'dark', 'full', 'dyslexic')
  const dysPage = await openDashboard(ctx, 'full')
  await shotFullPage(dysPage, OUT, 'page-dyslexic-full-dark.webp')
  await dysPage.close()

  // System composite (light upper-left, dark lower-right) → Theme-system tile
  await compositeSystem(browser, { lightBuf, darkBuf, dir: OUT, out: 'page-nova-full-system.webp' })
}

// ── Capture: README documentation screenshots (`documentation`) ────────────────────────────────────

// Capture the README screenshots into SHOTS (docs/screenshots/):
//   dashboard-{system,dark,light}  — the banner + gallery dashboards (system = light/dark split)
//   cal-{minimal,stacked}-dark     — the two extra calendar styles shown in the README gallery
//   {actions,stats,admin,settings}-dark — the four page screenshots
// All in one fixed configuration (dark / Full / Nova, default uncustomised stats order) except the
// light + system dashboards which drive the Theme banner.
async function captureDocsScreenshots(ctx, browser) {
  // Nova, full, light → the light dashboard; store PNG for the system composite.
  await setPrefs(ctx, 'light', 'full', 'nova')
  const lightPage = await openDashboard(ctx, 'full')
  const lightBuf = await shotFullPage(lightPage, SHOTS, 'dashboard-light.webp')
  await lightPage.close()

  // Nova, full, dark → the dark dashboard; store PNG for the system composite.
  await setPrefs(ctx, 'dark', 'full', 'nova')
  const darkPage = await openDashboard(ctx, 'full')
  const darkBuf = await shotFullPage(darkPage, SHOTS, 'dashboard-dark.webp')
  await darkPage.close()

  // System composite (light upper-left, dark lower-right) → the README banner.
  await compositeSystem(browser, { lightBuf, darkBuf, dir: SHOTS, out: 'dashboard-system.webp' })

  // The two extra calendar styles the README shows.
  await setPrefs(ctx, 'dark', 'minimal', 'nova')
  const minPage = await openDashboard(ctx, 'minimal')
  await shotCalendar(minPage, SHOTS, 'cal-minimal-dark.webp')
  await minPage.close()

  await setPrefs(ctx, 'dark', 'stacked', 'nova')
  const stkPage = await openDashboard(ctx, 'stacked')
  await shotCalendar(stkPage, SHOTS, 'cal-stacked-dark.webp')
  await stkPage.close()

  // The four page screenshots (dark / Full / Nova). setPrefs pins theme/font/calendar; the demo user
  // never customises statsFields, so the Stats page renders every tile in its default order.
  await setPrefs(ctx, 'dark', 'full', 'nova')
  await shotPage(ctx, '/actions',     '#action-list .dt-row',        SHOTS, 'actions-dark.webp')
  await shotPage(ctx, '/stats',       '#stats-list .card',           SHOTS, 'stats-dark.webp')
  await shotPage(ctx, '/admin/users', '#admin-users-list .dt-table', SHOTS, 'admin-dark.webp')
  await shotPage(ctx, '/settings',    '#prefs-form',                 SHOTS, 'settings-dark.webp')
}

// ── Main ───────────────────────────────────────────────────────────────────────────────────────

(async () => {
  if (wantApp) {fs.mkdirSync(OUT, { recursive: true })}
  if (wantDocs) {fs.mkdirSync(SHOTS, { recursive: true })}
  // Extra Chromium flags via PW_CHROMIUM_ARGS (space-separated). Used by the in-image build
  // (Dockerfile screenshots stage) to pass `--no-sandbox`, since Chromium refuses to run as root
  // without it; a normal local run leaves it empty (sandbox on).
  const launchArgs = (process.env.PW_CHROMIUM_ARGS || '').split(' ').filter(Boolean)
  const browser = await chromium.launch({ args: launchArgs })

  const ctx = await browser.newContext({
    viewport: { width: VW, height: VH },
    deviceScaleFactor: 2,
    timezoneId: 'UTC',
    colorScheme: 'light',
  })
  await seed(ctx)
  if (wantApp) {await captureAppPreviews(ctx, browser)}
  if (wantDocs) {await captureDocsScreenshots(ctx, browser)}
  await ctx.close()

  await browser.close()

  const rel = d => path.relative(path.join(__dirname, '..'), d)
  const dirs = [wantApp ? rel(OUT) : null, wantDocs ? rel(SHOTS) : null].filter(Boolean)
  console.log(`\nDone (${MODE}) — WebP files written to\n  ${dirs.join('\n  ')}`)
})().catch(e => { console.error(e); process.exit(1) })
