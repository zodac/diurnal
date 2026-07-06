# CLAUDE.md

> **Code style:** Project-specific expectations live in [`CODE_STYLE.md`](CODE_STYLE.md). Read it before writing or editing code.

> **No real URLs or internal IPs in comments or examples.** Use only `https://diurnal.example.com` or
`http://127.0.0.1:8080` as placeholder values. Never use production hostnames, LAN addresses (`192.168.*`, `10.*`,
`172.16–31.*`), or any other real hostname.

> **Never overwrite `RELEASE_NOTES.md` or `VERSION` unless explicitly asked.** These are hand-authored release
artefacts owned by the maintainer — leave them untouched (even if they appear modified in the working tree) unless the
request explicitly says to update them.

## Commands

```bash
# Fetch the code-quality-config submodule (required for -Dlint / -Dall)
git submodule update --init

# Build CSS (compiled Tailwind at /css/app.css; rebuild after any class/template change)
# The compiled file is a build artifact (.gitignored); any `mvn` build regenerates it via the
# POM's `css-build` exec, but it needs node_modules — so run `npm install` once after cloning.
# For a hot-reload dev loop, run `npm run css:watch` alongside quarkus:dev.
npm install        # one-time (required for `mvn` to build the CSS)
npm run css        # or: npm run css:watch

# Start dev PostgreSQL (required before quarkus:dev)
docker compose -f docker-compose.dev.yml up -d diurnal-db-dev

# Run in dev mode (hot reload, Swagger UI at /api, port 8081)
# ALWAYS stop when done: pkill -f "quarkus:dev"
mvn quarkus:dev

# Build JAR (no tests by default)
mvn package

# Run ALL tests + linters (full CI gate)
mvn clean install -Dall
# Prerequisite: cd e2e && npx playwright install

# Run linters only (no tests)
mvn clean install -Dlint

# Run unit tests only (no DB needed)
mvn test -Dtests
mvn test -Dtests -Dtest=MyTestClass

# Run Playwright E2E tests
cd e2e && npm test                                  # against :8080
cd e2e && BASE_URL=http://localhost:8081 npm test   # against dev / -Dall port

# Run the deployment-smoke suite against the REAL production image (the only tier that exercises the
# distroless/jlink/non-root runtime). Self-contained: builds the image, runs an isolated app+DB stack
# on :8082, runs the smoke specs, tears it all down. Included automatically in `mvn clean install -Dall`.
bash e2e/run-smoke.sh 8082 "$(pwd)"

# Full Docker deployment
cp .env.example .env   # fill in DB_PASSWORD and SESSION_ENCRYPTION_KEY
docker compose up -d --build
docker compose logs -f app
```

> **Always use `docker compose` (v2 plugin), never `docker-compose` (hyphenated).** Only the filenames keep the hyphen.

Dev mode expects PostgreSQL on `localhost:5432` with database `diurnal_db`, user `diurnal_user`, and password `diurnal_password`. Flyway migrations run automatically. Data is ephemeral (
wiped on container recreate).

> **Tear down the dev environment when finished.** Use `scripts/dev-up.sh` / `scripts/dev-teardown.sh`. Manual: `pkill -f "quarkus:dev"`, then
`docker compose -f docker-compose.dev.yml down`. The `-Dall` run manages the test DB automatically in `pre/post-integration-test`.

Config layers: `application.properties` (base/prod), `application-dev.properties` (port 8081, DEBUG), `application-test.properties` (UTC). Both
profile files must stay in `src/main/resources` — the E2E jar runs with `-Dquarkus.profile=test` and only reads bundled config.

**Port map**: 8080 = production; 8081 = dev mode, `@QuarkusTest`, and the E2E jar (never simultaneous); 8082 = the deployment-smoke stack (isolated
compose project, coexists with a running prod stack). Under `-Dall`, phase binding is the load-bearing detail — the inherited sortpom plugin re-sorts
plugins alphabetically, so `exec-maven-plugin` always sorts before `maven-failsafe-plugin`. `*IT` tests run in `integration-test`; the E2E run and the
deployment-smoke run are bound to `install` (after `verify`), ensuring ITs are confirmed green first. Both live in a **single** `install`-phase exec
(`e2e-then-smoke`) that chains `run-e2e.sh && run-smoke.sh` — smoke runs strictly after (and only if) E2E passes. Ordering is enforced by the `&&`, not
by phase/declaration order: `install` is the only post-`verify` phase `mvn clean install` runs, and sortpom would reorder two separate execs, so they
must share one exec. Their stacks use disjoint ports/DBs and each self-cleans, so running back-to-back is safe.

