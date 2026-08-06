# CLAUDE.md

> **Code style:** Project-specific expectations live in [`CODE_STYLE.md`](CODE_STYLE.md). Read it before writing or editing code.

> **UI patterns:** Template/CSS conventions (partial extraction, component classes, tokens, `id` rules) live in
[`UI_PATTERNS.md`](UI_PATTERNS.md). Read it before writing or editing templates or CSS.

> **Deep-reference docs (read the matching one before that kind of work):** authentication, sessions, OIDC & CSP
> live in [`AUTH.md`](AUTH.md); the front-end build/assets/CSS/calendar in [`FRONTEND.md`](FRONTEND.md); the test tiers &
> conventions in [`TESTING.md`](TESTING.md); the shared free-text validation pipeline in
> [`TEXT_INPUT.md`](TEXT_INPUT.md).

> **The per-date free-text notes feature** (the note box, the calendar's green day markers, notes as a stats subject)
> is documented in [`NOTES.md`](NOTES.md) — design, the decisions taken *with the rejected alternatives*, and the
> implementation history. **Read it before touching `net.zodac.diurnal.note`, the dashboard layout/grid, the calendar's
> month cache, or the notes rows on the Stats page.**

> **No real URLs or internal IPs in comments or examples.** Use only `https://diurnal.example.com` or
`http://127.0.0.1:8080` as placeholder values. Never use production hostnames, LAN addresses (`192.168.*`, `10.*`,
`172.16–31.*`), or any other real hostname.

> **Log output must be plain ASCII — never an em-dash (`—`) or any other non-ASCII character in a string that reaches the logs**: `LOGGER.*`
> messages, exception messages, and startup-failure text alike. The production container's console encoding renders non-ASCII as `?` (e.g.
> `... already exists ? sign in locally ...`). Use a plain hyphen `-` instead. UI/template/OpenAPI strings are unaffected (browser-rendered UTF-8),
> as are code comments and Javadoc.

> **Never overwrite `RELEASE_NOTES.md` or `VERSION` unless explicitly asked.** These are hand-authored release
artefacts owned by the maintainer — leave them untouched (even if they appear modified in the working tree) unless the
request explicitly says to update them.

> **NEVER create a git branch without explicit permission.** All work happens directly on `master` — no feature
branches, no working branches, regardless of how large the change is. If a branch genuinely seems necessary, ask
first and proceed only with an explicit yes.

## Commands

```bash
# Fetch the code-quality-config submodule (required for -Dlint / -Dall)
git submodule update --init

# Build CSS (compiled Tailwind at /css/app.css; rebuild after any class/template change)
# The compiled file is a build artifact (.gitignored); any `mvn` build regenerates it via the
# POM's `css-build` exec, but it needs frontend/node_modules — so run the install once after cloning.
# The Node UI-build project (package.json, tailwind.config.js, the CSS source) lives in frontend/.
# For a hot-reload dev loop, run the css:watch script alongside quarkus:dev.
npm --prefix frontend install    # one-time (required for `mvn` to build the CSS)
npm --prefix frontend run css    # or: npm --prefix frontend run css:watch

# Start dev PostgreSQL (required before quarkus:dev)
docker compose -f docker-compose.dev.yml up -d diurnal-db-dev

# Run in dev mode (hot reload, Swagger UI at /api, port 8081)
# ALWAYS stop when done: pkill -f "quarkus:dev"
mvn quarkus:dev

# Build JAR (no tests by default)
mvn package

# Run the quality gate via the wrapper — NOT `mvn clean install -Dall` directly (see the note below).
# No args = auto-detects which steps changed since the last tag; pass explicit steps to scope it.
.github/scripts/lint_and_tests.sh                 # only the steps whose files changed
.github/scripts/lint_and_tests.sh java            # the full Java gate (== mvn clean install -Dall)
.github/scripts/lint_and_tests.sh java,shellcheck # multiple steps, comma-separated
.github/scripts/lint_and_tests.sh -v java         # stream full output (default hides it, prints on fail)
# Valid steps: docker, grype, java, javascript, markdown, shellcheck, typescript
# Prerequisite for the java step: cd tests && npx playwright install

# Run unit tests only (no DB needed) — the one gate the wrapper has no scoped step for
mvn test -Dtests
mvn test -Dtests -Dtest=MyTestClass

# The Playwright E2E/UI suite (specs in tests/ui/) and the deployment-smoke suite are chained onto the
# wrapper's `java` step (mvn gate → E2E → smoke), NOT part of any `mvn` command. Run the whole JVM gate:
.github/scripts/lint_and_tests.sh java
# Or drive the tiers directly (e.g. iterating on one spec against an already-running app):
cd tests && npm test                                  # E2E against :8080
cd tests && BASE_URL=http://localhost:8081 npm test   # E2E against a dev instance
bash tests/run-e2e.sh 8081 "$(pwd)/target" "$(pwd)"   # E2E runner (needs a built target/quarkus-app)
bash tests/run-smoke.sh 8082 "$(pwd)"                 # deployment-smoke runner (self-contained)

# Full Docker deployment
cp docs/.env.example .env   # fill in DB_PASSWORD and NOTE_ENCRYPTION_KEY (openssl rand -base64 32)
docker compose up -d --build
docker compose logs -f app
```

