# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# After cloning, fetch the code-quality-config git submodule (the shared Checkstyle/PMD/SpotBugs/
# license config the linters read). Without it, -Dlint / -Dall fail to find the rule files.
git submodule update --init        # one-time (also: git clone --recurse-submodules)

# Build the CSS once after cloning (and after any class/template change). The compiled,
# purged Tailwind stylesheet is served at /css/app.css. The output is committed, so the app
# runs without Node, but it must be rebuilt when classes change.
npm install        # one-time
npm run css        # or: npm run css:watch  (rebuild on template/class changes during dev)

# Start the dev PostgreSQL (required before quarkus:dev). docker-compose.dev.yml holds BOTH the dev
# and test DBs as separate services — start them by name so you only get the one you want.
docker compose -f docker-compose.dev.yml up -d dev-db

# Run in dev mode (hot reload, Swagger UI at /q/swagger-ui)
# ALWAYS stop this instance once testing/verification is finished (see rule below):
#   pkill -f "quarkus:dev"
mvn quarkus:dev

# Build JAR (tests are OPT-IN — see note below — so a bare package skips them)
mvn package

# Run ALL tests (unit + integration + Playwright E2E) AND every inherited linter — handles Docker
# automatically. This is the full gate: surefire unit tests, the *IT failsafe tier, Playwright E2E,
# plus Checkstyle/PMD/SpotBugs/Javadoc/PITest/enforcer/license/dependency analysis.
mvn clean install -Dall
# Prerequisite: Playwright browsers must be installed once: cd e2e && npx playwright install

# Run ONLY the linters defined in the parent POM (no tests executed; test sources still compile so
# PITest mutation coverage can run). Use this for a fast quality gate.
mvn clean install -Dlint

# Run unit tests only (pure JUnit *Test classes — no DB/Docker needed). Tests are opt-in, so the
# `-Dtests` flag is required; a bare `mvn test` skips everything.
mvn test -Dtests

# Run a single test class
mvn test -Dtests -Dtest=MyTestClass

# Run only *IT.java integration tests (starts and stops the test DB automatically)
mvn clean install -Dit

# Run Playwright E2E tests only (test DB on :5433). Defaults to an app on :8080; point it at any
# running instance with BASE_URL (e.g. the dev server, or the -Dall jar — both on :8081).
cd e2e && npm test                                  # against :8080
cd e2e && BASE_URL=http://localhost:8081 npm test   # against the dev / -Dall testing port

