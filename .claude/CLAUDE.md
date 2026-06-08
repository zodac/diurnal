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

### HTMX partial responses

Most resources return **HTML fragments** rather than full pages. Qute templates in `src/main/resources/templates/` are either full-page layouts (e.g. `actions.html`) or partials in `templates/partials/`. Resources inject both and choose which to return:
- A full `@GET` returns a `TemplateInstance` for the whole page.
- HTMX-targeted endpoints return `Response.ok(partialTemplate.data(...)).build()`.

Error responses for HTMX mutations use `HX-Retarget` / `HX-Reswap` headers to redirect the swap into the error element rather than the default target.

### CalendarResource

`GET /logs/events` returns JSON (`CalendarEventDto` records) consumed directly by FullCalendar.js on the dashboard. It intentionally includes archived actions so historical entries still render on the calendar.

### Pagination

All three list views (actions, day-panel actions, stats) share the same in-memory pagination pattern: fetch all, filter, slice. The pagination controls are rendered server-side as HTML. Page size is a per-user setting validated by `UserSettings.sanitisePageSize()` against a fixed allow-list `{10, 25, 50, 100}`.

### Notable invariants

- `ActionLog.MAX_DAILY_COUNT = 255` — the count column is a `SMALLINT`; increment is silently capped.
- Actions are soft-deleted (`archived = true`); logs are hard-deleted when an action is deleted.
- `app.timezone` (defaults to `UTC`) is injected everywhere a "today" comparison is needed (streaks, future-date guard, dashboard). It must match `TZ` in `docker-compose.yml`.
- The dark-mode checkbox in settings uses a hidden `<input value="false">` followed by the real checkbox `<input value="true">`. When checked, the form posts `["false", "true"]`; when unchecked, just `["false"]`. `WebResource.updateSettings` checks for `"true"` in the list rather than treating the param as a boolean.
- `password.auth.enabled=false` disables the register page (returns 404) and skips the `PasswordIdentityProvider`. `AppLifecycle` enforces that at least one of password-auth or OIDC is enabled at startup.

### Database migrations

Flyway scripts live in `src/main/resources/db/migration/`. Schema versioning is sequential (`V1__` through `V8__`). Never edit an already-applied migration; always add a new `V{n+1}__` file.