> **Always run the quality gate through `.github/scripts/lint_and_tests.sh`, never `mvn clean install -Dall`
> directly**, and **scope it to the step you touched**: `… java` after ANY Java/template/CSS/UI-spec/Dockerfile
> change, `… shellcheck` after a `*.sh` edit, `… markdown` after docs, etc. (comma-separate to combine; bare =
> auto-detect changed steps; `-v` streams output; `-f`/`--force` runs everything). The `java` step **is** the
> whole JVM gate — `mvn clean install -Dall` (unit + `*IT` + linters) then, only if green, the E2E and
> deployment-smoke tiers. **The Maven build is unit + `*IT` (+ linters) ONLY; E2E/smoke/perf are chained onto
> the wrapper's steps, never in any `mvn` command — do not re-add them to the pom.** Full tier detail:
> [`TESTING.md`](TESTING.md).

> **Always use `docker compose` (v2 plugin), never `docker-compose` (hyphenated).** Only the filenames keep the hyphen.

> **After writing or editing any shell script (`*.sh`), run `.github/scripts/lint_and_tests.sh shellcheck`** and fix
> everything it reports before considering the change done — including `info`-level notes (e.g. `SC2312`). This is the
> same shellcheck gate CI runs; skipping it ships lint failures. Note the step is `shellcheck` (not `shellscript`).

Dev mode expects PostgreSQL on `localhost:5432` with database `diurnal_db`, user `diurnal_user`, and password `diurnal_password`. Flyway migrations
run automatically. Data is ephemeral (
wiped on container recreate).

> **Tear down the dev environment when finished.** Use `scripts/dev-up.sh` / `scripts/dev-teardown.sh`. Manual: `pkill -f "quarkus:dev"`, then
`docker compose -f docker-compose.dev.yml down`. The `-Dall` run manages the test DB automatically in `pre/post-integration-test`.

Config layers: `application.properties` (base/prod), `application-dev.properties` (port 8081, DEBUG), `application-test.properties` (UTC). Both
profile files must stay in `src/main/resources` — the E2E jar runs with `-Dquarkus.profile=test` and only reads bundled config.

**Port map**: 8080 = production; 8081 = dev mode, `@QuarkusTest`, and the E2E jar (never simultaneous); 8082 = deployment-smoke; 8083 = perf — each
tier an isolated compose project that coexists with a running prod stack. How the `java`/`perf` steps chain the fast-jar/E2E/smoke/perf tiers is in
[`TESTING.md`](TESTING.md).

## Architecture

### Build

Inherits from `net.zodac:parent-pom` (Maven Central). The parent manages all dependency/plugin versions (Quarkus BOM, JUnit BOM, jspecify,
etc.). Lint config lives in the `code-quality-config/` git submodule — run `git submodule update --init` after cloning.

Quality gates (opt-in):

- `-Dlint` — ErrorProne+NullAway (also run on every compile), Checkstyle, PMD, SpotBugs, Javadoc, Enforcer, license headers, dependency analysis,
  PITest. Compiles test sources but does not run tests.
