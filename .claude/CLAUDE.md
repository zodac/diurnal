# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Start the dev PostgreSQL (required before quarkus:dev)
docker-compose -f docker-compose.dev.yml up -d   # or the main compose if no dev file exists

# Run in dev mode (hot reload, Swagger UI at /q/swagger-ui)
mvn quarkus:dev

# Build JAR
mvn package

# Run ALL tests (unit + integration + Playwright E2E) — handles Docker automatically
mvn clean install -Dall
# Prerequisite: Playwright browsers must be installed once: cd e2e && npx playwright install

# Run unit + integration tests only (start the test DB first)
docker-compose -f docker-compose.test.yml up -d
mvn test

# Run a single test class
mvn test -Dtest=MyTestClass

# Run only *IT.java integration tests (starts and stops the test DB automatically)
mvn clean install -Dit

# Run Playwright E2E tests only (app must be running on :8080, test DB on :5433)
cd e2e && npm test

# Full Docker deployment
cp .env.example .env   # fill in DB_PASSWORD and SESSION_ENCRYPTION_KEY
docker-compose up -d --build
docker-compose logs -f app
```

Dev mode disables Testcontainers and expects a local PostgreSQL on `localhost:5432` with database/user/password all `lifetracker`. Flyway migrations run automatically at startup.

## Architecture

### Package layout

Code is organised by feature under `src/main/java/dev/lifetracker/`:

| Package  | Contents                                                                                                                    |
|----------|-----------------------------------------------------------------------------------------------------------------------------|
| `action` | `Action` entity + `ActionsWebResource` (CRUD for user-defined trackable habits)                                             |
| `log`    | `ActionLog` entity + `LogWebResource` (increment/decrement per day) + `CalendarResource` (JSON for FullCalendar)            |
| `stats`  | `StatsService` (streak/count calculations) + `StatsWebResource` (paginated stats page)                                      |
| `auth`   | REST API auth: `AuthResource` (register/login → JWT), `TokenService`, `PasswordIdentityProvider`, `TrustedIdentityProvider` |
| `user`   | `User` entity, `UserResource` (`/api/users/me`), `UserSettings` (page size options/validation)                              |
| `web`    | `WebResource` — all the top-level page routes (dashboard, login, register, logout, settings, theme toggle)                  |

### Two authentication surfaces

- **Web UI (`/*`)** — encrypted session cookie (`lt_session`), form-based. Quarkus form auth redirects unauthenticated requests to `/login`. `@RolesAllowed("user")` enforces auth at the method level.
- **REST API (`/api/*`)** — Bearer JWT signed with RSA-2048 keys. Keys live in `src/main/resources/jwt-keys/` (dev) or `secrets/` (production, auto-generated on first start by `JwtKeyProvisioner`).

The split is configured in `application.properties` with two `quarkus.http.auth.permission.*` blocks. `quarkus.http.auth.proactive=false` is required so Bearer doesn't intercept web requests before form auth can redirect.

`PasswordIdentityProvider` handles form/API password auth. `TrustedIdentityProvider` handles OIDC users (who have no password). OIDC users are stored with `oidcSubject` + `oidcIssuer` instead of a password hash; the composite unique index `(oidc_issuer, oidc_subject) WHERE oidc_subject IS NOT NULL` enforces uniqueness. OIDC is disabled by default (`quarkus.oidc.enabled=false`).

### HTMX partial responses

Most resources return **HTML fragments** rather than full pages. Qute templates in `src/main/resources/templates/` are either full-page layouts (e.g. `actions.html`) or partials in `templates/partials/`. Resources inject both and choose which to return:
- A full `@GET` returns a `TemplateInstance` for the whole page.
- HTMX-targeted endpoints return `Response.ok(partialTemplate.data(...)).build()`.

Error responses for HTMX mutations use `HX-Retarget` / `HX-Reswap` headers to redirect the swap into the error element rather than the default target.

### Shared data-table styling (`.dt-*`)

All data tables — Actions (`/actions`), Users (`/admin/users`), and any future ones — share a single
styling layer so they look and behave identically. The source of truth is a `<style>` block of
semantic `.dt-*` classes in `layout.html` (plain CSS, because Tailwind is CDN-loaded and can't `@apply`
at runtime; dark variants key off the `.dark` class). Wrap a table in `.dt-table` and use
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

### Pagination

All three list views (actions, day-panel actions, stats) share the same in-memory pagination pattern: fetch all, filter, slice. The pagination controls are rendered server-side as HTML. Page size is a per-user setting validated by `UserSettings.sanitisePageSize()` against a fixed allow-list `{10, 25, 50, 100}`.

`PaginatedDayActions` adds blank filler rows to keep every page the same height, preventing layout shift when fewer items are on the last page.

### Notable invariants

- `ActionLog.MAX_DAILY_COUNT = 255` — the count column is a `SMALLINT`; increment is silently capped.
- Actions are soft-deleted (`archived = true`); logs are hard-deleted when an action is deleted.
- `app.timezone` (defaults to `UTC`) is injected everywhere a "today" comparison is needed (streaks, future-date guard, dashboard). It must match `TZ` in `docker-compose.yml`.
- `LogWebResource.isFuture()` blocks logging for dates beyond today in the user's configured timezone.
- Action colour defaults to `#6366f1` (indigo); invalid hex colours are silently corrected to the default.
- The dark-mode checkbox in settings uses a hidden `<input value="false">` followed by the real checkbox `<input value="true">`. When checked, the form posts `["false", "true"]`; when unchecked, just `["false"]`. `WebResource.updateSettings` checks for `"true"` in the list rather than treating the param as a boolean.
- `password.auth.enabled=false` disables the register page (returns 404) and skips the `PasswordIdentityProvider`. `AppLifecycle` enforces that at least one of password-auth or OIDC is enabled at startup.
- Login page uses query params for state: `?error` signals a failed login; `?registered=true` shows a success message after registration.
- `ActionStats` exposes `sinceLabel()`, `monthTrend()`, `monthTrendClass()` helpers used directly in Qute templates for display formatting.

### Database migrations

Flyway scripts live in `src/main/resources/db/migration/`. Schema versioning is sequential (`V1__` through `V8__`). Never edit an already-applied migration; always add a new `V{n+1}__` file.

### Testing conventions

Integration tests extend `IntegrationTestBase`, which truncates tables in FK-safe order (`action_logs → actions → users`) before each test. Helper methods `newUser()`, `newAction()`, `newLog()`, and `runInTx()` are provided. Tests use `@TestSecurity` to set the principal email and roles. The `%test` profile forces `app.timezone=UTC` and uses the test DB on port 5433. BCrypt cost is set to 4 in tests for speed. E2E Playwright tests run sequentially (1 worker) against a live app instance; Chromium desktop and Galaxy S24 mobile viewports are both covered.