## Architecture

### Build

Inherits from `net.zodac:parent-pom` (Maven Central). The parent manages all dependency/plugin versions (Quarkus BOM, JUnit BOM, jbcrypt, jspecify,
etc.). Lint config lives in the `code-quality-config/` git submodule — run `git submodule update --init` after cloning.

Quality gates (opt-in):

- `-Dlint` — ErrorProne+NullAway (also run on every compile), Checkstyle, PMD, SpotBugs, Javadoc, Enforcer, license headers, dependency analysis,
  PITest. Compiles test sources but does not run tests.
- `-Dtests` — surefire unit tests (`*Test`) only.
- `-Dall` — everything: unit + `*IT` + E2E + deployment-smoke + full linters.

**All linters currently pass clean (Checkstyle/PMD/SpotBugs = 0, PITest strength = 100%); keep them that way.** Code must be NullAway-annotated (
JSpecify `@Nullable`), every public/package method and type carries Javadoc, locals/params are `final`, unit-test assertions carry messages.

### Package layout

Under `src/main/java/net/zodac/diurnal/`:

| Package  | Contents                                                                                                          |
|----------|-------------------------------------------------------------------------------------------------------------------|
| `action` | `Action` entity + `ActionsWebResource` (CRUD for user-defined habits)                                             |
| `log`    | `ActionLog` entity + `LogWebResource` (increment/decrement per day) + `CalendarResource` (`/logs/events` feed)    |
| `stats`  | `StatsService` + `ActionStats` (data record) + `ActionStatsExtensions` (template extensions) + `ActionStatField` (Stats-page tile catalogue) + `StatTile` (tile view-model) + `StatsWebResource` |
| `auth`   | `AuthResource` (register/login → JWT), `TokenService`, `PasswordIdentityProvider`, `TrustedIdentityProvider`      |
| `user`   | `User` entity, `UserResource` (`/api/users/me`), `UserSettings`                                                   |
| `web`    | `WebResource` — all top-level page routes (dashboard, login, register, logout, settings, theme toggle)            |

### Authentication

- **Web UI (`/*`)** — encrypted session cookie (`diurnal_session`), form-based; unauthenticated → `/login`. `@RolesAllowed("user")` at the method
  level.
- **REST API (`/api/*`)** — Bearer JWT (RSA-2048). Keys in `src/main/resources/jwt-keys/` (dev) or `secrets/` (prod, auto-generated by
  `JwtKeyProvisioner`).

`quarkus.http.auth.proactive=false` prevents Bearer from intercepting web requests before form auth can redirect.

> **In Bearer resources, resolve the user via `SecurityIdentity.getPrincipal().getName()` → `User.findByEmail(...)`, NOT `JsonWebToken.getSubject()`.
** When OIDC is enabled, the default `JsonWebToken` producer is the OIDC one and `getSubject()` returns `null`, causing a 500.

OIDC users store `oidcSubject` + `oidcIssuer` instead of a password hash; composite unique index
`(oidc_issuer, oidc_subject) WHERE oidc_subject IS NOT NULL`. OIDC is disabled by default (`quarkus.oidc.enabled=false`).

### HTMX partial responses

Qute templates in `src/main/resources/templates/` are full-page layouts or partials in `templates/partials/`. Full `@GET` returns a
`TemplateInstance`; HTMX endpoints return `Response.ok(partial.data(...)).build()`. Error responses use `HX-Retarget`/`HX-Reswap` to redirect the swap
into the error element.

> **Qute parses `{` everywhere in a template — including inside `<script>` blocks, JS comments, and HTML comments.** A
`{` immediately followed by a non-whitespace char (e.g. `{date}`, `{view}`, `{foo.bar}`) is read as an expression and will throw
`TemplateException: Key "date" not found …` at render time — even when it only appears in a code comment like
`// fetch /logs/day/{date}`. This bites repeatedly in `dashboard.html`'s inline JS. To write a literal brace in template text: put a space after it (
`{ foo`), use a different placeholder (`<date>`, `:date`), or wrap the whole region in a Qute comment `{! … !}` (which is NOT parsed — that's why
`d-cal-{view}` survives inside one). Only `{` + whitespace or `{!` is safe; everything else is an expression.

### Data records vs. logic (`*Extensions`)