- `-Dtests` — surefire unit tests (`*Test`) only.
- `-Dall` — unit + `*IT` + full linters (NOT E2E/smoke — those are chained onto the wrapper's `java` step, outside Maven).

**All linters currently pass clean (Checkstyle/PMD/SpotBugs = 0, PITest strength = 100%); keep them that way.** Code must be NullAway-annotated (
JSpecify `@Nullable`), every public/package method and type carries Javadoc, locals/params are `final`, unit-test assertions carry messages.

### Package layout

Under `src/main/java/net/zodac/diurnal/`:

| Package  | Contents                                                                                                                                                                                                                                                                                                            |
|----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `action` | `Action` entity + `ActionsWebResource` (the `/actions` page) + `ActionsInternalResource` (`/internal/actions` HTMX fragments/mutations) + `ActionsApiResource` (`/api/v1/actions` public CRUD) + `ActionValidation` (shared rules) + `ActionColours` (the randomise-button colour palette/suggestion rules)                                                                                  |
| `log`    | `ActionLog` entity + `LogWebResource` (`/internal/logs` day-panel fragments + increment/decrement) + `LogsApiResource` (`/api/v1/logs` public events feed + day read/write) + `CalendarResource` (`/internal/logs/minimal-events` dashboard feed) + `LogGuards`/`DateRanges` (shared rules)                         |
| `stats`  | `StatsService` + `SubjectStats` (data record, keyed on a `StatSubject` — an action OR the user's notes; `StatSubjectKind`/`StatSubjectExtensions`) + `SubjectStatsExtensions` (template extensions) + `StatField` (Stats-page tile catalogue) + `DisplayStat` (a shown stat + its caption) + `StatTile` (tile view-model) + `StatsSummary` (the dashboard summary card's shared parameter binding) + the frequency-graph family (`FrequencyPeriod`/`FrequencyKeys`/`FrequencyCharts` + the `FrequencyChart`/`FrequencySeries`/`FrequencySlot`/`FrequencyBar` records + their `*Extensions` + the sealed `FrequencyResult`) + `StatsWebResource` (the `/stats` page) + `StatsInternalResource` (`/internal/stats/list` + `/internal/stats/chart/{actionId}`(`/candidates`), plus the dashboard's `/internal/stats/summary/{date}` + `/internal/stats/summary-month/{yyyy-MM}`) + `StatsApiResource` (`GET /api/v1/stats`, `GET /api/v1/stats/{actionId}/frequency`)  |
| `auth`   | `AuthResource` (`/api/v1/auth` register/login/logout/revoke → session token), `AuthenticationService`+`LoginResult`, `RegistrationService`+`RegistrationResult`, `SessionStore`/`PostgresSessionStore` + `Session` entity + `SessionTokens` + `SessionAuthMechanism` + `SessionIdentityProvider` + `SessionSweeper` |
| `note`   | `Note` entity + `NoteQueries` + `NoteService`/`NoteResult` (the single owner of every note write, and the encrypt/decrypt of content) + `NoteContent` (the per-note seal, bound to owner+date) + `UserNotesKey` entity + `NoteKeys` (mints an account's data key at creation, opens it per request) + `NotesInternalResource` (`/internal/notes` range feed + save/clear) + `NotesApiResource` (`/api/v1/notes` public CRUD). One free-text note per user per day, writable for ANY date including future ones, encrypted at rest |
| `user`   | `User` entity, `UserResource` (`/api/v1/users/me`), `UserSettings`, and the settings-picker enums `Theme`/`Font`/`CalendarView` (each `implements PreviewOption`)                                                                                                                                                   |
| `web`    | `WebResource` — all top-level page routes (dashboard, login, register, logout, settings) + the `/internal/settings/*` preference endpoints; `AdminWebResource` (admin pages) + `AdminUsersInternalResource` (`/internal/admin/users` fragments) + `AppInfo` (footer/template metadata bean)                         |
| `colour` | `Colours` - the rules every user-chosen colour obeys: the `#rrggbb` format check, the HSL-to-hex conversion, and the lightening that makes a colour readable on a background (the calendar's brand-filled "today" cell). Shared by `action` and `user`                                                    |
| `crypto` | The encryption primitives, all pure statics with no persistence or request state: `Aes256Gcm` (AEAD seal/open, IV-prefixed), `Hkdf` (RFC 5869 over HMAC-SHA-256, for domain separation), `DataKeyEnvelope` (wrap/unwrap a user's data key under the application master key) and `MasterKey` (decode/validate the configured value). Used by `note`                     |
| `update` | `UpdateCheckService` (admin-only footer "newer version available" check) + `UpdateCheck` (pure version/URL logic) + `UpdateStatus`/`UpdateAvailability` + `LatestReleaseClient`/`GitHubLatestReleaseClient` (the outbound GitHub-release lookup seam)                                                               |

### API namespaces (the rule for every new endpoint)

- **`/api/v1/*` — the public REST API** (JSON in/out, Bearer session token — cookie also accepted, fully OpenAPI-annotated, appears in Swagger).
  The annotations in the code are the single source of truth — the spec is served live from `/q/openapi` and is deliberately NOT exported to a
  committed file (it would go stale). Nothing under `/api` may return HTML. **Every new public operation must be added to
  `OpenApiSurfaceIT.PUBLIC_API_CONTRACT`** — that IT pins the generated document to the exact endpoint set, so an addition (or an internal endpoint
  leaking into the docs) fails CI until the contract is consciously updated. Breaking changes to `/api/v1/*` are MAJOR-version events (see README).
- **`/internal/*` — web-UI plumbing** (HTMX fragments, fragment mutations, UI-cache JSON like `/internal/logs/month`). Never documented, no
  stability guarantees, anonymous requests get the browser `302 /login` challenge (vs `401` for `/api/*`).
- **Page routes stay top-level** (`/`, `/actions`, `/stats`, `/settings`, `/admin/*`, `/login`, `/register`, `/logout`), as do the OIDC routes.
  **The operational status/health probe is `GET /api/v1/status`** (a public API endpoint like any other, fully OpenAPI-annotated and in the
  `OpenApiSurfaceIT.PUBLIC_API_CONTRACT`): it returns JSON `{liveness, readiness, version, uptime}`, is anonymous (container `HEALTHCHECK`s / load
  balancers reach it tokenless), and is **readiness-gated** — `200` when the database is reachable, `503` when not — so the Docker `HEALTHCHECK` (and
  any probe keying on the status code) only reports healthy when the app can serve real traffic. Logic lives in `status/StatusService` +
  `status/StatusAssembler` (pure, unit-tested); the resource is a thin translator. There is no longer a top-level `/health` route.
- **Swagger descriptions capitalise the `id` acronym as `ID`** (never a standalone lowercase "id") — enforced by
  `OpenApiSurfaceIT.document_capitalisesTheIdAcronymInEveryDescription`, which scans every `description`/`summary` in the generated document. The
  `@Parameter(name = "id")` path-param *name* stays lowercase (it is the literal path token); only the human-readable text is affected.

### Single business logic (the rule for every mutation)

**Every backend use case has exactly ONE implementation, and every surface (public API, internal HTMX, web form) is a thin translator over it.**
The pattern is a `*Service` bean returning a sealed result type, with each resource doing an exhaustive `switch` to its own medium (JSON +
status codes vs partials/banners/redirects) — e.g. `AuthenticationService`→`LoginResult`, `ActionService`→`ActionResult`, `ProfileService`→
`ProfileResult`, `AdminUserService`→`AdminUserResult` (grep `*Service`/`*Result` for the full set; `SessionStore` and `StatsService` need no result type).

**Capability parity is mandatory, not just logic sharing: EVERY user-facing capability in the UI must have a matching `/api/v1` endpoint** (the
converse is not required — an API capability need not have UI). The API list endpoints paginate exactly like their pages (the user's page-size
preference, `?page=` clamped into range, `{items,totalCount,totalPages,currentPage}` envelopes). A new `@Preference` field must be readable via
`GET /api/v1/users/me` AND writable via `PATCH /api/v1/users/me` — `UserPreferencesExposureTest` fails otherwise. **Never re-implement a rule in a resource** — if a mutation rule is
being written inside a `*Resource` class, it belongs in the service. Deliberately different per-surface *input contracts* (e.g. the web form
coercing a non-positive amount to a no-op where the API 400s, the API-only first-user registration refusal, the web-only confirm-password
field) stay in the resources **with a comment marking them as surface policy**; the write rules behind them must still be the shared service.
Reads may compose shared entity queries into surface-specific presentations (pagination, DTOs) — presentation is not business logic.

**Checklist for adding/changing an endpoint** (each step is enforced by a failing test, named in brackets):

1. Put the logic in the shared `*Service` (or extend one) — never in the resource — and give every UI-facing capability its `/api/v1` twin.
   [`SurfaceParityIT` catches behavioural drift; sealed-result `switch` exhaustiveness catches unhandled cases at compile time;
   `UserPreferencesExposureTest` enforces preference read+write parity]
2. Pick the namespace: `/api/v1/*` public, `/internal/*` UI plumbing, or a page route. [`EndpointNamespaceTest` fails any `@Path` outside the
   sanctioned namespaces/page allowlist]
3. Public endpoint → add it to `OpenApiSurfaceIT.PUBLIC_API_CONTRACT`. [that IT fails on any contract mismatch, in either direction]
4. Public endpoint → full OpenAPI annotations (`@Tag`, `@Operation` summary+description, `@APIResponses`, `@SecurityRequirement`, `@Schema` on
   DTOs). [`OpenApiSurfaceIT.document_everyOperationIsFullyDocumented` fails a bare operation]
5. If both surfaces expose the use case, extend `SurfaceParityIT` with a same-input/same-DB-outcome case.
6. Breaking `/api/v1` changes are MAJOR-version events — flag for `RELEASE_NOTES.md` (hand-authored; never edit it yourself).

### Transaction handling (who owns the `@Transactional`)

**The default is: the resource method owns the transaction (`@Transactional` on the endpoint) and the `*Service` it calls assumes one is already
active.** Read-only endpoints (page renders, HTMX fragments, list/GET APIs) carry **no** `@Transactional` — Panache reads work without one, and holding
a connection for a whole render is wasteful. Keep transactions as short as the atomic work requires.

**Deliberate exception — services that hash own their own short transaction.** `AuthenticationService`, `RegistrationService` and
`PasswordChangeService` do the ~100 ms Argon2id work *outside* any transaction, then commit the actual write in a short `self`-invoked
`@Transactional` method (`recordLogin`/`createUser`/`applyChange`), re-reading the account by id inside it. So **their resource callers must NOT be
`@Transactional`** (a nested `REQUIRED` transaction would pull the hashing back inside it). This is the one place the "resources own the transaction"
rule is inverted; each such method's Javadoc says so.

**Rejected mutations must not commit a partial write.** Because a service reports failure by *returning* a sealed result (not throwing), the
surrounding `@Transactional` would otherwise commit — flushing any entity the service mutated before it hit the rejection (a later field failing
validation, a guard tripping after an earlier write). The class-level **`@RollbackOnErrorStatus`** binding (`web/ErrorStatusRollbackInterceptor`, one
priority step inside the Jakarta Transactions interceptor) marks the transaction rollback-only whenever a transactional endpoint answers `>= 400`, so a
4xx/5xx can never persist part of a mutation. **Put `@RollbackOnErrorStatus` on any resource class that has `@Transactional` write endpoints**; it is a
safe no-op on reads (guarded by `QuarkusTransaction.isActive()`). Prefer this to hand-written `setRollbackOnly()` calls. Where a service persists an
entity whose unique constraint could race a concurrent insert (`ActionService.create`, `RegistrationService.createUser`), it flushes and maps the
`ConstraintViolationException` to the duplicate result rather than letting it surface as a 500.

### Authentication & security

Session-based auth (one opaque token, one Postgres-backed store shared by the web UI and `/api/v1`), per-IP auth
throttling, OIDC sign-in policies, and the security-headers/CSP filter are documented in [`AUTH.md`](AUTH.md).
**Read it before touching `auth/`, the `web/` login/OIDC flows, session handling, or `SecurityHeadersFilter`/CSP.**
Load-bearing reminders: resolve the current user via `SecurityIdentity.getPrincipal().getName()` (the email) ->
`User.findByEmail(...)`, or the `userId` attribute -> `User.findByIdOptional` (see `CurrentUser`); there is **no
JWT and no encrypted-cookie key**; every mutation still obeys the single-business-logic rule above.

### HTMX partial responses

Full `@GET` returns a `TemplateInstance`; HTMX endpoints return `Response.ok(partial.data(...)).build()`; errors use `HX-Retarget`/`HX-Reswap`.
**Watch the Qute `{`-parsing gotcha** (a bare `{word` is read as an expression even inside JS/HTML comments). Templates, HTMX partials and that
gotcha in full: [`FRONTEND.md`](FRONTEND.md).

### Data records vs. logic (`*Extensions`)

Records hold data only; derived logic lives in a `<Type>Extensions` final class (private constructor) whose methods take the record as the first
parameter. Template-facing methods are annotated `@io.quarkus.qute.TemplateExtension` so Qute resolves `{x.foo}` against the record unchanged.

**This split is mandatory, not stylistic** — PITest refuses to hot-swap mutants into record classes (
`"class redefinition failed: attempted to change the Record attribute"`), silently leaving logic untested behind the 100% gate. Diagnose with
`-Dverbose=true`.

**When a record grows branching instance logic called by a template** (watch for `@SuppressWarnings("unused")`), move it to a `<Type>Extensions` class
and add a unit test. Exceptions: pure-data records, factory methods (`from`/`of`), and static validators/sanitisers.

> **Multi-column query projections MUST be a typed record via a JPQL `SELECT new <fqcn>(…)` constructor expression — NEVER a positional
> `Object[]` tuple** (no `(Object[]) …getSingleResult()` / `.getResultList()` then `row[0]`/`row[1]` casting). The `Object[]` form is untyped,
> re-orders silently, and needs manual casts; it was deliberately removed project-wide. Add a top-level record next to the query (see
> `MonthlyActionTotal`, `ActionPerformedDate`, `http.ChangeSignature`), pass its class to `createQuery(jpql, X.class)`, and let a nullable component
> (`@Nullable Instant`) carry a possibly-absent aggregate (`MAX(...)` over an empty set). Single-column scalar reads (a lone `COUNT`/id column) stay
> as-is — this rule is about **multi-column** rows only.

### Front-end build, assets, CSS & calendar

The Tailwind CSS build, colour tokens/component classes, the content-hashed served scripts
(`app.js`/`dashboard.js`/`actions.js`/...), the shared data-table (`.dt-*`) styling, static-asset caching, the settings preview thumbnails, brand
assets, the Font/typography setting, the hand-rolled dashboard-calendar engine + its feeds (`/api/v1/logs/events`, `/internal/logs/minimal-events`),
the user-configurable Stats-page tiles (`StatField`), and the templates/HTMX/Qute rules are all documented in [`FRONTEND.md`](FRONTEND.md).
**Read it before editing CSS, `frontend/`, any `/js/*.js`, a template, a data-table, the Stats picker or the calendar.** Load-bearing reminders:
**rebuild the CSS (`npm --prefix frontend run css`) after any class change in a template or in Java** (else it is purged); a new user-visible stat
needs both an `StatField` constant and a `StatTile` mapping in `SubjectStatsExtensions.tiles(...)` or it never appears. A stat's catalogue label
is only its DEFAULT caption - users may rename any stat (stored per key on `StatFieldPref.label`), so nothing may assume the rendered caption matches
`StatField.label()`.

### Pagination

All list views (actions, day-panel, stats) use in-memory pagination: fetch all, filter, slice. Page size is a per-user setting: the picker offers
`{5, 10, 25, 50, 100}` (default `5`), but any whole number in `[MIN_PAGE_SIZE, MAX_PAGE_SIZE]` is accepted, parsed and range-checked in ONE pass by
`UserSettings.parsePageSize()` - an out-of-range value is rejected, never coerced. `PaginatedDayActions` adds filler rows to keep every page the same
height.

### Notable invariants

- `ActionLog.MAX_DAILY_COUNT = 999` — `SMALLINT` column; increment, increment-by-10, and set are silently capped.
- **A day NOTE may be written for any date, including a future one** — `NoteService` deliberately does not apply the
  `LogGuards.isFuture` rule that blocks logging. An empty note is no row (saving blank content deletes it), and a note's
  CONTENT must never reach the application log. See [`NOTES.md`](NOTES.md).
- **Note content is ENCRYPTED AT REST, invisibly to the user.** Each account gets a random data key when it is created
  (`NoteKeys.assignTo`, from `RegistrationService.createUser` and `OidcUserProvisioner.provision`); every note is
  AES-256-GCM sealed under it with `user_id || note_date` as associated data; and the data key itself is stored only
  wrapped under `NOTE_ENCRYPTION_KEY`, which lives in **configuration, never the database**, in its own
  `user_notes_keys` table. **Nothing is asked of the user and no part of the UI mentions it** — there is no passphrase,
  no unlock step, no locked state. A stolen dump, backup or replica opens nothing; reading a note needs the database AND
  the environment file. It does **not** defend against an administrator with the running server, so this is encryption
  **at rest**, not end-to-end — **never describe it to a user as end-to-end or zero-knowledge.** Losing the key loses
  every note; `AppLifecycle` refuses to boot without a usable one, **and refuses to boot when a well-formed key does not
  open the data already stored** — otherwise a rotated key starts cleanly and every note silently vanishes from the UI
  while the rows sit untouched. **Rotation is config-driven** (`NOTE_ENCRYPTION_PREVIOUS_KEYS`): startup re-wraps every
  stored data key onto the current one, touching no note. See [`NOTES.md`](NOTES.md).
- **Statistics are computed per `StatSubject`, not per action.** A subject is an action or the user's day notes; the
  notes subject carries the fixed nil-UUID `StatSubject.NOTES_ID`, which is what lets every id-keyed path
  (`/internal/stats/chart/{actionId}`, its `compare` parameter, `GET /api/v1/stats/{actionId}/frequency`) stay
  `UUID`-typed. `StatsService.forAllSubjects` pins notes ahead of the actions BEFORE pagination, so "first" means page
  one. Every figure is computed by one `assemble(...)` from the same shape of input, so a new kind of subject needs no
  new statistics code. The notes subject's colour is the user's own `noteColour` preference (`StatSubject.notes(colour)`),
  resolved from the same `User` read that resolves "today".
- Actions are hard-deleted along with their logs when an action is deleted (no soft-delete/archive).
- **All date-boundary "now"/"today" goes through `AppClock`** (`@ApplicationScoped`). Business logic calls `clock.today()`/`clock.zone()`. Entity
  audit timestamps (`createdAt`/`updatedAt`/`lastLoginAt`) use `Instant.now()` directly (zone-independent, not date-boundary sensitive).
- `app.timezone` (default `UTC`) feeds `AppClock`; must match `TZ` in `docker-compose.yml`.
- `LogWebResource.isFuture()` blocks logging for future dates in the user's configured timezone.
- **A new action's colour is pre-randomised, never the neutral grey.** The `/actions` new-action picker is rendered
  with `ActionService.suggestColour(...)` already selected (`ActionsWebResource`), and `actions.js` re-draws it after
  every successful add — the colour just used is in use from then on, so a reset alone would hand the next action its
  twin. An **absent** colour on create takes that same suggestion in `ActionService.create`, so `POST /api/v1/actions`
  with no `colour` behaves like the form. `#64748b` (`ActionValidation.DEFAULT_COLOUR`, a neutral slate deliberately
  *not* the brand indigo `#6366f1` — a brand-coloured dot would vanish into the full calendar's brand-filled "today"
  cell) is no longer what anything is created in: it survives as the `Action.colour` column default, the edit form's
  `@DefaultValue` fallback, and a colour the suggester keeps its distance from. A malformed colour is **rejected** on
  both surfaces, never silently corrected.
- **Every user-chosen colour obeys one shared rule set in `colour/Colours`** — the `#rrggbb` format check
  (`isInvalidHex`, which `ActionValidation` delegates to), the HSL→hex conversion, and `readableOn(colour,
  background)`, which lightens a colour up the HSL lightness axis until it clears 3:1. A colour is stored and
  rendered **exactly as picked, in both themes** (there is no light/dark pair for any of them); the ONE derived
  shade in the app is the note marker on the calendar's brand-filled "today" cell, and it is derived precisely
  because it is a legibility floor rather than a preference. The colour suggester avoids every colour the user
  has in use — their actions' *and* their `noteColour` — so one endpoint (`/internal/actions/random-colour` +
  its API twin) serves both pickers. See [`NOTES.md`](NOTES.md).
- Dark-mode checkbox: hidden `<input value="false">` + real `<input value="true">`. Checked posts `["false","true"]`; unchecked posts `["false"]`.
  `updateSettings` checks for `"true"` in the list.
- `password.auth.enabled=false` disables register (404, except during first-run setup) and skips `PasswordIdentityProvider`. `AppLifecycle` enforces
  at least one auth mechanism at startup. Password MANAGEMENT stays available regardless: any account holding a password (the break-glass admin)
  can change it via Settings / `PUT /api/v1/users/me/password` — `PasswordChangeService` keys on the hash, not on `PASSWORD_AUTH_ENABLED`.
- Login uses query params: `?error` = failed login; `?registered=true` = success after registration.
- `SubjectStatsExtensions` exposes `sinceLabel()`, `monthTrend()`, `monthTrendClass()` etc. as Qute template extensions over `SubjectStats`.
- **UI text must use correct singular/plural** — never "1 days". `time/Durations.plural(count, unit)` is the ONE rule; `count(n, unit)` renders
  "1 day"/"5 days". `SubjectStatsExtensions` exposes it to templates via `totalDaysUnit()` etc., and words every streak/gap through `Durations.label`.
  Apply to any new pluralised count.
- **A run of days that is shown to a user as a duration is carried as a `time/DaySpan` (half-open `[start, endExclusive)`), never as a bare day count.**
  `time/Durations` is the only place one is measured (`days(span)`) or worded (`label(span)` — "412 days" condenses to "1 year, 1 month, 17 days";
  `exceedsOneMonth(span)` says whether it condensed). The month/year boundaries are **calendar**
  boundaries (a month counts only once the day-of-month is reached, so leap days are never lost), and that arithmetic depends on WHICH months the run
  covered — the same 31 days is "1 month" in one place in the calendar and "1 month, 3 days" in another. **A day count alone therefore cannot be rendered
  as a duration**: it could only be split against some arbitrary anchor, which makes a historical figure's label drift as "today" moves.
  `SubjectStats.currentStreak()/longestStreak()/longestGap()` are consequently spans, computed with their real dates in `StatsService`, as is the current
  gap (`SubjectStatsExtensions.currentGapSpan`). Both gap spans are framed as the **blank run** (the day after the last log, up to the next log — or
  tomorrow, for the still-open one), so the current gap measures identically to the longest gap it will eventually become. All four streak/gap tiles
  lead with the worded duration ("1 day", "1 month, 14 days" — never a bare number) and caption it with the run's own dates: "since {start}" while the
  run is still going (current streak/gap), "{start} – {end}" once it is closed, and nothing at all for an empty run.

### Update check (admin-only footer indicator)

An admin-only "update available" up-arrow in the footer (`partials/footer.html`), gated on `isAdmin && updateAvailable`, linking to the latest
release; all three signals ride the `{inject:appInfo}` bean (`updateAvailable`/`updateTooltip`/`updateUrl`), so no resource threads footer data.
The check runs **exactly once at startup and is never refreshed** (`UpdateCheckService.onStartup`): one best-effort `LatestReleaseClient` GitHub
lookup, result stored in an `AtomicReference`, so `status()` is a pure no-I/O read that may go stale over long uptime (accepted, to make no repeated
outbound calls). Any failure stores nothing → no indicator. Enabled by default; `APP_UPDATE_CHECK_ENABLED=false` skips it (set in smoke/perf/`test`
so no CI tier calls GitHub). All version/URL branching is the pure `UpdateCheck` (100% PIT); the HTTP call + startup trigger are thin glue.
**Deliberately no `/api/v1` twin** (surface policy: admin-console decoration, not a user action or data resource).

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

### Testing tiers & conventions

The integration-test base/helpers (`IntegrationTestBase`, `newUser()`/`newAction()`/`newLog()`/`runInTx()`), the
deterministic-time/UTC rules (`FIXED_TODAY`, `freezeDate`/`freezeInstant`), and the deployment-smoke
(`tests/smoke/`) and performance/load (`tests/perf/`) tiers are documented in [`TESTING.md`](TESTING.md). **Read
it before adding tests or touching a test tier.** Load-bearing reminder: the Maven build is unit + `*IT`
(+ linters) ONLY - the E2E, smoke and perf tiers are chained onto the wrapper's `java`/`perf` steps, never wired
into any `mvn` command; do not re-add them to the pom.
