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
 *   theme-light.png / theme-dark.png / theme-system.png  (theme picker; system = diagonal split)
 *   calendar-full.png / calendar-minimal.png / calendar-stacked.png  (calendar-style picker)
 * Every screenshot uses the SAME seeded data so the only visible difference is the theme / calendar
 * style — that is the whole point of the previews.
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
const VW = 1200, VH = 820; // dashboard capture viewport (16:11-ish landscape)

// Dedicated demo account — kept separate from real dev data.
const USER = { email: 'preview-demo@diurnal.local', password: 'preview_demo123', displayName: 'Preview Demo' };

// The fixed seed: four colourful habits logged across the current month. `days` are days-of-month
// (those after today are skipped — the server blocks logging future dates); `count` is how many
// times each is incremented so the "full" calendar shows a representative "×N".
const ACTIONS = [
  { name: 'Exercise', colour: '#ef4444', count: 1, days: [2, 4, 6, 9, 11, 13, 15] },
  { name: 'Read',     colour: '#3b82f6', count: 2, days: [1, 2, 3, 5, 8, 10, 12, 14] },
  { name: 'Meditate', colour: '#10b981', count: 1, days: [1, 2, 5, 7, 9, 12, 15] },
  { name: 'Water',    colour: '#f59e0b', count: 3, days: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15] },
];

const pad = n => String(n).padStart(2, '0');

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

// Keys (`date|colour`) for logs that already exist this month, so re-runs don't inflate counts.
async function existingLogKeys(ctx, y, mo) {
  const start = `${y}-${pad(mo)}-01`;
  const end = mo === 12 ? `${y + 1}-01-01` : `${y}-${pad(mo + 1)}-01`;
  const events = await (await ctx.request.get(`${BASE}/logs/events?start=${start}&end=${end}`)).json();
  return new Set(events.map(e => `${e.start}|${(e.backgroundColor || '').toLowerCase()}`));
}

async function seed(ctx) {
  await registerDemoUser(ctx);
  await login(ctx);

  const now = new Date();
  const y = now.getUTCFullYear(), mo = now.getUTCMonth() + 1, today = now.getUTCDate();

  const existing = await existingActions(ctx);
  const have = await existingLogKeys(ctx, y, mo);

  for (const action of ACTIONS) {
    const id = await ensureAction(ctx, existing, action);
    for (const day of action.days) {
      if (day > today) continue; // future dates are rejected by the server
      const date = `${y}-${pad(mo)}-${pad(day)}`;
      if (have.has(`${date}|${action.colour.toLowerCase()}`)) continue; // already logged
      for (let i = 0; i < action.count; i++) {
        await ctx.request.post(`${BASE}/logs/${date}/${id}/increment`);
      }
    }
  }
  console.log('seeded demo data');
}

// ── Screenshot capture ───────────────────────────────────────────────────────────────────────────

async function setPrefs(ctx, theme, calendarView) {
  // Persist prefs via the same endpoint the settings form posts to.
  const res = await ctx.request.post(BASE + '/settings',
    { form: { theme, pageSize: '10', calendarView, timezone: 'UTC' } });
  if (!res.ok()) throw new Error('setPrefs failed: ' + res.status());
}

async function shotDashboard(ctx, calendarView, file) {
  const page = await ctx.newPage();
  await page.goto(BASE + '/', { waitUntil: 'networkidle' }); // the dashboard is served at /
  // Wait for the calendar's activity markers so events are visibly loaded before capturing.
  const sel = calendarView === 'full' ? '.fc-event'
            : calendarView === 'stacked' ? '.lt-stk-bar'
            : '.lt-min-dot';
  await page.waitForSelector(sel, { timeout: 15000 });
  await page.waitForTimeout(600); // settle fonts/layout
  await page.screenshot({ path: path.join(OUT, file), clip: { x: 0, y: 0, width: VW, height: VH } });
  await page.close();
  console.log('wrote', file);
}

async function compositeSystem(browser) {
  // System theme = diagonal split of the light & dark dashboards (light upper-left, dark
  // lower-right; divider runs corner-to-corner top-right → bottom-left). Inlined as base64 so the
  // page has no cross-origin/file:// loading concerns.
  const lightB64 = fs.readFileSync(path.join(OUT, 'theme-light.png')).toString('base64');
  const darkB64 = fs.readFileSync(path.join(OUT, 'theme-dark.png')).toString('base64');
  const ctx = await browser.newContext({ deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  const PW = VW * 2, PH = VH * 2; // screenshots are 2x
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
  await (await page.$('#cmp')).screenshot({ path: path.join(OUT, 'theme-system.png') });
  await ctx.close();
  console.log('wrote theme-system.png');
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

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    viewport: { width: VW, height: VH },
    deviceScaleFactor: 2,
    timezoneId: 'UTC',
    colorScheme: 'light',
  });

  await seed(ctx);

  // The theme picker shows one fixed preview per theme (full calendar). The calendar picker shows
  // each style in BOTH themes — settings.html picks the light/dark variant matching the active mode
  // — so every calendar style is captured light AND dark. (calendar-full-{light,dark} are just the
  // theme-{light,dark} shots reused.)
  await setPrefs(ctx, 'light', 'full');
  await shotDashboard(ctx, 'full', 'theme-light.png');
  fs.copyFileSync(path.join(OUT, 'theme-light.png'), path.join(OUT, 'calendar-full-light.png'));

  await setPrefs(ctx, 'dark', 'full');
  await shotDashboard(ctx, 'full', 'theme-dark.png');
  fs.copyFileSync(path.join(OUT, 'theme-dark.png'), path.join(OUT, 'calendar-full-dark.png'));

  await setPrefs(ctx, 'light', 'minimal');
  await shotDashboard(ctx, 'minimal', 'calendar-minimal-light.png');
  await setPrefs(ctx, 'dark', 'minimal');
  await shotDashboard(ctx, 'minimal', 'calendar-minimal-dark.png');

  await setPrefs(ctx, 'light', 'stacked');
  await shotDashboard(ctx, 'stacked', 'calendar-stacked-light.png');
  await setPrefs(ctx, 'dark', 'stacked');
  await shotDashboard(ctx, 'stacked', 'calendar-stacked-dark.png');

  await compositeSystem(browser);

  await ctx.close();
  await browser.close();

  optimise();
  console.log('\nDone — review and commit the PNGs in\n  ' + path.relative(path.join(__dirname, '..'), OUT));
})().catch(e => { console.error(e); process.exit(1); });
