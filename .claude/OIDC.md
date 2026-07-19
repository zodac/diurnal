# OIDC / Local Account Management — Review & Decisions

> Status: **implemented** (2026-07-17). This document records the review findings, the decisions taken, and what shipped — including the
> `q_session` revocation guard (investigated, proposed, approved and applied the same day). Update as decisions are made.

## Decisions taken (2026-07-17)

1. **Email-based auto-linking is removed outright** (not gated behind an env var — deliberately backwards-incompatible). OIDC accounts resolve by
   the immutable `iss`+`sub` pair ONLY. An OIDC login whose email matches an unlinked local account is refused with a banner directing the user to
   sign in locally and use Settings → Connect. This kills the Grafana-CVE-2023-3128-class takeover (email claims are attacker-influenceable at many
   IdPs). Flag for `RELEASE_NOTES.md` as a breaking behaviour change (hand-authored — do not edit it yourself).
2. **`email_verified` is enforced for provisioning**: a token with `email_verified: false` can never create an account. An absent claim is treated
   as acceptable (many IdPs don't emit it). Linking ignores email entirely (the user proves control of both sides).
3. **Group sync can never demote the last administrator**: the login is denied (matching `AdminUserService`'s safeguard) with a neutral
   "access level could not be updated … contact the application owner" banner — deliberately NOT explaining the last-admin detail to the user; a
   detailed `WARN` (`the IdP groups would demote the last remaining administrator …`) goes to the server log for debugging.
4. ~~Explicit Connect/Disconnect UX; hybrid accounts as a legitimate steady state~~ — **superseded 2026-07-18 by decisions 6–7.**
5. **`q_session` fix**: investigate first, propose before changing — see below (applied).

## Decisions taken (2026-07-18)

6. **Connecting is a ONE-WAY conversion to OIDC-only sign-in.** `AccountLinkService.link` removes the password in the same step; there is no
   disconnect, no standalone "remove password", and no hybrid password+OIDC state (migration `V22` normalised pre-existing linked rows). The
   Settings Connect confirm warns "Your password will be removed". Rationale: two permanently-live credentials double the account's attack surface
   and make the login rules ambiguous; the IdP is the stronger authority once trusted. The `DELETE /api/v1/users/me/oidc|password` endpoints were
   removed from the public contract before ever shipping in a release.
7. **`PASSWORD_AUTH_ENABLED=false` enables email ADOPTION** (the migration path): an OIDC login whose **verified** email matches an unlinked local
   account adopts it (`OidcLoginDecision.AdoptByEmail` — identity attached, password removed) instead of refusing, because Settings → Connect
   cannot exist without a password login. This is safe precisely because the IdP is then the deployment's sole authentication authority — an email
   match there grants nothing an IdP login doesn't already grant; `email_verified: false` still never adopts, and the last-admin guard applies.
   With password auth enabled, the collision refusal stands unchanged (that is the genuinely dangerous case).
8. **The initial account is ALWAYS created locally — even in a pure-OIDC deployment** (`PASSWORD_AUTH_ENABLED=false`): the first-run setup flow
   (`/welcome` + `/register`) now ignores `PASSWORD_AUTH_ENABLED` exactly as it already ignored `ENABLE_REGISTRATION`, and OIDC never provisions
   user number one. That first administrator is the sysops **break-glass credential** (its password becomes usable by re-enabling password auth).
   The setup page's content is deliberately IDENTICAL in both auth modes — the deployer configured `PASSWORD_AUTH_ENABLED` and owns that context
   (an explanatory note was added and then removed on request, 2026-07-18). **Converting that account later is allowed** — an adoption/connect of
   the deployment's only administrator proceeds so long as the account REMAINS an administrator afterwards (the IdP asserts admin via the
   configured group, or no group mapping leaves the role untouched); admin continuity through the IdP is what matters, not password retention.
   (A stricter `LAST_PASSWORD_ADMIN` guard was added and removed the same day after field feedback — the deployer keeps a password-capable backup
   only by giving the break-glass admin a dedicated email the IdP never presents.) The only refusals protecting admin continuity are
   `ROLE_SYNC_REFUSED` (a conversion/login that would demote the last administrator) and `NOT_IN_GROUP`. The break-glass admin can also CHANGE its
   password while password login is off — password management keys on holding a password, not on `PASSWORD_AUTH_ENABLED` (`PasswordChangeService` +
   the Settings `canChangePassword` gate, 2026-07-19).

## What shipped

### Policy core (`auth` package, pure + 100% PIT)

- `OidcLoginPolicy.decide(OidcLoginFacts)` → `OidcLoginDecision` (`UseExisting` / `ProvisionNew` / `LinkToSessionUser` / `Deny(reason)`): the whole
  login branching — first-run bootstrap guard, missing/unverified email, group authorisation, linked-account resolution, email-collision refusal,
  last-admin demotion refusal.
- `OidcLinkPolicy.decide(...)`: the Settings "Connect" branching — identity ownership (`NONE`/`SESSION_USER`/`OTHER_USER`), already-linked refusal,
  last-admin guard. No email involvement at all.
- `OidcDenialReason`: every refusal reason with a stable cookie code + neutral user-facing message ( `{provider}` substituted). Carried to the login
  page via the short-lived `diurnal_oidc_error` cookie (the code-flow failure redirect is a fixed `/login?error=oidc`, so the reason rides a cookie,
  mirroring the lockout-cookie pattern); `WebResource.loginPage` maps it to a specific banner and clears it.
- `OidcUserProvisioner` is now glue: gathers facts (claims + DB + config + the link-intent/session cookies), applies the policy, and per-reason
  `WARN`-logs every denial with `iss`/`sub`/email detail.
- **Denials on a live request throw `AuthenticationRedirectException` (302 → `/login?error=oidc`), never `AuthenticationFailedException`.** Found
  in the field: the code mechanism wraps any other failure from the augmentor into `AuthenticationCompletionException` → a bare 401 error page plus
  an ERROR stack trace (`quarkus.oidc.authentication.error-path` only covers errors the IdP itself sends back). The redirect exception is the one
  type the OIDC layer passes through untouched, so the user lands on the login page with the reason banner. The revocation guard likewise expires
  `q_session` and redirects to `/oidc-login` (cookieless → straight to the IdP → callback re-mints the session; no loop). Direct service-level
  calls (tests, no `RoutingContext`) still get `AuthenticationFailedException`.

### Settings Connect (one-way conversion) + email adoption

- `POST /internal/settings/oidc/connect` sets the one-shot `diurnal_oidc_link` intent cookie (5 min, HttpOnly) and forwards into the code flow via
  `/oidc-login`. At the callback, `OidcUserProvisioner` sees intent cookie + valid `diurnal_session`, applies `OidcLinkPolicy`, and
  `AccountLinkService.link` attaches `iss`+`sub` AND removes the password; `WebResource.oidcCallback` then lands on `/settings?msg=oidc-connected`
  (the banner states the password was removed). The Settings UI arms a confirm first ("Your password will be removed"). Surface policy: no API twin
  (browser redirect dance). **The token's email must match the signed-in account's email** (`LINK_EMAIL_MISMATCH`, added 2026-07-19 after a field
  report) — not a security control (the user proved both sides), but the mistaken-account guard: completing the round trip signed in to the wrong
  IdP account must not silently bind a mismatched identity and discard the password. A missing email claim likewise refuses; an already-linked
  identity's re-login is still resolved by `iss`+`sub` only.
- With `PASSWORD_AUTH_ENABLED=false`, the same conversion happens automatically on login for a verified email match (`AdoptByEmail`, decision 7).
- There is no disconnect and no standalone password removal (decision 6); a linked account's Settings just shows "Connected to {provider}".
- The admin users table + `AdminUserDto` carry an auth-source column (`User.authSource()`: `local` / `oidc`; `local+oidc` survives only as a
  defensive label for hand-edited rows).
- Fixed a latent bug on the way: `settings.js` unconditionally dereferenced the password-flow elements, which don't render for OIDC-only accounts —
  the whole script crashed for them. The password block is now guarded.

### Interop

- `OIDC_SCOPES` (default `email,profile,groups`): Google rejects the non-standard `groups` scope with `invalid_scope`, so it's now overridable
  (`OIDC_SCOPES=email,profile` for Google). Group→role mapping simply sees no groups then.
- `OIDC_PKCE_ENABLED` (default `true`): PKCE on the code flow alongside the client secret (OAuth 2.1 direction; Authelia/Keycloak/Google all
  support it).
- The handshake itself is Quarkus's OIDC extension (discovery, code flow, full token validation before the augmentor runs) — spec-compliant RP;
  single-tenant by design (multi-provider deliberately out of scope).

### Tests

Unit (100% PIT on the pure classes): `OidcLoginPolicyTest` (incl. the adoption branches + revocation guard), `OidcLinkPolicyTest`,
`OidcDenialReasonTest`, `UserAuthSourceTest`, `UserRowExtensionsTest` (auth-source labels). ITs: `OidcUserProvisionerIT` (collision refusal,
`email_verified`, iss+sub resolution, last-admin group-sync guard — environment-aware over the group config like `RoleAssignerIT`),
`AccountLinkIT` (the connect trigger, the one-way conversion, Settings link-state rendering — under `OidcEnabledProfile`), `OidcEmailAdoptionIT`
(the password-auth-disabled adoption path — under `OidcOnlyAuthProfile`), `FirstUserCreationBlockedIT` (updated signature).

## The `q_session` revocation gap (confirmed, fix APPLIED)

**Confirmed by code-reading.** Protected pages are deliberately not pinned to one auth mechanism (`application.properties`, the `ui` permission),
so a request carrying ONLY a valid `q_session` cookie (no `diurnal_session`) authenticates via the Quarkus OIDC code mechanism +
`OidcUserProvisioner` — which builds a full identity from the DB **without consulting the `sessions` table at all**. Consequences:

- "Log out from everywhere" (`revokeAllForUser`) deletes every `sessions` row and clears cookies **on the pressing device only**. Another device's
  `q_session` (persistent, `session-age-extension=P90D`, silently refreshed) keeps authenticating pages indefinitely.
- An attacker holding an exfiltrated `q_session` cookie simply never sends a `diurnal_session` and is untouched by any revocation.
- Mitigating detail: a browser that still carries its (now-revoked) `diurnal_session` cookie fails auth and is bounced to `/login` —
  `SessionAuthMechanism` fails (not abstains) on a present-but-invalid token — so the *casual* UX mostly looks logged-out; the gap is that the
  cookie-holder controls which cookies to send.

**Applied fix (A, approved 2026-07-17):** on any path other than the code-flow callback (`/oauth2/callback/oidc`, where the Diurnal session hasn't
been minted yet), `OidcUserProvisioner` requires a valid `diurnal_session` resolving (via `SessionStore`) to the same user; otherwise it fails
authentication (`OidcLoginPolicy.revocationGuardSatisfied`, PIT-covered; glue in `OidcUserProvisioner.authenticated`). The failure challenge
re-enters the code flow, round-trips the IdP (instant when the IdP session is alive) and re-mints a Diurnal session — so revocation in the
`sessions` table is authoritative for OIDC users too, while `q_session` survives only for token refresh and RP-initiated logout, as originally
intended. Direct service-level calls (tests, null `RoutingContext`) are exempt — no cookies to judge.

**Not chosen (B):** pinning protected pages to the session mechanism in config — a previous revision did this and broke the post-IdP login bounce;
riskier to re-attempt and harder to test.

## Deliberately not done

- Multi-provider support (N `sub` links, provider picker) — single-household app, revisit on demand.
- Revoking existing sessions on connect/adoption — sessions remain valid until revoked/expired; the account owner just proved control of both
  credentials, so nothing suggests compromise. Revisit if wanted.

## External references (search by name — no URLs per project comment policy)

Grafana advisory CVE-2023-3128 + `oauth_allow_insecure_email_lookup`; Gitea `[oauth2_client] ACCOUNT_LINKING`; Immich OAuth linking docs; OIDC Core
1.0 §5.1 (`email_verified`, email is a claim not an identifier); OAuth 2.1 draft (PKCE for all clients).