Records hold data only; derived logic lives in a `<Type>Extensions` final class (private constructor) whose methods take the record as the first
parameter. Template-facing methods are annotated `@io.quarkus.qute.TemplateExtension` so Qute resolves `{x.foo}` against the record unchanged.

**This split is mandatory, not stylistic** — PITest refuses to hot-swap mutants into record classes (
`"class redefinition failed: attempted to change the Record attribute"`), silently leaving logic untested behind the 100% gate. Diagnose with
`-Dverbose=true`.

**When a record grows branching instance logic called by a template** (watch for `@SuppressWarnings("unused")`), move it to a `<Type>Extensions` class
and add a unit test. Exceptions: pure-data records, factory methods (`from`/`of`), and static validators/sanitisers.

### User-configurable Stats-page tiles (`ActionStatField`)

The Stats page (`partials/stats-cards.html`) renders one tile per **enabled** stat, in the user's chosen order — the "Action stats"
setting (`User.statsFields`). It is stored as a **`jsonb` array of `StatFieldPref` `{key, enabled}`** (`user.StatFieldPref`, mapped
via `@JdbcTypeCode(SqlTypes.JSON)`), holding **every** field in the user's arranged order — so a field's position is stable whether
it is shown or hidden (`NULL` = never customised → all fields, default order). This is a **display preference only**:
`StatsService`/`ActionStats` always compute every statistic regardless.

`net.zodac.diurnal.stats.ActionStatField` is the **single source of truth** for the tile catalogue (declaration order = default
order); each constant also carries a `description()` shown as the picker tooltip. `ActionStatsExtensions.tiles(stats, fields,
decimalPlaces)` (a `@TemplateExtension`) maps each enabled field to a `StatTile`, reusing the existing derived-label methods.
`LAST_PERFORMED` is `mandatory` (always rendered, only reorderable). Helpers all take/return `List<StatFieldPref>`:
`displayFields(stored)` → enabled fields to render; `choices(stored)` → every field (key/label/description/selected/mandatory) in
arranged order for the picker; `encode(order, enabledKeys)` → the arrangement to persist from a submission. The settings picker is a
single **Pointer Events** handler (mouse + touch, no library): a drag from the row **handle** reorders; a **short press** anywhere
else on a row toggles its (visual-only, `pointer-events-none`) checkbox; the description tooltip shows on **hover** (desktop, CSS
`group-hover`) or a **long press** of the text (touch, `.tip-open`). It posts every row's `statsOrder` plus the ticked
`statsEnabled` to `PATCH /settings/stats-fields`.

> A new stat's `ActionStatField` constant must also supply a `description()` (the constructor requires it) — it becomes the picker
> tooltip.

> **Any newly-computed stat that should be user-visible on the Stats page MUST be registered as an `ActionStatField` constant AND
> given a `StatTile` mapping in `ActionStatsExtensions.tiles(...)`** (plus a case in its `switch`, which is exhaustive over the enum
> so the compiler flags omissions). Without both it will never appear in the picker or on the page.

### CSS build & colour tokens

Tailwind is compiled (not CDN). `src/main/css/app.css` (the committed source) is built into `src/main/resources/META-INF/resources/css/app.css` (the
served output). **Rebuild with `npm run css` after any class change in templates or Java** or the class will be purged.

The compiled output is a **build artifact, not committed** (`.gitignore`d). Every Maven build regenerates it: the POM's `exec-maven-plugin`
`css-build` execution runs `npm run css` in `generate-resources` (before resources are copied/packaged), so `package`/`*IT`/E2E always bundle a fresh
stylesheet. This needs `node_modules` (`npm install` once). The Docker build instead compiles the CSS in a dedicated `css` stage and copies it in,
passing `-Dcss.build.skip=true` to `mvn package` so the Node-less Maven image skips the exec. Dev mode (`quarkus:dev`) serves the on-disk file
directly — keep `npm run css:watch` running, or run `npm run css` manually, to refresh it.

Colour tokens: `app.css` defines `--color-*` CSS variables (`:root` + `.dark`). Tailwind exposes semantic utilities: `bg-surface`/`bg-surface-muted`,
`text-ink`/`text-ink-muted`, `border-line`/`border-line-subtle`, `text-brand`, `bg-brand`, `text-success`, `text-danger`. Use these instead of raw
`gray-*`/`indigo-*`.

