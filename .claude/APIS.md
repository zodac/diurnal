# APIS.md — Public API consolidation plan

**Goal:** let open-source community users build mobile apps and other integrations against a single, consistent,
fully documented REST API. Everything integration-facing lives under `/api`; everything else is unambiguously
internal to the web UI.

**Status:** ✅ COMPLETE — all six phases done (Phases 1–5 on 2026-07-14, Phase 6 on 2026-07-15). This file is
now the record of what shipped plus the "Future considerations" backlog at the bottom. The conventions in §2
are also summarised in CLAUDE.md ("API namespaces" + "Single business logic") for day-to-day work. All work
landed directly on `master` (no branches, per CLAUDE.md) and is uncommitted until the maintainer commits;
record any future API change as a dated entry under "Post-completion additions" below.

---

## 1. Current-state audit (2026-07-14)

### 1.1 Public today (JSON, Bearer-token, documented in Swagger)

| Endpoint                  | Class              | Notes                                                                                                                                                        |
|---------------------------|--------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `POST /api/auth/register` | `AuthResource`     | JSON, but `@Operation(hidden = true)` — not visible in Swagger                                                                                               |
| `POST /api/auth/login`    | `AuthResource`     | Fully annotated                                                                                                                                              |
| `POST /api/auth/logout`   | `AuthResource`     | Fully annotated                                                                                                                                              |
| `GET /api/users/me`       | `UserResource`     | Fully annotated                                                                                                                                              |
| `GET /logs/events`        | `CalendarResource` | **The outlier** — public, documented, but outside `/api`; special-cased in `PublicApiFilter.PUBLIC_APP_PATHS`; doubles as the dashboard `full`-calendar feed |

### 1.2 Internal-only (web UI plumbing — HTMX fragments, form posts, UI-cache JSON)

| Surface        | Endpoints                                                                                                                                                                                   | Response type  |
|----------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------|
| Actions UI     | `GET /actions/list`, `GET /actions/{id}`, `GET /actions/{id}/confirm-delete`, `POST /actions`, `POST /actions/{id}`, `POST /actions/{id}/delete`                                            | HTML fragments |
| Logs UI        | `GET /logs/day/{date}`, `GET /logs/day/{date}/list`, `GET /logs/{date}/{actionId}`, `GET .../confirm-delete`, `POST .../delete`, `POST .../increment`, `POST .../decrement`, `POST .../set` | HTML fragments |
| Logs UI (JSON) | `GET /logs/month/{month}` (JSON map of date → day-panel **HTML**), `GET /logs/minimal-events` (`@Operation(hidden)`)                                                                        | JSON, UI-only  |
| Stats UI       | `GET /stats/list`                                                                                                                                                                           | HTML fragment  |
| Admin UI       | `GET /admin/users/list`, `GET /admin/users/{id}`, `GET .../confirm-delete`, `POST .../role`, `POST .../delete`                                                                              | HTML fragments |
| Settings posts | `POST/PATCH /settings/*` (11 endpoints), `POST /settings/sessions/revoke-all`                                                                                                               | HTML/redirects |

### 1.3 Pages & operational (neither public API nor "internal API" — they keep their URLs)

Full-page routes (`/`, `/login`, `/register`, `/welcome`, `/actions`, `/stats`, `/settings`, `/admin`,
`/admin/users`, `/admin/api-docs`, `/logout`, `/oidc-login`, `/oauth2/callback/oidc`), plus `GET /health`
(container HEALTHCHECK / probe endpoint — deliberately unauthenticated, stays at its stable root path).

### 1.4 The integration gap (key finding)