# Full Docker deployment
cp .env.example .env   # fill in DB_PASSWORD and SESSION_ENCRYPTION_KEY
docker compose up -d --build
docker compose logs -f app
```

> **Always invoke the Compose CLI as `docker compose` (the v2 plugin), NEVER the legacy
> `docker-compose` (hyphenated) binary.** This applies to every command; only the `docker-compose.yml`
> / `docker-compose.dev.yml` **filenames** keep the hyphen, since those are the literal files on disk.

Dev mode disables Testcontainers and expects a local PostgreSQL on `localhost:5432` with database/user/password all `diurnal` (start it with `docker compose -f docker-compose.dev.yml up -d dev-db`). Flyway migrations run automatically at startup. A single `docker-compose.dev.yml` defines **both** local databases as separate services: `dev-db` (persistent, 5432, `diurnal`) and `test-db` (tmpfs, 5433, `diurnal_test`); always start/stop them **by service name** so the two never interfere.

> **Always tear down the dev environment once testing/verification is finished.** Never leave dev
> resources running at the end of a task:
> 1. Stop the dev app: `pkill -f "quarkus:dev"` and confirm port 8081 is free (frees 8081 for the
>    `@QuarkusTest` test-port and stops a stale server masking changes).
> 2. Stop the local DBs: `docker compose -f docker-compose.dev.yml down` (brings down both `dev-db`
>    and `test-db`; the `dev_postgres_data` volume persists, so dev data survives the next start).
>
> The **test** DB (the `test-db` service in `docker-compose.dev.yml`, port 5433) does **not** need
> manual teardown for a test run: the `-Dall` / `-Dit` Maven profiles start it (`up … test-db`) in
> `pre-integration-test` and remove it (`rm -sf test-db`) in `post-integration-test` automatically,
> **scoped to the service** so they never touch a running `dev-db`. Only close it by hand if you
> started it yourself (the unit-test command `mvn test -Dtests` runs pure JUnit and needs no DB).

Config is layered by Quarkus profile: `application.properties` holds the base/production config, and profile-specific overrides live in `application-dev.properties` (dev mode — HTTP port **8081**, dev DB on 5432, DEBUG logging, Swagger UI) and `application-test.properties` (test DB on 5433, forced UTC). Both profile files **must** stay in `src/main/resources` (not `src/test/resources`): the `-Dall` E2E step runs the packaged jar with `-Dquarkus.profile=test`, which only reads config that was bundled into the jar.

**Port map**: **8080** = production (`application.properties` default, the `docker compose` container); **8081** = the single "testing" port — dev mode (`application-dev.properties`), the `@QuarkusTest` test-port the unit/`*IT` tests bind, *and* the packaged-jar E2E run under `-Dall` (`-Dquarkus.http.port=${e2e.http.port}` in `pom.xml`). These three never run at once, so they share one port. Under `-Dall` the ordering is the load-bearing detail, and it comes **only from Maven phase binding, never from plugin/execution declaration order** — the inherited sortpom plugin re-sorts plugins (by `artifactId`) and executions on every build, so anything relying on declaration order is silently broken (e.g. `exec-maven-plugin` always sorts before `maven-failsafe-plugin`). So the `*IT` tests run via failsafe in `integration-test` (failure deferred to `verify`), the **failsafe gate fails the build in `verify` if any IT failed**, and only then does the E2E run — bound to `install`, the first phase after `verify` — so the ITs are always confirmed green before E2E starts, and 8081 is long released by the `@QuarkusTest` instance. The E2E step (`e2e/run-e2e.sh`) is self-contained: it brings up its own test DB, starts the jar on 8081, runs Playwright, tears everything down via an EXIT trap, and exits with Playwright's code (so an E2E failure fails the build). Keeping testing on 8081 leaves 8080 free, so `-Dall` coexists with a running production container.

> **Don't `cd` into the project root before running commands.** The working directory is already the
> project root (`/home/arouge/git/diurnal`); use plain or absolute paths. A redundant `cd .` (or
> `cd` to the current dir) only triggers a needless permission prompt.

## Architecture

### Build: parent POM, submodule & quality gates

The build **inherits from `net.zodac:parent-pom`** (resolved from Maven Central, `<relativePath/>` empty —
no sibling checkout required). The parent centralises **all dependency and plugin versions** plus the
shared lint/test configuration; this POM declares dependencies/plugins without versions and lets the
parent manage them (the Quarkus BOM, JUnit BOM, jbcrypt, jspecify, etc. all come from the parent).

The parent's lint rule files (Checkstyle/PMD/SpotBugs filters, license header) live in a **git submodule**,
`code-quality-config/`, vendored into this repo (mirrors how the parent itself does it). The
`maven-checkstyle-plugin`/etc. `ci-config-directory` and `license-file` properties are overridden in
`pom.xml` to point at this local submodule, so the lint config resolves without the parent being a
filesystem parent. Run `git submodule update --init` after cloning or the linters can't find their config.

Quality gates are **opt-in via profile-activating properties** (the parent's model):
- `-Dlint` → runs the full linter suite: ErrorProne+NullAway (these also run on *every* compile, gated by
  the compiler, so the code must stay null-safe to build at all), Checkstyle, PMD, SpotBugs, Javadoc,
  Enforcer (dependency convergence/bans), license headers, dependency analysis, and **PITest** mutation
  coverage. Compiles test sources but does not execute tests.
- `-Dtests` → runs the surefire unit tests (`*Test`) only.
- `-Dit` → runs the failsafe `*IT` tier (auto-manages the test DB).
- `-Dall` → **everything**: unit + `*IT` + Playwright E2E **and** the full linter suite (it activates both
  the parent's `activate_all` profile and this POM's `all` profile). This is the complete CI gate.

A bare `mvn test` / `mvn package` runs **no tests** (skip-tests defaults true in the parent). Project-specific
lint adjustments live in `pom.xml`: a project-local `checkstyle/checkstyle-suppression.xml` (allows the `*IT`
naming convention), `maven-dependency-plugin` ignores for Quarkus's extension model, and `pitest-maven`
excludes `*IT` so mutation testing verifies only the unit tests. **All inherited linters currently pass clean
(Checkstyle/PMD/SpotBugs = 0, PITest strength = 100%); keep them that way** — e.g. the codebase is NullAway-
annotated (JSpecify `@Nullable`), every public/package method and type carries Javadoc, locals/params are
`final`, and unit-test assertions carry messages.

### Package layout

Code is organised by feature under `src/main/java/net/zodac/diurnal/`:

| Package  | Contents                                                                                                                    |
|----------|-----------------------------------------------------------------------------------------------------------------------------|
| `action` | `Action` entity + `ActionsWebResource` (CRUD for user-defined trackable habits)                                             |
| `log`    | `ActionLog` entity + `LogWebResource` (increment/decrement per day) + `CalendarResource` (JSON for FullCalendar)            |
| `stats`  | `StatsService` (streak/count calculations) + `StatsWebResource` (paginated stats page)                                      |
| `auth`   | REST API auth: `AuthResource` (register/login → JWT), `TokenService`, `PasswordIdentityProvider`, `TrustedIdentityProvider` |
| `user`   | `User` entity, `UserResource` (`/api/users/me`), `UserSettings` (page size options/validation)                              |
| `web`    | `WebResource` — all the top-level page routes (dashboard, login, register, logout, settings, theme toggle)                  |

### Two authentication surfaces

- **Web UI (`/*`)** — encrypted session cookie (`diurnal_session`), form-based. Quarkus form auth redirects unauthenticated requests to `/login`. `@RolesAllowed("user")` enforces auth at the method level.
- **REST API (`/api/*`)** — Bearer JWT signed with RSA-2048 keys. Keys live in `src/main/resources/jwt-keys/` (dev) or `secrets/` (production, auto-generated on first start by `JwtKeyProvisioner`).

The split is configured in `application.properties` with two `quarkus.http.auth.permission.*` blocks. `quarkus.http.auth.proactive=false` is required so Bearer doesn't intercept web requests before form auth can redirect.

`PasswordIdentityProvider` handles form/API password auth. `TrustedIdentityProvider` handles OIDC users (who have no password). OIDC users are stored with `oidcSubject` + `oidcIssuer` instead of a password hash; the composite unique index `(oidc_issuer, oidc_subject) WHERE oidc_subject IS NOT NULL` enforces uniqueness. OIDC is disabled by default (`quarkus.oidc.enabled=false`).

### HTMX partial responses

Most resources return **HTML fragments** rather than full pages. Qute templates in `src/main/resources/templates/` are either full-page layouts (e.g. `actions.html`) or partials in `templates/partials/`. Resources inject both and choose which to return:
- A full `@GET` returns a `TemplateInstance` for the whole page.
- HTMX-targeted endpoints return `Response.ok(partialTemplate.data(...)).build()`.

Error responses for HTMX mutations use `HX-Retarget` / `HX-Reswap` headers to redirect the swap into the error element rather than the default target.

### CSS build & colour tokens

Tailwind is **compiled, not CDN**. `src/main/css/app.css` (the `@tailwind` entrypoint + colour
tokens) is built by `npm run css` into `src/main/resources/META-INF/resources/css/app.css`, which
Quarkus bundles and serves at `/css/app.css` (`layout.html` links it). `tailwind.config.js` sets
`darkMode: 'class'` and scans **both** `templates/**/*.html` and `src/main/java/**/*.java` (a few
classes are returned from Java, e.g. `StatsService` trend colours — also `safelist`ed). The compiled
file is committed so the app runs without Node; **rebuild it (`npm run css`) after changing any class
in a template or Java file**, or the new class will be purged/missing. The Dockerfile builds it fresh
in a dedicated `css` stage (Node) and copies it into the Quarkus build — Node never reaches runtime.

Colour is tokenised: `app.css` defines `--color-*` CSS variables once (`:root` + `.dark`), and
`tailwind.config.js` exposes semantic utilities backed by them — `bg-surface` / `bg-surface-muted`,
`text-ink` / `text-ink-muted`, `border-line` / `border-line-subtle`, `text-brand`, `text-success`,
`text-danger` — that auto-adapt to dark mode **without a `dark:` variant**. Use these instead of raw
`gray-*`/`indigo-*` pairs (the filled primary button stays literal `indigo-600` on purpose — it must
not lighten in dark mode, so the `brand` token, which flips to indigo-400, is for accents/text only).

The variable set goes beyond the Tailwind-exposed utilities: the inline component CSS (the `.dt-*`
data-table layer and the `.banner-*` messages in `layout.html`, plus the calendar in
`dashboard.html`) consume the tokens directly as `var(--color-*)`, so it carries extra tokens for
states with no plain utility — `--color-brand-strong` / `--color-danger-strong` (deeper active-hover
shades), `--color-text-strong` / `--color-text-faint` (hover target for / resting state of muted
controls), `--color-brand-subtle` (faint brand tint → solid brand in dark, the calendar "today"
fill), `--color-ring-edit` / `--color-ring-confirm` (edit / confirm-delete row rings),
`--color-input-bg` / `--color-input-border` (table edit-row inputs), and a `--color-banner-{error,
success,warning}-{bg,border,text}` group. **No raw hex remains in the UI** except two deliberate
literals: the FOUC background guard in `layout.html` (injected before `app.css` loads, so it can't
reference the variables) and the per-action colour, which is stored user data (default `#6366f1`),
not chrome. Because each token carries its own dark value, the token-based component CSS needs almost
no `.dark` overrides.

