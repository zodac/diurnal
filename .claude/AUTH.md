# Authentication & Security

> **This file is ~24 KB. Read only the section you need** - `grep -n '^#' .claude/AUTH.md` for its
> line range, then read that range rather than the whole file.
>
> - **Package layout (`auth` and its four subpackages)**
> - **Authentication** — The two surfaces, one token, The session read path, Revocation, OpenAPI docs gating, Auth throttling and the lockout console,
>   Resolving the current user, OIDC sign-in
> - **Security headers / CSP**

> Auth architecture, per-IP throttling, OIDC sign-in policies and the security-headers/CSP filter for Diurnal.
> Extracted from `CLAUDE.md`; read before touching anything under `auth/`, the `web/` login/OIDC flows, session
> handling, or `SecurityHeadersFilter`/CSP. The OIDC review/decision log lives in [`OIDC.md`](OIDC.md).

## Package layout (`auth` and its four subpackages)

`auth` holds the **credentials core only** (`AuthResource`, `AuthenticationService`, `RegistrationService`, `PasswordChangeService`, `Passwords`,
`RoleAssigner` + their request/result types). Three subpackages carry the rest, and the dependency direction is **one-way and enforced by what
imports what**:

| Package         | Role                                                                                                      | Depends on                               |
|-----------------|-----------------------------------------------------------------------------------------------------------|------------------------------------------|
| `auth.session`  | the session substrate (store, tokens, auth mechanism, identity provider, sweeper, activity)               | **nothing else in `auth`**               |
| `auth.lockout`  | per-IP throttling + the admin lockout console                                                             | **nothing else in `auth`**               |
| `auth.oidc`     | the whole OIDC sign-in/link flow, including its browser routes (`OidcWebResource`)                        | `auth` (`RoleAssigner`) + `auth.session` |
| `auth.security` | response hardening applied to every request: `SecurityHeadersFilter`, `CspPolicy`, `CsrfProtectionFilter` | nothing else in `auth`                   |

So `auth.session` and `auth.lockout` are **sinks**: the credentials core reaches into them, never the reverse, which is what makes them safe for the
rest of the app (`web`, `user`, `log`, `openapi`) to import directly. `auth.oidc` sits above the core; **nothing in `auth` imports `auth.oidc`.**
Keep it that way — a back-edge from a sink to the core would make the whole thing one package again in all but name.

The web UI's authentication pages live here too — `AuthWebResource` (login, first-run setup, registration, logout) is the browser twin of
`AuthResource`, and each config mapping now sits with the subdomain that owns it (`SessionConfig` in `auth.session`, `OidcConfig` in `auth.oidc`,
`IpThrottleConfig` in `auth.lockout`, the password/registration ones here). The cookies both login paths set are built in one place,
`auth.session.SessionCookies`.

Two former members left `auth` outright, because neither was about authenticating anyone: the Swagger-docs gate
(`OpenApiDocsAccess`/`OpenApiDocsAuthFilter`/`OpenApiDocsPaths`) now sits in `openapi` beside `PublicApiFilter`, and `ClientAddress` — a plain "read
the client IP off a `RoutingContext`" helper used by `web` and `user` as much as by the throttles — is in `http`.

**Two declarations are public purely to cross these boundaries**, and both say so in their own Javadoc: `SessionTokenExtractor` (needed by
`auth.oidc` and `openapi`) and `OidcUserProvisioner.linkOrCreate` (needed by the first-run bootstrap guard, which spans the OIDC and API-registration
paths and so lives in the parent package). Neither is a supported surface; do not treat them as extension points.

## Authentication

**The login page is driven by query params**: `?error` means a failed login, `?registered=true` a successful registration. `?error=oidc` is the
OIDC variant, whose specific reason rides the `diurnal_oidc_error` cookie (see below).

### The two surfaces, one token

- **Web UI (`/*`)** — server-side session; opaque token in the `diurnal_session` cookie (`HttpOnly`/`SameSite=strict`/`Secure`), set by
  `AuthWebResource.doLogin`; unauthenticated → `/login`. `@RolesAllowed("user")` at the method level.
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

