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
 * `app` — 8 previews (web/desktop viewport), each written TWICE, in img/settings/:
 *   page-nova-full-{light,dark,system}.webp       — Theme picker (Nova font, Full calendar)
 *   cal-nova-{full,minimal,stacked}-dark.webp      — Calendar picker (Nova font, dark)
 *   page-{nova,standard,dyslexic}-full-dark.webp   — Font picker (Full calendar, dark)
 *   (page-nova-full-dark is shared between the Theme-dark tile and the Font-nova tile.)
 *
 *   img/settings/<name>.webp       — the picker TILE thumbnail, loaded on every Settings page view
 *   img/settings/full/<name>.webp  — the LIGHTBOX image, fetched only when a preview is opened
 *
 *   Same base name in both, so one config key maps to the pair. See writeShot/tilePreviewWidth for the
 *   sizing, and note the rule that every derived width must divide the capture exactly.
 *
 * `documentation` — 17 WebP files in docs/screenshots/, all captured dark / Full / Nova unless noted:
 *   dashboard-{system,dark,light}.webp             — dashboard banner + theme pair (system = light/dark split)
 *   dashboard-mobile.webp                          — dashboard at a phone viewport (minimal calendar), same light/dark
 *                                                    diagonal split as -system (viewport-only shots, not fullPage)
 *   cal-{full,minimal,stacked}-dark.webp           — the three calendar styles, all framed as #calendar-wrap
 *   dashboard-arabic-dark.webp                     — the whole dashboard page again, in Arabic (RTL demonstration
 *                                                    for the README's Languages section, full-page like dashboard-dark)
 *   language-dropdown-dark.webp                    — the Settings language picker, OPEN (crop capture of the
 *                                                    filter box and option list, English selected)
 *   {actions,stats,admin,settings}-dark.webp       — the four page screenshots
 *   stats-graph-dark.webp                          — the Stats page's frequency-graph modal, three actions
 *                                                    compared over one month (element shot of the dialog panel)
 *   note-box-dark.webp                             — the dashboard's note box holding the featured multi-paragraph
 *                                                    note (element shot of #note-panel)
 *   stats-notes-dark.webp                          — the Notes card on the Stats page, i.e. notes treated as a
 *                                                    statistics subject (element shot, located by subject name)
 *   login-dark.webp                                — the login page (logged-OUT, own context; shows password
 *                                                    + OIDC sign-in, so needs the OIDC preview boot below)
 *
 * PREREQUISITES
 * -------------
 *   1. A running dev server (defaults to http://localhost:8081):
 *        scripts/dev-up.sh                 # brings up diurnal-db-dev + quarkus:dev on 8081
 *      For `documentation`/`all`, the login shot (login-dark.webp) shows BOTH password and OIDC
 *      sign-in, so boot with OIDC enabled against a DUMMY IdP (the issuer is never contacted — the
 *      button renders from the OIDC_ENABLED flag alone; OIDC_VERIFY_ON_STARTUP=false so the startup
 *      discovery probe does not fail the boot on the unreachable dummy issuer):
 *        OIDC_PREVIEW=1 scripts/dev-up.sh  # same boot, plus a throwaway OIDC_* config
 *      shotLoginPage() throws if that button is missing, rather than committing a password-only shot.
 *   2. A Chromium that the tests/ Playwright can drive. Any installed Chromium build works — the
 *      launcher (resolveChromiumExecutable) reuses whatever is in the Playwright browser cache, so a
 *      minor Playwright-package bump does NOT force a re-download just to take a local screenshot. Only
 *      an empty cache needs the one-off install:
 *        cd tests && npx playwright install chromium
 *
 * USAGE
 * -----
 *   node scripts/generate-screenshots.cjs app             # in-app thumbnails only  (no DB access needed)
 *   node scripts/generate-screenshots.cjs documentation   # README screenshots only
 *   node scripts/generate-screenshots.cjs all             # both (default)
 *   BASE_URL=http://localhost:8080 node scripts/generate-screenshots.cjs app
 *
 * THE SEEDED MONTH
 * ----------------
 * All demo history is seeded into the LAST COMPLETE calendar month, and every shot is framed on it. The
 * current month is deliberately left empty: it is only ever filled up to today, so seeding it would make
 * the images depend on the run date (a run on the 3rd would produce a nearly-empty calendar and a
 * three-bar frequency graph; a run on the 28th a full one). Last month is always complete and always the
 * same shape, whenever the script runs.
 *
 * The month's length (28 / 29 / 30 / 31) falls out of the calendar itself — `Date.UTC(y, m, 0)` is the
 * last day of the previous month — so leap Februaries need no special case. Each action is logged by
 * WEEKDAY (see ACTIONS), so the pattern tiles across a month of any length and gives the frequency graph
 * a weekly rhythm rather than a flat row of identical bars.
 *
 * Because the app always opens the dashboard on TODAY, which is in the (empty) current month, the shots
 * that show a calendar step back a month and select its last day first (`showSeededMonth`), and the
 * frequency-graph shot steps its window back to the same month. Neither relies on a default view.
 *
 * The script registers a dedicated demo user, seeds a fixed set of actions and logs over HTTP
 * (idempotent — safe to re-run: the day counts are SET with PUT, not incremented towards), then drives a
 * headless browser to capture each configuration. The
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

// Phone capture viewport, for the mobile dashboard shot the README pairs with the desktop one. 390px
// is narrow enough to put every responsive breakpoint into its mobile form (the navbar collapses to
// the hamburger, the calendar/day-panel grid stacks). Unlike every other shot this one is captured
// viewport-only rather than fullPage, so the height is meaningful: it frames the image like a real
// phone screen instead of a very tall, very narrow strip of the whole scrolled page.
const MOBILE_VW = 390, MOBILE_VH = 844

// Dedicated demo account — kept separate from real dev data.
const USER = { email: 'preview-demo@diurnal.local', password: 'preview_demo123', displayName: 'Test User' }

// The fixed seed: four colourful habits, logged by WEEKDAY rather than as a hand-listed set of days.
// Each entry is a Sunday-first array of counts — index 0 = Sunday … 6 = Saturday, 0 meaning "not logged
// that weekday" — tiled across the whole seeded range. That keeps the pattern independent of the run
// date AND of the month's length, so it fills a 28-, 30- or 31-day month equally without any per-month
// bookkeeping, and gives the frequency graph a visible weekly rhythm instead of a flat row of identical
// bars (the counts deliberately vary day to day, so the chart has a real shape).
//
// The seeded RANGE is the whole of last month plus the current month up to today (see SEED_START). Last
// month is complete, which is what the frequency-graph screenshot frames; the current-month tail is what
// keeps the dashboard calendar shots populated around the highlighted "today" cell.
const ACTIONS = [
  // Mon/Wed/Fri, with a longer Saturday session.
  { name: 'Exercise', colour: '#ef4444', perWeekday: [0, 1, 0, 2, 0, 1, 3] },
  // Every day, and more of it at the weekend.
  { name: 'Read',     colour: '#3b82f6', perWeekday: [3, 1, 2, 1, 2, 1, 4] },
  // Most mornings, but not every one.
  { name: 'Meditate', colour: '#10b981', perWeekday: [1, 1, 1, 0, 1, 1, 0] },
  // Every day, several times a day.
  { name: 'Water',    colour: '#f59e0b', perWeekday: [6, 8, 7, 8, 7, 8, 5] },
]

// The note written on each weekday of the seeded month (Sunday-first, matching ACTIONS[].perWeekday). An
// empty string means "no note that day", which is what gives the calendar a mix of marked and unmarked
// days rather than a solid block of green.
// The note on the last day of the seeded month — the day every dashboard screenshot has selected, so this
// is the text the note box actually shows. Deliberately two paragraphs, to show that a note keeps the shape
// it was written in.
const NOTE_FEATURED = 'Rounded the month off well. Every target hit except Thursday, which I am not going to\nworry about.\n\nNext month: keep the earlier start, and try moving the long session to Saturday.'

const NOTE_BY_WEEKDAY = [
  'Rest day. Long walk after lunch, then read on the balcony until it got cold.',
  'Back to it. Morning session felt heavy but the last set came together.',
  '',
  'Good day - hit every target before lunch and still had energy left over.',
  'Short one. Tired, so I kept it easy rather than skipping altogether.',
  '',
  'Best session this week.\n\nWorth remembering what made the difference: earlier start, and no screen the night before.'
]

// A per-week nudge applied on top of `perWeekday`, cycling through the month. Without it the weekday
// tiling makes every week a carbon copy of the last — which reads as obviously fabricated in the
// calendar shots, where four identical rows of "Water x8, Exercise, Meditate, Read" sit side by side.
// It is a fixed cycle rather than anything random so the images stay reproducible run to run.
//
// The result is clamped at zero, so a nudge of -1 drops an action that only logs once on that weekday.
// That is deliberate: it puts occasional real gaps in the history, which is what gives the streak and
// gap tiles something other than a perfect, unbroken month to report.
const WEEK_NUDGE = [0, 1, -1, 2]

// Extra accounts registered only to populate the Admin-page user table so its screenshot shows a
// realistic list (the demo user alone would be a one-row table). All plain 'user' role; the demo
// user above is the sole administrator (promoted via the DB below). Only seeded in documentation/all.
const ADMIN_DEMO_USERS = [
  { email: 'alex.rivera@diurnal.local',  password: 'preview_demo123', displayName: 'Alex Rivera' },
  { email: 'sam.chen@diurnal.local',     password: 'preview_demo123', displayName: 'Sam Chen' },
  { email: 'jordan.blake@diurnal.local', password: 'preview_demo123', displayName: 'Jordan Blake' },
  { email: 'priya.nair@diurnal.local',   password: 'preview_demo123', displayName: 'Priya Nair' },
]

// Demo IP lockouts shown in the Admin page's IP-lockout table (documentation/all only), so the shot
// showcases the feature. A representative mix of the three statuses - one Active (still in force), one
// Expired (ran its course) and one admin-Unlocked - using RFC 5737 documentation IP ranges (never real
// addresses). Timestamps are DB `NOW()`-relative so every row stays inside the 7-day retention window on
// any run; the failure tally is the 15-attempt default. Seeded directly (there is no HTTP endpoint to mint
// history rows) and idempotently (the fixed demo IPs are cleared first). `unlocked_by` is the demo admin.
// `locked_at` values are spread so the table renders most-recent-first with the Active row on top. These
// are trusted constant SQL expressions (no user input), so they are interpolated into the INSERT directly.
// make_interval(...) is used instead of INTERVAL '…' so these constants carry no inner single quotes.
const DEMO_LOCKOUTS = [
  // Active: locked 4 min ago, still in force (expires in 11 min) - offers the manual unlock.
  { ip: '203.0.113.42',  lockedAt: 'NOW() - make_interval(mins => 4)',  lockedUntil: 'NOW() + make_interval(mins => 11)',   unlockedAt: 'NULL' },
  // Expired: locked 3 h ago, ran its full course.
  { ip: '198.51.100.23', lockedAt: 'NOW() - make_interval(hours => 3)', lockedUntil: 'NOW() - make_interval(mins => 165)',  unlockedAt: 'NULL' },
  // Unlocked: an admin cleared it ~27 h ago, shortly after it tripped.
  { ip: '192.0.2.15',    lockedAt: 'NOW() - make_interval(hours => 27)', lockedUntil: 'NOW() - make_interval(mins => 1605)', unlockedAt: 'NOW() - make_interval(mins => 1610)' },
  // Expired: locked 3 days ago (still within the retention window).
  { ip: '203.0.113.88',  lockedAt: 'NOW() - make_interval(days => 3)',  lockedUntil: 'NOW() - make_interval(mins => 4305)', unlockedAt: 'NULL' },
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

// ── The seeded month ─────────────────────────────────────────────────────────────────────────────
//
// Everything is seeded into the LAST COMPLETE calendar month, and the shots are framed on it. Using
// last month rather than the current one is what makes the images stable and complete: the current
// month is only ever filled up to today, so a run on the 3rd would produce a nearly-empty calendar and
// a frequency graph with three bars, while a run on the 28th would produce a full one.
//
// `Date.UTC(year, month, 0)` is day zero of the current month, i.e. the last day of the previous one,
// so the length (28 / 29 / 30 / 31) falls out of the calendar itself — no month-length table, and leap
// Februaries are correct for free.
const SEED_END = new Date(Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth(), 0))
// The month has this many days, and the anchor IS its last day, so offsets 0..SEED_DAYS-1 back from it
// cover the month exactly.
const SEED_DAYS = SEED_END.getUTCDate()
const SEED_END_ISO = dateMinusDays(SEED_END, 0)
const SEED_START_ISO = dateMinusDays(SEED_END, SEED_DAYS - 1)
const SEED_MONTH = SEED_END_ISO.slice(0, 7)

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

// Seed the demo IP-lockout history rows (see DEMO_LOCKOUTS) so the Admin page's lockout table has content
// to render. The live throttle is enabled by default, so the running app shows these rows; the manual
// unlock in the UI still keys on the in-memory throttle (not seeded here), which is irrelevant to a static
// screenshot. Idempotent: clears the fixed demo IPs first, then re-inserts with fresh NOW()-relative times.
async function seedIpLockouts() {
  await withDb(async c => {
    await c.query('DELETE FROM ip_lockouts WHERE ip_address = ANY($1)', [DEMO_LOCKOUTS.map(l => l.ip)])
    for (const l of DEMO_LOCKOUTS) {
      await c.query(
        `INSERT INTO ip_lockouts (ip_address, locked_at, locked_until, failure_count, unlocked_at, unlocked_by)
         VALUES ($1, ${l.lockedAt}, ${l.lockedUntil}, 15, ${l.unlockedAt}, $2)`,
        [l.ip, l.unlockedAt === 'NULL' ? null : USER.email],
      )
    }
  })
  console.log('seeded demo IP lockouts')
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
  // Submitting posts the form and (on success) 303-redirects to the dashboard, which sets the session
  // cookie on `ctx`. We wait only for the navigation to LEAVE /login — NOT for the dashboard to go
  // network-idle: its calendar background-prefetches neighbouring months, so `networkidle` never
  // settles and would time out. A stuck /login means a failed login, surfaced as this wait timing out.
  await Promise.all([
    page.waitForURL(url => !new URL(url).pathname.startsWith('/login'), { waitUntil: 'commit', timeout: 15000 }),
    page.click('button[type="submit"]'),
  ])
  await page.close()
}

// Map existing action name -> id, read from the PUBLIC API rather than scraped out of the rendered table.
//
// This used to parse `/internal/actions/list` with a regex over `<tr id="action-{uuid}">` … `<span
// data-dt-view>`. That regex went stale the moment the name span gained a class (`<span data-dt-view
// class="dt-text-inset">`) and silently matched nothing — so every action looked missing, ensureAction
// tried to create one that already existed, got a 409 carrying a banner instead of a row, and the run died
// with "Could not create or locate action". It only ever showed up on a re-run, because against a fresh
// database there is nothing to find and the create path works. Reading JSON has no markup to go stale.
//
// The list is paginated by the demo user's own page-size preference, so page through it rather than
// assuming one page holds every action.
async function existingActions(ctx) {
  const map = {}
  for (let page = 1; ; page++) {
    const res = await ctx.request.get(`${BASE}/api/v1/actions?page=${page}`)
    if (!res.ok()) {throw new Error(`Could not list actions (HTTP ${res.status()})`)}
    const body = await res.json()
    for (const action of body.items) {map[action.name] = action.id}
    if (page >= body.totalPages) {return map}
  }
}

async function ensureAction(ctx, existing, { name, colour }) {
  if (existing[name]) {return existing[name]}
  const res = await ctx.request.post(`${BASE}/api/v1/actions`, { data: { name, colour } })
  // The status is part of the message on purpose: a 409 means the action exists but the listing above did
  // not see it, which is a different fault from a 400/401 and should not be guessed at.
  if (!res.ok()) {throw new Error(`Could not create action "${name}" (HTTP ${res.status()})`)}
  return (await res.json()).id
}

async function seed(ctx) {
  await registerDemoUser(ctx)
  if (wantDocs) {
    // The Admin-page screenshot needs a populated user table and an admin session; app mode skips both
    // (and so needs no DB access at all).
    await registerAdminDemoUsers(ctx)
    await promoteDemoUserToAdmin()
    await seedIpLockouts()
  }
  await login(ctx)

  const existing = await existingActions(ctx)
  for (const action of ACTIONS) {
    const id = await ensureAction(ctx, existing, action)
    for (let offset = SEED_DAYS - 1; offset >= 0; offset--) {
      const date = dateMinusDays(SEED_END, offset)
      // The weekday of the day being written, so the pattern tiles across the month. Parsed back as UTC
      // (not `new Date(date)` on a local clock) to stay on the same UTC footing as everything else here.
      const day = new Date(`${date}T00:00:00Z`)
      const base = action.perWeekday[day.getUTCDay()]
      const count = base === 0 ? 0 : Math.max(0, base + WEEK_NUDGE[Math.floor((day.getUTCDate() - 1) / 7) % WEEK_NUDGE.length])
      // PUT SETS the count rather than incrementing towards it: one call per day instead of `count` of
      // them, and no "what is already logged?" pre-read at all.
      //
      // A zero is WRITTEN, not skipped (0 deletes the day's entry). Skipping it would leave whatever a
      // previous run had put there, so re-seeding a database after changing the pattern would silently
      // blend the old shape into the new one — the totals drift and the screenshots stop being
      // reproducible. Writing every day of the month makes the seed genuinely idempotent.
      await ctx.request.fetch(`${BASE}/api/v1/logs/${date}/${id}`, { method: 'PUT', data: { count } })
    }
  }
  await seedNotes(ctx)
  console.log(`seeded demo data across ${SEED_MONTH} (${SEED_START_ISO} to ${SEED_END_ISO})`)
}

// A day note on a fixed subset of the seeded month, so the dashboard shows a written note, the calendar
// shows its green day numbers, and the Stats page has a Notes card with a real streak on it. Written with
// PUT (which SETS) and applied to EVERY day of the month — a day off the pattern is written blank, which
// deletes it — so re-seeding after changing the pattern cannot blend the old shape into the new one, for
// the same reason the action counts above are written rather than skipped.
async function seedNotes(ctx) {
  for (let offset = SEED_DAYS - 1; offset >= 0; offset--) {
    const date = dateMinusDays(SEED_END, offset)
    const day = new Date(`${date}T00:00:00Z`)
    const text = NOTE_BY_WEEKDAY[day.getUTCDay()]
    // The LAST day of the seeded month always carries the featured note, because that is the day every
    // dashboard shot has selected (showSeededMonth picks it) — so the note box is shown holding real
    // multi-paragraph prose rather than its placeholder. Every other day follows the weekday pattern,
    // thinned out earlier in the month so the run near the end reads as a streak rather than a solid block.
    const patterned = (text === '' || (offset > 6 && day.getUTCDate() % 3 === 0)) ? '' : text
    const content = offset === 0 ? NOTE_FEATURED : patterned
    await ctx.request.fetch(`${BASE}/api/v1/notes/${date}`, { method: 'PUT', data: { content } })
  }
}

// ── Screenshot capture ───────────────────────────────────────────────────────────────────────────

async function setPrefs(ctx, theme, calendarView, font = 'nova', language = 'en-GB') {
  // Preferences are updated in one shot via the consolidated PATCH /internal/settings endpoint (a
  // form-encoded body; returns 204). Only the fields this script cares about are sent — the resource
  // treats absent fields as "keep". `language` defaults to 'en-GB' so every call site resets it back —
  // only the Arabic RTL shot passes a different one, and every shot after it must not silently inherit
  // that switch.
  const res = await ctx.request.fetch(`${BASE}/internal/settings`, {
    method: 'PATCH',
    form: { theme, font, calendarView, language, pageSize: '10', timezone: 'UTC' },
  })
  if (!res.ok()) {throw new Error(`setPrefs failed: ${res.status()}`)}
}

// Open the dashboard and wait until the chosen calendar style's activity markers are painted.
// Caller closes the page.
// Steps the dashboard calendar back to the seeded month and selects its last day, driving the real
// toolbar/grid rather than a URL (the dashboard takes no date parameter — it always opens on today).
//
// This is what puts the SEEDED month on screen: the seed fills last month, so leaving the calendar on
// its default view would show the current month, which is empty. Selecting the last day also drives the
// day panel and the stats-summary card beside it, so those are populated too rather than showing a
// no-activity day.
async function showSeededMonth(page) {
  await page.locator('#cal-prev').click()
  const selectedCell = page.locator(`.d-min-cell[data-date="${SEED_END_ISO}"]`)
  await selectedCell.waitFor({ timeout: 15000 })
  await selectedCell.click()
  await page.waitForSelector(`.d-min-cell[data-date="${SEED_END_ISO}"].d-min-selected`, { timeout: 15000 })
}

async function openDashboard(ctx, calendarView) {
  const page = await ctx.newPage()
  // `load` (not `networkidle`): the dashboard's calendar background-prefetches adjacent months, so the
  // network never idles. The waitForSelector below is the real gate — it blocks until the chosen
  // style's markers are actually painted.
  await page.goto(`${BASE}/`, { waitUntil: 'load' })
  await showSeededMonth(page)
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
//
// -z 9 is the slowest/strongest LOSSLESS preset (it implies -lossless). It is never a quality trade —
// only a search-effort one — and it beat both the plain -lossless default and -m 6 on every image
// measured here (settings-dark 289,940 -> 216,670; page-standard-full-dark 84,582 -> 81,864).
let _cwebpReady = false
// `crop` (optional {x,y,w,h}, PNG pixel coordinates) uses cwebp's OWN `-crop` flag rather than a
// separate image-cropping dependency — see shotLanguageDropdown for why a Playwright-level `clip`
// can't be used instead for that one shot.
function pngToLosslessWebp(pngBuf, resizeWidth, crop) {
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
  // -crop runs BEFORE -resize (cwebp's own order), so a resizeWidth alongside a crop would scale the
  // CROPPED region — not used together today, but correct if it ever is.
  const cropArgs = crop ? ['-crop', String(crop.x), String(crop.y), String(crop.w), String(crop.h)] : []
  // -resize W 0: scale to W, height derived from the aspect ratio. -o -: write WebP to stdout.
  const resize = resizeWidth ? ['-resize', String(resizeWidth), '0'] : []
  try {
    return execFileSync('cwebp', ['-z', '9', '-quiet', ...cropArgs, ...resize, tmp, '-o', '-'], { maxBuffer: 50 * 1024 * 1024 })
  } finally {
    fs.unlinkSync(tmp)
  }
}

// Read pixel dimensions from a PNG IHDR chunk (big-endian uint32 at offsets 16 and 20).
function pngSize(buf) {
  return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) }
}

// ── in-app preview sizing ────────────────────────────────────────────────────
// The picker tile and the lightbox are SEPARATE files. One 3456px-wide file served both, but the tile
// paints it at ~185 CSS px and the lightbox panel is capped at max-w-5xl = 1024 CSS px, so every
// Settings page load spent ~393 kB to fill a row of thumbnails. Splitting them costs a second file
// per option, paid ONLY by the reader who opens a preview.
//
// EVERY derived width must divide the capture EXACTLY. This is the load-bearing rule: libwebp's
// lossless coder lives on flat colour runs and exact repeats, and a fractional resize ratio blends
// neighbouring pixels into gradients that destroy both. Measured on these screenshots, 3456 -> 1920
// came out 2% BIGGER than the untouched 3456 original, and 3456 -> 2048 saved nothing; 3456 -> 1728
// (exactly half) saves 38%. Fewer pixels is NOT automatically fewer bytes.
const PREVIEW_TILE_MIN_WIDTH = 384  // the tile is ~185 CSS px, so this still covers it past DPR 2
const PREVIEW_FULL_MIN_WIDTH = 1024 // the lightbox panel is max-w-5xl = 1024 CSS px

// Full-size preview: halve a DPR-2 capture (which recovers the native CSS-pixel image exactly), but
// only while that still fills the lightbox. The calendar crops are captured at 1586 px = 793 CSS px,
// and the lightbox shows them at their natural 793 CSS px, so halving those would visibly soften a
// DPR-2 screen for no good reason — they stay as captured.
function fullPreviewWidth(width) {
  return width % 2 === 0 && width / 2 >= PREVIEW_FULL_MIN_WIDTH ? width / 2 : width
}

// Tile thumbnail: the smallest exact divisor of the capture that still clears the tile's needs.
function tilePreviewWidth(width) {
  for (let divisor = Math.floor(width / PREVIEW_TILE_MIN_WIDTH); divisor > 1; divisor--) {
    if (width % divisor === 0) { return width / divisor }
  }
  return width
}

// Sub-directory of OUT holding the lightbox images, so both forms keep the SAME base name (a
// `-full` suffix would read as `cal-nova-full-dark-full.webp`). Mirrored by hash-static-assets.sh
// and AppInfo.settingsFullImage.
const FULL_SUBDIR = 'full'

// Write one screenshot. Documentation shots stay a single file; an in-app preview becomes the tile
// thumbnail plus its lightbox image (see the sizing rules above). `crop` (optional, SHOTS-only — an
// in-app preview never needs one) is a PNG-pixel {x,y,w,h} rectangle, see pngToLosslessWebp.
function writeShot(dir, file, pngBuf, crop) {
  if (dir !== OUT) {
    fs.writeFileSync(path.join(dir, file), pngToLosslessWebp(pngBuf, undefined, crop))
    console.log('wrote', file)
    return
  }
  const { w } = pngSize(pngBuf)
  const full = fullPreviewWidth(w)
  const tile = tilePreviewWidth(w)
  fs.mkdirSync(path.join(dir, FULL_SUBDIR), { recursive: true })
  fs.writeFileSync(path.join(dir, FULL_SUBDIR, file), pngToLosslessWebp(pngBuf, full === w ? 0 : full))
  fs.writeFileSync(path.join(dir, file), pngToLosslessWebp(pngBuf, tile))
  console.log('wrote', `${file} (${tile}px tile + ${full}px full, from a ${w}px capture)`)
}

// Theme preview: the WHOLE dashboard page (navbar, heading, calendar, day panel, stats) — fullPage
// captures the entire scroll height, not just the viewport. Returns the PNG buffer so the caller can
// pass it to compositeSystem for compositing.
async function shotFullPage(page, dir, file) {
  const pngBuf = await page.screenshot({ fullPage: true })
  writeShot(dir, file, pngBuf)
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
  await shotElement(page.locator('#calendar-wrap'), dir, file)
}

// An element screenshot of an arbitrary locator, written to `dir`. Captures the whole element even where
// it overflows the viewport, so a tall card is never cut off.
async function shotElement(locator, dir, file) {
  const pngBuf = await locator.screenshot()
  writeShot(dir, file, pngBuf)
}

// Full page screenshot at a URL (whole page), written to `dir`. Waits for the page's key content to
// render before the full-page capture.
async function shotPage(ctx, url, waitSelector, dir, file) {
  const page = await ctx.newPage()
  // `load`, then gate on the page's key content selector (see openDashboard for why not networkidle).
  await page.goto(`${BASE}${url}`, { waitUntil: 'load' })
  await page.waitForSelector(waitSelector, { timeout: 15000 })
  await page.waitForTimeout(600) // settle fonts/layout
  const pngBuf = await page.screenshot({ fullPage: true })
  writeShot(dir, file, pngBuf)
  await page.close()
}

// Login page: captured in a FRESH context with NO session cookie — every other documentation shot is
// authenticated, and reusing `ctx` would just bounce to the dashboard. An anonymous request has no
// stored theme preference so the page renders the `system` default, which resolves via the FOUC
// bootstrap's prefers-color-scheme check; colorScheme 'dark' therefore lands it dark, matching the set.
//
// The committed shot shows BOTH sign-in methods, so the instance must be booted with OIDC enabled
// against a DUMMY IdP (see PREREQUISITES in the header — `OIDC_PREVIEW=1 scripts/dev-up.sh`, or the
// same OIDC_* env on a fast-jar boot). The "Log in with <provider>" button is server-rendered purely
// from the OIDC_ENABLED flag (the login page never contacts the IdP), so an unreachable issuer is
// enough. We wait for that button and THROW if it is missing, rather than silently committing a
// password-only shot. (The button needs a user to exist so /login stops redirecting to first-run
// setup — the caller seeds one before this runs.)
async function shotLoginPage(browser, dir, file) {
  const anonCtx = await browser.newContext({
    viewport: { width: VW, height: VH },
    deviceScaleFactor: 2,
    timezoneId: 'UTC',
    colorScheme: 'dark',
  })
  const page = await anonCtx.newPage()
  await page.goto(`${BASE}/login`, { waitUntil: 'load' })
  await page.waitForSelector('input[name="password"]', { timeout: 15000 })
  try {
    await page.waitForSelector('a[href="/oidc-login"]', { timeout: 15000 })
  } catch {
    throw new Error(
      'Login page has no OIDC button. Boot the instance with OIDC enabled against a dummy IdP '
      + '(OIDC_PREVIEW=1 scripts/dev-up.sh, or the OIDC_* env documented in this script\'s header) '
      + `so ${file} shows both password and OIDC sign-in.`)
  }
  await page.waitForTimeout(600) // settle fonts/layout
  const pngBuf = await page.screenshot({ fullPage: true })
  writeShot(dir, file, pngBuf)
  await anonCtx.close()
}

// Mobile dashboard: the same demo account at a phone viewport, captured in BOTH themes and composited
// into the same diagonal light/dark split as the desktop dashboard-system banner (compositeSystem is
// dimension-agnostic, so it frames a portrait phone shot exactly as it does the landscape one). Uses
// the MINIMAL calendar style (compact coloured dots) rather than Full: at phone width the Full style's
// per-action event text is cramped, whereas the dot grid reads cleanly. The theme is an account-level
// preference, so setPrefs over the shared desktop `ctx` drives what the mobile page renders; the mobile
// context still needs its own login because a browser context owns its cookie jar. Explicit
// `light`/`dark` themes render `data-theme` server-side, so the context's colorScheme (which only
// resolves the `system` theme) is irrelevant here. Viewport-only capture; see MOBILE_VW/MOBILE_VH above
// for why this one is not fullPage.
async function shotMobileDashboard(browser, ctx, dir, file) {
  const mobileCtx = await browser.newContext({
    viewport: { width: MOBILE_VW, height: MOBILE_VH },
    deviceScaleFactor: 2,
    timezoneId: 'UTC',
    colorScheme: 'dark',
    hasTouch: true,
  })
  await login(mobileCtx)

  // Render the mobile dashboard in `theme` and return its viewport PNG. Waiting for `.d-min-dot` (the
  // minimal style's per-action dot) ensures the calendar's data has painted, not just the empty grid.
  const shoot = async theme => {
    await setPrefs(ctx, theme, 'minimal', 'nova')
    const page = await mobileCtx.newPage()
    await page.goto(`${BASE}/`, { waitUntil: 'load' })
    await showSeededMonth(page)
    await page.waitForSelector('.d-min-dot', { timeout: 15000 })
    await page.waitForTimeout(600) // settle fonts/layout
    const buf = await page.screenshot()
    await page.close()
    return buf
  }

  const lightBuf = await shoot('light')
  const darkBuf = await shoot('dark')
  await mobileCtx.close()

  // Same diagonal split as the desktop banner (light upper-left, dark lower-right).
  await compositeSystem(browser, { lightBuf, darkBuf, dir, out: file })
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
  writeShot(dir, out, compositePng)
  await ctx.close()
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
// The Stats page's frequency-graph modal, showing THREE actions compared over one month — the README
// pairs it with the plain Stats page shot. Driven through the real UI (open the dialog from a card, add
// two comparisons through the picker) rather than by hand-building a URL, so the shot can only be
// produced by a working control flow.
//
// Captured viewport-only rather than fullPage: the dimmed backdrop over the page behind it is what
// makes it read as a dialog, and a fullPage shot of a scrolling stats page would bury it.
//
// The window is stepped back to the seeded month: the graph opens on the app's real current month, which
// the seed deliberately leaves empty (see SEED_END), so the default window would draw nothing.
async function shotStatsGraph(ctx, dir, file) {
  const primary = ACTIONS[0].name
  const compared = [ACTIONS[1].name, ACTIONS[2].name]
  const targetMonth = SEED_MONTH

  const page = await ctx.newPage()
  await page.goto(`${BASE}/stats`, { waitUntil: 'load' })
  await page.waitForSelector('#stats-list .card')

  await page.locator(`[data-chart-name="${primary}"]`).click()
  await page.waitForSelector('.chart-wrap')

  for (const name of compared) {
    await page.locator('[data-chart-compare-open]').click()
    await page.locator('.chart-candidate', { hasText: name }).first().click()
    await page.waitForFunction(
      count => document.querySelectorAll('.chart-chip').length === count,
      compared.indexOf(name) + 2,
      { timeout: 15000 })
  }

  // Step back to the anchored month. Bounded by the number of steps the seed could possibly need, so a
  // mis-set anchor fails loudly here instead of spinning.
  const earlier = page.locator('button[data-chart-at]').first()
  for (let step = 0; step < 24; step++) {
    const shown = await page.locator('.chart-wrap').getAttribute('data-chart-shown-at')
    if (shown === targetMonth) {break}
    if (await earlier.isDisabled()) {
      throw new Error(`Cannot reach ${targetMonth} in the frequency graph - stopped at ${shown} with no earlier window`)
    }
    await earlier.click()
    await page.waitForFunction(
      previous => document.querySelector('.chart-wrap')?.dataset.chartShownAt !== previous,
      shown,
      { timeout: 15000 })
  }
  const landed = await page.locator('.chart-wrap').getAttribute('data-chart-shown-at')
  if (landed !== targetMonth) {
    throw new Error(`Frequency graph is showing ${landed}, expected ${targetMonth}`)
  }
  // The plot itself, not a bar: an unlogged slot's bar is zero-height, and Playwright counts a
  // zero-height element as not visible, so waiting on the first bar can hang on an empty day.
  await page.waitForSelector('.chart-plot')

  await page.waitForTimeout(600) // settle fonts/layout
  const pngBuf = await page.screenshot() // viewport only - keeps the dimmed backdrop in frame
  writeShot(dir, file, pngBuf)
  await page.close()
}

// The dashboard's note box, framed as the #note-panel card. showSeededMonth selects the last day of the
// seeded month, which is the day carrying NOTE_FEATURED — so this shows the box holding real multi-paragraph
// prose rather than its empty-day placeholder.
//
// Waits for the textarea to actually hold text, not merely to exist: the note card ships with the page and
// is filled from the client-side month cache when the date changes, so the element is present and EMPTY for
// a moment before the seeded note lands in it. Screenshotting on presence alone yields a blank box.
async function shotNoteBox(ctx, dir, file) {
  const page = await openDashboard(ctx, 'full')
  await page.waitForFunction(
    () => (document.getElementById('note-input')?.value.length ?? 0) > 0,
    null,
    { timeout: 15000 })
  await page.waitForTimeout(300) // settle the auto-sized box
  await shotElement(page.locator('#note-panel'), dir, file)
  await page.close()
}

// The Notes card on the Stats page. Selected by its chart button's subject name rather than by position:
// notes are pinned first today (StatsService.forAllSubjects), but a shot that silently captures whichever
// card happens to be first is a shot that starts lying the moment that ordering changes.
async function shotNotesStatsCard(ctx, dir, file) {
  const page = await ctx.newPage()
  await page.goto(`${BASE}/stats`, { waitUntil: 'load' })
  await page.waitForSelector('#stats-list .card')
  const card = page.locator('#stats-list .card')
    .filter({ has: page.locator('[data-chart-name="Notes"]') })
    .first()
  await card.waitFor({ timeout: 15000 })
  await page.waitForTimeout(600) // settle fonts/layout
  await shotElement(card, dir, file)
  await page.close()
}

// The Settings language picker, OPEN — showing its filter box and both of every language's names.
//
// It used to be a native <select>, whose option list is the BROWSER's own popup rather than app markup,
// and that made this the fiddliest shot in the set: the popup only painted at deviceScaleFactor 1, and
// only into an UN-clipped screenshot. Both workarounds are kept, because both are still the safe
// choice and neither costs anything: the panel is now ordinary DOM (partials/combo-field.html), so it
// would survive a clip and a scaled DPR, but a cropped capture of a small control loses nothing at
// DPR 1 and the crop maths below is the same either way.
//
// The crop is the UNION of the closed button and the open panel, measured after opening: the panel is
// absolutely positioned and hangs BELOW and (under LTR) to the START of the button, so neither box
// alone contains the control as a reader sees it.
async function shotLanguageDropdown(browser, dir, file) {
  const dsf1Ctx = await browser.newContext({ viewport: { width: VW, height: VH }, timezoneId: 'UTC' })
  await login(dsf1Ctx)
  const page = await dsf1Ctx.newPage()
  await page.goto(`${BASE}/settings`, { waitUntil: 'load' })
  const button = page.locator('#language-button')
  // `block: 'start'` (not scrollIntoViewIfNeeded's nearest-edge default) so the control lands near the
  // TOP of the viewport, leaving room below it for the panel to paint within the viewport bounds.
  await button.evaluate(el => el.scrollIntoView({ block: 'start' }))
  await button.click()
  await page.waitForTimeout(300) // let the panel paint
  const box = await button.boundingBox()
  const panel = await page.locator('#language-panel').boundingBox()
  const pngBuf = await page.screenshot() // NOT clip — see the function comment above
  const x = Math.floor(Math.min(box.x, panel.x))
  const right = Math.ceil(Math.max(box.x + box.width, panel.x + panel.width))
  const bottom = Math.ceil(panel.y + panel.height)
  writeShot(dir, file, pngBuf, { x, y: Math.floor(box.y), w: right - x, h: bottom - Math.floor(box.y) })
  await dsf1Ctx.close()
}

// The WHOLE dashboard page, in Arabic — the README's one RTL demonstration. A full-page shot (like
// dashboard-{dark,light}), not just the calendar element, so it also shows the navbar/day-panel/stats
// summary alongside the calendar: the navbar stays pinned LTR by design while the calendar/day-panel
// content mirrors, which only reads clearly with both in the same frame. Weekday order, day-of-month
// digits (Eastern Arabic-Indic) and text alignment all mirror; the calendar toolbar's own «‹›» chevrons
// deliberately do NOT (see `.claude/I18N.md`'s "Right-to-left support") — real, current behaviour, not a
// shot artefact. Resets the language back to English afterwards, since every shot after this one in the
// sequence assumes it.
async function shotArabicDashboard(ctx, dir, file) {
  await setPrefs(ctx, 'dark', 'full', 'nova', 'ar-SA')
  const page = await openDashboard(ctx, 'full')
  await shotFullPage(page, dir, file)
  await page.close()
  await setPrefs(ctx, 'dark', 'full', 'nova')
}

async function captureDocsScreenshots(ctx, browser) {
  // Nova, full, light → the light dashboard; store PNG for the system composite.
  await setPrefs(ctx, 'light', 'full', 'nova')
  const lightPage = await openDashboard(ctx, 'full')
  const lightBuf = await shotFullPage(lightPage, SHOTS, 'dashboard-light.webp')
  await lightPage.close()

  // Nova, full, dark → the dark dashboard; store PNG for the system composite. The same page also
  // yields the Full calendar shot: the README compares the three calendar styles side by side, so all
  // three are captured as the #calendar-wrap ELEMENT (identical framing and dimensions) rather than
  // letting Full be a whole-page shot while the other two are element shots.
  await setPrefs(ctx, 'dark', 'full', 'nova')
  const darkPage = await openDashboard(ctx, 'full')
  const darkBuf = await shotFullPage(darkPage, SHOTS, 'dashboard-dark.webp')
  await shotCalendar(darkPage, SHOTS, 'cal-full-dark.webp')
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

  // The two shots the README's Languages section pairs: the whole dashboard in Arabic (RTL
  // demonstration) and the Settings language picker showing its options. shotArabicDashboard resets the
  // language back to English on its way out, so shotLanguageDropdown (and everything after it) renders
  // in English.
  await shotArabicDashboard(ctx, SHOTS, 'dashboard-arabic-dark.webp')
  await shotLanguageDropdown(browser, SHOTS, 'language-dropdown-dark.webp')

  // The four page screenshots (dark / Full / Nova). setPrefs pins theme/font/calendar; the demo user
  // never customises statsFields, so the Stats page renders every tile in its default order.
  await setPrefs(ctx, 'dark', 'full', 'nova')
  await shotPage(ctx, '/actions',     '#action-list .dt-row',        SHOTS, 'actions-dark.webp')
  await shotPage(ctx, '/stats',       '#stats-list .card',           SHOTS, 'stats-dark.webp')
  await shotPage(ctx, '/admin/users', '#admin-users-list .dt-table', SHOTS, 'admin-dark.webp')
  await shotPage(ctx, '/settings',    '#prefs-form',                 SHOTS, 'settings-dark.webp')
  await shotStatsGraph(ctx, SHOTS, 'stats-graph-dark.webp')

  // The two Notes shots the README's Notes section pairs: the editor, and notes as a statistics subject.
  await shotNoteBox(ctx, SHOTS, 'note-box-dark.webp')
  await shotNotesStatsCard(ctx, SHOTS, 'stats-notes-dark.webp')

  // The two shots that need their own browser context: the login page is anonymous, and the mobile
  // dashboard is a different viewport. Both run last so the mobile one inherits the dark/Full/Nova
  // preferences set above.
  await shotLoginPage(browser, SHOTS, 'login-dark.webp')
  await shotMobileDashboard(browser, ctx, SHOTS, 'dashboard-mobile.webp')
}

// ── Browser resolution ─────────────────────────────────────────────────────────────────────────

// Locate a Chromium binary that the installed Playwright can launch. Playwright hard-pins each package
// version to ONE browser build number, and `chromium.launch()` defaults to the chrome-headless-shell
// binary at exactly that build. The browsers are a separate `playwright install` step, so after a
// tests/ Playwright bump the pinned build's binary is often not on disk yet and a bare launch dies with
// "Executable doesn't exist … run npx playwright install". Requiring a browser re-download for every
// local screenshot run is the papercut this avoids: we reuse ANY installed Chromium build instead.
//
//   pinned build present     -> use it (exact match, identical to the default).
//   only another build present-> use the newest installed one (a small build drift renders fine).
//   nothing installed        -> return null; the caller prints the exact install command and exits.
//
// The cache root and the pinned build number are both derived from Playwright's own executablePath
// (…/<cacheRoot>/chromium-<build>/<platform>/chrome), so this stays correct across OSes and custom
// PLAYWRIGHT_BROWSERS_PATH locations without re-encoding those rules. The Docker `screenshots` stage
// installs the pinned build in the image, so it always takes the first branch; this fallback only ever
// engages on a drifted local checkout.
function resolveChromiumExecutable() {
  const pinned = chromium.executablePath() // …/chromium-<build>/<platform-dir>/chrome[.exe]
  const cacheRoot = path.dirname(path.dirname(path.dirname(pinned)))
  const pinnedBuild = (path.basename(path.dirname(path.dirname(pinned))).match(/-(\d+)$/) || [])[1]

  // The binary lives one platform-named directory below the build dir; its name varies by channel/OS,
  // so scan rather than hard-code `chrome-linux64`. headless-shell is preferred (it is what launch()
  // uses by default); full chrome is the fallback when only the headed build is present.
  const findBinaryIn = (buildDir, names) => {
    if (!fs.existsSync(buildDir)) {return null}
    for (const sub of fs.readdirSync(buildDir)) {
      for (const name of names) {
        const candidate = path.join(buildDir, sub, name)
        if (fs.existsSync(candidate)) {return candidate}
      }
    }
    return null
  }

  if (!fs.existsSync(cacheRoot)) {return null}
  // All installed Chromium build numbers, pinned first, then newest-to-oldest.
  const builds = [...new Set(fs.readdirSync(cacheRoot)
    .map(dir => (dir.match(/^chromium(?:_headless_shell)?-(\d+)$/) || [])[1])
    .filter(Boolean))]
    .sort((a, b) => (a === pinnedBuild ? -1 : b === pinnedBuild ? 1 : Number(b) - Number(a)))

  for (const build of builds) {
    const binary =
      findBinaryIn(path.join(cacheRoot, `chromium_headless_shell-${build}`), ['chrome-headless-shell', 'chrome-headless-shell.exe']) ||
      findBinaryIn(path.join(cacheRoot, `chromium-${build}`), ['chrome', 'chrome.exe'])
    if (binary) {return { execPath: binary, build, pinned: build === pinnedBuild }}
  }
  return null
}

// ── Main ───────────────────────────────────────────────────────────────────────────────────────

(async () => {
  if (wantApp) {fs.mkdirSync(OUT, { recursive: true })}
  if (wantDocs) {fs.mkdirSync(SHOTS, { recursive: true })}
  // Extra Chromium flags via PW_CHROMIUM_ARGS (space-separated). Used by the in-image build
  // (Dockerfile screenshots stage) to pass `--no-sandbox`, since Chromium refuses to run as root
  // without it; a normal local run leaves it empty (sandbox on).
  const launchArgs = (process.env.PW_CHROMIUM_ARGS || '').split(' ').filter(Boolean)

  const resolved = resolveChromiumExecutable()
  if (!resolved) {
    console.error('No Chromium build found in the Playwright browser cache. Install one with:\n  cd tests && npx playwright install chromium')
    process.exit(1)
  }
  if (!resolved.pinned) {
    console.warn(`⚠ Pinned Chromium build not installed; using build ${resolved.build} instead (fine for screenshots).`)
  }
  const browser = await chromium.launch({ executablePath: resolved.execPath, args: launchArgs })

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