**Component classes** (in `app.css` `@layer components`, the single source for each "type" of element
so it looks identical everywhere; built with `@apply`):
- `.btn-primary` (filled CTA), `.btn-secondary` (outlined/OIDC)
- `.card` (surface shell — padding/shadow stay at the call site), `.stat-tile` (inset metric tile)
- `.form-input` (width set by caller), `.form-select`, `.field-label`, `.field-label-caps`, `.help-text`
- `.nav-link` / `.nav-link-active`, `.swatch` + `.swatch-sm`/`.swatch-md` (pagination links reuse `.dt-page-link`)

**Shared structural partials**: `partials/nav-links.html` (one link list rendered into both the desktop
bar and the mobile menu — they can't drift), `partials/stat-tile.html`, `partials/pagination.html` (now
used by Actions, Users, **and** Stats; the dashboard day panel reuses the same `.dt-pagination`/`.dt-page-link`
look but keeps its own markup for live-search passthrough). Theme switching is centralised in
`window.Diurnal.applyTheme(theme, opts)` (defined inline in `layout.html` for the FOUC pass, reused by
the settings picker).

The table chrome (`.dt-*`) still lives inline in `layout.html` (see below) — the one component layer not
yet folded into `app.css` — but it already consumes the `app.css` colour tokens via `var(--color-*)`.