**The brand colour is generated — never hand-edit it.** The `--color-brand*` family lives in `@generated:brand` regions of `app.css`, computed by
`scripts/generate-brand.py` from the `fill` of `scripts/assets/wordmark.svg` (the single source of truth). To rebrand: change the `fill`, then
`npm run brand`. Base colour: `#6366f1`, constant across light and dark.

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
(NOT inside `@layer`)**, so it rides the compiled, content-hashed, `immutable` stylesheet instead of being re-transferred
on every no-cache navigation. It is kept un-layered on purpose: exactly as it was inline (un-layered, after the linked
sheet), so it still wins over Tailwind's layered utilities — which is why the defensive `[data-dt-view].hidden` /
`[data-dt-edit].hidden` re-assertions are retained. Every colour is a `var(--color-*)` token, so no `.dark` twins are needed.

### Served front-end scripts (content-hashed, `immutable`)

Three scripts are served from `META-INF/resources/js/` and referenced from the templates via
`{inject:appInfo.*}`, all sharing one cache-busting pattern: served un-hashed in dev (`no-store`), and at image-build
time the Dockerfile renames each to `name.<sha256-12>.ext`, bakes the hashed name into
`microprofile-config.properties` (read by `AppConfig`/`AppInfo`), and serves it `public, max-age=31536000, immutable`
(`application.properties`, the `/js/` filter).

- `htmx.min.js` (`AppInfo.jsFile`) — **vendored** from npm by `scripts/vendor-assets.cjs` (`.gitignored` build artifact).
- `app.js` (`AppInfo.jsAppFile`) — the shared per-page behaviour extracted from `layout.html` (dt edit/confirm toggles,
  form validation + AJAX submit, locale number grouping, the tooltip long-press, the password-requirements popover). A
  **committed** hand-written file. Loaded as a classic script at the end of `<body>` on every page, so the document is
  parsed when it runs and its document-level handlers register in the original order (the `data-validate` handler must
  precede `data-ajax-submit`).
- `dashboard.js` (`AppInfo.jsDashboardFile`) — the hand-rolled calendar engine extracted from `dashboard.html`. A
  **committed** file, loaded only on the dashboard. Its two server-injected values (the app's UTC `today` and the user's
  `calendarView`) arrive via `window.Diurnal.dashboard`, set by a tiny inline bootstrap just before it loads. Because it
  is now a plain `.js` file (not a Qute template) the `{`-escaping caveat below no longer applies to it.

> **The FOUC-critical `window.Diurnal.applyTheme('{theme}')` stays inline in `<head>`** — it must run before the
> stylesheet loads and carries a server-injected value, so it is neither externalised nor hashed.

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

### CalendarResource

`GET /logs/events` returns `CalendarEventDto` JSON (one event per logged action, title carries the `×N` multiplier), including archived actions. It is
the **public logged-events API** — authenticates both session cookie and Bearer JWT, published in Swagger by `PublicApiFilter` — and is also the feed
the dashboard's `full` calendar reads. `start`/`end` are mandatory ISO-8601 dates (missing → 400). Anonymous requests → 302 to `/login`.
`/logs/minimal-events` is internal (`@Operation(hidden = true)`) and feeds the `minimal`/`stacked` styles (≤4 dots per day).

### Dashboard calendar (hand-rolled, no library)

All three calendar styles (`full`/`minimal`/`stacked`, `UserSettings.CALENDAR_VIEW_OPTIONS`, default `full`) are drawn by **one** vanilla-JS engine,
`buildGridCalendar()` in `dashboard.html` — a shared 7×6 / 42-cell, Sunday-first month grid with its own month cache, LRU eviction and idle prefetch (
`±2` months). There is no FullCalendar (or any) calendar library. `calendarView` only changes (a) which feed `fetchMonth` reads — `full` →
`/logs/events`, others → `/logs/minimal-events`, both normalised into a uniform `dayData[date] = [{colour, label}]` — and (b) how `renderGrid` paints
each cell: `full` = bordered cell with top-right day number + an uncapped event list (`.d-full-*`); `minimal` = centred date circle + dots (
`.d-min-dot`); `stacked` = circle + bars (`.d-stk-bar`). Every cell is a shared `.d-min-cell[data-date]` carrying `.d-min-today`/`.d-min-selected`/
`.d-min-other`; the active style is mirrored onto `#calendar-wrap` and `#d-min-grid` as `.d-cal-{view}` so the `full` look is CSS-scoped. The shared
chrome (toolbar, jump picker, day-panel load, the verb-gated `htmx:afterRequest` → `cal.refresh()`) drives a 4-method adapter (`currentView`/
`goToMonth`/`setHighlight`/`refresh`). **When the dashboard calendar appearance changes, regenerate the settings previews** (see below).

