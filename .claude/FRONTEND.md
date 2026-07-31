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

Seven scripts are served from `META-INF/resources/js/` and referenced from the templates via
`{inject:appInfo.*}`, all sharing one cache-busting pattern: served un-hashed in dev (`no-store`), and at image-build
time the Dockerfile esbuild-minifies the six handwritten scripts (`npm run js:min` in the `css` stage — the committed
sources stay readable; only the image ships the minified form), then content-hashes them. **All asset hashing lives in
one place — `scripts/hash-static-assets.sh`** (invoked by a single `RUN` in the Dockerfile's build stage): it renames
every fingerprinted asset (CSS, the 7 scripts, the settings thumbnails, the vector marks) to `name.<sha256-12>.ext`
(the hash is inserted after the first name segment, so `htmx.min.js` → `htmx.<hash>.min.js`) and bakes the hashed name
into `microprofile-config.properties` (read by `AppConfig`/`AppInfo`). All are then served
`public, max-age=31536000, immutable` by the single `app-immutable` filter (`application.properties`). See
"Static-asset caching" below for the full model.

- `htmx.min.js` (`AppInfo.jsFile`) — **vendored** from npm by `scripts/vendor-assets.cjs` (`.gitignored` build artifact).
- `app.js` (`AppInfo.jsAppFile`) — the shared per-page behaviour extracted from `layout.html` (dt edit/confirm toggles,
  form validation + AJAX submit, locale number grouping, the tooltip long-press, the password-requirements popover, the
  delegated `htmx:configRequest` search-filter listener, the mobile-menu toggle). A **committed** handwritten file.
  Loaded as a classic script at the end of `<body>` on every page, so the document is parsed when it runs and its
  document-level handlers register in the original order (the `data-validate` handler must precede `data-ajax-submit`).
- `dashboard.js` (`AppInfo.jsDashboardFile`) — the hand-rolled calendar engine extracted from `dashboard.html`. A
  **committed** file, loaded only on the dashboard. Its two server-injected values (the app's UTC `today` and the user's
  `calendarView`) arrive via `data-today`/`data-calendar-view` attributes on `#dashboard-main`, read directly off
  `dataset` — no inline bootstrap. Because it is a plain `.js` file (not a Qute template) the `{`-escaping caveat below
  no longer applies to it.
- `actions.js`, `admin-users.js`, `admin-api-docs.js`, `settings.js` (`AppInfo.jsActionsFile`/`jsAdminFile`/
  `jsApiDocsFile`/`jsSettingsFile`) — the page-specific behaviour extracted from `actions.html`, `admin-users.html`,
  `admin-api-docs.html`, and `settings.html` respectively (extracted during the CSP hardening), each
  loaded only on its own page and wired via `data-*` hooks + `addEventListener` (no inline `on*=`/`hx-on=` attributes
  remain anywhere in the app — see "Security headers / CSP" below).

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
`PreviewOption` (`value`/`label`/`title`/`alt`/`previewImage`), following the `ActionStatField` "single source of truth" pattern. `WebResource` passes
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
node scripts/generate-screenshots.cjs documentation   # the 9 README shots (docs/screenshots/, COMMITTED)
node scripts/generate-screenshots.cjs all             # both (default)
scripts/dev-teardown.sh
```

> **`app` vs `documentation` are NOT the same set and are refreshed on different cadences.**
> - **`app`** → the **8** Settings preview thumbnails in `img/settings/` (Theme/Calendar/Font pickers, listed above).
>   These are **uncommitted build artifacts** — you rarely run this by hand; the Docker build's `screenshots` stage runs
>   `generate-screenshots.cjs app` for you (see the note under "Settings preview thumbnails"), so every image has current
>   previews. Running it manually just writes them into the (gitignored) `img/settings/` for a local eyeball.
> - **`documentation`** → the **9** committed README screenshots in `docs/screenshots/`: `dashboard-{system,dark,light}`,
>   `cal-{minimal,stacked}-dark`, and `{actions,stats,admin,settings}-dark`. These are allowed to **lag**; regenerate and
>   commit them manually when a README-visible page changes.
>
> So when asked to "regenerate the in-app previews" run `app`; to "update the README screenshots" run `documentation`; only
> "regenerate everything" means `all`. Only the `documentation` (or `all`) output is committed — the `app` output is gitignored.

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

### User-configurable Stats-page tiles (`ActionStatField`)

The Stats page (`partials/stats-cards.html`) renders one tile per **enabled** stat, in the user's chosen order and under the user's
name for it — the "Action stats" setting (`User.statsFields`). It is stored as a **`jsonb` array of `StatFieldPref`
`{key, enabled, label}`** (`user.StatFieldPref`, mapped via `@JdbcTypeCode(SqlTypes.JSON)`), holding **every** field in the user's
arranged order — so a field's position is stable whether it is shown or hidden (`NULL` = never customised → all fields, default
order, default names; a `null` `label` = that stat is not renamed, which is also how a pre-rename stored arrangement deserialises,
so renaming needed no migration). This is a **display preference only**: `StatsService`/`ActionStats` always compute every
statistic regardless, and a rename changes only the caption.

`net.zodac.diurnal.stats.ActionStatField` is the **single source of truth** for the tile catalogue (declaration order = default
order); each constant also carries a `description()` shown as the picker tooltip. `ActionStatsExtensions.tiles(stats, fields,
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
label). Names are normalised (whitespace collapsed, control characters stripped) and a name over `MAX_LABEL_LENGTH` characters is
**rejected on both surfaces** — 422 on the web, 400 on the API — never truncated. The cap is **25**, sized against the catalogue's own
wording (the longest built-in label, "Average count per month", is 23) so a custom name is never much wordier than the stat beside it
and every built-in label is itself a legal custom name. **A stat's own built-in label is not a rename**: the editor pre-fills with the
current caption, so saving an un-renamed row untouched submits that label, and storing it would pin the wording against future
re-labelling — `ActionStatField.encode`/`parse` map it back to "not renamed" on both write and read, and `settings.js` mirrors it so
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

> A new stat's `ActionStatField` constant must also supply a `description()` (the constructor requires it) — it becomes the picker
> tooltip.

> **Any newly-computed stat that should be user-visible on the Stats page MUST be registered as an `ActionStatField` constant AND
> given a `StatTile` mapping in `ActionStatsExtensions.tiles(...)`** (plus a case in its `switch`, which is exhaustive over the enum
> so the compiler flags omissions). Without both it will never appear in the picker or on the page.

> **A `key` is permanent, a `label` is not.** Keys are stored per user, so a rename only ever touches the `label` — hence
> `LONGEST_GAP` still keying on `biggest-gap` and `WEEKLY_DAY_AVERAGE` on `weekly-average`. Changing a key drops that stat from
> every stored arrangement (it re-appears appended at the end), silently reshuffling everyone's page.

Three tile shapes come out of `tiles(...)`: the big-number `numeric` tile (counts, averages, a day run still under a month), the
smaller two-line `labelTile` (a date, a month/year high score, a **condensed** duration — see `time/DaySpan` + `time/Durations`),
and the `trendTile` (a signed figure in a trend colour). A day run switches shape at one calendar month, so a long streak reads
"1 year, 2 months, 3 days" with the exact day count demoted to the sub-caption instead of overflowing the big-number slot. The
streak/gap statistics are `DaySpan`s (real date ranges), not day counts — that is what makes the breakdown exact and stable rather
than shifting as "today" moves; see the `DaySpan` note in `CLAUDE.md`.

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

### Calendar feeds (LogsApiResource / CalendarResource)

`GET /api/v1/logs/events` (`LogsApiResource`) returns `CalendarEventDto` JSON (one event per logged action, title carries the `×N` multiplier). It is
the **public logged-events API** — authenticates both the session cookie and a Bearer session token — and is also the feed the dashboard's `full`
calendar reads. `start`/`end` are mandatory ISO-8601 dates (missing → 400, parsed by the shared `DateRanges`). Anonymous requests → `401` (the
`/api/*` challenge); `dashboard.js`'s `feedJson()` turns that into a `/login` navigation. `LogsApiResource` also carries the public day read/write
endpoints (`GET /api/v1/logs/{date}`, `PUT`/`POST increment|decrement`/`DELETE /api/v1/logs/{date}/{actionId}`), sharing `LogGuards` with
`LogWebResource`. `GET /internal/logs/minimal-events` (`CalendarResource`) is web-UI-internal (pruned from the docs by namespace) and feeds the
`minimal`/`stacked` styles (≤4 dots per day).
