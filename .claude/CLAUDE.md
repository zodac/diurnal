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

Some HTMX partials are rendered as raw HTML strings via `StringBuilder` inside Java (e.g. `ActionsWebResource.renderActionsList()`), rather than Qute templates, for fine-grained control over pagination links.

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
