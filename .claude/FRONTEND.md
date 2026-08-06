# Front-end: Build, Assets, CSS & Calendar

> The Tailwind build pipeline, colour tokens/component classes, content-hashed served scripts, shared data-table
> styling, static-asset caching, settings preview thumbnails, brand assets, the Font/typography setting and the
> hand-rolled dashboard-calendar engine. Extracted from `CLAUDE.md`; read before editing CSS, `frontend/`, any
> `/js/*.js`, a data-table or the calendar. Complements [`UI_PATTERNS.md`](UI_PATTERNS.md) (template/markup rules).

### CSS build & colour tokens

Tailwind is compiled (not CDN). `frontend/css/app.css` (the committed source) is built into `src/main/resources/META-INF/resources/css/app.css` (the
served output). **Rebuild with `npm --prefix frontend run css` after any class change in templates or Java** or the class will be purged.

The compiled output is a **build artifact, not committed** (`.gitignore`d). Every Maven build regenerates it: the POM's `exec-maven-plugin`
`css-build` execution runs `npm run css` in `generate-resources` (before resources are copied/packaged), so `package`/`*IT`/E2E always bundle a fresh
stylesheet. This needs `frontend/node_modules` (`npm --prefix frontend install` once). The Docker build instead compiles the CSS in a dedicated `css`
stage and copies it in, passing `-Dcss.build.skip=true` to `mvn package` so the Node-less Maven image skips the exec. Dev mode (`quarkus:dev`) serves
the on-disk file directly — keep `npm --prefix frontend run css:watch` running, or run `npm --prefix frontend run css` manually, to refresh it.

Colour tokens: `app.css` defines `--color-*` CSS variables (`:root` + `.dark`). Tailwind exposes semantic utilities: `bg-surface`/`bg-surface-muted`,
`text-ink`/`text-ink-muted`, `border-line`/`border-line-subtle`, `text-brand`, `bg-brand`, `text-success`, `text-danger`. Use these instead of raw
`gray-*`/`indigo-*`.

**The brand colour is generated — never hand-edit it.** The `--color-brand*` family lives in `@generated:brand` regions of `app.css`, computed by
`scripts/generate-brand.py` from the `fill` of `assets/wordmark.svg` (the single source of truth). To rebrand: change the `fill`, then
`npm --prefix frontend run brand`. Base colour: `#6366f1`, constant across light and dark.

Every accent must resolve to the brand: `.btn-primary`, active nav links, log increment `+`, focus rings, calendar "today" fill, Edit button, edit-row
highlight. Route new accented elements through `bg-brand`/`text-brand`/`border-brand`/`ring-brand-ring`/`text-on-brand` — **never a literal `indigo-*`
**.

Extra tokens consumed as `var(--color-*)` in inline CSS: `--color-brand-strong`/`-subtle`/`-faint`/`-ring`/`-ring-edit`, `--color-danger-strong`,
`--color-text-strong`/`-faint`, `--color-input-bg`/`-border`, `--color-banner-{error,success,warning}-{bg,border,text}`.

Component classes in `app.css @layer components`: `.btn-primary`, `.btn-secondary`, `.card`, `.stat-tile`, `.form-input`, `.form-select`,
`.field-label`, `.field-label-caps`, `.help-text`, `.nav-link`/`.nav-link-active`, `.swatch`/`.swatch-sm`/`.swatch-md`, `.app-tooltip`.

The stable component CSS that used to live in the templates' inline `<style>` blocks — the shared data-table (`.dt-*`)
styling, the settings-field chrome, the theme-transition rules, the message banners (all from `layout.html`) and the
dashboard calendar styling (`.d-*`, `.cal-*` from `dashboard.html`) — now lives at the **bottom of `app.css` as plain CSS
(NOT inside `@layer`)**. It rides the compiled, content-hashed, `immutable` stylesheet instead of being re-transferred
on every no-cache navigation. It is kept un-layered on purpose: exactly as it was inline (un-layered, after the linked
sheet), so it still wins over Tailwind's layered utilities — which is why the defensive `[data-dt-view].hidden` /
`[data-dt-edit].hidden` re-assertions are retained. Every colour is a `var(--color-*)` token, so no `.dark` twins are needed.

### Served front-end scripts (content-hashed, `immutable`)

