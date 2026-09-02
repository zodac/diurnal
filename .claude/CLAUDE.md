# CLAUDE.md

> **Code style:** Project-specific expectations live in [`CODE_STYLE.md`](CODE_STYLE.md). Read it before writing or editing code.

> **UI patterns:** Template/CSS conventions (partial extraction, component classes, tokens, `id` rules) live in
[`UI_PATTERNS.md`](UI_PATTERNS.md). Read it before writing or editing templates or CSS.

> **Deep-reference docs (read the matching one before that kind of work):** authentication, sessions, OIDC & CSP
> live in [`AUTH.md`](AUTH.md); the front-end build/assets/CSS/calendar in [`FRONTEND.md`](FRONTEND.md); the test tiers &
> conventions in [`TESTING.md`](TESTING.md); the shared free-text validation pipeline in
> [`TEXT_INPUT.md`](TEXT_INPUT.md); languages, message bundles, locale-aware formatting, RTL and fonts in
> [`I18N.md`](I18N.md).

> **The per-user data export/import** (the ZIP of CSVs, the replace-everything import and its preview, the Settings
> "Data" card) is documented in [`TRANSFER.md`](TRANSFER.md) — the file format, the decisions taken *with the rejected
> alternatives*, and the untrusted-input rules. **Read it before touching `net.zodac.diurnal.transfer`,
> `NoteService.replaceAll`, or the Settings Data card.**

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

> **A log line names an account by its EMAIL, never by its `UUID`.** An id tells the operator reading the log nothing -
> it cannot be matched against a support request or an authentication log, and tracing one line to the next costs a
> database query per line. Where only an id is in hand, resolve the email beside the logging call (see `NoteKeys`),
> never pass `something.userId`/`user.id` into the format arguments. `LogsIdentifyUsersByEmailTest` fails any logging
> statement in `src/main/java` that mentions a user id.

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
.github/scripts/lint_and_tests.sh java:qodana     # ONE tier of a step: step:substep (see below)
# Valid steps: docker, java, javascript, markdown, perf, shellcheck, typescript
# Substeps (`docker` and `java` have them): docker:hadolint, docker:grype; java:mvn, java:e2e, java:smoke,
# java:qodana — a bare step runs all of its tiers. `java:e2e` alone reuses the fast-jar, so package it
# first (`java:mvn,java:e2e`).
# The `docker` step is the whole Docker gate: hadolint over the Dockerfiles (seconds) AND the production
# runtime image's build + grype CVE scan (minutes), run in parallel with each other. Auto-detection scopes
# it to the tier the change touched (a docker-compose edit → `docker:hadolint`, a dependency bump →
# `docker:grype`), so use `docker:hadolint` when iterating on a Dockerfile rather than paying for the scan.
# A `docker` step carrying the scan runs AFTER the java step rather than alongside it, so its image build
# is a cache hit off the smoke tier's identical one.
# Prerequisite for the java step: cd tests && npx playwright install