### Shared data-table styling (`.dt-*`)

All data tables — Actions (`/actions`), Users (`/admin/users`), and any future ones — share a single
styling layer so they look and behave identically. The source of truth is a `<style>` block of
semantic `.dt-*` classes in `layout.html` (still plain CSS and inline for now, though every colour is
already a `var(--color-*)` token; moving the rules into `app.css` with `@apply` is the remaining
Tier-2 step; the few remaining dark variants key off the `.dark` class). Wrap a table in `.dt-table` and use
`.dt-row`/`.dt-cell`; `{#include partials/pagination …}` for the footer.

**Two table variants:**
- **Non-editable** — just `.dt-row`/`.dt-cell`, no trailing actions cell.
- **Editable** — adds the shared edit/delete chrome. The mechanism (canonical: the Users table) is an
  **in-place client-side toggle**, *not* a server round-trip: each row renders both a view state
  (`[data-dt-view]`) and a hidden edit state (`[data-dt-edit]`), toggled by `dtStartEdit`/`dtCancelEdit`
  in `layout.html`. Save submits the row's `<form>`; Cancel just restores the view (and `form.reset()`s).

**Shared editable-row chrome (change once, every table updates):**
- `partials/dt-row-actions.html` — the trailing options cell: Edit + Delete in view, Save + Cancel in
  edit. Parameterised by primitives only (`id`, `rowPrefix`, `formPrefix`, `confirmBase`) so it never
  leaks entity specifics; it builds the composite ids/URLs in its own attribute text. Save uses the
  HTML5 `form="{formPrefix}-{id}"` association so it can live in a different cell from the form.
  Buttons sit in fixed-width centred `.dt-actions` slots (right-aligned), so Edit↔Save / Delete↔Cancel
  swap without shifting — and the confirm row reuses `.dt-actions` so its Cancel lands exactly where
  the original Delete was. View-mode actions reveal only when the row is highlighted (`:hover` /
  `:focus-within` — on touch a tap applies `:hover`, so they reveal on tap, same as desktop); edit +
  confirm actions are always visible.