Nine scripts are served from `META-INF/resources/js/` and referenced from the templates via
`{inject:appInfo.*}`, all sharing one cache-busting pattern: served un-hashed in dev (`no-store`), and at image-build
time the Dockerfile esbuild-minifies the eight handwritten scripts (`npm run js:min` in the `css` stage — the committed
sources stay readable; only the image ships the minified form), then content-hashes them. **All asset hashing lives in
one place — `scripts/hash-static-assets.sh`** (invoked by a single `RUN` in the Dockerfile's build stage): it renames
every fingerprinted asset (CSS, the 9 scripts, the settings thumbnails, the vector marks) to `name.<sha256-12>.ext`
(the hash is inserted after the first name segment, so `htmx.min.js` → `htmx.<hash>.min.js`) and bakes the hashed name
into `microprofile-config.properties` (read by `AppConfig`/`AppInfo`). All are then served
`public, max-age=31536000, immutable` by the single `app-immutable` filter (`application.properties`). See
"Static-asset caching" below for the full model.

- `htmx.min.js` (`AppInfo.jsFile`) — **vendored** from npm by `scripts/vendor-assets.cjs` (`.gitignored` build artifact).
- `app.js` (`AppInfo.jsAppFile`) — the shared per-page behaviour extracted from `layout.html` (dt edit/confirm toggles,
  form validation + AJAX submit, locale number grouping, the tooltip long-press, the password-requirements popover, the
  delegated `htmx:configRequest` search-filter listener, the mobile-menu toggle, the delegated
  `[data-random-colour]` suggestion handler + the `Diurnal.suggestColourInto(input, url, keep)` helper behind it, which
  `actions.js` reuses to re-randomise the new-action picker after each add). A **committed** handwritten file.
  Loaded as a classic script at the end of `<body>` on every page, so the document is parsed when it runs and its
  document-level handlers register in the original order (the `data-validate` handler must precede `data-ajax-submit`).
- `dashboard.js` (`AppInfo.jsDashboardFile`) — the hand-rolled calendar engine extracted from `dashboard.html`. A
  **committed** file, loaded only on the dashboard. Its two server-injected values (the app's UTC `today` and the user's
  `calendarView`) arrive via `data-today`/`data-calendar-view` attributes on `#dashboard-main`, read directly off
  `dataset` — no inline bootstrap. Because it is a plain `.js` file (not a Qute template) the `{`-escaping caveat below
  no longer applies to it. Two self-contained units sit at its top before the calendar engine, each with one job and no
  dependency on the rest of the file: `createFragmentCache(dayUrl, monthUrl)` (the per-day HTML cache the day panel and
  the stats summary are both built from) and `createInkMetrics()` (pure canvas typography — glyph widths and the optical
  centring of the calendar's date numbers; call `reset()` after a web font swaps in, since every cached measurement was
  taken against the fallback face).
- `actions.js`, `admin-users.js`, `admin-api-docs.js`, `settings.js` (`AppInfo.jsActionsFile`/`jsAdminFile`/
  `jsApiDocsFile`/`jsSettingsFile`) — the page-specific behaviour extracted from `actions.html`, `admin-users.html`,
  `admin-api-docs.html`, and `settings.html` respectively (extracted during the CSP hardening), each
  loaded only on its own page and wired via `data-*` hooks + `addEventListener` (no inline `on*=`/`hx-on=` attributes
  remain anywhere in the app — see "Security headers / CSP" below).
- `note.js` (`AppInfo.jsNoteFile`) — the dashboard's day-note box: the textarea, its caches, the Save/Undo/Clear
  controls, the character counter, the drag-resize and the unsaved-work unload guard. A **committed** file, loaded only
  on the dashboard and **before `dashboard.js`**, which reads the `Diurnal.noteBox` module it publishes. Split out of
  `dashboard.js` once that file held the calendar engine, three caches, the day panel, the summary and all of this; the
  seam is real rather than cosmetic, because the calendar needs only to know WHICH days have a note (for its green day
  numbers) plus hooks to load, clear and evict them — that interface is the whole of what the module exposes. With no
  `#note-panel` in the DOM every method is a no-op.
- `stats.js` (`AppInfo.jsStatsFile`) — the Stats page's frequency-graph dialog. A **committed** file, loaded only on
  `/stats`. It holds no charting code and no copy of the chart's wording: it opens/closes the dialog and re-fetches the
  server-rendered `partials/stats-chart.html` fragment whenever the selection changes, reading the current
  period/window/comparisons back off the fragment's own `data-chart-shown-*` attributes (see "Stats-page frequency
  graph" below).

> **The FOUC-critical theme bootstrap stays inline in `<head>` (`layout.html`)** — it must run before the stylesheet
> loads and the `system` option needs `prefers-color-scheme`, which can't be resolved server-side. It reads the theme
> from `data-theme` on `<html>` (server-rendered, mirroring `.font-nova`) rather than having Qute interpolate the value.
> So its bytes are byte-static across every render and are covered by a pinned CSP hash instead of being inline-allowed
> (see "Security headers / CSP" below).

**Tooltips**: the app's single tooltip style is `.app-tooltip` (theme-matched: `bg-surface`/`text-ink`/`border-line` + shadow), rendered
via **`partials/tooltip.html`** (`text`/`pos`/`align` params). Put it inside a host with `group relative` (and an `aria-label`, since the
bubble is `aria-hidden`); it reveals on **hover** (desktop, CSS `.group:hover > .app-tooltip`) or a **long press** (touch) via the global
handler in `layout.html`, which adds `.tip-open` to the host and swallows the press's click. Icon buttons across the app (calendar
toolbar, day-panel +/−/erase, colour pickers, navbar) use this instead of a native `title=`. The Action-stats picker manages its OWN
hosts (they also drag/toggle) in its own script, so the global handler skips `#stats-fields-list`. **Never use a native `title=` for a
hover tooltip** — use this component so styling + the touch long-press stay consistent; edge buttons pass `align="left"`/`"right"` so
the bubble can't push the page sideways.

### Shared data-table styling (`.dt-*`)

All tables (Actions, Users, future) share `.dt-*` classes in a `<style>` block in `layout.html` (every colour is `var(--color-*)`). Wrap in
`.dt-table`, use `.dt-row`/`.dt-cell`, include `partials/pagination` for the footer.

**Two variants:** non-editable (just `.dt-row`/`.dt-cell`) and editable (in-place client-side toggle via `dtStartEdit`/`dtCancelEdit`, not a server
round-trip). Each row renders `[data-dt-view]` + `[data-dt-edit]` states.

Shared editable-row chrome:

- `partials/dt-row-actions.html` — trailing cell: Edit + Delete (view) / Save + Cancel (edit). Parameterised by `id`, `rowPrefix`, `formPrefix`,
  `confirmBase`. View actions reveal on hover/focus-within only.
- `partials/dt-confirm-delete-row.html` — in-place confirm-delete row, rendered from the resource via
  `.data(rowId, cols, swatchColour, label, prompt, deleteUrl/deleteTarget/deleteSwap, restoreUrl)`.
- `.dt-row-highlight` — inset `box-shadow` ring; colour from `--dt-highlight` (`.dt-row-edit` = indigo, `.dt-row-confirm` = red). Edit rows trim cell
  padding to keep the same row height.

Cross-table conventions: explicit Save tick required (only exception: Settings → User Preferences); at most one 'armed row' at a time (
`dtClearArmedRows` disarms others); destructive button left, Cancel right. `partials/pagination.html` exposes `#showing-shown`/`#showing-total` for
surgical HTMX count updates.