### The session read path

**`SessionStore.resolve` is deliberately NOT `@Transactional`, and that is load-bearing for every authenticated request.** A `@Transactional` read
runs in a transaction-scoped persistence context that closes at commit, so the `User` its `JOIN FETCH` loaded is discarded the moment the method
returns and the resource's first `CurrentUser.get()` reads the same row a second time — one wasted `users` read on *every* authenticated request.
Reading with no transaction instead uses the **request-scoped** persistence context, which outlives authentication, so the account is still managed
when the resource asks and `CurrentUser` answers from the first-level cache with no statement at all (measured: `GET /api/v1/users/me` and
`GET /settings` 2 statements → 1, `GET /` 4 → 3). The two writes `resolve` may make — the coalesced `last_used_at` touch and the delete of a session
found expired — therefore open short transactions of their own **programmatically** (`QuarkusTransaction.joiningExisting()`), each a single bulk
statement keyed on the token hash so neither has to re-read the row. A `@Transactional` endpoint (any write) opens its own persistence context as it
always did and is unaffected. `AuthenticationQueryCountIT` pins the account to ONE load per request through Hibernate's `Statistics`, so re-adding an
innocent-looking `@Transactional` fails the build. **The one caller that must supply its own transaction is `OpenApiDocsAuthFilter`** — a Vert.x route
handler has no CDI request context at all, so with no transaction either, Hibernate throws `ContextNotActiveException`; it wraps its `resolve` in
`QuarkusTransaction.requiringNew()`.

### Revocation

**Revocation = deleting rows:** logout (`revoke`, this device only), password change (`revokeOthersForUser`, all *but* the current), "Log out from
everywhere" (`revokeAllForUser`, incl. current — `POST /internal/settings/sessions/revoke-all` from Settings, or its API twin
`POST /api/v1/auth/revoke`), and **account deletion** (`AdminUserService.deleteUser` calls `revokeAllForUser` and logs it at `DEBUG` before deleting).
That last one is redundant against the `sessions.user_id` `ON DELETE CASCADE` in `V20` — deliberately so: it keeps "a deleted account can no longer
authenticate" stated in the code and visible in the log, rather than being a property of the schema that nothing in Java mentions, and it is the
prerequisite for any future caching of session lookups (a cascade is invisible to a cache). OIDC folds in: `OidcWebResource.oidcCallback` mints a
Diurnal session
(`auth_source='oidc'`) and sets our cookie, so OIDC users ride the same revocable model (the `q_session` cookie survives only so logout can trigger
RP-initiated IdP logout).

`quarkus.http.auth.proactive=false` keeps auth lazy so `SessionAuthMechanism` can abstain (no token → let the OIDC code mechanism try) and, as the
top-priority mechanism, issue the right challenge.

### OpenAPI docs gating

