# CLAUDE.md

> **Code style:** Project-specific expectations live in [`CODE_STYLE.md`](CODE_STYLE.md). Read it before writing or editing code.

> **UI patterns:** Template/CSS conventions (partial extraction, component classes, tokens, `id` rules) live in
[`UI_PATTERNS.md`](UI_PATTERNS.md). Read it before writing or editing templates or CSS.

> **No real URLs or internal IPs in comments or examples.** Use only `https://diurnal.example.com` or
`http://127.0.0.1:8080` as placeholder values. Never use production hostnames, LAN addresses (`192.168.*`, `10.*`,
`172.16–31.*`), or any other real hostname.

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
cp .env.example .env   # fill in DB_PASSWORD and SESSION_ENCRYPTION_KEY
docker compose up -d --build
docker compose logs -f app
```

> **Always run the quality gate through `.github/scripts/lint_and_tests.sh`, never `mvn clean install -Dall`
> directly.** The wrapper's `java` step is the whole JVM gate: it runs `mvn clean install -Dall` (unit + `*IT`
> + linters) and then, only if that passed, chains on the Playwright E2E/UI suite (`tests/run-e2e.sh`, reusing
> the jar the build just made) and the deployment-smoke suite (`tests/run-smoke.sh`, the real prod image). The
> other steps are the non-JVM linters: docker/grype/js/ts/markdown/shellcheck. Each step prints a `→` progress
> line per substep (e.g. java shows mvn → e2e → smoke; grype shows build → scan) and hides that substep's own
> output until it fails; `-v` streams everything live. **Scope it to the language/step you touched** rather
> than running the whole thing: `… java` after ANY Java/template/CSS/UI-spec/Dockerfile change (it covers the
> build, ITs, E2E and smoke), `… shellcheck` after a `*.sh` edit, `… markdown` after docs, etc. —
> comma-separate to combine. Bare (no args) it auto-detects changed steps since the last tag; `-f`/`--force`
> runs every step regardless of the diff (can't be combined with an explicit step list). Unit-tests-only
> (`mvn test -Dtests`) is the one exception — it has no scoped wrapper step.
>
> **The `mvn`/Maven build is unit + ITs (+ linters) ONLY — the E2E and deployment-smoke tiers are NOT wired
> into any `mvn` command.** They were deliberately split out of the `-Dall` profile and are instead chained
> onto the wrapper's `java` step (after the Maven gate). Do not re-add them to the pom.

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

**Port map**: 8080 = production; 8081 = dev mode, `@QuarkusTest`, and the E2E jar (never simultaneous); 8082 = the deployment-smoke stack (isolated
compose project, coexists with a running prod stack). The `mvn clean install -Dall` build runs unit tests (`test`), packages the fast-jar, then the
`*IT` tests (`integration-test`, DB brought up/down in `pre`/`post-integration-test`), gated at `verify` — and stops there. **The E2E and
deployment-smoke tiers are NOT in the Maven build**: they are chained onto the wrapper's `java` step, which runs `mvn clean install -Dall &&
tests/run-e2e.sh && tests/run-smoke.sh`. The E2E runner reuses the fast-jar the build just produced (own DB, Playwright UI suite on 8081); the smoke
runner builds the real prod image and brings up an isolated app+DB stack on 8082 (black-box smoke suite). Each runner is self-contained (brings up its
own deps, tears them down via an EXIT trap) and exits with Playwright's code; the `&&` chain gates each tier on the previous passing (E2E after the ITs
are green, smoke after E2E) and their stacks use disjoint ports/DBs. So a single `.github/scripts/lint_and_tests.sh java` is the whole JVM gate.

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
| `action` | `Action` entity + `ActionsWebResource` (the `/actions` page) + `ActionsInternalResource` (`/internal/actions` HTMX fragments/mutations) + `ActionsApiResource` (`/api/v1/actions` public CRUD) + `ActionValidation` (shared rules)                                                                                  |
| `log`    | `ActionLog` entity + `LogWebResource` (`/internal/logs` day-panel fragments + increment/decrement) + `LogsApiResource` (`/api/v1/logs` public events feed + day read/write) + `CalendarResource` (`/internal/logs/minimal-events` dashboard feed) + `LogGuards`/`DateRanges` (shared rules)                         |
| `stats`  | `StatsService` + `ActionStats` (data record) + `ActionStatsExtensions` (template extensions) + `ActionStatField` (Stats-page tile catalogue) + `StatTile` (tile view-model) + `StatsWebResource` (the `/stats` page) + `StatsInternalResource` (`/internal/stats/list`) + `StatsApiResource` (`GET /api/v1/stats`)  |
| `auth`   | `AuthResource` (`/api/v1/auth` register/login/logout/revoke → session token), `AuthenticationService`+`LoginResult`, `RegistrationService`+`RegistrationResult`, `SessionStore`/`PostgresSessionStore` + `Session` entity + `SessionTokens` + `SessionAuthMechanism` + `SessionIdentityProvider` + `SessionSweeper` |
| `user`   | `User` entity, `UserResource` (`/api/v1/users/me`), `UserSettings`, and the settings-picker enums `Theme`/`Font`/`CalendarView` (each `implements PreviewOption`)                                                                                                                                                   |
| `web`    | `WebResource` — all top-level page routes (dashboard, login, register, logout, settings) + the `/internal/settings/*` preference endpoints; `AdminWebResource` (admin pages) + `AdminUsersInternalResource` (`/internal/admin/users` fragments)                                                                     |

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
status codes vs partials/banners/redirects). The existing pairs: `AuthenticationService`→`LoginResult` (login), `RegistrationService`→
`RegistrationResult` (register), `ActionService`→`ActionResult` (action CRUD), `LogService`→`LogResult` (day-count writes), `ProfileService`→
`ProfileResult` (display name + every preference), `PasswordChangeService`→`PasswordChangeResult` (password verify/change), `AdminUserService`→
`AdminUserResult` (admin user management, incl. the last-admin safeguards), plus `SessionStore` (session mint/revoke) and `StatsService` (stats)
which need no result type.

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

### Authentication

- **Web UI (`/*`)** — server-side session; opaque token in the `diurnal_session` cookie (`HttpOnly`/`SameSite=strict`/`Secure`), set by
  `WebResource.doLogin`; unauthenticated → `/login`. `@RolesAllowed("user")` at the method level.
- **REST API (`/api/v1/*`)** — the **same** opaque session token sent as `Authorization: Bearer` (from `POST /api/v1/auth/login`).

Both surfaces share ONE server-side session store (`SessionStore` → `PostgresSessionStore`, the `sessions` table; migration `V20`). There is **no JWT
and no encrypted-cookie key** — a login mints a 32-byte random token (`SessionTokens.generate()`) and persists only its SHA-256 hash (`token_hash`),
so a
DB read leak yields no usable sessions. `SessionAuthMechanism` (a custom `HttpAuthenticationMechanism`, priority above the built-ins) is the single
authenticator for every route: it extracts the token from the `diurnal_session` cookie OR a Bearer header and hands it to `SessionIdentityProvider`
(blocking DB lookup off the IO thread via `runBlocking`), which resolves it through `SessionStore.resolve` and builds the identity with **live** roles
(`UserIdentities.of` — roles read from `User.role` each request, so role/admin changes take effect instantly with no session write). Validity = idle
timeout (`SESSION_IDLE_TIMEOUT`, default `P30D`, sliding on `last_used_at`) AND absolute cap (`SESSION_ABSOLUTE_LIFETIME`, default `P90D`); the
boundary
logic is pure in `SessionTokens` (100% PIT). The challenge is path-based (`SessionAuthMechanism.challengeFor`): `/api/*` → `401`, everything else →
`302 /login`. `SessionSweeper` (`@Scheduled`, `SESSION_CLEANUP_INTERVAL`) prunes absolute-expired rows; idle-expired rows are pruned lazily on
`resolve`.
**Revocation = deleting rows:** logout (`revoke`, this device only), password change (`revokeOthersForUser`, all *but* the current), and "Log out from
everywhere" (`revokeAllForUser`, incl. current — `POST /internal/settings/sessions/revoke-all` from Settings, or its API twin
`POST /api/v1/auth/revoke`). OIDC folds in: `WebResource.oidcCallback` mints a Diurnal session
(`auth_source='oidc'`) and sets our cookie, so OIDC users ride the same revocable model (the `q_session` cookie survives only so logout can trigger
RP-initiated IdP logout).

`quarkus.http.auth.proactive=false` keeps auth lazy so `SessionAuthMechanism` can abstain (no token → let the OIDC code mechanism try) and, as the
top-priority mechanism, issue the right challenge.

**OpenAPI docs are admin-gated** — the Swagger UI shell (`/api`) and the generated OpenAPI document (`/q/openapi`) are served in every profile and sit
on `permit` paths, so without a gate they leak the whole API surface to anonymous callers. Because `proactive=false` leaves those framework-served
paths with no resolved identity (a named roles-allowed HTTP policy wouldn't fire — same reason the admin *pages* use per-endpoint `@RolesAllowed`),
`OpenApiDocsAuthFilter` enforces it as a **low-order Vert.x route** (order `MIN_VALUE + 1`, just after `SecurityHeadersFilter`) that resolves the
request's session token itself via `SessionStore` — reusing `SessionTokenExtractor`, shared with `SessionAuthMechanism` — and applies the pure
`OpenApiDocsAccess.decide`: admin → `next()`, anonymous → `302 /login`, authenticated non-admin → `403`. The branching is unit-tested (100% PIT); the
Vert.x glue is NO_COVERAGE like the rest of the auth mechanism.

**Auth throttling (one global per-IP lockout)** — `AttemptThrottle` is a plain, key-agnostic fixed-window throttle (config
snapshot + a `ConcurrentHashMap`; counters **decay** after a quiet window so shared keys don't accumulate). `IpThrottle`
(`@ApplicationScoped`, `auth`) runs **one** instance keyed by client IP (`IpThrottleConfig`, env
`AUTH_IP_THROTTLE_{ENABLED,MAX_ATTEMPTS,LOCKOUT_DURATION}`, default 15/`PT15M`). This is the **only** auth lockout — there is
**deliberately no per-account (email) dimension**, because keying on the email would let an attacker deny service to a chosen
victim by failing their logins. **One shared counter tallies both failed logins and failed registrations**; once it trips, that IP
is blocked from **both** logging in and registering. `isLocked`/`recordFailure`/`lockoutRemaining` all key on the IP; there is **no
`recordSuccess`/reset** — a valid login or registration must not launder an IP's brute-force budget, so the counter only clears by
decaying (the distributed many-IP brute-force this trades away is mitigated by Argon2id + uniform timing, not account lockouts).
The client IP comes from `ClientAddress.of(routingContext)` → Vert.x `remoteAddress()` (honours `TRUST_X_FORWARDED_HEADERS`), so
this is only meaningful behind a trusted proxy. **Login** verifies credentials through the **same** `AuthenticationService` (which
owns the `IpThrottle` check + Argon2id verification and returns a `LoginResult`) — `AuthResource.login` (JSON API → `429` +
`Retry-After`) and `WebResource.doLogin` (web form). **Registration** likewise runs through one shared `RegistrationService`
(which owns the `IpThrottle` entry-check + failure recording, the unified field validation — email `@`, display name 2–100 chars,
password ≤128 — the duplicate-email check and account creation, returning a sealed `RegistrationResult`) — `AuthResource.register`
(JSON API → `429` + `Retry-After`; the deliberately-API-only first-user refusal stays in the resource) and `WebResource.register`
(web form → `429`, carrying the seconds-left `X-Lockout-Retry-After` header + a `[data-form-errors]` banner; the web-only
confirm-password rule is expressed by passing `confirmPassword` to the service). The locked-out message states the **exact** whole seconds remaining
with **neutral** wording (`LockoutMessages.retryMessage`, e.g. "Too many failed attempts. Please try again in 240 seconds.") —
deliberately NOT naming login vs registration (one shared counter feeds both, so "too many failed logins" after failed *registrations*
would be misleading) and disclosing nothing about account existence (a non-existent email is keyed and locked identically, no
enumeration). The API returns it as the `429` body, alongside the exact `Retry-After` header. The web login form: `WebResource.doLogin`
owns `POST /login` directly (there is no Quarkus form auth), so on a lockout it simply sets the short-lived `diurnal_login_lockout`
cookie (value = seconds left) onto its own `303 /login` redirect. `WebResource.loginPage` reads that cookie to show the lockout banner
(over the generic error), clears it, AND echoes the **seconds left** in an `X-Lockout-Retry-After` response header — because the login
form posts via `fetch` (`data-ajax-submit` in `app.js`) and never renders that HTML, so the AJAX handler reads the header and runs a
**live mm:ss countdown** via the shared `window.Diurnal.startLockoutCountdown` helper (greying/disabling the submit button until it
hits `00:00`, then hiding the banner and restoring the button; the cookie value carries the same seconds for the no-JS server-rendered
banner). The registration form (`data-ajax-errors`) reuses that **same** helper + `X-Lockout-Retry-After` header to show an identical
countdown on its `429`. The lockout is revealed on the first attempt made *while* locked (entry check), not the threshold-tripping
one. Failure logging (`LoginAttemptLog` for logins, `RegistrationAttemptLog` for registrations) is `Failed login/registration
attempt (x of y) … (IP: …)` plus a `WARN` `IP locked out …` when the counter trips, using the duration carried on the
`FailureOutcome`. State is **in-memory** (resets on restart, not shared across instances) — acceptable for the single-instance
deploy. Time comes in as an explicit `Instant` param (from `AppClock.now()`) so the logic is pure/unit-testable and ITs can
freeze/advance the clock; keep the branching in `AttemptThrottle`/`IpThrottle` (unit-tested to 100% PIT strength), not the glue.

> **Resolve the current user via `SecurityIdentity.getPrincipal().getName()` (the email) → `User.findByEmail(...)`, or the `userId` attribute →
`User.findByIdOptional`** (see `CurrentUser`). The session identity (`UserIdentities.of`) sets the email principal plus `userId`/`displayName`
attributes; there is no JWT/`JsonWebToken` in play.

OIDC users store `oidcSubject` + `oidcIssuer` instead of a password hash; composite unique index
`(oidc_issuer, oidc_subject) WHERE oidc_subject IS NOT NULL`. OIDC is disabled by default (`quarkus.oidc.enabled=false`).

### Security headers / CSP

`SecurityHeadersFilter` (a top-priority Vert.x route, `order(Integer.MIN_VALUE)`) adds a full set of security headers to
every response: `Content-Security-Policy`, `X-Content-Type-Options: nosniff`, `Referrer-Policy:
strict-origin-when-cross-origin`, `X-Frame-Options: SAMEORIGIN`, `Cross-Origin-Opener-Policy: same-origin`,
`Cross-Origin-Resource-Policy: same-origin`, `Permissions-Policy` (denies geolocation/camera/microphone/payment). HSTS is
deliberately **not** emitted — that's the TLS-terminating reverse proxy's job, not the app's (plaintext HTTP behind the
proxy makes an HSTS header meaningless).

The CSP is decided by the pure `CspPolicy.forPath(path)` (100% PIT, `CspPolicyTest`) and branches on path:

- **User-facing routes** get the strict policy: `default-src 'self'; frame-ancestors 'self'; base-uri 'self';
  form-action 'self'; object-src 'none'; script-src 'self' '<pinned FOUC-script hash>'; script-src-attr 'none';
  style-src 'self' '<pinned FOUC-style hash>'; style-src-attr 'unsafe-inline'; img-src 'self'; font-src 'self';
  connect-src 'self'`. There is **no inline script or `on*=`/`hx-on=` attribute anywhere in the app** except one
  byte-static FOUC theme bootstrap in `layout.html`'s `<head>` (covered by a pinned `sha256-…` hash,
  `SecurityHeadersFilterIT` re-derives it from a live fetch on every run so an edit without re-pinning fails CI) —
  every other script lives in a committed, content-hashed `/js/*.js` file (see "Served front-end scripts" above).
  `style-src-attr 'unsafe-inline'` is the one deliberate laxity: per-user swatch colours render as inline `style="…"`
  attributes (can't be a static class or hash since the colour varies per action); it can't execute script. An audit
  found no `data:` URI, cross-origin font, or cross-origin fetch/HTMX target anywhere in the app, so `img-src`/
  `font-src`/`connect-src`/`default-src` all stay at `'self'` with no relaxation.
- **The admin-gated OpenAPI docs paths** (`/api` Swagger UI shell, `/q/openapi*`, matched via the shared
  `OpenApiDocsPaths` regexes) get a separate, relaxed policy (`script-src 'self' 'unsafe-inline' 'unsafe-eval';
  style-src 'self' 'unsafe-inline'; img-src 'self' data:`) because Quarkus's bundled Swagger UI bootstraps itself with
  inline script/styles. This only ever reaches an authenticated administrator — both paths are already gated by
  `OpenApiDocsAuthFilter`.

`<meta name="htmx-config" content='{"allowEval":false,"includeIndicatorStyles":false}'>` in `layout.html` stops htmx
from executing `hx-on=`/`Function`-constructor code paths (would otherwise need `'unsafe-eval'`) and from injecting its
`.htmx-indicator` styles as an inline `<style>` (would otherwise need broader `style-src`); the indicator CSS lives in
`app.css` instead, ready for the day a `.htmx-indicator` element is used.

Regression coverage: `CspPolicyTest` (unit, the path→policy branching), `SecurityHeadersFilterIT` (the live FOUC-hash
re-derivation + header presence), a deployment-smoke spec (`tests/smoke/smoke.spec.ts`) asserting the full header set on
the **real production image**, and an E2E fixture (`tests/helpers/fixtures.ts`'s `page` fixture override) that fails any
Playwright spec if a `securitypolicyviolation` DOM event fires or a CSP-related console error is logged — turning
"watch the console, zero expected" into a permanent gate across the whole E2E suite rather than a one-off manual pass.
CSRF is a separate concern, handled by `CsrfProtectionFilter` (request-origin validation), not this filter.

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
`statsEnabled` to the consolidated `PATCH /internal/settings` endpoint.

> A new stat's `ActionStatField` constant must also supply a `description()` (the constructor requires it) — it becomes the picker
> tooltip.

> **Any newly-computed stat that should be user-visible on the Stats page MUST be registered as an `ActionStatField` constant AND
> given a `StatTile` mapping in `ActionStatsExtensions.tiles(...)`** (plus a case in its `switch`, which is exhaustive over the enum
> so the compiler flags omissions). Without both it will never appear in the picker or on the page.

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

### Calendar feeds (LogsApiResource / CalendarResource)

`GET /api/v1/logs/events` (`LogsApiResource`) returns `CalendarEventDto` JSON (one event per logged action, title carries the `×N` multiplier). It is
the **public logged-events API** — authenticates both the session cookie and a Bearer session token — and is also the feed the dashboard's `full`
calendar reads. `start`/`end` are mandatory ISO-8601 dates (missing → 400, parsed by the shared `DateRanges`). Anonymous requests → `401` (the
`/api/*` challenge); `dashboard.js`'s `feedJson()` turns that into a `/login` navigation. `LogsApiResource` also carries the public day read/write
endpoints (`GET /api/v1/logs/{date}`, `PUT`/`POST increment|decrement`/`DELETE /api/v1/logs/{date}/{actionId}`), sharing `LogGuards` with
`LogWebResource`. `GET /internal/logs/minimal-events` (`CalendarResource`) is web-UI-internal (pruned from the docs by namespace) and feeds the
`minimal`/`stacked` styles (≤4 dots per day).

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

### Pagination

All list views (actions, day-panel, stats) use in-memory pagination: fetch all, filter, slice. Page size is a per-user setting validated against
`{5, 10, 25, 50, 100}` (default `5`) by `UserSettings.sanitisePageSize()`. `PaginatedDayActions` adds filler rows to keep every page the same height.

### Notable invariants

- `ActionLog.MAX_DAILY_COUNT = 999` — `SMALLINT` column; increment, increment-by-10, and set are silently capped.
- Actions are hard-deleted along with their logs when an action is deleted (no soft-delete/archive).
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
`newLog()`, `runInTx()`. Tests use `@TestSecurity`. The `test` profile forces `app.timezone=UTC`. Password hashing runs at minimal cost in tests:
seeded users (`newUser()`) get a cheap Argon2id hash whose parameters mirror the `test` profile's pinned `password.hash.argon2.*` values (so a seeded
login does not trigger a re-hash).

**Deterministic time:** `IntegrationTestBase` freezes `AppClock` in `@BeforeEach` to `FIXED_TODAY = 2026-06-15`, restoring in `@AfterEach`. Use
`freezeDate(LocalDate)` or `freezeInstant(Instant, ZoneId)` for boundary cases. Unit tests pass a fixed `today` directly. Surefire/failsafe pin
`-Duser.timezone=UTC`. E2E specs use UTC date APIs (`setUTCDate`/`getUTCDate`/`toISOString`) and `timezoneId: 'UTC'` in Playwright.

### Deployment-smoke tier (`tests/smoke/`)

The test pyramid has a fourth tier on top of unit / `*IT` / E2E: **deployment-smoke**, the only tier that runs the **actual production Docker image
** (distroless, jlink custom JRE, non-root UID 65532) rather than a full JDK. It exists because that runtime is now a real source of bugs none of the
lower tiers can see — e.g. a jlink module trimmed too far (the `java.rmi` boot failure), non-root write permissions, or a CSS-hash/favicon build-stage
desync.

- **Files:** `tests/docker-compose.smoke.yml` (isolated app+DB stack built from the `Dockerfile`), `tests/run-smoke.sh` (build → `up --wait` → run →
  trap-teardown), `tests/playwright.smoke.config.ts` (`testDir: ./smoke`, single chromium project), `tests/smoke/*.spec.ts`.
- **Runs the prod profile** against a live Postgres — so there is **NO frozen clock and NO seeded DB**. Smoke specs must **self-seed** and use only
  the app's own UTC "today" (`TZ=UTC` in the compose stack; browser pinned to UTC). Do **not** port frozen-time E2E specs here. Keep the suite small
  and image-focused (boot/health, hashed assets, one persisted round-trip through the server-side session store) — feature behaviour belongs in the
  E2E suite.
- **Isolation:** dedicated compose project (`-p diurnal-smoke`), ephemeral tmpfs DB, host port **8082**. Coexists with a running prod stack. (The app
  writes nothing to the filesystem now — session state is in Postgres — so no writable secrets mount is needed.)
- **CI wiring:** chained onto the `java` step of `.github/scripts/lint_and_tests.sh` (which runs `mvn clean install -Dall && tests/run-e2e.sh &&
  tests/run-smoke.sh`) — **not** part of any `mvn` command. The Maven build (`-Dall`) is unit + `*IT` + linters only; the E2E and deployment-smoke
  tiers were split out of the pom and are now chained after the Maven gate (smoke runs only if the build, ITs and E2E all passed). The image's own
  HEALTHCHECK drives `up --wait`, so a boot failure fails the step before Playwright starts. In the wrapper's auto-detect mode any app-code or
  Dockerfile/runtime change selects `java`; run the whole JVM gate explicitly with `.github/scripts/lint_and_tests.sh java`.

### Performance/load tier (`tests/perf/`)

The fifth tier: a k6 load suite that — like the smoke tier — runs against the **real production Docker image** (not the fast-jar), because the jlink
JRE's startup, JIT warm-up and memory profile are what actually ship. It exists to catch performance regressions (an N+1 query, an unindexed scan, a
jlink/heap bloat, a slow cold boot) that no functional tier can see. It is a standalone `lint_and_tests.sh` step (**not** part of any `mvn` command and
**not** chained onto the `java` gate — a perf regression must not fail the functional build, and vice versa), but it **is auto-detected on the SAME
file set as `java`** (any `src/`, `frontend/`, `pom.xml`, Dockerfile or java-config change — the things that plausibly move performance), **plus** the
perf suite's own files (`tests/perf/**`, `tests/run-perf.sh`, `tests/docker-compose.perf.yml`). It keys off `java`'s final detection outcome, so the
same comment-only / project version-bump suppression applies. Run it explicitly with **`.github/scripts/lint_and_tests.sh perf`**, scoped alongside
others (`… java,perf`), or via `tests/run-perf.sh <port> <projectRoot>` directly; `-f/--force` and a no-tag run include it like any other step.

- **Files:** `tests/docker-compose.perf.yml` (isolated app+DB stack from the `Dockerfile`), `tests/run-perf.sh` (build → boot-timing → seed → k6 load
  → trap-teardown), `tests/perf/seed.mjs` (k6 seeder) and `tests/perf/load.mjs` (k6 scenarios + thresholds). The k6 scripts are **`.mjs`** (ES
  modules, like `tests/measure.mjs`) so the CommonJS `javascript` lint step doesn't try to parse them; k6 itself accepts `.mjs`.
- **What it measures:** (1) **cold-boot** latency — container start → first `200 /api/v1/status` with readiness `UP` — plus post-boot RSS, asserted
  against `PERF_BOOT_BUDGET_S` / `PERF_RSS_MAX_MB` (RSS is best-effort: skipped if `docker stats` can't be parsed); (2) **steady-state throughput** —
  one k6 `constant-arrival-rate` scenario per public-API use-case group (`= OpenApiSurfaceIT.PUBLIC_API_CONTRACT`: status, login, actions list, action
  CRUD, log write, calendar feed, stats), each with its own p95-latency + error-rate **threshold** (a breach makes k6 exit non-zero → the runner and
  the `perf` step fail); (3) **heavy-data edge cases** — the seed populates a large account (`PERF_SEED_ACTIONS` × `PERF_SEED_LOG_DAYS`) so the list /
  stats / calendar-feed scenarios exercise real fan-out, not empty-DB best cases.
- **Runs the prod profile** against a live Postgres — **NO frozen clock, NO pre-seeded DB** (like smoke). `seed.mjs` self-seeds (bootstraps the
  first-user admin via the web `/register` form, then seeds via the API) and hands credentials/IDs to `load.mjs` via a base64 `PERFSTATE:…` stdout
  token the runner decodes (k6's `handleSummary` runs in a separate isolate and can't see iteration state, so a file-write handover isn't possible).
  The per-IP auth throttle is disabled in the perf stack (`AUTH_IP_THROTTLE_ENABLED=false`) so the single-source load generator isn't locked out.
- **Isolation:** dedicated compose project (`-p diurnal-perf`), ephemeral tmpfs DB, host port **8083** (!= 8080 prod / 8081 dev+E2E / 8082 smoke), so
  a perf run coexists with everything. An EXIT/INT/TERM/HUP trap always tears the stack down and removes the scratch state dir.
- **Tuning knobs** (env, all with sensible defaults, forwarded by name into the k6 container by `run-perf.sh`):
  `PERF_SEED_ACTIONS`, `PERF_SEED_LOG_DAYS`, `PERF_DURATION`, `PERF_RATE`, `PERF_VUS`, `PERF_P95_TOLERANCE`, `PERF_BOOT_BUDGET_S`,
  `PERF_RSS_MAX_MB`; the per-scenario latency/error budgets live in `load.mjs`'s `options.thresholds` — tune them to the deployment's
  SLOs. `PERF_P95_TOLERANCE` (default `1`) scales **every** p95 latency budget by a single multiplier so the same suite gates both a
  fast dev box and a small shared CI runner without re-numbering each threshold — it scales latency ONLY (error-rate budgets stay
  absolute). `publish.yml` sets a lighter `PERF_RATE`/`PERF_VUS` + a `PERF_P95_TOLERANCE` on the gate step because `ubuntu-latest` is a
  2-vCPU box co-running the app, Postgres and k6, so the dev-workstation load-shape collapses into queueing (seconds of pure queue
  time). The k6 image is pinned (`grafana/k6`), matching the containerised-tool pattern of the lint steps (no host k6 install needed).
