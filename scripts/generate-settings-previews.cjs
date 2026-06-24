#!/usr/bin/env node
/**
 * Regenerates the Settings page preview thumbnails — the scaled-down dashboard screenshots shown
 * for the Theme and Calendar-style pickers (see settings.html → partials/preview-option.html).
 *
 * WHEN TO RUN THIS
 * ----------------
 * These six PNGs are committed assets (src/main/resources/META-INF/resources/img/settings/). They
 * are NOT rebuilt by the app or the Maven/Docker build — they only change when you re-run this
 * script. Re-run it whenever the dashboard's appearance changes in a way the previews should
 * reflect, e.g.:
 *   - the calendar (full / minimal / stacked) markup or styling changes,
 *   - the light/dark colour tokens or overall dashboard chrome change,
 *   - the navbar / day-panel / layout on the dashboard changes.
 * The script optimises the PNGs itself (see below), so afterwards just review and commit them.
 * Nothing else needs to change — settings.html references the files by name.
 *
 * WHAT IT PRODUCES
 * ----------------
 * Two sets of the same configurations — a web (landscape) set and a mobile (portrait, `-mobile`
 * suffix) set, so the Settings tiles can show the device-appropriate shot (web at the `sm` breakpoint
 * and up, mobile below it). Captures span the full `font × calendar-style × theme` matrix so EVERY
 * picker (Theme, Calendar style, Font) can reveal the variant matching the user's current font + style
 * + theme. The naming is uniform:
 *   page-{nova,standard}-{full,minimal,stacked}-{light,dark,system}.png  (+ -mobile)
 *                          full-page shot — used by the Theme picker (reveal style+font) and the Font
 *                          picker (reveal style+theme); `system` is a diagonal light/dark split.
 *   cal-{nova,standard}-{full,minimal,stacked}-{light,dark}.png          (+ -mobile)
 *                          calendar-only shot — used by the Calendar picker (reveal font+theme).
 * Every screenshot uses the SAME seeded data so the only visible difference within a viewport is the
 * font / calendar style / theme — that is the whole point of the previews. (**60 PNGs total.**)
 *
 * PREREQUISITES
 * -------------
 *   1. A running dev server (defaults to http://localhost:8081):
 *        docker compose -f docker-compose.dev.yml up -d dev-db
 *        mvn quarkus:dev
 *   2. Playwright's browser binaries (already installed for the e2e suite):
 *        cd e2e && npx playwright install
 *
 * USAGE
 * -----
 *   node scripts/generate-settings-previews.cjs            # against http://localhost:8081
 *   BASE_URL=http://localhost:8080 node scripts/generate-settings-previews.cjs
 *
 * The script is self-contained: it registers a dedicated demo user, seeds a fixed set of actions
 * and logs over HTTP (idempotent — safe to re-run), then drives a headless browser to capture each
 * configuration. It does NOT touch the database directly, so it works against any running instance.
 * Finally, it losslessly optimises the PNGs with optipng, installing it via apt-get (Debian/Ubuntu)
 * if it isn't already on PATH.
 */
const path = require('path');
const fs = require('fs');
const { execFileSync, execSync } = require('child_process');
// Reuse Playwright from the e2e workspace so this script needs no dependencies of its own.
const { chromium } = require(path.join(__dirname, '..', 'e2e', 'node_modules', 'playwright'));

const BASE = process.env.BASE_URL || 'http://localhost:8081';
const OUT = path.join(__dirname, '..', 'src', 'main', 'resources', 'META-INF', 'resources', 'img', 'settings');
const VW = 1200, VH = 820;  // web capture viewport (full-page/element shots ignore the height)
const MVW = 390, MVH = 844; // mobile capture viewport — realistic phone size (height ~unused too)

// Dedicated demo account — kept separate from real dev data.
const USER = { email: 'preview-demo@diurnal.local', password: 'preview_demo123', displayName: 'Preview Demo' };

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
];

const pad = n => String(n).padStart(2, '0');

// `base` minus `n` calendar days as a UTC `YYYY-MM-DD` string (n may be negative for future days).
// Date.UTC normalises an out-of-range day-of-month, so this rolls correctly across month/year edges.
const dateMinusDays = (base, n) => {
  const d = new Date(Date.UTC(base.getUTCFullYear(), base.getUTCMonth(), base.getUTCDate() - n));
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
};

// ── Seeding (over HTTP, via the logged-in browser-context cookies) ───────────────────────────────

async function registerDemoUser(ctx) {
  // Idempotent: a 409 (already registered) is expected on re-runs and ignored.
  await ctx.request.post(BASE + '/api/auth/register', { data: USER }).catch(() => {});
}

async function login(ctx) {
  const page = await ctx.newPage();
  await page.goto(BASE + '/login');
  await page.fill('input[name="email"]', USER.email);
  await page.fill('input[name="password"]', USER.password);
  await Promise.all([page.waitForLoadState('networkidle'), page.click('button[type="submit"]')]);
  await page.close();
}