**OpenAPI docs are admin-gated** — the Swagger UI shell (`/api`) and the generated OpenAPI document (`/q/openapi`) are served in every profile and sit
on `permit` paths, so without a gate they leak the whole API surface to anonymous callers. Because `proactive=false` leaves those framework-served
paths with no resolved identity (a named roles-allowed HTTP policy wouldn't fire — same reason the admin *pages* use per-endpoint `@RolesAllowed`),
`OpenApiDocsAuthFilter` enforces it as a **low-order Vert.x route** (order `MIN_VALUE + 1`, just after `SecurityHeadersFilter`) that resolves the
request's session token itself via `SessionStore` — reusing `SessionTokenExtractor`, shared with `SessionAuthMechanism` — and applies the pure
`OpenApiDocsAccess.decide`: admin → `next()`, anonymous → `302 /login`, authenticated non-admin → `403`. The branching is unit-tested (100% PIT); the
Vert.x glue is NO_COVERAGE like the rest of the auth mechanism.

### Auth throttling and the lockout console

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
`Retry-After`) and `AuthWebResource.doLogin` (web form). **Registration** likewise runs through one shared `RegistrationService`
(which owns the `IpThrottle` entry-check + failure recording, the unified field validation — email `@`, display name 2–100 chars,
password ≤128 — the duplicate-email check and account creation, returning a sealed `RegistrationResult`) — `AuthResource.register`
(JSON API → `429` + `Retry-After`; the deliberately-API-only first-user refusal stays in the resource) and `AuthWebResource.register`
(web form → `429`, carrying the seconds-left `X-Lockout-Retry-After` header + a `[data-form-errors]` banner; the web-only
confirm-password rule is expressed by passing `confirmPassword` to the service). The locked-out message states the **exact** whole seconds remaining
with **neutral** wording (`LockoutMessages.retryMessage`, e.g. "Too many failed attempts. Please try again in 240 seconds.") —
deliberately NOT naming login vs registration (one shared counter feeds both, so "too many failed logins" after failed *registrations*
would be misleading) and disclosing nothing about account existence (a non-existent email is keyed and locked identically, no
enumeration). The API returns it as the `429` body, alongside the exact `Retry-After` header. The web login form: `AuthWebResource.doLogin`
owns `POST /login` directly (there is no Quarkus form auth), so on a lockout it simply sets the short-lived `diurnal_login_lockout`
cookie (value = seconds left) onto its own `303 /login` redirect. `AuthWebResource.loginPage` reads that cookie to show the lockout banner
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

**Admin lockout view + history (`IpLockoutService`).** When the lockout is enabled, administrators get a management surface for it, on the
`/admin/users` page under User Management and gated on `AUTH_IP_THROTTLE_ENABLED` (disabled → the section is hidden and every endpoint `404`s, the
`register`-when-off precedent). The web UI shows a **single table** of every lockout within the one-week retention window (`IpLockout` entity + `V23`
migration), most recent first — active, expired and manually-unlocked alike — paginated by the viewer's page-size preference, with an **Unlock**
button on the active rows only; the whole section (heading included) is omitted when there are no lockouts to show. The live in-memory
`AttemptThrottle` snapshot (`IpThrottle.currentLockouts`) is no longer surfaced as its own table — it is exposed only via the public API's
`GET /api/v1/admin/ip-lockouts`. The history persists even after a lockout expires or is manually cleared. The history is **durable** (survives
restart, unlike the in-memory enforcement it logs); a row is written the moment a lockout trips (via
`IpLockoutService.recordFailure`, which both `AuthenticationService` and `RegistrationService` now call in place of `IpThrottle.recordFailure` — it
records the in-memory failure and, on the tripping failure, persists the history row in its own short `self`-invoked transaction, the hashing-service
pattern). Each persisted lockout also prunes rows older than `IpLockout.HISTORY_RETENTION` (7 days), so the table stays bounded with no sweeper. A
manual unlock (`IpLockoutService.unlock`) clears the in-memory entry **and** stamps the matching history row's `unlockedAt`/`unlockedBy`; a row's
displayed status (`ACTIVE`/`EXPIRED`/`UNLOCKED`) is derived by the pure `IpLockoutStatus` enum. Both surfaces are thin over the one service — the web
UI's HTMX endpoints (`/internal/admin/ip-lockouts/*`, `AdminIpLockoutsInternalResource`) and the public REST twins (`GET /api/v1/admin/ip-lockouts`,
`GET …/history`, `DELETE …/{ip}`, `AdminIpLockoutsApiResource`, in `OpenApiSurfaceIT.PUBLIC_API_CONTRACT`).

### Resolving the current user

> **Resolve the current user via `SecurityIdentity.getPrincipal().getName()` (the email) → `User.findByEmail(...)`, or the `userId` attribute →
`User.findByIdOptional`** (see `CurrentUser`). The session identity (`UserIdentities.of`) sets the email principal plus `userId`/`displayName`
attributes; there is no JWT/`JsonWebToken` in play.