- `partials/dt-confirm-delete-row.html` — the in-place confirm-delete row, **rendered from the resource**
  via `.data(rowId, cols, swatchColour, label, prompt, deleteUrl/deleteTarget/deleteSwap, restoreUrl)`.
- `.dt-row-highlight` (on the row) draws the accent ring — one definition shared by edit and
  confirm rows, drawn with an inset `box-shadow` so it never changes the row's size. Only the
  colour is passed in via `--dt-highlight` (set by `.dt-row-edit` indigo / `.dt-row-confirm` red).
  Edit rows additionally trim cell padding + size inputs to the row line-height so the row keeps
  the same height as its view state (the ring never appears to enlarge the row).

To add an editable table: render each row with `[data-dt-view]`/`[data-dt-edit]` pairs for the editable
cells plus an `id="{formPrefix}-{id}"` edit `<form>`, end the row with
`{#include partials/dt-row-actions id=… rowPrefix=… formPrefix=… confirmBase=… /}`, and return
`partials/dt-confirm-delete-row` from the row's `…/confirm-delete` endpoint.

Cross-table conventions:
- **Explicit confirm-to-save.** Table edits require an explicit Save tick. The **only**
  auto-save-on-change surface is Settings → *User Preferences*, a deliberately non-table panel.
- **Single armed row.** At most one row may be mid-edit or mid-delete-confirm; an armed row always shows
  a visible `.dt-btn-cancel`. `dtClearArmedRows` (in `layout.html`) disarms the rest when another row is
  selected, and the admin page calls it to disarm a row whose delete the server rejected.
- **Red-accent confirm-delete.** Destructive button left, Cancel right (where the original Delete was).

`partials/pagination.html` exposes `#showing-shown` / `#showing-total` count spans so surgical HTMX
deletions (Actions delete returns **204**; the `htmx:beforeSwap` handler in `actions.html` removes the
row and decrements the counters) stay in sync without re-rendering the whole list. Admin delete/role
changes instead re-render the whole `admin-users-list` partial.

### CalendarResource