// Map existing action name -> id by parsing the actions list (rows are `<tr id="action-{uuid}">`).
async function existingActions(ctx) {
  const html = await (await ctx.request.get(BASE + '/actions/list')).text();
  const map = {};
  const re = /id="action-([0-9a-fA-F-]{36})"[\s\S]*?<span data-dt-view>([^<]+)<\/span>/g;
  let m;
  while ((m = re.exec(html))) map[m[2].trim()] = m[1];
  return map;
}

async function ensureAction(ctx, existing, { name, colour }) {
  if (existing[name]) return existing[name];
  const res = await ctx.request.post(BASE + '/actions', { form: { name, colour } });
  const id = (await res.text()).match(/id="action-([0-9a-fA-F-]{36})"/);
  if (!id) throw new Error(`Could not create or locate action "${name}"`);
  return id[1];
}

// Keys (`date|colour`) for logs already present in [start, end), so re-runs don't inflate counts.
async function existingLogKeys(ctx, start, end) {
  const events = await (await ctx.request.get(`${BASE}/logs/events?start=${start}&end=${end}`)).json();
  return new Set(events.map(e => `${e.start}|${(e.backgroundColor || '').toLowerCase()}`));
}

async function seed(ctx) {
  await registerDemoUser(ctx);
  await login(ctx);

  const now = new Date();
  // The seed window: from the oldest offset through today (the /logs/events `end` is exclusive, so
  // pass today + 1). Covers the whole range even when it straddles a month/year boundary.
  const maxAgo = Math.max(...ACTIONS.flatMap(a => a.daysAgo));
  const existing = await existingActions(ctx);
  const have = await existingLogKeys(ctx, dateMinusDays(now, maxAgo), dateMinusDays(now, -1));

  for (const action of ACTIONS) {
    const id = await ensureAction(ctx, existing, action);
    for (const daysAgo of action.daysAgo) {
      const date = dateMinusDays(now, daysAgo); // offsets are never future, so nothing is skipped
      if (have.has(`${date}|${action.colour.toLowerCase()}`)) continue; // already logged
      for (let i = 0; i < action.count; i++) {
        await ctx.request.post(`${BASE}/logs/${date}/${id}/increment`);
      }
    }
  }
  console.log('seeded demo data');
}

// ── Screenshot capture ───────────────────────────────────────────────────────────────────────────

async function setPrefs(ctx, theme, calendarView, font = 'nova') {
  // Persist prefs via the same endpoint the settings form posts to. `font` defaults to 'nova' (the
  // app default) so the theme/calendar captures are taken in the brand font; the font picker's
  // Standard tile is captured by passing font='standard'.
  const res = await ctx.request.post(BASE + '/settings',
    { form: { theme, font, pageSize: '10', calendarView, timezone: 'UTC' } });
  if (!res.ok()) throw new Error('setPrefs failed: ' + res.status());
}

// Open the dashboard and wait until the chosen calendar style's activity markers are painted, so
// every capture taken from the returned page sees fully-loaded events. Caller closes the page.
async function openDashboard(ctx, calendarView) {
  const page = await ctx.newPage();
  await page.goto(BASE + '/', { waitUntil: 'networkidle' }); // the dashboard is served at /
  const sel = calendarView === 'full' ? '.fc-event'
            : calendarView === 'stacked' ? '.d-stk-bar'
            : '.d-min-dot';
  await page.waitForSelector(sel, { timeout: 15000 });
  await page.waitForTimeout(600); // settle fonts/layout
  return page;
}

// Theme preview: the WHOLE dashboard page (navbar, heading, calendar, day panel, stats) — fullPage
// captures the entire scroll height, not just the viewport.
async function shotFullPage(page, file) {
  await page.screenshot({ path: path.join(OUT, file), fullPage: true });
  console.log('wrote', file);
}

// Calendar-style preview: ONLY the calendar. Every calendar style now shares the #calendar-wrap
// container (shared toolbar + the style's grid). We screenshot `#calendar-wrap` ITSELF, NOT its
// `.card` parent: the card adds a `rounded-2xl border` that would sit on the image edge and, inside
// the lightbox modal's own `rounded-lg border`, read as a spurious "double" rounded outline that the
// theme (full-page) shots don't have. Shooting the wrap keeps the calendar flush to the edge, so both
// pickers' full-size previews are framed identically. An element screenshot captures the whole element
// even where it overflows the viewport, so it is never cut off.
async function shotCalendar(page, file) {
  await page.locator('#calendar-wrap').screenshot({ path: path.join(OUT, file) });
  console.log('wrote', file);
}

// Read a PNG's pixel dimensions straight from its IHDR (width/height are big-endian uint32s at byte
// offsets 16/20). Lets the system composite size itself to whatever the (variable-height) source is.
function pngSize(file) {
  const b = fs.readFileSync(file);
  return { w: b.readUInt32BE(16), h: b.readUInt32BE(20) };
}