### Dashboard calendar (hand-rolled, no library)

All three calendar styles (`full`/`minimal`/`stacked`, the `CalendarView` enum, default `full`) are drawn by **one** vanilla-JS engine,
`buildGridCalendar()` in `dashboard.html` — a shared 7×6 / 42-cell, Sunday-first month grid with its own month cache, LRU eviction and idle prefetch (
`±2` months). There is no FullCalendar (or any) calendar library. `calendarView` only changes (a) which feed `fetchMonth` reads — `full` →
`/api/v1/logs/events`, others → `/internal/logs/minimal-events`, both normalised into a uniform `dayData[date] = [{colour, label}]` — and (b) how
`renderGrid` paints each cell: `full` = bordered cell with top-right day number + an uncapped event list (`.d-full-*`); `minimal` = centred date
circle + dots (`.d-min-dot`); `stacked` = circle + bars (`.d-stk-bar`). Every cell is a shared `.d-min-cell[data-date]` carrying `.d-min-today`/
`.d-min-selected`/`.d-min-other`; the active style is mirrored onto `#calendar-wrap` and `#d-min-grid` as `.d-cal-{view}` so the `full` look is
CSS-scoped. The shared chrome (toolbar, jump picker, day-panel load, the verb-gated `htmx:afterRequest` → `cal.refresh()`) drives a 4-method adapter (
`currentView`/`goToMonth`/`setHighlight`/`refresh`). **When the dashboard calendar appearance changes, regenerate the settings previews** (see below).

### Dashboard layout & the note box

The dashboard's four panels live in **ONE grid**, placed explicitly into a 2x2 arrangement at `lg`+:

```
row 1:  calendar (cols 1-2)        |  day logger (col 3)
row 2:  stats summary (cols 1-2)   |  note box    (col 3)
```

So the summary is exactly the calendar's width, the note sits in the logger's column, and the summary and the note
share row 2 **with their tops level**. Grid items are left to STRETCH (no `items-start`), which is what makes the logger
fill row 1 so the note still sits directly beneath it however few actions the day has. DOM order is calendar, logger,
note, summary — exactly the order wanted once the grid collapses to one column — so the placements are all
`lg:`-prefixed and no `order-*` utilities are needed.

> Two arrangements were tried and rejected: a full-width summary below the grid (not "alongside" the note at all), and
> nesting the logger+note in a flex column spanning both rows (which made the note hug the logger but left it floating
> well above the summary whenever the logger was short). See [`NOTES.md`](NOTES.md).

The **note box** (`#note-panel`) is server-rendered ONCE with the page and is **never a swap target**: changing the
selected day only rewrites the textarea's value from `dashboard.js`'s cache. That is what makes its drag-resized
dimensions durable across a date change with no re-application, and it is why the card is written inline in
`dashboard.html` rather than as a partial (single use — see [`UI_PATTERNS.md`](UI_PATTERNS.md) §1). Its `.note-*` CSS
family lives un-layered at the bottom of `app.css` beside `.d-*` and `.chart-*`.

- **Default size**: `--note-default-h` is derived from a three-row stats-summary card's own metrics, so the two panels
  sit level; pinned by a Playwright guard that renders a real three-action summary and compares heights.
- **Resize**: three hand-rolled Pointer Events handles (right edge, bottom edge, corner) — native `resize: both` offers
  only a corner grip and cannot do edges. The floor is measured **off the panel itself** (drop its inline width for one
  layout read on pointerdown, then restore), never off a sibling: the day logger shares the note's grid column, so
  deriving the floor from it let the floor climb with every drag.
- **Buttons**: Save / Undo / Clear. Clear empties the box WITHOUT writing, so no single click is destructive; it is
  hidden unless the stored note is non-empty. Save and Undo are inert unless the box is dirty, and the save handler
  re-checks, so a no-op write is never sent.