### Typography & Font setting

Two Nova superfamily webfonts served as `woff2` from `src/main/resources/META-INF/resources/fonts/`: **Nova Flat** (body/UI) and **Nova Round** (
display/headings). `@font-face` blocks in `app.css`.

Font family is indirect via `--font-body`/`--font-display` CSS variables. The **Font setting** (`User.font`: `nova`|`standard`, default `nova`,
migration V13) switches them. `layout.html` renders `.font-nova` on `<html>` server-side (`{#if font != 'standard'}`), no FOUC. **`font` must be
passed to every full-page template** (mirror `theme` 1:1; HTMX day-panel partials need neither).

### Brand assets

No logo/icon mark — purely typographic. **`scripts/assets/wordmark.svg` is the single source of truth** (outside `src/`, not packaged by Maven).
Everything under `src/main/resources/META-INF/resources/img/` is generated output.

**To rebrand: change `fill` in `wordmark.svg`, then `npm run brand`** — chains `generate-brand.py` → `generate-favicons.cjs` → `npm run css`. Docker
re-renders rasters from committed `favicon.svg` but does not run `generate-brand.py`.

Served assets: `wordmark.svg` (navbar/headings), `favicon.svg` (scalable favicon), `footer-mark.svg` (snug "d" for footer). Rasters: `favicon.ico` (
16/32/48, at web root), `icon-192.png` (Chromium-Android tab icon — **must** be a `<link rel="icon">` tag, not just manifest), `icon-512.png` (PWA
manifest pair), `apple-touch-icon.png` (180px iOS), `manifest.json`.

### Settings preview thumbnails

Theme, Calendar style, and Font pickers show real dashboard screenshots (via `partials/preview-option.html`). WebP files in
`src/main/resources/META-INF/resources/img/settings/`, one viewport set (web).

**7 WebP files**, fixed per picker:

- Theme: `page-nova-full-{system,light,dark}.webp`
- Calendar: `cal-nova-{full,minimal,stacked}-dark.webp`
- Font: `page-{nova,standard}-full-dark.webp`

`page-nova-full-dark` is shared by the Theme-dark and Font-nova tiles.

Loading: `data-src` instead of `src` (no fetches until JS assigns). Two-phase load: visible images immediately, then `requestIdleCallback` for the
rest.

Thumbnails use a fixed-ratio frame (`aspect-[3/4] sm:aspect-[3/2]` in `.preview-thumb`), cropped to the top — not tied to image aspect ratios. Route
any future settings thumbnail through `partials/preview-thumb.html`.

**Regenerate when dashboard appearance changes:**

```bash
scripts/dev-up.sh
node scripts/generate-settings-previews.cjs
scripts/dev-teardown.sh
```

### Pagination

All list views (actions, day-panel, stats) use in-memory pagination: fetch all, filter, slice. Page size is a per-user setting validated against
`{5, 10, 25, 50, 100}` (default `5`) by `UserSettings.sanitisePageSize()`. `PaginatedDayActions` adds filler rows to keep every page the same height.

### Notable invariants

- `ActionLog.MAX_DAILY_COUNT = 999` — `SMALLINT` column; increment, increment-by-10, and set are silently capped.
- Actions are soft-deleted (`archived = true`); logs are hard-deleted when an action is deleted.
- **All date-boundary "now"/"today" goes through `AppClock`** (`@ApplicationScoped`). Business logic calls `clock.today()`/`clock.zone()`. Entity
  audit timestamps (`createdAt`/`updatedAt`/`lastLoginAt`) use `Instant.now()` directly (zone-independent, not date-boundary sensitive).