A mobile app **cannot actually do anything useful today**: it can log in and read a calendar feed, but every
mutation (create action, increment a day's count) exists only as an HTMX endpoint that consumes form params and
returns HTML fragments. "Expose all APIs consistently" therefore means **building** JSON equivalents for the core
resources (Phase 3), not just relocating paths.

### 1.5 Path-coupled infrastructure (must be kept in sync with any move)

- `SessionAuthMechanism.API_PATH_PREFIX = "/api/"` — anonymous `/api/*` → `401`, everything else → `302 /login`.
- `application.properties`: `quarkus.http.auth.permission.api.paths=/api/*` (permit), `quarkus.swagger-ui.path=/api`
  (the Swagger shell itself lives at `/api`), `swagger-ui-assets` + `html-pages` filter regexes (`html-pages`
  excludes `api/` — anything under a new `/internal/` prefix keeps `no-cache`, which is what we want).
- `PublicApiFilter` (`API_PREFIX` + `PUBLIC_APP_PATHS`), `NotFoundExceptionMapper` (`/api`|`/q/` → JSON 404).
- `OpenApiDocsPaths` / `OpenApiDocsAuthFilter` / `CspPolicy` — gate/relax only the docs shell (`/api`,
  `/q/openapi*`); unaffected as long as the shell path is unchanged.
- `CsrfProtectionFilter` — method+cookie based, not path based; Bearer-only API calls are exempt by design. No change.
- Frontend: `dashboard.js` fetches `/logs/events`, `/logs/minimal-events`, `/logs/day/{date}`, `/logs/month/{ym}`;
  `day-panel.html`/`day-actions-list.html` build `/logs/day/...` URLs.
- Tests: `CalendarResourceIT`, `CalendarApiAuthIT`, `PublicApiFilterTest`, `SessionAuthMechanism` tests,
  `tests/ui/dashboard.spec.ts` (waits on `/logs/events`), `tests/helpers/fixtures.ts` + `tests/ui/*.spec.ts` +
  `tests/smoke/smoke.spec.ts` (`/api/auth/register`), `README.md:330` (names `/logs/events` as a public contract).

---

## 2. Target conventions

1. **Public API namespace: `/api/v1/*`.** Versioned now, while there are zero external consumers — renaming later
   would be a breaking change for exactly the community this work courts. `v2` can be added someday without
   breaking `v1`. The Swagger UI shell stays at `/api` (it documents the namespace it sits on; no clash with
   `/api/v1/*` resources).
2. **Public = JSON in, JSON out, Bearer session token (cookie also accepted), fully OpenAPI-annotated,
   appears in Swagger.** Nothing under `/api` may return HTML; nothing outside `/api` may be documented.
3. **Internal UI namespace: `/internal/*`** for HTMX fragments, fragment-mutations, and UI-cache JSON.
   Deliberately **not** `/api/internal/*`: `/api/*` gets the `401` challenge (fragments need the `302 /login`),
   is covered by the `permit` permission block and the Swagger/asset filter regexes, and would put HTML back into
   the namespace we are cleaning. Precedent: GitLab `/-/`, k8s-style `/internal`. Page routes and classic
   full-page form posts (`POST /login`, `POST /register`) keep their user-visible URLs.
4. **`/health` stays put** — probes and container HEALTHCHECKs reference it; it is operational, not integration API.
5. **Rule for future endpoints:** integration-usable → `/api/v1/` + full annotations; UI-only → `/internal/`
    + `@Operation(hidden = true)` is no longer needed (the filter prunes by prefix).

### Decision points (recommended defaults — flag to maintainer before the relevant phase)

| #  | Decision                                                   | Recommendation                                                                                                                                                                                                                                                                                                                                                                |
|----|------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| D1 | Version prefix `/api/v1` vs unversioned `/api`             | **`/api/v1`** (see above)                                                                                                                                                                                                                                                                                                                                                     |
| D2 | Internal prefix                                            | **`/internal`** (not `/api/internal`, reasons above)                                                                                                                                                                                                                                                                                                                          |
| D3 | Keep `/logs/events` as a deprecated alias for one release? | **No alias** — pre-1.0, single known deployment, README already reserves the right to break with a release-notes entry. Maintainer must note it in `RELEASE_NOTES.md` (hand-authored — do not edit it in this work)                                                                                                                                                           |
| D4 | Scope of the `/internal` move                              | **Move HTMX fragments + fragment-mutations + UI JSON** (§1.2). Settings posts move too (they are fetch/HTMX). `POST /login`/`POST /register` stay (classic page-flow posts)                                                                                                                                                                                                   |
| D5 | Docs access for community devs (Swagger is admin-gated)    | **Keep the runtime admin gate.** ~~Additionally publish the generated spec as a committed `docs/openapi.json`~~ — **REVERSED by maintainer 2026-07-15**: a committed export goes stale whenever an annotation changes without a rebuild+recommit, so it was deleted. The annotations are the single source of truth; the spec is read live from `/q/openapi` / the Swagger UI |
| D6 | `/api/v1/logs` read shape                                  | Keep the calendar-event feed (`GET /api/v1/logs/events`, existing `CalendarEventDto` contract) **and** add `GET /api/v1/logs/{date}` for per-day counts                                                                                                                                                                                                                       |

---

## 3. Phases

### Phase 1 — Adopt `/api/v1` and absorb the outlier `/logs/events`  [STATUS: ✅ DONE 2026-07-14]

Small, shippable, establishes the convention. All breaking renames happen in this one phase.

**As implemented (deltas from the plan below):**

- `AuthResource` → `/api/v1/auth`, `UserResource` → `/api/v1/users`.
- `CalendarResource` split: new `log/LogsApiResource` (`/api/v1/logs/events`, carries `CalendarEventDto`);
  `CalendarResource` kept only `minimal-events`, moved to `/internal/logs/minimal-events` already (per the
  "prefer moving it now" note). Shared `requireDate` extracted to package-private `log/DateRanges`
  (+ `DateRangesTest`; the length check uses `Math.min` instead of a ternary — a `>`/`>=` boundary mutant at
  exactly 10 chars is equivalent and would break the 100% PIT strength gate).
- `dashboard.js`: feed URLs centralised in `feedEndpoint()`; new `feedJson()` guard redirects to `/login` on a
  `401` (the `/api/*` challenge is now 401, not the 302 browsers used to follow into a JSON parse error).
- `PublicApiFilter`: prefix-only (`PUBLIC_APP_PATHS` allow-list deleted) — Phase 4 step 2 is therefore done.
- Tests: `CalendarResourceIT` split into `LogsApiResourceIT` (events) + `CalendarResourceIT` (minimal-events);
  `CalendarApiAuthIT` renamed `LogsApiAuthIT` (anonymous now asserts `401`, not the login redirect). All
  `/api/auth`/`/api/users`/`/logs/events` strings updated across `src/test` and `tests/` (fixtures + specs).
- **Also fixed pre-existing lint debt** (the code-quality-config 1.2.4 bump — see memory
  `lint-gate-submodule-bump-debt`): 9 Checkstyle violations (line-length reflows in `AppConfig`/`AppInfo`/
  `AppConfigTest`/`AuthResourceIT` + indentation in `AuthResourceIT`) and 1 surviving PIT mutant
  (`AppInfo.image()` boundary conditional → replaced with branch-free `split("\\.", 2)`). The `-Dall` gate was
  red on master before this; it is green now.
- Verified: `mvn clean install -Dall` green — 424 unit, 302 IT, 221 E2E (+1 pre-existing conditional skip in
  `admin.spec.ts`), 4 smoke, all linters clean, PIT strength 100%.
- ⚠️ Maintainer TODO: `RELEASE_NOTES.md` entry — breaking renames `/api/auth/*`→`/api/v1/auth/*`,
  `/api/users/me`→`/api/v1/users/me`, `/logs/events`→`/api/v1/logs/events` (no deprecated aliases, per D3).

1. Move `AuthResource` → `@Path("/api/v1/auth")`, `UserResource` → `@Path("/api/v1/users")`.
2. Split `CalendarResource`:
    - New `log/LogsApiResource` (or rename) at `@Path("/api/v1/logs")` carrying `GET /events` (unchanged
      behaviour, DTOs, `@Compressed`, annotations). `CalendarEventDto` moves with it.
    - `minimalEvents` stays behind, internal (it moves again under `/internal` in Phase 2 — or move it now to
      avoid touching `dashboard.js` twice; **prefer moving it now**: `/internal/logs/minimal-events`).
3. Update `dashboard.js`: `full` feed → `/api/v1/logs/events`; `minimal/stacked` feed → the minimal-events URL
   chosen in step 2. Add a small `401 → window.location = '/login'` guard on these fetches (anonymous `/api/*`
   now returns `401` instead of the `302` the browser used to follow into a JSON parse error — strictly better,
   but handle it).
4. Sync path-coupled infra (§1.5): `PublicApiFilter` — drop `PUBLIC_APP_PATHS`, prefix stays `/api/` (v1 is
   inside it); update the `POST /api/auth/login` strings in `DiurnalApiDefinition`/`TokenResponse` docs and
   `AuthResource` Javadoc; `SessionAuthMechanism`/`application.properties` need **no** change (`/api/` prefix
   still covers `/api/v1/`).
5. Update tests: `CalendarResourceIT`, `CalendarApiAuthIT`, `PublicApiFilterTest`, `tests/helpers/fixtures.ts`,
   `tests/ui/*.spec.ts` (register calls + `/logs/events` waits), `tests/smoke/smoke.spec.ts`.
6. Update README API references (`README.md:330` and any curl examples). Flag the breaking rename to the
   maintainer for `RELEASE_NOTES.md` (do not edit that file).
7. Gate: `mvn clean install -Dall`.

### Phase 2 — Move UI-internal endpoints under `/internal`  [STATUS: ✅ DONE 2026-07-14]

Cosmetic-but-clarifying; do after Phase 1 so the boundary rule is already real. Scope per D4.

**As implemented:**

- `LogWebResource` → `@Path("/internal/logs")` wholesale (it was all fragments; no page).
- Page/fragment splits, one new `*InternalResource` per area (page class keeps its URL and template; the
  internal class owns the fragments, mutations, and the shared pagination helper as a Javadoc'd
  package-private static + package-private record):
    - `action/ActionsInternalResource` (`/internal/actions`) — list/row/confirm-delete/create/update/delete +
      `getActions`/`PaginatedActions`; `ActionsWebResource` keeps only the `/actions` page.
    - `stats/StatsInternalResource` (`/internal/stats`) — `list` + `paginate`/`PaginatedStats`;
      `StatsWebResource` keeps only the `/stats` page.
    - `web/AdminUsersInternalResource` (`/internal/admin/users`) — list/row/confirm-delete/role/delete +
      `getUsersPage(pageNum, pageSize, zone)`/`PaginatedUsers` (zone passed in, so the helper is static);
      `AdminWebResource` keeps the `/admin/users` + `/admin/api-docs` pages.
- `WebResource` settings endpoints re-pathed in place (class is rooted at `/`): all 12 fragment/fetch posts —
  theme, font, calendar-view, timezone, page-size, decimal-places, stats-fields, show-stats-summary,
  display-name, password, password/verify, sessions/revoke-all — now `internal/settings/*`. The `/settings`
  page, `/login`, `/register`, `/logout`, OIDC routes unchanged.
- Consumers updated: templates (`actions.html`, `settings.html`, `partials/{action-row,actions-list,
  admin-user-row,admin-users-list,day-action-item,day-action-item-confirm-delete,day-actions-list,day-panel,
  search-input,pagination,stats-cards}.html` — note `pagination`'s `pageUrl` non-JS fallback keeps the PAGE
  URL, only `listUrl` moved), `dashboard.js` (`/internal/logs/day|month`), Java-built `deleteUrl`/`restoreUrl`
  strings, ITs (`ActionsResourceIT`, `LogResourceIT`, `AdminWebResourceIT`, `SettingsIT`, `IpThrottleIT`,
  `StatsResourceIT`), E2E specs (`actions`, `dashboard`, `settings`, `stats`, `cursor`, `smoke` — the
  `form[hx-post="/internal/actions"]` selectors and fetch URLs; `endsWith("/actions")` waits still match).
- Verified: `mvn clean install -Dall` green — 424 unit, 302 IT (PIT 100%), 221 E2E, 4 smoke.
- ⚠️ Maintainer TODO: `RELEASE_NOTES.md` — internal endpoints relocated (only matters to anyone who scripted
  against the undocumented HTMX endpoints).

1. Re-path the §1.2 inventory: `/actions/*` fragments → `/internal/actions/*`, `/logs/*` UI endpoints →
   `/internal/logs/*`, `/stats/list` → `/internal/stats/list`, `/admin/users/*` fragments →
   `/internal/admin/users/*`, `/settings/*` posts → `/internal/settings/*`. Full-page `@GET`s stay at their
   current paths (split the `@Path` onto methods where a class currently mixes page + fragments, or split
   classes — follow CODE_STYLE.md).
2. Update every consumer: templates (`hx-get`/`hx-post`/`data-*` URLs, `partials/day-panel.html`,
   `partials/day-actions-list.html`, `partials/dt-*` `deleteUrl`/`restoreUrl` built in Java resources),
   `dashboard.js` (`/logs/day/`, `/logs/month/`), `settings.js`, `actions.js`, `admin-users.js`.
3. Confirm challenge behaviour: `/internal/*` anonymous → `302 /login` (default branch — already correct) and
   `html-pages` filter regex still applies `no-cache` (it will, `/internal/` is not excluded).
4. Update E2E specs that assert on fragment URLs; run `mvn clean install -Dall`.
5. No dashboard visual change is expected, so no settings-preview regeneration — but if any template change
   alters rendered markup, regenerate per CLAUDE.md.

### Phase 3 — Build the public JSON API for core resources  [STATUS: ✅ DONE 2026-07-14]

**As implemented (deltas from the plan below):**

- New resources (all `@RolesAllowed(USER)`, JSON, fully OpenAPI-annotated, `BearerAuth` security requirement):
    - `action/ActionsApiResource` (`/api/v1/actions`) — GET list (name-sorted), POST create (201; 400
      blank/over-100-chars name; 409 duplicate; invalid colour silently falls back to default, same as web),
      GET/{id}, PATCH/{id} (absent fields keep current values), DELETE/{id} (204, cascade-deletes logs).
      Nested `ActionRequest`/`ActionDto` records.
    - `log/LogsApiResource` grew the write surface: GET `/{date}` (day's logged counts, name-sorted, logged
      actions only), PUT `/{date}/{actionId}` `{"count":n}` (set; 0 deletes; clamped to 0–999; method named
      `updateCount` — PMD `LinguisticNaming` rejects a non-void `setCount`), POST `.../increment` +
      `.../decrement` `{"amount":n}` (amount defaults to 1 when body/field omitted; `< 1` → 400; atomic,
      race-safe; decrement floors at 0 and removes the row), DELETE `/{date}/{actionId}` (204, no-op if
      absent). Errors: 400 invalid/future date (user's timezone) or bad count/amount, 404 unowned action.
      Nested `SetCountRequest`/`AmountRequest`/`LogEntryDto`/`DayLogEntryDto` records.
    - `stats/StatsApiResource` (`GET /api/v1/stats`) — every active action's stats via the existing
      `StatsService`; nested `ActionStatsDto` (all `ActionStats` fields except `today`, plus
      actionId/name/colour).
- Shared-rule extraction (no logic duplication with the UI):
    - `action/ActionValidation` (package-private) — `sanitiseColour` + `DEFAULT_COLOUR` + `NAME_MAX_LENGTH`,
      used by both `ActionsInternalResource` and the API; covered by `ActionValidationTest` (PIT).
    - `log/LogGuards` (package-private) — `isFuture(date, user, clock)` + `ownedAction(user, actionId)`,
      used by both `LogWebResource` and the API.
    - `openapi/ApiErrorResponse` — the single error-body shape for every `/api/v1` rejection (the auth
      endpoints still use their own identical-shape `AuthResource.ErrorResponse`; consolidate if touched).
- ITs: `ActionsApiResourceIT` (19), `LogsApiWriteIT` (19), `StatsApiResourceIT` (4) — CRUD, validation,
  clamps, future-date on the frozen clock, ownership isolation, cascade-delete.
- Verified: `mvn clean install -Dall` green — 430 unit, 348 IT (PIT 100%), 221 E2E, 4 smoke.
- Out-of-scope items unchanged (admin API, per-token rate limiting, PATs) — see step 6 below.

Original plan:

The substance: make integrations possible. New `*ApiResource` classes (JSON, `@Path("/api/v1/...")`), sharing
behaviour with the UI resources via extracted services/pure static helpers — **no logic duplication** (ownership
checks, `MAX_DAILY_COUNT` clamp, future-date rule via `AppClock`, colour sanitising, duplicate-name rule).
Records stay pure-data; branching goes in helpers/Extensions (PIT gate). Read `.claude/CODE_STYLE.md` first.

Proposed surface (each step = extract shared logic → add resource + DTOs → annotate → IT):

1. **Actions** — `GET /api/v1/actions` (list), `POST` (201, 409 duplicate name, 400 blank), `GET /{id}`,
   `PATCH /{id}` (name/colour), `DELETE /{id}` (204; **document that logs cascade-delete**). Extract shared
   validation from `ActionsWebResource`.
2. **Logs** — `GET /api/v1/logs/events?start&end` (done in Phase 1); `GET /api/v1/logs/{date}` (per-action
   counts that day); `PUT /api/v1/logs/{date}/{actionId}` body `{"count": n}` (set; 0 deletes; clamped to
   0–999); `POST .../increment` + `POST .../decrement` body `{"amount": n}` (kept as verbs — they are the
   atomic race-safe ops mobile clients need); `DELETE /api/v1/logs/{date}/{actionId}`. Errors: 400 future
   date/invalid date, 404 unowned action. Extract the shared guards from `LogWebResource`.
3. **Stats** — `GET /api/v1/stats` (all active actions' `ActionStats` as JSON; computation already exists in
   `StatsService`, needs only a DTO).
4. Each endpoint: `@Tag`, `@Operation`, `@APIResponses`, `@SecurityRequirement(name = "BearerAuth")`, `@Schema`
   on DTOs — match the standard `AuthResource.login` sets.
5. Integration tests per resource (extend `IntegrationTestBase`, `@TestSecurity`/Bearer flows, frozen clock for the
   future-date rule); consider one Playwright smoke assertion that a Bearer round-trip works on the prod image.
6. Out of scope, noted for later: admin API (`/api/v1/admin/users`), per-token rate limiting for write
   endpoints (auth throttle only covers login/registration today), long-lived PATs (sessions expire at
   `SESSION_ABSOLUTE_LIFETIME` = 90d — fine for apps that re-login, awkward for headless scripts).

### Phase 4 — Swagger completeness + a guard against future outliers  [STATUS: ✅ DONE 2026-07-14]

**As implemented:**

- `POST /api/v1/auth/register` un-hidden and fully annotated (201/400/403/404/409/429, the first-admin and
  `ENABLE_REGISTRATION` guards documented as part of the contract).
- `PublicApiFilter` prefix-only pruning had already landed in Phase 1; unchanged here.
- **`openapi/OpenApiSurfaceIT`** — the surface guard: fetches `/q/openapi` as an admin and asserts (a) every
  documented path starts with `/api/v1/` and (b) the METHOD+path set equals the checked-in
  `PUBLIC_API_CONTRACT` (16 operations). Publishing a new endpoint requires consciously updating that
  constant; an internal endpoint leaking into the docs fails CI.
- ~~Spec export to committed `docs/openapi.{json,yaml}`~~ — shipped, then **REMOVED on 2026-07-15 (see D5)**:
  the maintainer judged a committed export perpetually out-of-sync in practice. The
  `store-schema-directory` config was removed (a comment in `application.properties` records why); the spec
  is generated from the annotations and served live at `/q/openapi` / the Swagger UI only.
- Verified: `mvn clean install -Dall` green (430 unit, 350 IT, 221 E2E, 4 smoke, PIT 100%).

1. Un-hide `POST /api/v1/auth/register` (annotate its 201/403/404/409/429 responses — the first-admin and
   `ENABLE_REGISTRATION` guards are part of the contract). Keep `/logs/minimal-events`-style UI feeds out by
   namespace, not by `hidden` flags.
2. Simplify `PublicApiFilter`: prefix-only pruning (no allow-list), keep tag/schema pruning + version stamping.
3. **Add a surface guard test** (pattern: the existing `/me` preferences guard): an IT that fetches `/q/openapi`
   and asserts (a) every documented path starts with `/api/v1/`, and (b) the path+method set equals a
   checked-in expected list — a new endpoint must be consciously added to the public contract, and an internal
   endpoint leaking into the doc fails CI.
4. Docs publication (D5): add a build step exporting the spec to `docs/openapi.json` (Quarkus writes
   `target/openapi/` with `quarkus.smallrye-openapi.store-schema-directory`; wire a copy + commit it), link it
   from the README so integrators get docs without an admin login. Runtime gate stays admin-only.

### Phase 5 — Documentation & handover  [STATUS: ✅ DONE 2026-07-14]

1. ✅ README: new "REST API" section (base URL, login→Bearer curl example, endpoint table, integrator notes,
   pointing at the admin Swagger UI / live `/q/openapi` document); Versioning section now names `/api/v1/*`
   as the breaking-change surface.
2. ✅ CLAUDE.md: package-layout table refreshed (all the new `*ApiResource`/`*InternalResource` classes), new
   "API namespaces" rule section (public vs internal vs pages + the `OpenApiSurfaceIT` contract requirement),
   auth/calendar-feed sections re-pathed.
3. ⚠️ Maintainer TODO (hand-authored, deliberately not edited here): `RELEASE_NOTES.md` entries —
    - BREAKING: `/api/auth/*` → `/api/v1/auth/*`, `/api/users/me` → `/api/v1/users/me`,
      `/logs/events` → `/api/v1/logs/events` (no deprecated aliases).
    - Internal HTMX/UI endpoints relocated under `/internal/*` (undocumented surface).
    - NEW: full public JSON API — actions CRUD, log day read/write (set/increment/decrement/delete), stats;
      register endpoint now documented; committed OpenAPI spec at `docs/openapi.{json,yaml}`.

### Phase 6 — Structurally prevent web-UI/API divergence  [STATUS: ✅ DONE 2026-07-15]

**As implemented (deltas from the plan below):**

- `action/ActionService` + sealed `action/ActionResult` (`Success(action)` | `BlankName` | `NameTooLong` |
  `DuplicateName(name)` | `NotFound`) — owns create/update/delete + `findOwned`; `update` has PATCH semantics
  (null = keep), so the web resources normalise an absent form field to blank ("reject", not "keep").
- `log/LogService` + sealed `log/LogResult` (`Updated(action, count)` | `FutureDate` | `NotOwned`) — owns
  set/adjust/delete/read-count with the shared guard order (future → ownership) via `LogGuards`; named
  `updateCount` not `setCount` (PMD `LinguisticNaming` again). `Updated` carries the owned `Action` so the
  HTMX side renders its partial without re-fetching.
- All four resources (`ActionsInternalResource`, `ActionsApiResource`, `LogWebResource`, `LogsApiResource`)
  are now pure translators: exhaustive `switch` over the sealed result → partial/banner (HTMX) or
  DTO/`ApiErrorResponse`+status (API). Mutation audit logging moved into the services (one message per event;
  the "via API" log variants were dropped).
- **Deliberate per-surface input contracts stay in the resources, now explicitly commented as such**: web
  coerces non-positive amount → no-op (`readCount`) and negative set-count → 0; the API 400s both.
- **Bugfix**: the HTMX surface now rejects >100-char names with the conflict banner (was previously
  unchecked server-side → DB-constraint 500; templates already had `maxlength="100"` client-side).
- `net.zodac.diurnal.SurfaceParityIT` (5 tests) — same inputs through both surfaces, asserting equivalent DB
  state: over-long name, duplicate name, future-date write, the 999 cap, unowned action.
- Verified: `mvn clean install -Dall` green — 430 unit, 355 IT (PIT 100%), 221 E2E, 4 smoke.
- ⚠️ Maintainer TODO: `RELEASE_NOTES.md` line for the over-long-name bugfix.

Original proposal:

**Problem:** Phases 1–5 share the deep invariants (atomic `ActionLog` writes + 999 clamp, `LogGuards`,
`ActionValidation`, `DateRanges`, `StatsService`, the shared events feed), but the endpoint *orchestration* is
still parallel code — `ActionsInternalResource` vs `ActionsApiResource` each run their own blank→strip→
duplicate-query→persist sequence (and each keeps a private `findOwnedAction`), and `LogWebResource` vs
`LogsApiResource` guard/adjust in parallel. Evidence it drifts: the over-100-char name rule exists only on the
API (the HTMX form would surface it as a DB-constraint 500).

**Fix — the pattern login already uses** (`AuthenticationService` + sealed `LoginResult`, shared by
`WebResource.doLogin` and `AuthResource.login`):

1. `action/ActionService` — `create(user, name, colour)`, `update(user, id, name, colour)`, `delete(user, id)`
   returning a sealed result: `Created/Updated(action)` | `BlankName` | `NameTooLong` | `DuplicateName(name)` |
   `NotFound`. Owns the duplicate-name query and `findOwnedAction`; absorbs `ActionValidation`.
2. `log/LogService` — `setCount(user, day, actionId, count)`, `adjust(user, day, actionId, amount, direction)`,
   `deleteEntry(...)` returning `Updated(count)` | `FutureDate` | `NotOwned` | `InvalidAmount`. Deliberate
   surface differences (web no-ops `amount=0`, API 400s it) become explicit branches on named results, not two
   silently different code paths.
3. Resources become pure translators: HTMX → row partials / `HtmxResponses.conflictBanner`; API → DTOs /
   `ApiErrorResponse` + status. New rules land in the service or nowhere.
4. Sealed-result branching is unit-testable (PIT-countable) service logic instead of NO_COVERAGE resource glue.
5. Secondary net: parameterised parity ITs — same inputs through both surfaces, assert equivalent outcomes
   (e.g. duplicate create → 409 JSON vs conflict banner; future-date write → 400 both).
6. Gate: `mvn clean install -Dall`; behaviour-neutral refactor except closing the over-long-name gap on the
   HTMX form (a bugfix worth a release-notes line).

### Post-completion additions

- **2026-07-15 — `POST /api/v1/auth/revoke`** (maintainer request): the API twin of the Settings page's "Log
  out from everywhere" — revokes every session for the account (web + API, including the calling token) via
  the same `SessionStore.revokeAllForUser` the web flow calls, so the semantics cannot diverge. Added to the
  `OpenApiSurfaceIT` contract (now 17 operations); `AuthRevokeIT` asserts both cross-surface directions (API
  revoke kills web cookie sessions; web revoke-all kills Bearer tokens).

- **2026-07-15 — `auth/RegistrationService` + sealed `RegistrationResult`** (final single-logic review): the
  last parallel orchestration. Web and API registration each re-implemented throttle-check → validation →
  duplicate-check → creation, and had genuinely diverged (display-name 2–100 only on the API — the web could
  500 on a 300-char name via the DB column; `lastLoginAt` stamped only by the web; validation failures fed the
  IP throttle only on the web). Now one service owns all of it (API dropped `@Valid` — the service rules are
  authoritative; the record's annotations remain for schema docs). Deliberate per-surface policies stay in the
  resources, commented as such: the API-only first-user refusal, and the web-only confirm-password rule
  (expressed as a nullable service parameter). New ITs pin the unified rules on both surfaces.

- **2026-07-15 — consistency guards** (so future work cannot silently break the conventions):
  `EndpointNamespaceTest` (unit) scans every `@Path` in `src/main/java` and fails any endpoint outside
  `/api/v1/*`, `/internal/*`, or the page-route allowlist — closing the hole where an endpoint outside `/api`
  was invisible to `OpenApiSurfaceIT` (the filter prunes it from the document). `OpenApiSurfaceIT` gained
  `document_everyOperationIsFullyDocumented` (every public operation must carry summary, description,
  responses and a tag). Together with the existing contract test, the sealed-result exhaustive switches and
  `SurfaceParityIT`, the enforcement chain for a new endpoint is: namespace → contract → documentation →
  behavioural parity. The full rule + checklist now lives in CLAUDE.md ("Single business logic").

- **2026-07-15 — Swagger documentation polish + completeness guard**: every path parameter now carries an
  `@Parameter` description (the log-write `date`/`actionId`, the actions `{id}`); the optional
  increment/decrement bodies are marked `@RequestBody(required = false)`; `StatFieldPref` gained `@Schema`
  docs; the SmallRye-generated `UUID`/`LocalDate` schemas are described by `PublicApiFilter`
  (`SHARED_SCHEMA_DESCRIPTIONS`), which also now strips the auto-generated `servers` block (Swagger UI then
  targets its own origin); the top-of-page `@Info` description explains auth, dates and the error shape.
  Permanently enforced by `OpenApiSurfaceIT.document_everyParameterAndSchemaIsDescribed` — every operation
  parameter, component schema and schema **property** must carry a description, so an undocumented field
  fails CI.

### Phase 7 — Full UI↔API capability parity  [STATUS: ✅ DONE 2026-07-15]

**The rule this phase established (now in CLAUDE.md): every user-facing UI capability must have a matching
`/api/v1` endpoint.** The earlier "single business logic" review only prevented *duplicated* rules; it filed
UI-only capabilities under "single-surface, no divergence possible" — the maintainer corrected that criterion.

- **Profile/preferences API** — `PATCH /api/v1/users/me` (display name + all 8 preferences, PATCH semantics)
  and `PUT /api/v1/users/me/password`, via new `user/ProfileService`+`ProfileResult` and
  `auth/PasswordChangeService`+`PasswordChangeResult` (the web's 12 settings endpoints are now translators
  over the same services). Unified along the way: the Settings display-name update now enforces the same
  2–100 bound as registration (was blank-check only — another latent 500 via the 255-char column); the
  bounds live in `UserSettings` (`isValidDisplayName`/`DISPLAY_NAME_RANGE_MESSAGE`), shared with
  `RegistrationService`. Coercion vs rejection semantics preserved exactly (enums/timezone coerce; page
  size/decimal places/display name reject).
- **Admin API** — `/api/v1/admin/users` (GET list, GET/{id}, PATCH/{id} role, DELETE/{id}) via new
  `user/AdminUserService`+`AdminUserResult` (owns the last-admin safeguards; `AdminUsersInternalResource` and
  `AdminWebResource` are now translators). Last-admin violations → `409` on the API, banners on the web.
- **Pagination parity** — `GET /api/v1/actions` (+`q` search), `GET /api/v1/stats` and
  `GET /api/v1/admin/users` now return `{items,totalCount,totalPages,currentPage}` envelopes paged by the
  user's page-size preference with clamped `?page=`, reusing the exact pagination statics the pages use.
  **BREAKING**: the actions/stats responses changed from bare arrays to envelopes (release-notes entry).
- **CORS (opt-in)** — `quarkus.http.cors.enabled=true` (build-time) + `CORS_ALLOWED_ORIGINS` env (runtime,
  default empty = same-origin only, behaviour unchanged). README gained a CORS section with a compose +
  fetch example. `CorsIT` (default = blocked) + `CorsEnabledIT` (test profile with an origin = allowed).
- **Guard upgrade** — `UserPreferencesExposureTest.everyPreferenceFieldIsUpdatableViaTheApi`: a new
  `@Preference` field must appear in `UserResource.PreferencesUpdate` (same name), so a preference can no
  longer ship with only its Settings control.
- Contract now 23 operations (`OpenApiSurfaceIT`); new ITs: `UserMeApiIT`, `AdminUsersApiIT`, `CorsIT`,
  `CorsEnabledIT`, envelope updates in `ActionsApiResourceIT`/`StatsApiResourceIT`, page-size parity in
  `SurfaceParityIT`.
- ⚠️ Maintainer TODO (`RELEASE_NOTES.md`): BREAKING array→envelope on `GET /api/v1/actions`+`/api/v1/stats`;
  NEW profile/password/admin APIs + CORS env var; bugfix: Settings display-name length now validated.

- **2026-07-15 — consolidated `PATCH /internal/settings`** (maintainer request): the 9 per-field Settings
  endpoints (theme, font, calendar-view, timezone, page-size, decimal-places, stats-fields,
  show-stats-summary, display-name) collapsed into ONE partial-update endpoint mirroring the API's
  `PATCH /api/v1/users/me` — only the form fields present in a request change; each Settings control still
  auto-saves by PATCHing with just its own field (deliberately one setting's data per request). Two
  degenerate-case semantics changed with the PATCH contract (both updated in `SettingsIT`): an empty request
  is a `204` no-op (was a display-name `422`), and an absent field keeps its value (previously a
  field-less request to a per-field endpoint coerced it to the default). The password (`password`,
  `password/verify`) and `sessions/revoke-all` flows stay separate (multistep / destructive confirm).

- **2026-07-15 — invalid preference values are now rejected, never coerced** (maintainer request): theme,
  font, calendar view and timezone submissions with unrecognised values return an error naming the allowed
  values (web `422`, API `400`) instead of silently falling back to the default — on both surfaces, via the
  shared `ProfileService`. The enums' `from()` coercers and `UserSettings.sanitiseTimezone` were replaced by
  `isValid()`/`isValidTimezone()` validators (read-side legacy values still render as defaults — templates
  compare raw strings). One deliberate special case: a **blank** timezone remains the explicit "follow the
  server default" reset (the API's only way to clear the override). Swagger texts updated accordingly.

### Future considerations (not scheduled)

- Per-token/per-user rate limiting on the write endpoints (the IP throttle only covers login/registration).
- Long-lived personal access tokens for headless scripts (sessions cap at `SESSION_ABSOLUTE_LIFETIME`, 90d).
- Consolidate `AuthResource.ErrorResponse` into the shared `openapi/ApiErrorResponse` next time auth is touched.
- If community demand warrants offline/public docs, generate the spec in CI (re-add
  `quarkus.smallrye-openapi.store-schema-directory` for that build only) and publish it to a docs site /
  Swagger-UI GitHub Page as a build artifact — never as a committed file (see the D5 reversal).

---

## 4. Verification per phase

Every phase ends with `mvn clean install -Dall` green (unit + IT + E2E + smoke + linters, PIT at 100%,
NullAway/Checkstyle/PMD/SpotBugs clean). Phases 1–2 additionally: manual dashboard sanity check in dev mode
(calendar feeds, day panel, increment/decrement) since they rewire the UI's own fetch paths.