- **Status line**: "Unsaved changes" in `text-brand` (the active navbar link's colour), "Saved" in `text-success` (the
  settings cards' green), flashed for 2s. Both are set explicitly at each call site — deriving them from the dirty flag
  is what made an earlier version clear "Saved" the instant it was set.
- **The unsaved draft survives a navigation**, mirrored into `sessionStorage` under `diurnal.noteDraft` as
  `{date, content}` — **exactly one**, the day last edited, written on every keystroke and removed once the draft catches
  up with the stored note (a save or an undo). Drafts on other days still ride the in-memory per-date map across a date
  change; they just do not survive a page load. That is the tab's own lifetime: in-app navigation and reloads keep the
  draft, closing the tab drops it, and `app.js` clears the key on the login page beside `diurnal.selectedDate` so a
  journal entry never carries across a logout. **No `beforeunload` prompt** — an earlier version raised the browser's
  confirmation on every in-app click and was replaced by retaining the work; the status line is the whole of the signal.

### Calendar note markers & the split month cache

A day with a note gets a **coloured day number** (`.d-note-day` on the shared `.d-min-cell`, so one rule covers all
three styles), painted with **`--note-colour`** — an inline custom property `dashboard.html` sets on `#calendar-wrap`
from the user's `noteColour` preference (default green-600). It is the picked value **verbatim in both themes**, like an
action's colour, so there is deliberately no `.dark` twin; the `:root` declaration is only the fallback for a render
that has not set it. **Today is the one exception**: its number sits on a solid brand fill where green-600 (and any
other dark pick) is ~1.4:1, so it takes **`--note-colour-on-brand`** — the same colour raised up the HSL lightness axis
by `Colours.readableOn` until it clears 3:1, computed server-side per request. The number still goes the note colour,
which is the whole signal. An earlier version kept today's number white and marked it with a thin underline or a ring;
both were reported as the marker "not working". Full rationale (including why it is ONE picker, not a light/dark pair):
[`NOTES.md`](NOTES.md).

Notes ride the calendar's **existing** per-month cache, with their own promise map, loaded flag and radius:

| Shared (one implementation) | Split (per data type) |
|---|---|
| `lru` recency list + `touch` | `monthPromises` / `notePromises` |
| `CACHE_LIMIT = 12` | `monthLoaded` / `monthNotesLoaded` |
| `PINNED_MONTHS` (prev/current/next) | `PREFETCH_RADIUS` 2 / `NOTE_PREFETCH_RADIUS` 1 |
| `evictIfNeeded` (resident if EITHER side is loaded) | the merge function |
| `dropMonth` — clears `dayData` **and** the note cache | |

Two radii mean a month can hold one side without the other, so `fetchAndRender`'s early-out is **two-sided** and
`Promise.all`s whatever is missing, giving one repaint rather than a flicker per side. Selecting a day whose month is
resident costs **no request at all** — which is why the note box needs no per-day endpoint.

### Dashboard stats summary (follows the selected day)

The card under the calendar (`partials/stats-summary.html`, hosted by the stable `#stats-summary` wrapper, gated on the
`showStatsSummary` preference) summarises **the selected day**: that day's three most-logged actions, each row carrying the user's
**top three enabled "Action stats"** in their chosen order. The daily counts only *rank* which actions appear — every figure shown
still spans the action's whole history, so the tiles read the same as the Stats page's.

It is cached client-side exactly like the day panel — literally so: both are instances of `dashboard.js`'s one
`createFragmentCache(dayUrl, monthUrl)` factory, which is the only place that eviction, in-flight de-duplication and the
idle back-fill are implemented, so a change to any of them applies to both. `dashboard.js` fetches the selected day from
`/internal/stats/summary/{date}`, then one idle `/internal/stats/summary-month/{yyyy-MM}` request back-fills the rest of
the month, capped at 12 resident months (LRU). The server-rendered card the page ships for its initial day is **seeded into the cache** off `#stats-summary`'s
`data-summary-date`, so opening the dashboard on today costs no summary request. Because the swap is a plain `fetch` +
`innerHTML` (no HTMX event), it re-runs `Diurnal.formatNumbers()` and `Diurnal.fitFigures()` by hand.

**Invalidation is whole-cache, deliberately.** The tiles report whole-history figures, so logging against *any* day moves the
numbers shown on *every* day — dropping just the edited date would leave the rest stale. A day-panel mutation therefore clears the
whole summary cache and reloads only the visible card; the month back-fill re-arms on the next day the user selects, so a run of
increments never fires a bulk fetch per tap.

### Typography & Font setting

Webfonts served as `woff2` from `src/main/resources/META-INF/resources/fonts/`, with `@font-face` blocks in `app.css`: the **Nova** superfamily —
**Nova Flat** (body/UI) and **Nova Round** (display/headings) — plus **OpenDyslexic** (an SIL-OFL accessibility face; Regular/Bold each with an
italic, used as both body and display face). Master files live outside `src/` in `assets/Nova/` (`.ttf`) and `assets/OpenDyslexic/` (`.otf` +
`OFL.txt`); the served `woff2` are generated from them (Nova via the curated masters, OpenDyslexic via fontTools `TTFont(otf).flavor='woff2'`).

Font family is indirect via `--font-body`/`--font-display` CSS variables. The **Font setting** is the `Font` enum (`nova`|`standard`|`dyslexic`,
default `nova`; column `users.font` is `VARCHAR(16)`, no CHECK, migration V13, so new values need no migration) — the single source of truth for the
picker, each constant carrying its value + label + preview metadata (see the picker-enum note below). `WebResource.updateFont` coerces the submitted
value via `Font.from(raw).value()`. `layout.html` renders the class on `<html>` server-side
(`{#if font == 'dyslexic'}font-dyslexic{#else if font != 'standard'}font-nova{/if}`), no FOUC, and preloads that theme's primary face; `standard`
renders no class (system sans). The settings picker toggles the same classes live (`settings.js`). **`font` must be passed to every full-page
template** (mirror `theme` 1:1; HTMX day-panel partials need neither).

**Settings preview-tile pickers (Theme / Font / Calendar style) are enum-driven.** Each is a Java enum (`Theme`, `Font`, `CalendarView`) implementing
`PreviewOption` (`value`/`label`/`title`/`alt`/`previewImage`), following the `StatField` "single source of truth" pattern. `WebResource` passes
`X.values()` to `settings.html`, which **loops** the constants into `partials/preview-option.html` (no hardcoded parallel tiles), and each
submitted value is validated via `X.isValid(raw)` (an unrecognised value is REJECTED — 422 on the web, 400 on the API — never silently coerced;
unit-tested to 100% PIT). The DB columns stay `String` (not `@Enumerated`); templates compare raw values, so a legacy/unknown stored value simply
renders as the default without throwing. To add an option: add a constant (+ its CSS
class/preview WebP/JS branch) and it appears in the picker automatically — no template change. **Timezone is deliberately NOT an enum** (a curated
`List<String>` of IANA ids ordered dynamically by offset via `UserSettings.timezoneChoices`).

### Brand assets

No logo/icon mark — purely typographic. **`assets/wordmark.svg` is the single source of truth** (outside `src/`, not packaged by Maven).
Everything under `src/main/resources/META-INF/resources/img/` is generated output.

**To rebrand: change `fill` in `wordmark.svg`, then `npm --prefix frontend run brand`** — chains `generate-brand.py` → `generate-favicons.cjs` →
`npm run css`. Docker re-renders rasters from committed `favicon.svg` but does not run `generate-brand.py`.

Served assets: `wordmark.svg` (navbar/headings), `favicon.svg` (scalable favicon), `footer-mark.svg` (snug "d" for footer). Rasters: `favicon.ico` (
16/32/48, at web root), `icon-192.png` (Chromium-Android tab icon — **must** be a `<link rel="icon">` tag, not just manifest), `icon-512.png` (PWA
manifest pair), `apple-touch-icon.png` (180px iOS), `manifest.json`.

### Settings preview thumbnails

Theme, Calendar style, and Font pickers show real dashboard screenshots (via `partials/preview-option.html`). WebP files in
`src/main/resources/META-INF/resources/img/settings/`, one viewport set (web).

> **These 8 thumbnails are NOT committed — they are generated INSIDE the Docker build** (`.gitignore`d, like the compiled CSS
> / vendored htmx). The Dockerfile's `screenshots` stage boots a throwaway Postgres + a `previewbuild` fast-jar + headless
> Chromium and runs `scripts/generate-screenshots.cjs app` (via `scripts/run-screenshot-build.sh`) to capture them, then the
> `build` stage copies them in and content-hashes them. So **any** `docker build` / `docker compose up --build` produces fresh
> previews with nothing committed — no wrapper, no CI plumbing. The cost is a heavier build (extra app build + Postgres +
> Chromium); the **smoke/perf** compose files pass the `GENERATE_PREVIEWS=false` build arg to skip the whole preview
> toolchain (those tiers don't use the previews), and `hash-static-assets.sh` then skips them (`AppInfo.settingsImage` falls
> back to the un-hashed `<base>.webp` name). A dev / `mvn package` run likewise has none — the `<img>` attribute is still
> present (fallback name), the file just 404s locally. The committed README shots live under `docs/screenshots/` instead (see
> below).

**8 WebP files**, fixed per picker:

- Theme: `page-nova-full-{system,light,dark}.webp`
- Calendar: `cal-nova-{full,minimal,stacked}-dark.webp`
- Font: `page-{nova,standard,dyslexic}-full-dark.webp`

`page-nova-full-dark` is shared by the Theme-dark and Font-nova tiles.

Loading: `data-src` instead of `src` (no fetches until JS assigns). Two-phase load: visible images immediately, then `requestIdleCallback` for the
rest.

Thumbnails use a fixed-ratio frame (`aspect-[3/4] sm:aspect-[3/2]` in `.preview-thumb`), cropped to the top — not tied to image aspect ratios. Route
any future settings thumbnail through `partials/preview-thumb.html`.

**Cache-busting (content-hashed, like CSS/JS).** These WebP files are **content-hashed at image-build time** (by
`scripts/hash-static-assets.sh`, which now first asserts the generated thumbnails are present — see the uncommitted-artifact
note above) exactly like the `/css/`+`/js/` assets: each is renamed `<base>.<hash>.webp` and a
`base→hashed` map is baked into `microprofile-config.properties` (`app.assets.settings-images.<base>=…`).
`AppConfig.settingsImages()` exposes that map; `AppInfo.settingsImage(base)` resolves it (falling back to the un-hashed
`<base>.webp` when the map is empty — a non-Docker `mvn package`/dev run); `preview-thumb.html` emits
`/img/settings/{inject:appInfo.settingsImage(imgBase)}`. Because the enum-driven `imgBase` (`PreviewOption.previewImage`) can't
carry a per-file config key like the fixed CSS/JS names, the map is the indirection (the top-level `/img/` vector marks use the
same trick — `AppConfig.hashedImages()` / `AppInfo.image('wordmark.svg')`). See "Static-asset caching" below for how the served
URLs are cached.

### Static-asset caching

Two `quarkus.http.filter` rules cover every served static asset (`application.properties`; both overridden to `no-store` in dev):

- **`app-immutable`** — `public, max-age=31536000, immutable`, for everything the build **content-hashes**
  (`scripts/hash-static-assets.sh`): `/css/*`, `/js/*`, the settings thumbnails `/img/settings/*`, and the top-level vector
  marks `/img/*.svg` (wordmarks + `favicon.svg`). A hashed URL changes only when the bytes change, so caching it forever is
  safe. Referenced via `AppInfo` (`cssFile`/`js*File`/`settingsImage`/`image`), all falling back to the un-hashed name when the
  build config is absent (dev/`mvn package`).
- **`app-static`** — `public, max-age=604800` (7-day, **not** `immutable`), for the assets that **cannot** be hashed and so
  keep a stable URL: the woff2 fonts (referenced by `@font-face` inside the compiled CSS — deliberately NOT rewritten, too
  brittle), the raster app-icons `/img/*.png` (`icon-192`/`512` are pinned by `manifest.json`; `apple-touch`), `/favicon.ico`
  (browsers probe that fixed root path) and `/manifest.json`. Bounded so a re-brand propagates within a week.

The two regexes are provably disjoint (`/img/*.svg`+`/img/settings/` vs `/img/*.png`), so they never fight over `Cache-Control`.
`html-pages` (`no-cache`) and `swagger-ui-assets` are unchanged. Covered by `CacheHeadersIT` (immutable on css/js/svg/settings,
7-day on `/img/*.png`), `AppInfoTest`/`AppConfigTest` (the map lookups, fallbacks and hyphenated-key binding). **To hash a new
asset:** add a `bake` line to `scripts/hash-static-assets.sh` + wire its `AppInfo` reference; to add one that can't be hashed,
ensure it falls under an `app-static` alternative.

**Regenerate screenshots — `scripts/generate-screenshots.cjs <mode>`** (needs a live dev server; `scripts/dev-up.sh`
first, `scripts/dev-teardown.sh` after). There are **two independent sets**, split by mode — pick the one you mean:

```bash
scripts/dev-up.sh
node scripts/generate-screenshots.cjs app             # the 8 in-app thumbnails (img/settings/, UNCOMMITTED)
node scripts/generate-screenshots.cjs documentation   # the 13 README shots (docs/screenshots/, COMMITTED)
node scripts/generate-screenshots.cjs all             # both (default)
scripts/dev-teardown.sh
```

> **`app` vs `documentation` are NOT the same set and are refreshed on different cadences.**
> - **`app`** → the **8** Settings preview thumbnails in `img/settings/` (Theme/Calendar/Font pickers, listed above).
>   These are **uncommitted build artifacts** — you rarely run this by hand; the Docker build's `screenshots` stage runs
>   `generate-screenshots.cjs app` for you (see the note under "Settings preview thumbnails"), so every image has current
>   previews. Running it manually just writes them into the (gitignored) `img/settings/` for a local eyeball.
> - **`documentation`** → the **13** committed README screenshots in `docs/screenshots/`: `dashboard-{system,dark,light}`,
>   `dashboard-mobile`, `cal-{full,minimal,stacked}-dark`, `{actions,stats,admin,settings}-dark`, `stats-graph-dark` (the
>   frequency-graph modal with three actions compared) and `login-dark`. These are allowed to **lag**; regenerate and
>   commit them manually when a README-visible page changes.
>
> So when asked to "regenerate the in-app previews" run `app`; to "update the README screenshots" run `documentation`; only
> "regenerate everything" means `all`. Only the `documentation` (or `all`) output is committed — the `app` output is gitignored.

> **Everything is seeded into the LAST COMPLETE calendar month, and every shot is framed on it.** The current month is
> deliberately left empty: it is only ever filled up to today, so seeding it would make the images depend on the run date
> (a run on the 3rd would give a nearly-empty calendar and a three-bar frequency graph; one on the 28th a full set). The
> month's length (28/29/30/31) falls out of `Date.UTC(y, m, 0)` — the last day of the previous month — so leap Februaries
> need no special case. Actions are logged **by weekday** (`ACTIONS[].perWeekday`, Sunday-first counts) so the pattern
> tiles across a month of any length, with a fixed per-week nudge (`WEEK_NUDGE`) so successive weeks are not carbon
> copies. Counts are written with `PUT` (set, not increment), so a re-run rewrites the same values instead of inflating
> them and no "what is already logged?" pre-read is needed.
>
> Because the app always opens the dashboard on **today** — which is in the empty current month — every calendar shot
> first steps back a month and selects its last day (`showSeededMonth`), and the frequency-graph shot steps its window
> back to the same month and throws if it cannot reach it. Nothing relies on a default view.

### Templates, HTMX partials & the Qute `{` gotcha

Qute templates in `src/main/resources/templates/` are full-page layouts or partials in `templates/partials/`. Full `@GET` returns a
`TemplateInstance`; HTMX endpoints return `Response.ok(partial.data(...)).build()`. Error responses use `HX-Retarget`/`HX-Reswap` to redirect the swap
into the error element.

> **Qute parses `{` everywhere in a template — including inside `<script>` blocks, JS comments, and HTML comments.** A
`{` immediately followed by a non-whitespace char (e.g. `{date}`, `{view}`, `{foo.bar}`) is read as an expression and will throw
`TemplateException: Key "date" not found …` at render time — even when it only appears in a code comment like
`// fetch /logs/day/{date}`. This bites repeatedly in `dashboard.html`'s inline JS. To write a literal brace in template text: put a space after it (
`{ foo`), use a different placeholder (`<date>`, `:date`), or wrap the whole region in a Qute comment `{! … !}` (which is NOT parsed — that's why
`d-cal-{view}` survives inside one). Only `{` + whitespace or `{!` is safe; everything else is an expression.