- `app.timezone` (default `UTC`) feeds `AppClock`; must match `TZ` in `docker-compose.yml`.
- `LogWebResource.isFuture()` blocks logging for future dates in the user's configured timezone.
- Action colour defaults to `#64748b` (a neutral slate, deliberately *not* the brand indigo `#6366f1` — a
  brand-coloured dot would vanish into the full calendar's brand-filled "today" cell); invalid hex is
  silently corrected to the default.
- Dark-mode checkbox: hidden `<input value="false">` + real `<input value="true">`. Checked posts `["false","true"]`; unchecked posts `["false"]`.
  `updateSettings` checks for `"true"` in the list.
- `password.auth.enabled=false` disables register (404) and skips `PasswordIdentityProvider`. `AppLifecycle` enforces at least one auth mechanism at
  startup.
- Login uses query params: `?error` = failed login; `?registered=true` = success after registration.
- `ActionStatsExtensions` exposes `sinceLabel()`, `monthTrend()`, `monthTrendClass()` etc. as Qute template extensions over `ActionStats`.
- **UI text must use correct singular/plural** — never "1 days". `ActionStatsExtensions` centralises the rule via `plural(count, unit)`, exposed as
  `currentStreakLabel()`/`longestStreakLabel()`/`currentStreakUnit()`/`longestStreakUnit()`/`totalDaysUnit()`. Apply to any new pluralised count.

### Database migrations

Flyway scripts in `src/main/resources/db/migration/`, sequential (`V1__`, `V2__`, …).

> **NEVER modify an existing migration file — not the SQL, not even a comment or a whitespace. This is
> absolute: it applies to brand-new/uncommitted migrations, to "minor" tweaks, to fixing a typo, and to
> reverting a change you just made. ALWAYS express any change — including a reversion — as a NEW
> `V{n+1}__` file.**
>
> Flyway records a checksum of every applied migration and validates it at every startup. The instant a
> migration file's bytes change after it has been applied to *any* database (including a local/dev one
> that has already run it), that database fails to boot with a `Migration checksum mismatch` — recovering
> then requires a manual `flyway repair` or hand-editing `flyway_schema_history`. To change a column you
> already shipped in `V{n}`, add `V{n+1}` with the `ALTER`. To undo `V{n}`, add `V{n+1}` that reverses it.
> Treat every migration file as immutable the moment it exists.

### Testing conventions

Integration tests extend `IntegrationTestBase` (truncates `action_logs → actions → users` before each test). Helpers: `newUser()`, `newAction()`,
`newLog()`, `runInTx()`. Tests use `@TestSecurity`. The `test` profile forces `app.timezone=UTC`. BCrypt cost = 4 in tests.

**Deterministic time:** `IntegrationTestBase` freezes `AppClock` in `@BeforeEach` to `FIXED_TODAY = 2026-06-15`, restoring in `@AfterEach`. Use
`freezeDate(LocalDate)` or `freezeInstant(Instant, ZoneId)` for boundary cases. Unit tests pass a fixed `today` directly. Surefire/failsafe pin
`-Duser.timezone=UTC`. E2E specs use UTC date APIs (`setUTCDate`/`getUTCDate`/`toISOString`) and `timezoneId: 'UTC'` in Playwright.

### Deployment-smoke tier (`e2e/smoke/`)

The test pyramid has a fourth tier on top of unit / `*IT` / E2E: **deployment-smoke**, the only tier that runs the **actual production Docker image
** (distroless, jlink custom JRE, non-root UID 65532) rather than a full JDK. It exists because that runtime is now a real source of bugs none of the
lower tiers can see — e.g. a jlink module trimmed too far (the `java.rmi` boot failure), non-root write permissions, or a CSS-hash/favicon build-stage
desync.

- **Files:** `docker-compose.smoke.yml` (isolated app+DB stack built from the `Dockerfile`), `e2e/run-smoke.sh` (build → `up --wait` → run →
  trap-teardown), `e2e/playwright.smoke.config.ts` (`testDir: ./smoke`, single chromium project), `e2e/smoke/*.spec.ts`.
- **Runs the prod profile** against a live Postgres — so there is **NO frozen clock and NO seeded DB**. Smoke specs must **self-seed** and use only
  the app's own UTC "today" (`TZ=UTC` in the compose stack; browser pinned to UTC). Do **not** port frozen-time E2E specs here. Keep the suite small
  and image-focused (boot/health, non-root JWT key-gen, hashed assets, one persisted round-trip) — feature behaviour belongs in the E2E suite.
- **Isolation:** dedicated compose project (`-p diurnal-smoke`), ephemeral tmpfs DB, host port **8082**, tmpfs `/run/secrets` (mode 1777) so the
  non-root UID can write the generated keypair. Coexists with a running prod stack.
- **CI wiring:** run by the `e2e-then-smoke` exec in the `-Dall` profile (chained `run-e2e.sh && run-smoke.sh`, bound to `install`, gated after the
  `verify` IT check) — smoke runs only if E2E passed; the image's own HEALTHCHECK drives `up --wait`, so a boot failure fails the build before
  Playwright starts.