# The Qodana whole-program analysis is a TIER of the `java` gate above (in parallel with the Maven/E2E/
# smoke tiers), so `… java` and `-f` both run it. It is the only check that sees an unused PUBLIC/
# package-private declaration - PMD, ErrorProne and SpotBugs all stop at `private` - running the reviewed
# inspection set in code-quality-config/java/qodana/profiles/java.yaml (every switched-off rule carries its
# reason beside it). WARM it is not the longest tier: ~2m35s (index + module model cached) against ~4m for
# Maven, so the Maven tier - and the E2E run chained behind it - is what sets the java step's wall clock.
# COLD it is ~10m and dominates everything, and CI caches .qodana/cache for exactly that reason, but
# never .qodana/results (the SARIF, which must not be inherited). Running it in parallel with the Maven
# tier stays worth it on a busy machine (measured: 436s together against 581s one after the other), and
# capping the scan's CPU makes it worse, not better - the two just converge on equal finish times. Its dead-code check needs the entry
# points in code-quality-config/java/qodana/overrides/, which the wrapper copies into .qodana/idea-config and
# mounts over .idea/ inside the scan container - without
# them 238 framework-instantiated declarations report as unused, with them 0.
# BOTH INPUTS ARE IN THE SUBMODULE (`git submodule update --init` is a prerequisite of the scan, and the
# wrapper refuses to start the container without them); code-quality-config-overrides/qodana.yaml is the
# only Qodana config tracked here, and `.qodana/` is now purely the scan's gitignored working dir
# (cache/results/idea-config). THAT FILE IS NOT AT THE REPO ROOT, so the CLI does not find it by
# convention - the wrapper passes it with `--config`, and so must any hand-run scan. Its two kinds of
# relative path resolve DIFFERENTLY, and `--config --help` is wrong about the first: `imports:` is relative
# to the CONFIG FILE (hence the `../` in front of the submodule profile), while `exclude:` and the
# per-inspection `ignore:` globs are relative to the PROJECT. A bad import fails the run outright
# ("imports file not found"), so that one cannot go green by accident.
# A change under code-quality-config/java/ OR to that qodana.yaml auto-detects as the `java` step, so
# editing the profile, the overrides or the scan's own config re-triggers the tier that reads them.
# The grype ignore list sits beside it (code-quality-config-overrides/.grype.yaml, passed with `-c`), so
# the repo root holds no linter dotfiles at all.
# ORDER DECIDES inside the profile - an individual `- inspection:` entry placed ABOVE its own
# `- group: "category:..."` is silently re-enabled by it. It reports on JAVA SOURCE ONLY - the profile's
# first entry is
# `ignore: ["*", "**/*", "!**/*.java"]`, so every other file type stays with the gate that already covers
# it (shellcheck, markdown, typescript, hadolint). The profile MUST be reached by `imports:` in that
# qodana.yaml: `profile: path:` / `base: path:` / `--profile-path` are accepted and then silently
# ignored, falling back to the IDE Default profile (which omits UnusedDeclaration - the whole point of
# the step). `--config`, unlike those, IS honoured - which is what let the file leave the root at all. It is a SUBSTEP of `java`, not a step of its own - scope the gate down to it when iterating
# on its config:
.github/scripts/lint_and_tests.sh java:qodana

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
docker compose up -d --build
docker compose logs -f app
```

> **Always run the quality gate through `.github/scripts/lint_and_tests.sh`, never `mvn clean install -Dall`
> directly**, and **scope it to the step you touched**: `… java` after ANY Java/template/CSS/UI-spec/Dockerfile
> change, `… shellcheck` after a `*.sh` edit, `… markdown` after docs, etc. (comma-separate to combine; bare =
> auto-detect changed steps; `-v` streams output; `-f`/`--force` runs everything; `step:substep` narrows a step to
> one of its tiers, which today means `docker:hadolint`/`docker:grype` and
> `java:mvn`/`java:e2e`/`java:smoke`/`java:qodana`). The `java` step **is** the
> whole JVM gate — `mvn clean install -Dall` (unit + `*IT` + linters) then, only if green, the E2E tier,
> with the deployment-smoke and Qodana tiers running in parallel alongside them. **The Maven build is unit + `*IT` (+ linters) ONLY; E2E/smoke/perf are chained onto
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
  PITest. Compiles test sources but does not run tests. **Checkstyle runs TWICE**: the inherited `default` execution over the Java sources, and a
  second `pom` execution over `pom.xml` itself (the shared config's file-level rules — `LineLength` max 150, `NewlineAtEndOfFile`,
  `FileTabCharacter` — already cover `xml`, but the source-root scan never fed it any). It is the ONLY check in the whole gate that reads an XML
  file, so a long line in the POM used to be found by nothing but the release pipeline's SonarQube run; other XML (e.g. `docs/*.xml`) is still
  unlinted.
- `-Dtests` — surefire unit tests (`*Test`) only.
- `-Dall` — unit + `*IT` + full linters (NOT E2E/smoke — those are chained onto the wrapper's `java` step, outside Maven).

**All linters currently pass clean (Checkstyle/PMD/SpotBugs = 0, PITest strength = 100%); keep them that way.** Code must be NullAway-annotated (
JSpecify `@Nullable`), every public/package method and type carries Javadoc, locals/params are `final`, unit-test assertions carry messages.

### Package layout

Under `src/main/java/net/zodac/diurnal/`:

| Package        | Contents                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
|----------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `action`       | `Action` entity + `ActionsWebResource` (the `/actions` page) + `ActionsInternalResource` (`/internal/actions` HTMX fragments/mutations) + `ActionsApiResource` (`/api/v1/actions` public CRUD) + `ActionValidation` (shared rules) + `ActionColours` (the randomise-button colour palette/suggestion rules)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `log`          | `ActionLog` entity (+ `ActionLogId`, its composite key — the table has no surrogate id) + `DatedActionCount` (the ranged read's projection) + `LogWebResource` (`/internal/logs` day-panel fragments + increment/decrement) + `LogsApiResource` (`/api/v1/logs` public events feed + day read/write) + `CalendarResource` (`/internal/logs/minimal-events` dashboard feed) + `LogGuards`/`DateRanges` (shared rules)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `stats`        | `StatsService` + `SubjectStats` (data record, keyed on a `StatSubject` — an action OR the user's notes; `StatSubjectKind`/`StatSubjectExtensions`) + `SubjectStatsExtensions` (template extensions) + `StatField` (Stats-page tile catalogue) + `DisplayStat` (a shown stat + its caption) + `StatTile` (tile view-model) + `StatsSummary` (the dashboard summary card's shared parameter binding) + the frequency-graph family (`FrequencyPeriod`/`FrequencyKeys`/`FrequencyCharts` + the `FrequencyChart`/`FrequencySeries`/`FrequencySlot`/`FrequencyBar` records + their `*Extensions` + the sealed `FrequencyResult`) + `StatsWebResource` (the `/stats` page) + `StatsInternalResource` (`/internal/stats/list` + `/internal/stats/chart/{actionId}`(`/candidates`), plus the dashboard's `/internal/stats/summary/{date}` + `/internal/stats/summary-month/{yyyy-MM}`) + `StatsApiResource` (`GET /api/v1/stats`, `GET /api/v1/stats/{actionId}/frequency`) |
| `auth`         | **The credentials core only** — `AuthResource` (`/api/v1/auth` register/login/logout/revoke → session token), `AuthenticationService`+`LoginResult`, `RegistrationService`+`RegistrationResult`, `PasswordChangeService`+`PasswordChangeResult`, `Passwords`, `RoleAssigner`, the `LoginRequest`/`RegisterRequest`/`TokenResponse` DTOs and the `LoginAttemptLog`/`RegistrationAttemptLog` projections. Everything else lives in one of the three subpackages below — or left `auth` entirely: the Swagger-docs gate (`OpenApiDocsAccess`/`OpenApiDocsAuthFilter`/`OpenApiDocsPaths`) is now in `openapi` beside `PublicApiFilter`, and `ClientAddress` (read the client IP off a `RoutingContext`) is in `http`                                                                                                                                                                                                                                                   |
| `auth.session` | The session substrate: `Session` entity + `SessionStore`/`PostgresSessionStore` + `SessionTokens` + `SessionTokenExtractor` + `SessionTokenAuthenticationRequest` + `SessionAuthMechanism` + `SessionIdentityProvider` + `SessionSweeper` + `SessionActivityService` + `RecentActivity`/`UserLastSeen`/`UserIdentities`. **Depends on nothing else in `auth`** — it is a sink, which is what lets the rest of the app import it freely                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `auth.lockout` | Per-IP auth throttling and the admin lockout console: `AttemptThrottle` + `IpThrottle` + `IpLockout` + `IpLockoutService` + `IpLockoutStatus` + `IpUnlockResult` + `LockoutMessages` + `AdminIpLockoutsApiResource` (`/api/v1/admin/ip-lockouts`). Also a sink — the core's login/registration paths reach into it, never the reverse                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `auth.oidc`    | The whole OIDC sign-in flow: `OidcUserProvisioner` + `OidcDiscovery` + the pure policy core (`OidcLoginPolicy`/`OidcLinkPolicy` → `OidcLoginDecision`, over `OidcLoginFacts`/`OidcIdentityState`, with `OidcDenialReason`/`OidcDisplayName`) + `AccountLinkService`. Depends only on `auth` (`RoleAssigner`) and `auth.session`; nothing in `auth` depends on it. See [`OIDC.md`](OIDC.md)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `note`         | `Note` entity + `NoteQueries` + `NoteService`/`NoteResult` (the single owner of every note write and every search, and the encrypt/decrypt of content) + `NoteField` (the day-note `TextField` at this deployment's configured `NOTE_MAX_LENGTH`) + `NoteContent` (the per-note seal, bound to owner+date) + `UserNotesKey` entity + `NoteKeys` (mints an account's data key at creation, opens it per request) + `NoteSearch` (the pure match + snippet rules) + `NoteHit`/`NoteSnippetPart`/`NoteRow`/`PaginatedNotes` + `NotePages` (the notes page's slice/row presentation) + `NotesWebResource` (the `/notes` page) + `NotesInternalResource` (`/internal/notes` range feed + `/list` search fragment + save/clear) + `NotesApiResource` (`/api/v1/notes` public CRUD + search). One free-text note per user per day, writable for ANY date including future ones, encrypted at rest                                                                         |
| `user`         | `User` entity, `UserResource` (`/api/v1/users/me`), `UserSettings`, the per-section pagination trio `PageSection`/`PageSizePref`/`PageSizes`, the settings-picker enums `Theme`/`Font`/`CalendarView` (each `implements PreviewOption`), and `WeekStart` (the calendar's first day; `NULL` = follow the account's language)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `web`          | The app shell only: `DashboardWebResource` (`GET /`), `AppInfo` (footer/template metadata bean), `TextFieldCatalogue` (the `{inject:textFields}` bean), `ErrorPages` + the two exception mappers, `RequestLoggingFilter`, `HtmxResponses` and `AssetsConfig` (the `app.assets.*` served-filename manifest). **Every other page route lives with its domain** — `auth.AuthWebResource` (login/register/logout), `auth.oidc.OidcWebResource`, `user.SettingsWebResource`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `web.admin`    | The admin console, a leaf nothing else in `web` references: `AdminWebResource` (the `/admin/*` pages) + `AdminUsersInternalResource` (`/internal/admin/users` fragments) + `AdminIpLockoutsInternalResource` (`/internal/admin/ip-lockouts` fragments) + the `UserRow`/`UserRowExtensions`/`IpLockoutHistoryRow` view-models                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `page`         | `Pages` + `PageWindow` - the one place a requested page number is resolved against a total (`window(...)`, clamping into range) and an already-fetched list is sliced into that page (`slice(...)`). Every list view and its API twin goes through it; see the Pagination section below                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `http`         | The request-level plumbing shared by every surface, owned by no feature: `RollbackOnErrorStatus` + `ErrorStatusRollbackInterceptor` (the rollback-on-4xx binding, applied by resources in six packages), `NotUiFacing` (marks text that reaches an `/api/v1` body or a log but never a page, so it is hardcoded English and never a `msg:` entry - see [`I18N.md`](I18N.md); `NotUiFacingTest` fails if a web/internal surface calls one), `ClientAddress` (the client IP off a `RoutingContext`), `EntityTags`/`ChangeSignature` (conditional-GET support) and `HttpStatus`. **A cross-cutting helper belongs here, not in the feature package that happened to need it first**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `colour`       | `Colours` - the rules every user-chosen colour obeys: the `#rrggbb` format check, the HSL-to-hex conversion, and the lightening that makes a colour readable on a background (the calendar's brand-filled "today" cell). Shared by `action` and `user`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `persistence`  | The handwritten-query plumbing every entity shares: `QueryParameter` (a query's `:name` placeholder as a typed token) plus `JpqlQuery`/`SqlQuery`, the two wrappers that prepare a query and bind through those tokens. **Every named parameter in the app is bound this way, never by string** - see the rule below the projections one. Also the vendor seam: `LogStatements`/`NoteStatements`, the two interfaces holding every statement JPQL cannot express, implemented in `persistence.postgres` - see the Database vendor seam section                                                                                                                                                                                                                                                                                                                                                                                                          |
| `note.crypto`  | The encryption primitives behind the note seal, all pure statics with no persistence or request state: `Aes256Gcm` (AEAD seal/open, IV-prefixed), `Hkdf` (RFC 5869 over HMAC-SHA-256, for domain separation), `DataKeyEnvelope` (wrap/unwrap a user's data key under the application master key) and `MasterKey` (decode/validate the configured value). A subpackage of `note` because notes are the only thing encrypted; it imports nothing from its parent, so the direction stays one-way                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `transfer`     | The per-user data export/import: `Csv` (RFC 4180 read/write) + `TransferArchive` (ZIP pack/unpack with zip-bomb caps) + `TransferFiles` (the three members and their headers) + `ImportParser`/`ParseOutcome` (the pure parse+validate) + the `ImportPlan`/`ActionDraft`/`LogDraft`/`NoteDraft`/`ImportProblem`/`ImportSummary` records (+ `ImportSummaryExtensions`) + `ExportService` + `ImportService`/`ImportResult` (the single owner of the import) + `TransferApiResource` (`/api/v1/data/*`) + `TransferInternalResource` (`/internal/data/*`). See [`TRANSFER.md`](TRANSFER.md)                                                                                                                                                                                                                                                                                                                                                                           |
| `update`       | `UpdateCheckService` (admin-only footer "newer version available" check) + `UpdateCheck` (pure version/URL logic) + `UpdateStatus`/`UpdateAvailability` + `LatestReleaseClient`/`GitHubLatestReleaseClient` (the outbound GitHub-release lookup seam)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |

### API namespaces (the rule for every new endpoint)

- **`/api/v1/*` — the public REST API** (JSON in/out, Bearer session token — cookie also accepted, fully OpenAPI-annotated, appears in Swagger).
  The annotations in the code are the single source of truth — the spec is served live from `/q/openapi` and is deliberately NOT exported to a
  committed file (it would go stale). Nothing under `/api` may return HTML. **Every new public operation must be added to
  `OpenApiSurfaceIT.PUBLIC_API_CONTRACT`** — that IT pins the generated document to the exact endpoint set, so an addition (or an internal endpoint
  leaking into the docs) fails CI until the contract is consciously updated. Breaking changes to `/api/v1/*` are MAJOR-version events (see README).
- **`/internal/*` — web-UI plumbing** (HTMX fragments, fragment mutations, UI-cache JSON like `/internal/logs/month`). Never documented, no
  stability guarantees, anonymous requests get the browser `302 /login` challenge (vs `401` for `/api/*`).
- **Page routes stay top-level** (`/`, `/actions`, `/notes`, `/stats`, `/settings`, `/admin/*`, `/login`, `/register`, `/logout`), as do the OIDC routes.
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
4. Public endpoint → full OpenAPI annotations (`@Tag`, `@Operation` summary+description, `@APIResponse`, `@SecurityRequirement`, `@Schema` on
   DTOs). [`OpenApiSurfaceIT.document_everyOperationIsFullyDocumented` fails a bare operation]
5. If both surfaces expose the use case, extend `SurfaceParityIT` with a same-input/same-DB-outcome case.
6. Breaking `/api/v1` changes are MAJOR-version events — flag for `RELEASE_NOTES.md` (hand-authored; never edit it yourself).

### Transaction handling (who owns the `@Transactional`)

**The default is: the resource method owns the transaction (`@Transactional` on the endpoint) and the `*Service` it calls assumes one is already
active.** Read-only endpoints (page renders, HTMX fragments, list/GET APIs) carry **no** `@Transactional` — Panache reads work without one, and holding
a connection for a whole render is wasteful. Keep transactions as short as the atomic work requires.

**Deliberate exception — services that hash own their own short transaction.** `AuthenticationService`, `RegistrationService` and
`PasswordChangeService` do the ~100 ms Argon2id work *outside* any transaction, then commit the actual write request in a short `self`-invoked
`@Transactional` method (`recordLogin`/`createUser`/`applyChange`), re-reading the account by id inside it. So **their resource callers must NOT be
`@Transactional`** (a nested `REQUIRED` transaction would pull the hashing back inside it). This is the one place the "resources own the transaction"
rule is inverted; each such method's Javadoc says so.

**Rejected mutations must not commit a partial write.** Because a service reports failure by *returning* a sealed result (not throwing), the
surrounding `@Transactional` would otherwise commit — flushing any entity the service mutated before it hit the rejection (a later field failing
validation, a guard tripping after an earlier write). The class-level **`@RollbackOnErrorStatus`** binding (`http/ErrorStatusRollbackInterceptor`, one
priority step inside the Jakarta Transactions interceptor) marks the transaction rollback-only whenever a transactional endpoint answers `>= 400`, so a
4xx/5xx can never persist part of a mutation. **Put `@RollbackOnErrorStatus` on any resource class that has `@Transactional` write endpoints**; it is a
safe no-op on reads (guarded by `QuarkusTransaction.isActive()`). Prefer this to handwritten `setRollbackOnly()` calls. Where a service persists an
entity whose unique constraint could race a concurrent insert (`ActionService.create`, `RegistrationService.createUser`), it flushes and maps the
`ConstraintViolationException` to the duplicate result rather than letting it surface as a 500.

### Authentication & security

Session-based auth (one opaque token, one Postgres-backed store shared by the web UI and `/api/v1`), per-IP auth
throttling, OIDC sign-in policies, and the security-headers/CSP filter are documented in [`AUTH.md`](AUTH.md).
**Read it before touching `auth/` (including `auth.security`, which holds `SecurityHeadersFilter`/`CspPolicy`/`CsrfProtectionFilter`), the login/OIDC flows, or session handling.**
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

> **A query's named parameters MUST be bound through a typed `persistence.QueryParameter` token — NEVER a bare string** (no
> `.setParameter("userId", …)`). The name inside the quotes compiles whatever is typed, so a slip surfaces only when that query first runs, which for
> the upserts and row locks means it surfaces in a mutation path rather than in a test. Declare the token beside the query text it belongs to
> (`ActionLogQueries`/`NoteQueries`, or a `private static final` on the class holding an inline query), **typed with the value it takes**
> (`QueryParameter<UUID>`, `QueryParameter<Collection<UUID>>` — a type argument rather than a `Class` token, since a `Class` cannot express a
> parameterised type), and bind it through `JpqlQuery`/`SqlQuery`: a misspelled name — or a value of the wrong type for it — is then a compile error.
> `bind(QueryParameter<T>, T)` is what enforces the second half, so a new token must carry its type argument or it degrades to `Object` and enforces
> nothing. `QueryBindingsAreTypedTest` fails any source file that goes back
> to the string form, and the `*QueriesTest` classes pin the other half (the `:name` text inside each query, which no Java type can reach) against the
> tokens declared for it.

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

All list views (actions, day-panel, stats) use in-memory pagination: fetch all, filter, slice. **The arithmetic behind that is `page/Pages`, never
written out at the list**: `Pages.window(totalCount, pageNum, pageSize)` resolves the requested page (an empty list still has a page 1; a page past
the end is CLAMPED to the last real one) and `Pages.slice(all, window)` takes its rows. The counted-query lists (admin users, IP-lockout history) ask
for the same window and feed `currentPage() - 1` to Panache's `Page.of`. A public API resource asks for the window too, but uses only
`totalPages()` - it REJECTS an out-of-range page instead of taking the clamp (surface policy, marked as such at each API resource).

Page size is a per-user setting: the picker offers
`{5, 10, 25, 50}` (default `5`; `MAX_PAGE_SIZE` is deliberately not a preset - five pills plus the per-section rows' "Default" pill do not fit one
line of a phone-width card, and it stays reachable through the stepper), but any whole number in `[MIN_PAGE_SIZE, MAX_PAGE_SIZE]` is accepted, parsed
and range-checked in ONE pass by
`UserSettings.parsePageSize()` - an out-of-range value is rejected, never coerced. `PaginatedDayActions` adds filler rows to keep every page the same
height.

**A list asks `PageSizes.forSection(user, PageSection.X)` for its size - never `user.pageSize` directly.** `users.page_size` is the GENERAL
preference; on top of it a user may give any `PageSection` (`dashboard`, `actions`, `notes`, `stats`, `users`) its own value, stored as a jsonb array
of `PageSizePref` on `users.page_sizes` (the `stats_fields` pattern). **A section with no entry - and a `NULL` column - means "follow the general
value", so there is no sentinel to interpret and no backfill was needed**; the resolution lives in that one method, and the Settings rows come from
`PageSizes.rows(user)` (admin-only sections only for an admin). Both surfaces submit the WHOLE set, and it REPLACES what is stored (`ProfileService
.updatePageSizes` → `PageSizes.parse`): a blank/absent section is the reset, an unknown section key is dropped (as `StatField.encode` drops one), and
an out-of-range value is rejected with the same message the general page size uses. A new paginated view needs a `PageSection` constant and that one
call - the Settings row, the API field and the storage follow. `NotesApiResource`'s range feed keeps its FIXED 31/page (documented there), and the
admin IP-lockout history follows the general value (no section of its own).

### Notable invariants

- `ActionLog.MAX_DAILY_COUNT = 999` — `SMALLINT` column; increment, increment-by-10, and set are silently capped.
- **A day NOTE may be written for any date, including a future one** — `NoteService` deliberately does not apply the
  `LogGuards.isFuture` rule that blocks logging. An empty note is no row (saving blank content deletes it), and a note's
  CONTENT must never reach the application log. See [`NOTES.md`](NOTES.md).
- **The dashboard calendar's first column is a user preference defaulted from the LANGUAGE** (`user/WeekStart`,
  Settings > **Preferences** > "Week starts on"). `users.week_start` is `NULL` for "follow my language" - the same
  no-sentinel shape `timezone` uses for the server default - and `WeekStart.resolve(...)` is the one place that
  becomes a real day (CLDR's `WeekFields`, so Monday for `en-GB`/`es-ES`, Sunday for `en-US`/`ar-SA`/`ja-JP`). All
  seven days are offered, not just the three CLDR returns. **Resolve it ONCE per render**: the header words
  (`DayLabels.weekdayAbbreviations(locale, firstDay)`) and the grid's cell offset (`data-week-start`, a browser
  `Date#getDay()` index read by `dashboard.js`) must come from the same resolution or they drift a column apart.
  See [`FRONTEND.md`](FRONTEND.md) and [`I18N.md`](I18N.md).
- **The note box's character counter is a user preference** (`User.showNoteCounter`, default on, Settings > **Notes**
  card alongside the note colour). Display-only: it never changes the bound, and `note.js` shows the counter anyway
  while a note is OVER the bound (`SHOW_COUNT || noteIsOverLimit()`) because it is the only thing explaining an inert
  Save button - so the `<span id="note-count">` is always rendered and the preference gates its `hidden` flag, never
  its markup. See [`NOTES.md`](NOTES.md).
- **A note's length bound is the ONE per-deployment entry in the `TextFields` catalogue** (`NOTE_MAX_LENGTH`, default
  10,000, range `[1, 100_000]` enforced at startup by `AppLifecycle`). Read it through **`note/NoteField`**, never
  `TextFields.NOTE` — that constant is only the DEFAULT instance, kept for `all()` and for tests. It is configurable
  precisely because it is the one bound with no column behind it (`notes.content` was dropped in `V28`; a sealed
  `bytea` has no width), so it needs no migration; the ceiling is set by the bulk-content paths (the dashboard's
  three-month warm-up, the API's 31/page, a search opening the whole journal), not by storage. **Lowering it keeps
  notes already stored above it** — the bound applies on write only, so they stay readable/searchable/exportable and
  the note box shows them in full, but they cannot be re-saved unedited and an export holding one cannot be
  re-imported (`ImportParser` applies the bound to every row and an import is all-or-nothing). See [`NOTES.md`](NOTES.md).
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
- **Notes are searched by OPENING them, never by a database predicate.** The content exists only as ciphertext, so
  `NoteService.search` resolves the owner's data key once, opens the notes the caller selected and keeps the ones whose
  text contains the term (`NoteSearch.matches`, a plain case-insensitive substring — the same rule the actions and
  day-panel filters use). A per-word blind index was rejected: deterministic tokens over prose are the textbook
  frequency-analysis target, which would undo what encrypting the column bought. **The search TERM is as private as the
  note it finds and must never be logged** — `SecretsStayOutOfLogsTest` fails on `query`/`searchTerm`/`term`/`snippet`
  in a `note`/`crypto` logging statement, alongside the content and key identifiers. The `/notes` page and
  `GET /api/v1/notes?q=` share that one rule but choose their own selection and ordering (whole history newest-first vs
  a date range earliest-first); snippets are a `List<NoteSnippetPart>`, never marked-up HTML, so Qute still escapes
  every character. **Matching stays EXACT; a term that matched nothing is answered with a "did you mean"**
  (`NoteSearch.suggest`) — the closest word the journal itself holds, offered on both surfaces and searched for
  exactly. Fuzzy matching was measured and rejected on semantics, not cost: word-level fuzz loses the substring rule
  (`run` stops finding `running`) and character-level fuzz matches every note for a short term. A suggestion is note
  content, so it is **never logged** either. See [`NOTES.md`](NOTES.md).
- **Statistics are computed per `StatSubject`, not per action.** A subject is an action or the user's day notes; the
  notes subject carries the fixed nil-UUID `StatSubject.NOTES_ID`, which is what lets every id-keyed path
  (`/internal/stats/chart/{actionId}`, its `compare` parameter, `GET /api/v1/stats/{actionId}/frequency`) stay
  `UUID`-typed. `StatsService.forAllSubjects` pins notes ahead of the actions BEFORE pagination, so "first" means page
  one. Every figure is computed by one `assemble(...)` from the same shape of input, so a new kind of subject needs no
  new statistics code. The notes subject's colour is the user's own `noteColour` preference (`StatSubject.notes(colour)`),
  resolved from the same `User` read that resolves "today".
- Actions are hard-deleted along with their logs when an action is deleted (no soft-delete/archive).
- **A data import REPLACES everything and is all-or-nothing.** `ImportService` removes every action, day count and
  note the account holds and writes the archive's contents in their place; if ANY row is refused, nothing at all is
  written (a partial commit would delete the data the refused rows were the replacement for). The preview is the
  same code path with the write left off, and is **stateless** — the browser re-sends the file to confirm, so no
  journal is ever staged server-side. Every row obeys the rules that already exist (`TextFields.ACTION_NAME`/`NOTE`,
  `Colours.isInvalidHex`, `ActionLog.MAX_DAILY_COUNT`, `LogGuards.isFuture`) and is **rejected, never coerced** — a
  count of 1500 is refused rather than clamped, because a file of ten thousand rows cannot afford a silent
  correction nobody is watching. **The exported archive holds note content in the CLEAR** (encryption is at rest;
  an export necessarily opens them), which is why `transfer` joins `note`/`crypto` (and `persistence`, which now holds
  the note upserts) in `SecretsStayOutOfLogsTest.GUARDED_PACKAGES` and why no rejection message may quote note content. Notes are written
  back only through `NoteService.replaceAll`, the one thing that can seal them. See [`TRANSFER.md`](TRANSFER.md).
- **All date-boundary "now"/"today" goes through `AppClock`** (`@ApplicationScoped`). Business logic calls `clock.today(clock.zoneFor(user.timezone))`
  / `clock.now()` - there is deliberately NO zero-argument `today()`, since every user-visible date boundary belongs to that user's timezone. Entity
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
- `SubjectStatsExtensions` exposes `lastLabel()`, `latestLabel()`, `monthTrend()`, `monthTrendClass()` etc. as Qute template extensions over
  `SubjectStats`.
- **Text worded in Java is either UI text or `@NotUiFacing`, never both.** A rejection is worded twice: once for the `/api/v1` body (hardcoded
  English, marked `http/NotUiFacing`, plural concatenated) and once as a whole translated sentence in `partials/text-failure-message.html` for the
  page. **A `*WebResource`/`*InternalResource` must render the partial, never call the Java wording** — `NotUiFacingTest` fails if it does, if an
  annotated member is also a `@TemplateExtension`, or if the marker lands on a `private` member. See [`I18N.md`](I18N.md).
- **UI text must use correct singular/plural** — never "1 days". A Java call can never be locale-aware (see `web/AppMessages`' class Javadoc), so
  pluralisation is resolved entirely inside a `@Message` value's own `{#if count == 1}...{#else}...{/if}` — e.g. `AppMessages#duration(long, long,
  long)` for a calendar duration, `#importActionsCount(int)`/`#importLogsCount(int)`/`#importNotesCount(int)` for the data-import preview's counts.
  `time/Durations` itself only MEASURES a span (`days(span)`, `breakdown(span)` → `time/DurationParts`); it holds no English words and composes no
  text. Apply the same "raw count in, `{#if}`-branching `@Message` out" shape to any new pluralised count.
- **A run of days that is shown to a user as a duration is carried as a `time/DaySpan` (half-open `[start, endExclusive)`), never as a bare day count.**
  `time/Durations.breakdown(span)` is the only place one is measured into its years/months/days components (`DurationParts`) — "412 days" becomes
  `(1, 1, 17)`, worded as "1 year, 1 month, 17 days" only inside `AppMessages#duration`, never in Java. The month/year boundaries are **calendar**
  boundaries (a month counts only once the day-of-month is reached, so leap days are never lost), and that arithmetic depends on WHICH months the run
  covered — the same 31 days is "1 month" in one place in the calendar and "1 month, 3 days" in another. **A day count alone therefore cannot be rendered
  as a duration**: it could only be split against some arbitrary anchor, which makes a historical figure's label drift as "today" moves.
  `SubjectStats.currentStreak()/longestStreak()/longestGap()` are consequently spans, computed with their real dates in `StatsService`, as is the current
  gap (`SubjectStatsExtensions.currentGapSpan`). Both gap spans are framed as the **blank run** (the day after the last log, up to the next log — or
  tomorrow, for the still-open one), so the current gap measures identically to the longest gap it will eventually become. All four streak/gap tiles
  lead with the worded duration ("1 day", "1 month, 14 days" — never a bare number) and caption it with the run's own dates: "since {start}" while the
  run is still going (current streak/gap), "{start} – {end}" once it is closed, and nothing at all for an empty run. `StatTile` carries the raw
  `durationYears`/`durationMonths`/`durationDays` (dual-purpose per `key` — see its own Javadoc), never a pre-worded string.

### Update check (admin-only footer indicator)

An admin-only "update available" up-arrow in the footer (`partials/footer.html`), gated on `isAdmin && updateAvailable`, linking to the latest
release; all three signals ride the `{inject:appInfo}` bean (`updateAvailable`/`updateTooltip`/`updateUrl`), so no resource threads footer data.
The check runs **exactly once, triggered by startup, and is never refreshed** (`UpdateCheckService.onStartup`): one best-effort
`LatestReleaseClient` GitHub lookup, result stored in an `AtomicReference`, so `status()` is a pure no-I/O read that may go stale over long uptime
(accepted, to make no repeated outbound calls). Any failure stores nothing → no indicator. Enabled by default; `APP_UPDATE_CHECK_ENABLED=false`
skips it (set in smoke/perf/`test` so no CI tier calls GitHub).

> **The lookup is triggered BY startup but must never run ON the startup thread.** It is an outbound HTTPS call — DNS, a TLS handshake on a cold JVM
> and a round trip, bounded only by `app.update-check.timeout` (default 3s) — and running it inline put all of that between the app being built and
> being ready to serve, measured at **~0.7s of every boot** for an admin-only footer arrow. `onStartup` hands it to a **virtual thread** (a daemon, so
> a lookup still in flight cannot hold up a shutdown) and returns; `status()` reports `UNKNOWN` until it lands, which is exactly what it reports when
> the lookup fails, so nothing downstream has a new state to handle. Note the `System cold start` figure `AppLifecycle` logs would **not** show a
> regression here — that line is printed by a separate `StartupEvent` observer with no `@Priority` ordering between them, so it can be emitted before
> this lookup finishes. Measure a boot instead: container start → first `200 /api/v1/status`, which is what the perf tier already times.
>
> **There is deliberately no unit test pinning this, and adding one will fail the build.** `UpdateCheckService` is thin glue: after the early returns,
> its only remaining branch picks *which line to log*, so any unit test reaching `checkForUpdate` makes that branch's mutant covered-but-unkillable
> and PITest strength drops to 99% against the 100% threshold (there is no log-capture harness in this project, and adding one to kill a log-only
> mutant is not worth it). A dispatch guard was written, confirmed to catch a re-inlined call, and then removed for exactly this reason — do not
> re-add it. The feature's behaviour stays covered by `UpdateCheckIndicatorIT`, which PITest excludes.

All version/URL branching is the pure `UpdateCheck` (100% PIT); the HTTP call + startup trigger are thin glue.
**Deliberately no `/api/v1` twin** (surface policy: admin-console decoration, not a user action or data resource).

### Database vendor seam

**Every SQL statement in the app that JPQL cannot express lives behind `persistence.LogStatements` or
`persistence.NoteStatements`, implemented by `persistence.postgres.PostgresLogStatements`/`PostgresNoteStatements`.**
Six statements in total, and that is the floor: the two `action_logs` upserts, its bulk `unnest` write, the
`CROSS JOIN LATERAL` earliest-logged probe, plus the two `notes` upserts. Three idioms — `ON CONFLICT`, `unnest` over
parallel arrays, and one `LATERAL` written to force a plan — over the two tables that are written hot. Supporting a
second database means adding an implementation and a `db/migration/<db-kind>/` directory — not editing an entity.

- **The implementation is chosen by `@IfBuildProperty(name = "quarkus.datasource.db-kind", ...)`**, so the statements
  and the datasource can never disagree. `@LookupIfProperty` was rejected: it makes a bean reachable *only* by
  programmatic `Instance<T>` lookup, which contradicts the constructor-injection rule in
  [`CODE_STYLE.md`](CODE_STYLE.md).
- **Panache entity statics take the statements as their first parameter** (`ActionLog.setCount(statements, …)`,
  `Note.upsert(statements, …)`) rather than reaching for the bean. A static method cannot be injected into, and an
  `Arc.container()` service locator inside an entity would defeat the "build it with `new` + stubs, no CDI container"
  property the same rule exists to protect. The four callers (`LogService`, `StatsService`, `NoteService`,
  `ImportService`) inject the interface through their existing `@Inject` constructors.
- **ONLY genuinely vendor-specific statements live here, and "it sits next to one that is" is not a reason.** Every
  `*_JPQL` constant stays in `ActionLogQueries`/`NoteQueries` beside its entity: Hibernate already renders those for the
  configured dialect, so duplicating them per vendor would be a portability *cost*. Four statements were removed from
  the interface on exactly that test — `selectCount` (now `ActionLogQueries.ENTRY_COUNT_JPQL`) and the decrement's
  `selectForUpdate`/`deleteEntry`/`decrementUpdate` arms, which were plain ANSI carried along by the locking read
  beside them. **Before adding a method here, check the ORM cannot say it**: the row lock in particular is
  `LockModeType.PESSIMISTIC_WRITE` on a `findById`, and Hibernate knows each dialect's locking clause (`FOR UPDATE` on
  PostgreSQL, `WITH (UPDLOCK, ROWLOCK)` on SQL Server), so a statement for it would have been a vendor spelling the ORM
  already owns.
- **The typed `QueryParameter` tokens stay in `ActionLogQueries`/`NoteQueries` and are shared by every
  implementation** — a placeholder name is part of the contract, not a vendor's choice. Each interface method's Javadoc
  records the exact set its statement must declare, and `ActionLogQueriesTest`/`NoteQueriesTest` pin the shipped
  implementation to it (they instantiate the `Postgres*` class directly, no container needed). A second implementation
  is pinned by adding its own cases there.
- **`auth.session.PostgresSessionStore` is NOT part of this seam and was deliberately left alone.** Despite the name it
  contains no PostgreSQL whatsoever — it is plain Panache/JPA and would run unchanged on any database Hibernate
  supports. Its name is aspirational for the *Redis* swap `SessionStore` exists for. Moving it into
  `persistence.postgres` would file the one genuinely portable class under "rewrite this per vendor".
- **`ActionLog.decrementCount` is the one write on `action_logs` that takes NO `statements` parameter**, because it is
  entirely ORM: a `PESSIMISTIC_WRITE` `findById` through `ActionLogId.of(...)`, then the new count assigned to the
  loaded entity (dirty checking + `@PreUpdate` stamp `updated_at`) or `entity.delete()`. That also keeps the
  persistence context in step with the row, where the native form left it stale. **The caveat is that a pessimistic
  load of an ALREADY-MANAGED instance takes the lock without re-reading the row**, so a caller that hydrated an
  `ActionLog` for the same key earlier in the transaction would decrement from a stale count; nothing does today
  (`LogService.adjust`'s guards load `Action` only), and the method's Javadoc says so. `DecrementLockIT` pins the
  mixed-flush orderings against a real database.
- **What a second vendor still has to deal with beyond these six statements:** the migrations, which are now
  essentially the whole of the real work. The two jsonb columns on `User` are no longer on this list — their
  `columnDefinition = "jsonb"` was DDL-only dead weight (`quarkus.hibernate-orm.schema-management.strategy=none`, so
  Hibernate generates and validates nothing; Flyway owns the schema) and has been removed, leaving
  `@JdbcTypeCode(SqlTypes.JSON)` to map per dialect. **Do not put a vendor type name back in a `columnDefinition`** —
  it buys nothing here and files an entity under "rewrite this per vendor".

### Database migrations

Flyway scripts in `src/main/resources/db/migration/postgresql/`, sequential (`V1__`, `V2__`, …). The directory is keyed by
database vendor: `quarkus.flyway.locations` is `classpath:db/migration/${quarkus.datasource.db-kind}`, so the datasource
and the migrations can never disagree and a second vendor adds a sibling directory rather than branching inside these.
Flyway records a script by its name relative to the location root and matches applied migrations on version + checksum,
**not** on path — verified by pointing the app at a database whose history was written under the old flat
`db/migration/` path: all 42 migrations validated and the app booted clean, so the move is transparent to existing
deployments.

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
deterministic-time/UTC rules (`FIXED_TODAY`, `freezeInstant`), and the deployment-smoke
(`tests/smoke/`) and performance/load (`tests/perf/`) tiers are documented in [`TESTING.md`](TESTING.md). **Read
it before adding tests or touching a test tier.** Load-bearing reminder: the Maven build is unit + `*IT`
(+ linters) ONLY - the E2E, smoke and perf tiers are chained onto the wrapper's `java`/`perf` steps, never wired
into any `mvn` command; do not re-add them to the pom.

### Deferred work

Changes that were measured, judged not worth doing *yet*, and should be revisited against a trigger rather than
re-litigated from scratch. Each records what was measured, so the next person starts from evidence.

**Class data sharing (AppCDS) was built, measured end-to-end and REMOVED. Do not rebuild it without reading
this.** It is not that CDS does nothing - it is that what it does never reaches the user:

| | in-app `System cold start` | container create -> first `200 /api/v1/status` |
|---|---|---|
| CDS off | 2.48s | **3.39s** |
| CDS on | 1.61s | **3.34s** |

- **Measure the right thing.** Interleaved, 3 reps each, same container, page cache held warm. The JVM-side
  saving is real, large and almost perfectly repeatable (-0.87s, -35%, sigma ~0.003s). Time to *serving*
  moves 0.05s - about 1.5%. **The `System cold start` line is not a proxy for readiness**, and measuring
  with it is what made this look like a 35% win for three rounds of work.
- **The headline that sold it was a measurement error.** An earlier "7.4s -> 3.4s" came from comparing a cold
  container (first read of ~95MB of jars + JRE off the overlay, *and* building the archive) against warm
  ones. Hold the page cache constant and an archive-less boot is 3.39s. That was page cache, not CDS.
- **Against 0.05s stood**: ~117 lines of entrypoint shell in a distroless container (which produced two real
  bugs, `rm: not found` and a stale-archive-never-retrained bug, *both* surfacing only on upgrade, i.e. on a
  user's machine and not in any tier); a named volume across three compose files plus ~100MB of host disk;
  and an upgrade path no test tier covers. Baking the archive into the image instead costs +26MB on every
  pull, +23MB per release (it changes every build, defeating the four-layer split), and ~1 min on every
  image build.
- **The budgets say there was nothing to win.** The image `HEALTHCHECK` allows a 30s start period and
  `PERF_BOOT_BUDGET_S` is 20s, against a ~3.4s boot - roughly 6x headroom, on an app a self-hoster restarts
  a handful of times a year.
- **Trigger to revisit**: a deployment shape where containers start *often* and JVM startup is therefore the
  user-visible number - Kubernetes with scale-to-zero, or ephemeral per-request containers. Not "boot feels
  slow" on a long-lived compose stack. If it is revisited, measure container-create-to-serving with a warm
  page cache, never `System cold start`.

**What DID move readiness, and was kept**: the admin update check no longer runs on the startup thread (see
the Update check section). Verified end-to-end - the app answers its first `200` at `14:38:14.035` and the
GitHub lookup completes at `14:38:14.334`, 0.3s *after* readiness, where it previously blocked it.

**Do NOT re-derive these conclusions** — they were measured on the real image at 30 actions x 3 years (32,850
`action_logs` rows):

- **In-memory pagination (`Pages.slice`) is correct as it stands and must not be "fixed".** The lists using it are
  bounded by the action count (~15-30 rows; the actions list measured 13.3 ms), and the notes page already lets the
  database page an unfiltered listing. Converting them to `LIMIT`/`OFFSET` would need a `COUNT` plus a page query —
  two round trips where there is currently one, at ~4 ms each — and so would be measurably SLOWER at these sizes.
- **The Stats page is the path that actually degrades with time**, not either of the above: 89.8 ms, because
  streaks, gaps and days-with-multiples are defined over all history, so `ALL_DAILY_TOTALS_JPQL` rolls up every row
  on each view. `V37`'s `INCLUDE (count)` already made that query index-only, so the remaining cost is transferring
  and aggregating the rows in Java. It grows roughly 30 ms per year of history. The only real lever left is caching
  computed stats per user with invalidation on write; that is a large change for 90 ms, so the trigger is a user
  reporting the page feels slow, not a number in a profile.
  **Most of that figure has since been removed** and the remainder re-measured at a deliberately worse size
  (4.78M rows, 1,000 accounts, a 50-action x 10-year account = 182,600 rows): `V41` turned the query's `ORDER BY`
  from a disk-spilling sort into an Incremental Sort (~105 ms -> ~59 ms warm), and `LOGGED_ACTION_IDS_JPQL` went from
  scanning the whole history to probing per action (23.2 ms -> 0.25 ms).
  **`MONTHLY_TOTALS_JPQL` then left the Stats page altogether**, which is worth recording as a caution: it looked like
  an irreducible ~84 ms aggregate-over-all-history, and the earlier note here said so. It was not. A month's total is
  the sum of its days' totals, and `assembleAll` was ALREADY reading the daily rollup over the same rows - so the
  monthly query was a second whole-history aggregate producing a coarser view of data the caller had in hand.
  `StatsService.assemble` now derives the per-month and per-year figures in Java and the query is gone from that path
  (verified identical against the database: 6,050 rows each way, zero differing in either direction). **The lesson is
  that "no index helps" is not the same as "this cost is inherent"** - the fix was to stop issuing the query.
  What remains is the single daily rollup (~59 ms), which IS inherent: streaks, gaps and days-with-multiples are
  defined over all history. Caching stays the only lever for that, on the same trigger as before.

**From the schema review that produced `V38`/`V39`** — measured with `EXPLAIN (ANALYZE, BUFFERS)` on PostgreSQL 18.6
at 323,154 `action_logs` rows (one 30-action, 3-year account among 199 lighter ones) and 12,182 notes:

- **A GIN index for the notes search is not possible, and would target the wrong 6% if it were.** `content_encrypted`
  is ciphertext, so a GIN needs either plaintext or deterministic per-word tokens — the frequency-analysis exposure
  `NOTES.md` already records as rejected. And the database is not the cost: a whole-journal search over 1,096 notes
  measured **1.6 ms** to read the sealed rows, **3.5 ms** to open them all (AES-256-GCM), **2.0 ms** for the
  substring match and **~20 ms** for `NoteSearch.suggest`. A search that finds something costs ~7 ms; one that finds
  nothing costs ~27 ms, nearly all of it the "did you mean". If notes search is ever reported as slow, `suggest` is
  the thing to look at — not the query, and not an index.
- **A GIN index for action filtering has nothing to gain either.** That filtering is done in Java over ~30 rows, and
  `actions_user_name_unique (user_id, name)` already answers every access index-only.
- **`INCLUDE (updated_at)` on the change-signature (ETag) indexes is not worth it**, though those queries run on
  every conditional GET: `ActionLog.rangeVersion` measured 0.33 ms, `Note.version` 0.43 ms, `Note.rangeVersion`
  0.06 ms, `Action.userVersion` 0.05 ms. All already sub-millisecond.
- **`INCLUDE (action_id, count)` on `idx_action_logs_user_date`** would make the dashboard's three-month warm-up
  index-only, but measured 0.77 ms -> 0.55 ms for **+11 MB** of index. Rejected on that ratio.
- **The query layer has no N+1.** `StatsService` (both the day/month summaries and the frequency chart), the admin
  user list via `SessionActivityService.recentActivityByUser` and both calendar feeds all batch already. The app has
  essentially no JPA relations, which is what keeps it that way.
- **The one N+1 that did exist has been fixed**: `Session.user` is the only `@ManyToOne` in the app, so it defaults to
  `FetchType.EAGER`, and an eager to-one on an HQL root is resolved by a SECOND statement rather than by a join — so
  `Session.findByTokenHash` cost two round trips on every authenticated request. Confirmed by running `SessionStoreIT`
  with `org.hibernate.SQL` at `DEBUG` (a `sessions` select followed by a `users where id=?` select), fixed with a
  `JOIN FETCH s.user`, and re-verified the same way: one statement, zero standalone user lookups. **That DEBUG-log
  run is the way to check this class of bug** — the relation count is low enough that nothing else is at risk today,
  but a second `@ManyToOne` added anywhere would have the same default.
  **That fix was only half the job, and the same DEBUG-log run found the other half**: the row the `JOIN FETCH`
  loaded was then thrown away, because `PostgresSessionStore.resolve` was `@Transactional` and so read it into a
  transaction-scoped persistence context that closes at commit — leaving the resource's first `CurrentUser.get()` to
  read the identical row again. EVERY authenticated request paid it: `GET /api/v1/users/me` and `GET /settings` were
  2 statements of which both were auth, `GET /` 4 of which 2, and one dashboard view is 4-8 such requests. Dropping
  that `@Transactional` moves the read into the REQUEST-scoped persistence context, which outlives authentication, so
  `CurrentUser` answers from the first-level cache with no statement at all (measured 2 -> 1 and 4 -> 3; `resolve`'s
  two writes moved to short programmatic transactions carrying one bulk statement each, and
  `AuthenticationQueryCountIT` pins the account to ONE load per request so re-adding the annotation fails the build).
  **One statement is the floor without caching the session row itself**, and a token cache was measured against that
  and REJECTED: it saves only the remaining lookup (0.46 ms locally, 1.24 ms at 4.78M rows) and can reach zero
  statements only by caching roles and identity state — exactly what `Session`'s "roles are resolved live" design
  forbids — while its eviction surface includes the invisible `sessions.user_id ON DELETE CASCADE`. Trigger for
  revisiting: a SECOND application instance, at which point the answer is a Redis `SessionStore` (the interface
  exists for precisely that), not a cache in front of `CurrentUser`, because an in-JVM cache makes revocation stop
  working in the very deployment that motivated it.
- **`users.created_at` was the deferred index whose trigger actually fired** - `V42` adds it. It was correctly not
  worth it at 200 and at 1,000 accounts (1.17 ms); at 50,000 the admin list's first page was 13-15 ms and its last
  page 127 ms, because the page was `Seq Scan` + top-N heapsort over every account. With the index: 0.2 ms and
  6.5 ms. **The lesson is the deferral note's own advice - re-measure at the size that matters rather than assuming
  the earlier figure still holds.** `ip_lockouts.ip_address` remains unindexed and remains fine: that table is pruned
  to a week of lockouts, so it does not grow with usage at all.
- **`notes` was measured for the same natural-key change `V39` made to `action_logs` and deliberately left alone**:
  its ~1.5 KB ciphertext payload dominates, and the table came out at 20 MB with or without the surrogate id.
- **The frequency chart now reads only the window it draws.** Its monthly rollup used to cover the subject's whole
  history so the caller could keep the anchor year's twelve months out of it, and its navigation bound
  (`earliestLoggedMonth`) was the minimum of that same rollup - so the MONTH view paid for a whole-history read it
  drew none of. The rollup is now range-bound (~65 ms -> 1.3 ms for three subjects) and the bound is its own query.
  **That bound is a `LATERAL` per subject on purpose**: a plain `SELECT MIN(log_date) ... WHERE action_id IN (...)`
  cannot use the index, because PostgreSQL will not push the `MIN` into each branch of the nested loop and reads
  every row instead (32.3 ms). Asked once per subject it is one index probe each (0.27 ms), and a chart holds at most
  `FrequencyCharts.MAX_SERIES` of them. **A denormalised "first logged" column was considered and rejected**: it
  would save ~0.3 ms, would need maintaining on six write paths, would still need this query to recompute whenever
  the earliest entry is deleted, would not cover the notes subject, and would fail silently (a stale bound blocks or
  invents chart navigation rather than erroring).
- **The notes search is the one path that grows and that NO index can reach**, because the content is ciphertext.
  Its cost is JVM-side and linear in journal length; measured over sealed 1.5 KB notes (open + match + the
  `NoteSearch.suggest` miss path):
  3 years/1,096 notes = 4.3 + 0.9 + 17.0 = **22 ms**; 10 years/3,652 = 8.7 + 3.6 + 54.0 = **66 ms**;
  20 years/7,300 = 17.2 + 5.5 + 125.0 = **148 ms**; 30 years/11,000 = 29.2 + 11.2 + 178.3 = **219 ms**.
  The database part is negligible throughout (3.2 ms to read a 10-year journal's 5,350 kB of ciphertext).
  **`suggest` is 70-80% of it and runs ONLY when a search matched nothing**, so a hit stays cheap (~40 ms even at
  30 years) and a miss is what degrades. An early exit on the first distance-1 candidate would cut it, but it would
  change WHICH word is suggested (ties currently break on occurrence count across the whole journal), so that is a
  behaviour decision rather than a free optimisation - do not take it as a pure perf change. Trigger: a user with a
  many-year journal reporting that a fruitless search feels slow.
- **Everything outside the Stats page and that search holds up at 10x the baseline** and needs no further indexing. Measured at
  4.78M `action_logs` rows / 1,000 accounts / a 10-year account, all sub-2 ms: the dashboard's three-month warm-up
  (0.33 ms), the frequency chart's month window (1.5 ms), the whole-journal notes read at 3,652 notes (1.06 ms), the
  session lookup (1.24 ms) and all four ETag signatures. **The admin user list at 1,000 accounts is 1.17 ms**, so the
  `users.created_at` index noted above is still not warranted - re-measure before adding it rather than assuming the
  row count alone is the trigger.