### User-configurable Stats-page tiles (`StatField`)

The Stats page (`partials/stats-cards.html`) renders one card per **`StatSubject`** — each of the user's actions, plus their day
notes pinned first — and within a card one tile per **enabled** stat, in the user's chosen order and under the user's
name for it — the "Action stats" setting (`User.statsFields`). It is stored as a **`jsonb` array of `StatFieldPref`
`{key, enabled, label}`** (`user.StatFieldPref`, mapped via `@JdbcTypeCode(SqlTypes.JSON)`), holding **every** field in the user's
arranged order — so a field's position is stable whether it is shown or hidden (`NULL` = never customised → all fields, default
order, default names; a `null` `label` = that stat is not renamed, which is also how a pre-rename stored arrangement deserialises,
so renaming needed no migration). This is a **display preference only**: `StatsService`/`SubjectStats` always compute every
statistic regardless, and a rename changes only the caption.

`net.zodac.diurnal.stats.StatField` is the **single source of truth** for the tile catalogue (declaration order = default
order); each constant also carries a `description()` shown as the picker tooltip. `SubjectStatsExtensions.tiles(stats, fields,
decimalPlaces)` (a `@TemplateExtension`) maps each displayed stat to a `StatTile`, reusing the existing derived-label methods.
`LAST_PERFORMED` is `mandatory` (always rendered, only reorderable). Helpers all take `List<StatFieldPref>`:
`displayFields(stored)` → the `DisplayStat`s (field + resolved caption) to render; `choices(stored)` → every field
(key/label/defaultLabel/customLabel/description/selected/mandatory) in arranged order for the picker; `encode(order, enabledKeys,
labels)` → the arrangement to persist from a submission; `sanitiseLabel`/`isValidLabel`/`labelsByKey` are the rename rules. The
settings picker is a single **Pointer Events** handler (mouse + touch, no library): a drag from the row **handle** reorders; a
**short press** anywhere else on a row toggles its (visual-only, `pointer-events-none`) checkbox; the hover/focus-revealed
**Rename** button swaps the caption for an in-place input; the description tooltip shows on **hover** (desktop, CSS `group-hover`)
or a **long press** of the text (touch, `.tip-open`). It posts every row's `statsOrder` + `statsLabel` plus the ticked
`statsEnabled` to the consolidated `PATCH /internal/settings` endpoint.