OIDC users store `oidcSubject` + `oidcIssuer` and **no password** — connecting an identity provider is a one-way conversion that removes the
password in the same step (`AccountLinkService.link`; migration `V22` normalised pre-existing rows), so there is no hybrid state and no disconnect
(`User.authSource()`: `local`/`oidc` — the `local+oidc` label survives only as a defensive fallback — shown in the admin users table and
`AdminUserDto`); composite unique index
`(oidc_issuer, oidc_subject) WHERE oidc_subject IS NOT NULL`. OIDC is disabled by default (`OIDC_ENABLED=false`); `OIDC_SCOPES` (default
`email,profile,groups` — set `email,profile` for providers like Google that reject the non-standard `groups` scope) and `OIDC_PKCE_ENABLED`
(default `true`) tune the handshake.

### OIDC sign-in

**OIDC sign-in is decided by pure policies (100% PIT), with `OidcUserProvisioner` as glue** (see `.claude/OIDC.md` for the full review/decisions):

- **Accounts resolve by `iss`+`sub` ONLY — there is deliberately NO email-based auto-linking while password auth is enabled** (a
  Grafana-CVE-2023-3128-class takeover vector; removed as a conscious breaking change). An OIDC login whose email matches an unlinked local account
  is refused and directed to Settings → Connect. **The one exception is `PASSWORD_AUTH_ENABLED=false`** (the migration path): Settings → Connect
  cannot exist without a password login, and the IdP is the deployment's sole authority anyway, so a **verified** email ADOPTS the unlinked local
  account (`OidcLoginDecision.AdoptByEmail` → `AccountLinkService.link`: identity attached, password removed) — still subject to the last-admin
  guard. `email_verified: false` never provisions OR adopts; group sync never demotes the last administrator (denied with a neutral banner +
  detailed `WARN`). `OidcLoginPolicy.decide(OidcLoginFacts)` → `OidcLoginDecision`; every refusal is an `OidcDenialReason` whose code rides the
  short-lived `diurnal_oidc_error` cookie so `loginPage` can show a specific banner after the `/login?error=oidc` redirect (the lockout-cookie
  pattern). **Denials on a live request throw `AuthenticationRedirectException`**, never `AuthenticationFailedException` — anything else is wrapped
  by the code mechanism into an `AuthenticationCompletionException` and surfaces as a bare 401 + ERROR stack trace.
- **Linking is an explicit act** (password-auth-enabled deployments): Settings → "Connect {provider}" (`POST /internal/settings/oidc/connect`) sets
  the one-shot `diurnal_oidc_link` intent cookie and enters the code flow; at the callback the provisioner sees intent + valid `diurnal_session`
  and applies `OidcLinkPolicy` → `AccountLinkService.link`, landing on `/settings?msg=oidc-connected`. The Settings confirm warns that the password
  will be removed. Surface policy: the connect flow has no API twin (browser redirect dance).
- **The initial account is ALWAYS created locally** — the first-run setup flow (`/welcome` + `/register`) ignores BOTH `ENABLE_REGISTRATION` and
  `PASSWORD_AUTH_ENABLED` until a user exists, and OIDC never provisions user number one. In a pure-OIDC deployment that first administrator is the
  sysops break-glass credential. Converting it later (adoption/connect) is allowed so long as the account remains an administrator (IdP-asserted
  admin group, or no group mapping) — only a demotion of the last administrator is refused. A deployer keeps a password-capable backup by giving
  the break-glass admin a dedicated email the IdP never presents.
- **The `q_session` cookie alone never grants page access** (`OidcLoginPolicy.revocationGuardSatisfied`): outside the code-flow callback, an
  OIDC-authenticated request must also carry a live `diurnal_session` for the same user, or authentication fails and the code flow re-runs
  (re-minting a session at the callback) — so "log out from everywhere" is authoritative for OIDC devices too; `q_session` survives only for token
  refresh and RP-initiated logout.
- **There is deliberately NO disconnect and NO standalone password-removal** — connecting (or being adopted) IS the password removal, and a linked
  account stays IdP-managed. Two permanently-live credentials would double the attack surface; a stray linked-with-password row (only possible by
  hand-editing the DB) still renders defensively.

## Security headers / CSP

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
