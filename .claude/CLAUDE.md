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

> **After writing or editing any shell script (`*.sh`), run `.github/scripts/lint_and_tests.sh shellcheck`** and fix
> everything it reports before considering the change done — including `info`-level notes (e.g. `SC2312`). This is the
> same shellcheck gate CI runs; skipping it ships lint failures. Note the step is `shellcheck` (not `shellscript`).

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

Inherits from `net.zodac:parent-pom` (Maven Central). The parent manages all dependency/plugin versions (Quarkus BOM, JUnit BOM, jspecify,
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
| `auth`   | `AuthResource` (register/login/logout → session token), `AuthenticationService`, `SessionStore`/`PostgresSessionStore` + `Session` entity + `SessionTokens` + `SessionAuthMechanism` + `SessionIdentityProvider` + `SessionSweeper` |
| `user`   | `User` entity, `UserResource` (`/api/users/me`), `UserSettings`                                                   |
| `web`    | `WebResource` — all top-level page routes (dashboard, login, register, logout, settings, theme toggle)            |

### Authentication

- **Web UI (`/*`)** — server-side session; opaque token in the `diurnal_session` cookie (`HttpOnly`/`SameSite=strict`/`Secure`), set by
  `WebResource.doLogin`; unauthenticated → `/login`. `@RolesAllowed("user")` at the method level.
- **REST API (`/api/*`)** — the **same** opaque session token sent as `Authorization: Bearer` (from `POST /api/auth/login`).

Both surfaces share ONE server-side session store (`SessionStore` → `PostgresSessionStore`, the `sessions` table; migration `V20`). There is **no JWT
and no encrypted-cookie key** — a login mints a 32-byte random token (`SessionTokens.generate()`) and persists only its SHA-256 hash (`token_hash`), so a
DB read leak yields no usable sessions. `SessionAuthMechanism` (a custom `HttpAuthenticationMechanism`, priority above the built-ins) is the single
authenticator for every route: it extracts the token from the `diurnal_session` cookie OR a Bearer header and hands it to `SessionIdentityProvider`
(blocking DB lookup off the IO thread via `runBlocking`), which resolves it through `SessionStore.resolve` and builds the identity with **live** roles
(`UserIdentities.of` — roles read from `User.role` each request, so role/admin changes take effect instantly with no session write). Validity = idle
timeout (`SESSION_IDLE_TIMEOUT`, default `P30D`, sliding on `last_used_at`) AND absolute cap (`SESSION_ABSOLUTE_LIFETIME`, default `P90D`); the boundary
logic is pure in `SessionTokens` (100% PIT). The challenge is path-based (`SessionAuthMechanism.challengeFor`): `/api/*` → `401`, everything else →
`302 /login`. `SessionSweeper` (`@Scheduled`, `SESSION_CLEANUP_INTERVAL`) prunes absolute-expired rows; idle-expired rows are pruned lazily on `resolve`.
**Revocation = deleting rows:** logout (`revoke`, this device only), password change (`revokeOthersForUser`, all *but* the current), and "Log out from
everywhere" (`revokeAllForUser`, incl. current — `POST /settings/sessions/revoke-all`). OIDC folds in: `WebResource.oidcCallback` mints a Diurnal session
(`auth_source='oidc'`) and sets our cookie, so OIDC users ride the same revocable model (the `q_session` cookie survives only so logout can trigger
RP-initiated IdP logout).

`quarkus.http.auth.proactive=false` keeps auth lazy so `SessionAuthMechanism` can abstain (no token → let the OIDC code mechanism try) and, as the
top-priority mechanism, issue the right challenge.

**OpenAPI docs are admin-gated** — the Swagger UI shell (`/api`) and the generated OpenAPI document (`/q/openapi`) are served in every profile and sit on
`permit` paths, so without a gate they leak the whole API surface to anonymous callers (free reconnaissance, even though each endpoint enforces its own
auth). Because `proactive=false` leaves those framework-served paths with no resolved identity (a named roles-allowed HTTP policy wouldn't fire — same
reason the admin *pages* use per-endpoint `@RolesAllowed`), `OpenApiDocsAuthFilter` enforces it as a **low-order Vert.x route** (order `MIN_VALUE + 1`,
just after `SecurityHeadersFilter`) that resolves the request's session token itself via `SessionStore` — reusing `SessionTokenExtractor`, shared with
`SessionAuthMechanism` — and applies the pure `OpenApiDocsAccess.decide`: admin → `next()`, anonymous → `302 /login`, authenticated non-admin → `403`. The
branching is unit-tested (100% PIT); the Vert.x glue is NO_COVERAGE like the rest of the auth mechanism.

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
`Retry-After`) and `WebResource.doLogin` (web form). **Registration** consults the same `IpThrottle` directly in
`AuthResource.register` (JSON API → `429` + `Retry-After`) and `WebResource.register` (web form → `429`, carrying the seconds-left
`X-Lockout-Retry-After` header + a `[data-form-errors]` banner). The locked-out message states the **exact** whole seconds remaining
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
re-derivation + header presence), a deployment-smoke spec (`e2e/smoke/smoke.spec.ts`) asserting the full header set on
the **real production image**, and an E2E fixture (`e2e/helpers/fixtures.ts`'s `page` fixture override) that fails any
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

Seven scripts are served from `META-INF/resources/js/` and referenced from the templates via
`{inject:appInfo.*}`, all sharing one cache-busting pattern: served un-hashed in dev (`no-store`), and at image-build
time the Dockerfile renames each to `name.<sha256-12>.ext`, bakes the hashed name into
`microprofile-config.properties` (read by `AppConfig`/`AppInfo`), and serves it `public, max-age=31536000, immutable`
(`application.properties`, the `/js/` filter).

- `htmx.min.js` (`AppInfo.jsFile`) — **vendored** from npm by `scripts/vendor-assets.cjs` (`.gitignored` build artifact).
- `app.js` (`AppInfo.jsAppFile`) — the shared per-page behaviour extracted from `layout.html` (dt edit/confirm toggles,
  form validation + AJAX submit, locale number grouping, the tooltip long-press, the password-requirements popover, the
  delegated `htmx:configRequest` search-filter listener, the mobile-menu toggle). A **committed** hand-written file.
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
> from `data-theme` on `<html>` (server-rendered, mirroring `.font-nova`) rather than having Qute interpolate the value,
> so its bytes are byte-static across every render and are covered by a pinned CSP hash instead of being inline-allowed
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

### CalendarResource

`GET /logs/events` returns `CalendarEventDto` JSON (one event per logged action, title carries the `×N` multiplier), including archived actions. It is
the **public logged-events API** — authenticates both the session cookie and a Bearer session token, published in Swagger by `PublicApiFilter` — and is also the feed
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
`newLog()`, `runInTx()`. Tests use `@TestSecurity`. The `test` profile forces `app.timezone=UTC`. Password hashing runs at minimal cost in tests: seeded users (`newUser()`) get a cheap Argon2id hash whose parameters mirror the `test` profile's pinned `password.hash.argon2.*` values (so a seeded login does not trigger a re-hash).

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
  and image-focused (boot/health, hashed assets, one persisted round-trip through the server-side session store) — feature behaviour belongs in the E2E suite.
- **Isolation:** dedicated compose project (`-p diurnal-smoke`), ephemeral tmpfs DB, host port **8082**. Coexists with a running prod stack. (The app
  writes nothing to the filesystem now — session state is in Postgres — so no writable secrets mount is needed.)
- **CI wiring:** run by the `e2e-then-smoke` exec in the `-Dall` profile (chained `run-e2e.sh && run-smoke.sh`, bound to `install`, gated after the
  `verify` IT check) — smoke runs only if E2E passed; the image's own HEALTHCHECK drives `up --wait`, so a boot failure fails the build before
  Playwright starts.