**The rename row is pixel-stable across the flip**, mirroring the data-table edit rows (`partials/dt-row-actions.html`, which pairs
Edit/Save and Delete/Cancel in the same two slots). The caption and the editor are **siblings in one slot**, not two row states:
`.stats-field-caption` carries the input's border/padding (transparent) and both share a `line-height`, so the text keeps the same
pixel and the row the same height. `.stats-field-actions` reserves the width of *both* edit-mode buttons and packs from the **left**
(overriding `.dt-actions`' right-alignment and its 3.5rem per-button minimum), so **Save renders exactly where Rename was** and
Cancel merely appears beside it. `tests/ui/settings.spec.ts` measures both modes relative to the row and fails on any shift.

**Renaming a stat** stores the user's wording against the key, and only ever affects the caption (the Stats page, the dashboard
summary strip and the picker row). The row's hidden `statsLabel` holds the **custom** name, never the rendered caption — posting the
caption back would pin every stat's wording the first time any one of them was renamed, so an un-renamed stat would stop tracking
the catalogue label. A blank name means "use the catalogue label", which is how a rename is cleared (the input's placeholder is that
label). Names go through the shared text-input pipeline (`TextFields.STAT_NAME` - see [`TEXT_INPUT.md`](TEXT_INPUT.md)), so they are
normalised exactly like every other free-text value in the app, and a name over the catalogue maximum is **rejected on both surfaces** — 422 on the web, 400 on the API — never truncated. The cap is **25**, sized against the catalogue's own
wording (the longest built-in label, "Average count per month", is 23) so a custom name is never much wordier than the stat beside it
and every built-in label is itself a legal custom name. **A stat's own built-in label is not a rename**: the editor pre-fills with the
current caption, so saving an un-renamed row untouched submits that label, and storing it would pin the wording against future
re-labelling — `StatField.encode`/`parse` map it back to "not renamed" on both write and read, and `settings.js` mirrors it so
the UI does not fire a pointless save. It bounds LENGTH, not rendered width: the
caption box runs from roughly 129px to 220px across layouts (the tile grid sizes from a minimum width and reflows its column
count), so 23 characters of an unusually wide mix can still cost a caption line the built-ins do not — the accepted trade for an
expressive name.
What a name may never do is escape its tile, so `.stat-tile dt` carries `break-words` (without it a name with no spaces sits on one
line and spills out sideways). The Playwright guard in `tests/ui/stats.spec.ts` renders a max-length name at both widths, reading
the cap from the rename input's own `maxlength`: built-in-style wording must be no deeper than the built-ins, and even the
widest-glyph worst case must not overflow. Committing a rename writes the hidden input and
dispatches `change` on it, so it saves through the picker's single PATCH; the editor's own input carries no `name` and its native
change is swallowed, so an abandoned edit never saves.