// System theme = diagonal split of the light & dark dashboards (light upper-left, dark lower-right;
// divider runs corner-to-corner top-right → bottom-left). Inlined as base64 so the composing page has
// no cross-origin/file:// loading concerns. `light`/`dark` are the source filenames and `out` the
// target; the canvas matches the sources' actual pixel size (they are already 2x captures).
async function compositeSystem(browser, { light, dark, out }) {
  const lightB64 = fs.readFileSync(path.join(OUT, light)).toString('base64');
  const darkB64 = fs.readFileSync(path.join(OUT, dark)).toString('base64');
  const { w: PW, h: PH } = pngSize(path.join(OUT, light));
  const ctx = await browser.newContext({ deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  await page.setViewportSize({ width: PW, height: PH });
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
    </body>`);
  await page.evaluate(() => Promise.all([...document.images].map(i => i.decode().catch(() => {}))));
  await page.waitForTimeout(200);
  await (await page.$('#cmp')).screenshot({ path: path.join(OUT, out) });
  await ctx.close();
  console.log('wrote', out);
}

// ── Lossless optimisation ────────────────────────────────────────────────────────────────────────

function hasOptipng() {
  try { execFileSync('optipng', ['--version'], { stdio: 'ignore' }); return true; }
  catch { return false; }
}

// Install optipng on demand (Debian/Ubuntu host) so the caller doesn't need it preinstalled.
function installOptipng() {
  const sudo = process.getuid && process.getuid() !== 0 ? 'sudo ' : '';
  console.log('optipng not found — installing it via apt-get…');
  execSync(`${sudo}apt-get update && ${sudo}apt-get install -y optipng`, { stdio: 'inherit' });
}

// Shrink every PNG losslessly (optipng -o2 typically saves ~10%). Pure metadata/encoding change —
// the pixels are untouched, so it never alters how a preview looks.
function optimise() {
  if (!hasOptipng()) installOptipng();
  const files = fs.readdirSync(OUT).filter(f => f.endsWith('.png')).map(f => path.join(OUT, f));
  execFileSync('optipng', ['-quiet', '-o2', ...files], { stdio: 'inherit' });
  console.log(`optimised ${files.length} PNGs`);
}

// ── Main ───────────────────────────────────────────────────────────────────────────────────────

const CALENDAR_VIEWS = ['full', 'minimal', 'stacked'];
const FONTS = ['nova', 'standard'];

// Capture the full set (theme + calendar pickers) for one viewport. `suffix` is appended before the
// extension ('' for web, '-mobile' for the portrait phone shots). Prefs are stored server-side on the
// demo user, so the same login drives both viewports; the only difference between a web and mobile
// pair is the viewport. Each (calendar style, theme) combo yields BOTH a full-page theme shot AND a
// calendar-only shot from the same page load — so the theme picker has one preview per calendar style
// (settings.html then shows the one matching the user's selected style).
async function captureSet(ctx, suffix) {
  const f = base => base + suffix + '.png';

  for (const font of FONTS) {
    for (const view of CALENDAR_VIEWS) {
      for (const theme of ['light', 'dark']) {
        await setPrefs(ctx, theme, view, font);
        const page = await openDashboard(ctx, view);
        await shotFullPage(page, f(`page-${font}-${view}-${theme}`));  // Theme + Font pickers: whole page
        await shotCalendar(page, f(`cal-${font}-${view}-${theme}`));   // Calendar picker: calendar only
        await page.close();
      }
    }
  }
}

// Build the `system` theme preview (diagonal light/dark split) — full-page only, for every font × style
// (the Theme picker's System tile resolves to this; the Calendar picker has no System tile).
async function compositeSystemAll(browser, suffix) {
  const s = suffix;
  for (const font of FONTS) {
    for (const view of CALENDAR_VIEWS) {
      await compositeSystem(browser, {
        light: `page-${font}-${view}-light${s}.png`,
        dark: `page-${font}-${view}-dark${s}.png`,
        out: `page-${font}-${view}-system${s}.png`,
      });
    }
  }
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();

  // Web (landscape) capture context — also does the one-time data seeding.
  const webCtx = await browser.newContext({
    viewport: { width: VW, height: VH },
    deviceScaleFactor: 2,
    timezoneId: 'UTC',
    colorScheme: 'light',
  });
  await seed(webCtx);
  await captureSet(webCtx, '');
  await compositeSystemAll(browser, '');
  await webCtx.close();

  // Mobile (portrait) capture context — same seeded data, phone-width viewport. Logs in afresh
  // because cookies are per-context; prefs are shared (stored on the user), so it reuses captureSet.
  const mobileCtx = await browser.newContext({
    viewport: { width: MVW, height: MVH },
    deviceScaleFactor: 2,
    timezoneId: 'UTC',
    colorScheme: 'light',
    isMobile: true,
    hasTouch: true,
  });
  await login(mobileCtx);
  await captureSet(mobileCtx, '-mobile');
  await compositeSystemAll(browser, '-mobile');
  await mobileCtx.close();

  await browser.close();

  optimise();
  console.log('\nDone — review and commit the PNGs in\n  ' + path.relative(path.join(__dirname, '..'), OUT));
})().catch(e => { console.error(e); process.exit(1); });