`GET /logs/events` returns JSON (`CalendarEventDto` records) consumed directly by FullCalendar.js on the dashboard. It intentionally includes archived actions so historical entries still render on the calendar. The dashboard uses a custom month/year picker overlay (not FullCalendar's built-in navigation); `eventClick` mirrors `dateClick` to open the day panel.

### Settings preview thumbnails

The **Theme** and **Calendar style** pickers in `settings.html` are not abstract icons — each option is a
scaled-down real screenshot of the dashboard in that configuration (rendered by
`partials/preview-option.html`, with an `(!)` button that opens the full-size image in a lightbox). Nine
PNGs live in `src/main/resources/META-INF/resources/img/settings/`: `theme-{light,dark,system}.png` (the
theme picker; `system` is a diagonal light/dark split) and `calendar-{full,minimal,stacked}-{light,dark}.png`
(the calendar picker — captured in **both** themes; the tile shows the variant matching the active mode via
a `dark:` class toggle, so it always matches the chosen light/dark theme). `calendar-full-{light,dark}.png`
are copies of `theme-{light,dark}.png`.

**These are committed assets — nothing in the app or the Maven/Docker build regenerates them.** Re-run
`scripts/generate-settings-previews.cjs` whenever the dashboard's appearance changes in a way the
previews should reflect (calendar markup/styling, light/dark colour tokens, navbar/day-panel/layout):

```bash
docker compose -f docker-compose.dev.yml up -d dev-db && mvn quarkus:dev   # need a running dev server
node scripts/generate-settings-previews.cjs                                # defaults to :8081; then commit the PNGs
```

The script is self-contained: it registers a throwaway demo user, seeds a fixed set of actions/logs over
HTTP (idempotent), captures every theme/calendar combination from the **same** data so the only visible
difference between previews is the setting itself, and finally losslessly optimises the PNGs with
`optipng` (installing it via `apt-get` if missing — so neither the runtime image nor the host needs it
preinstalled). It talks to the app only over HTTP (no DB access), so `BASE_URL=…` can point it at any
running instance.

### Pagination

All three list views (actions, day-panel actions, stats) share the same in-memory pagination pattern: fetch all, filter, slice. The pagination controls are rendered server-side as HTML. Page size is a per-user setting validated by `UserSettings.sanitisePageSize()` against a fixed allow-list `{10, 25, 50, 100}`.

`PaginatedDayActions` adds blank filler rows to keep every page the same height, preventing layout shift when fewer items are on the last page.

### Notable invariants

- `ActionLog.MAX_DAILY_COUNT = 255` — the count column is a `SMALLINT`; increment is silently capped.
- Actions are soft-deleted (`archived = true`); logs are hard-deleted when an action is deleted.
- **All date-boundary "now"/"today" goes through `AppClock`** (`net.zodac.diurnal.time.AppClock`, `@ApplicationScoped`), the single injectable clock built from `app.timezone`. Business logic calls `clock.today()` / `clock.zone()` instead of `LocalDate.now(...)` directly (streaks in `StatsService`, the future-date guard in `LogWebResource`, the dashboard's pre-selected day in `WebResource`, admin timestamp formatting in `AdminWebResource`). This is the seam tests freeze (see Testing conventions). Entity audit timestamps (`createdAt`/`updatedAt`/`lastLoginAt`) deliberately stay on plain `Instant.now()` — they're zone-independent and not date-boundary sensitive, so they bypass `AppClock`.
- `app.timezone` (defaults to `UTC`) feeds `AppClock`'s zone, so it governs every "today" comparison. It must match `TZ` in `docker-compose.yml`.
- `LogWebResource.isFuture()` blocks logging for dates beyond today in the user's configured timezone.
- Action colour defaults to `#6366f1` (indigo); invalid hex colours are silently corrected to the default.
- The dark-mode checkbox in settings uses a hidden `<input value="false">` followed by the real checkbox `<input value="true">`. When checked, the form posts `["false", "true"]`; when unchecked, just `["false"]`. `WebResource.updateSettings` checks for `"true"` in the list rather than treating the param as a boolean.
- `password.auth.enabled=false` disables the register page (returns 404) and skips the `PasswordIdentityProvider`. `AppLifecycle` enforces that at least one of password-auth or OIDC is enabled at startup.
- Login page uses query params for state: `?error` signals a failed login; `?registered=true` shows a success message after registration.
- `ActionStats` exposes `sinceLabel()`, `monthTrend()`, `monthTrendClass()` helpers used directly in Qute templates for display formatting.

### Database migrations

Flyway scripts live in `src/main/resources/db/migration/`. Schema versioning is sequential (`V1__` through `V8__`). Never edit an already-applied migration; always add a new `V{n+1}__` file.

### Testing conventions

Integration tests extend `IntegrationTestBase`, which truncates tables in FK-safe order (`action_logs → actions → users`) before each test. Helper methods `newUser()`, `newAction()`, `newLog()`, and `runInTx()` are provided. Tests use `@TestSecurity` to set the principal email and roles. The `test` profile (`application-test.properties`) forces `app.timezone=UTC` and uses the test DB on port 5433. BCrypt cost is set to 4 in tests for speed. E2E Playwright tests run sequentially (1 worker) against a live app instance; Chromium desktop and Galaxy S24 mobile viewports are both covered.

**Deterministic time in tests.** `IntegrationTestBase` injects `AppClock` and, in `@BeforeEach`, freezes it to a fixed date — `public static final LocalDate FIXED_TODAY` (currently `2026-06-15`) — restoring the real clock in `@AfterEach`. Date-relative IT tests anchor on `FIXED_TODAY` (e.g. `static final LocalDate TODAY = FIXED_TODAY`) rather than `LocalDate.now()`, which removes the old class-load-vs-request midnight race. Per-test, call `freezeDate(LocalDate)` or `freezeInstant(Instant, ZoneId)` to drive boundary cases — see `LogResourceIT.futureGuard_rollsOverAtMidnight` (midnight rollover) and `futureGuard_dependsOnConfiguredZone` (same instant, UTC vs `Pacific/Auckland`). Pure unit tests (`StatsServiceTest`, `ActionStatsTest`) pass a fixed `today` directly. Surefire/failsafe also pin `-Duser.timezone=UTC` so a non-UTC host can't mask a missing-`ZoneId` regression. E2E specs compute dates entirely in UTC (`setUTCDate`/`getUTCDate` + `toISOString`, never the local `setDate`), Playwright pins the browser clock with `timezoneId: 'UTC'`, and e2e must run against a UTC server (the `-Dall` jar is; a dev server on a non-UTC host can disagree near midnight).