> A new stat's `StatField` constant must also supply a `description()` (the constructor requires it) — it becomes the picker
> tooltip.

> **Any newly-computed stat that should be user-visible on the Stats page MUST be registered as an `StatField` constant AND
> given a `StatTile` mapping in `SubjectStatsExtensions.tiles(...)`** (plus a case in its `switch`, which is exhaustive over the enum
> so the compiler flags omissions). Without both it will never appear in the picker or on the page.

> **A `key` is permanent, a `label` is not.** Keys are stored per user, so a rename only ever touches the `label` — hence
> `LONGEST_GAP` still keying on `biggest-gap` and `WEEKLY_DAY_AVERAGE` on `weekly-average`. Changing a key drops that stat from
> every stored arrangement (it re-appears appended at the end), silently reshuffling everyone's page.

Four tile shapes come out of `tiles(...)`: the locale-grouped `numeric` tile (counts, averages), the `labelTile` (a date or a
month/year high score), the `trendTile` (a signed figure in a trend colour), and the `durationTile` (the four streak/gap runs — see
`time/DaySpan` + `time/Durations`). A duration **always** leads with its figure AND unit, whatever its length ("1 day", "5 days",
"1 year, 2 months, 3 days"), so the four runs read alike instead of a short one showing a bare number; its sub-caption carries the
run's own dates — `"since <start>"` while the run is still going (current streak/gap), `"<start> – <end>"` once it is closed, and
nothing for an empty run. The streak/gap statistics are `DaySpan`s (real date ranges), not day counts — that is what makes both the
breakdown and those dates exact and stable rather than shifting as "today" moves; see the `DaySpan` note in `CLAUDE.md`.

> A tile's `sub` is always `data-fit` (it may hold a date range, which the fitting ladder shortens); `subNum` says only whether it
> also carries locale-groupable **numbers**. A date must never be grouped — "1 August 2026" would render as "1 August 2,026".

### Stats-page frequency graph (`partials/stats-chart.html` + `stats.js`)

Each Stats card's header carries a chart button (`[data-chart-action]`) opening a page-level dialog
(`#stats-chart-modal`, one instance in `stats.html`, outside `#stats-list` so paginating the cards never destroys
it). The dialog draws **one to `FrequencyCharts.MAX_SERIES` (3) actions' logged frequency** over one calendar
window as a **grouped bar chart**: a `month` window is one column per day, a `year` window one column per month,
and every charted action contributes one bar to every column.

**There is no charting library and no client-side chart state.** The whole chart is server-rendered
(`StatsInternalResource.chart` → `partials/stats-chart.html`), so the bar heights, axis captions and hover wording
sit on the same code path as every other rendered figure. `stats.js` only opens/closes the dialog and re-fetches
the fragment; it reads the current selection back off the rendered wrapper's `data-chart-shown-period` /
`-at` / `-compare` attributes (deliberately named differently from the *buttons'* `data-chart-period`/`data-chart-at`,
so its delegated `closest(...)` lookups can't mistake the wrapper for a control). Because the swap is a plain
`innerHTML` write, it re-runs `Diurnal.formatNumbers`/`fitFigures` **and `htmx.process(body)`** by hand — the
compare picker's search box arrives inside the fragment and would otherwise never be bound.

- **Every slot of the window is drawn, including the empty ones**, so the axis stays evenly spaced and a blank run
  reads as a trough. A month's 31 ticks are too many to label, so CSS captions only every fifth
  (`[data-chart-shown-period="month"] .chart-col:not(:nth-child(5n+1)) .chart-tick`).
- **All bars scale against ONE peak** (`FrequencyCharts.heightPercent`), never per action — that is what makes two
  charted actions comparable. A logged slot is floored at 3% so it can't round away to an invisible sliver.
- **Hover is per COLUMN, not per bar** (`FrequencySlotExtensions.tooltip`): at 31 days × 3 actions a bar is a couple
  of pixels wide. One action reads `3 July 2026: 4 times`; two or more list each action on its own line, which the
  `white-space: pre-line` rule on `.chart-col > .app-tooltip` renders. Counts go through `Durations.count`, so a lone
  entry reads "1 time". The shared 500ms tooltip dwell delay is dropped here so hovering along the axis feels live.
- **Compare picker**: `Compare to...` reveals `#chart-compare-panel`, whose search box reuses
  `partials/search-input.html` to HTMX-swap **only** `#chart-candidate-list` (so the box keeps focus/caret across a
  keystroke). It offers the user's actions that have **≥1 logged entry**, are not already charted, and match the
  term; `FrequencyChartExtensions.candidatesUrl` bakes the current comparisons into its `hx-get` so a charted action
  is never re-offered.
- **Validation is in the service, not the surfaces** (`StatsService.frequency` → sealed `FrequencyResult`), so the
  page and `GET /api/v1/stats/{actionId}/frequency?compare=…` accept exactly the same selections. Nothing is
  coerced: an unrecognised `period`, a malformed `at` key, >3 actions, a repeated action, and a never-logged
  *comparison* each get their own 4xx. The **primary** action is exempt from the "must have been logged" rule — its
  card is reachable with no logs, and an empty chart is the honest answer there.
- Bars are painted from each action's **own colour** via an inline `style=` (permitted by `style-src-attr`, like the
  swatches the same cards render). Two actions sharing a colour are told apart by the legend chips and the hover
  bubble's names.

### Responsive figure fitting (`data-fit` → `Diurnal.fitFigures`)

Server-rendered figures always carry their **fullest** form — the month spelled out (`15 June 2026`), a 4-digit year, an exact
count — because only the browser knows whether it fits: the tile width depends on the viewport, the locale's grouping separators
and the user's font. Each shortenable line is marked `data-fit` (see `partials/stat-tile.html` / `stat-tile-compact.html`) and
`Diurnal.fitFigures()` in `app.js` walks a ladder, taking one step at a time and only while the line still overflows its own box:

1. spelled-out month → 3-letter (`June` → `Jun`)
2. 4-digit year → 2-digit (`2026` → `26`)
3. a count of 10,000 or more → `10.0k`, at the user's decimal-place preference (read from the nearest `[data-decimal-places]`
   ancestor — `partials/stats-cards.html` and the dashboard's `#stats-summary` carry it)

Each step is measured with the line forced onto one line by `.fit-measure`, which is removed again immediately, so a line that is
still too wide at its shortest step **wraps** rather than being clipped. Because a grouped number cannot be parsed back reliably
(`1.000` is 1000 in `en`, 1 in `de`), `formatNumbers` stashes the ungrouped server text on `data-num-raw` for any element holding a
5+-digit figure, and the count step re-derives from that. It re-runs on `htmx:afterSwap` and (debounced) on `resize`, so widening
the window restores the full text. `Diurnal.MONTHS_FULL`/`MONTHS_ABBR` and this ladder are the one place the project abbreviates a
month — the calendar toolbar's own title fitting (`setCalTitle`/`fitCalTitle` in `dashboard.js`) reads the same tables; it keeps its
own measurement because it fits against the **toolbar's** overflow, not the title's own box.

### Expired sessions on the dashboard (`requireSession`)

**Every `fetch` in `dashboard.js` runs its response through `requireSession(resp)` before touching the body.** An
expired session arrives in TWO shapes, because the namespaces challenge differently: `/api/v1/*` answers a **401** (no
redirect — correct for a programmatic client), while `/internal/*` answers a **302 to the login PAGE**, which `fetch`
follows, so the response lands as login HTML with **status 200** and a `url` of `/login`. The guard treats either as
"sign in again" and navigates.

Handling only the 401 (as the old `feedJson` did) left the dashboard **silently dead**: the JSON callers threw a parse
error into their own retry `.catch`, so nothing ever loaded and no error surfaced; an HTML caller would have swapped
the entire login page into the day panel or the summary card. Do not add a `fetch` here without it —
`tests/ui/auth.spec.ts` pins the behaviour.

### Calendar feeds (LogsApiResource / CalendarResource)

`GET /api/v1/logs/events` (`LogsApiResource`) returns `CalendarEventDto` JSON (one event per logged action, title carries the `×N` multiplier). It is
the **public logged-events API** — authenticates both the session cookie and a Bearer session token — and is also the feed the dashboard's `full`
calendar reads. `start`/`end` are mandatory ISO-8601 dates (missing → 400, parsed by the shared `DateRanges`). Anonymous requests → `401` (the
`/api/*` challenge); `dashboard.js`'s `feedJson()` turns that into a `/login` navigation. `LogsApiResource` also carries the public day read/write
endpoints (`GET /api/v1/logs/{date}`, `PUT`/`POST increment|decrement`/`DELETE /api/v1/logs/{date}/{actionId}`), sharing `LogGuards` with
`LogWebResource`. `GET /internal/logs/minimal-events` (`CalendarResource`) is web-UI-internal (pruned from the docs by namespace) and feeds the
`minimal`/`stacked` styles (≤4 dots per day).
