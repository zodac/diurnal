# Privacy & External Communication

> **This file is the complete inventory of every byte that leaves a Diurnal deployment.** Read it before adding any
> outbound call, any third-party asset, or any new `app.*` setting that names a host.
>
> - **The promise**
> - **Runtime egress from the app container** — The update check, OIDC
> - **The browser makes no external request** — Why that holds, and what enforces it
> - **Deploy-time and build-time** — Not runtime, but asked about
> - **Known leaks and accepted gaps**
> - **How to run with zero egress**
> - **Rules for adding anything new**

## The promise

**A default Diurnal deployment makes no outbound network connection of any kind, and the browser loads nothing from
any host but the one serving the app.** Both halves of that are deliberate, and both are enforceable rather than
conventional — the browser half by a CSP the app sends on every response, the server half by there being exactly two
call sites in the whole codebase, both behind a flag that is off.

That promise is the reason the update check is **opt-in**. It was on by default in earlier releases, and making it
opt-in is a **breaking change** — an operator who upgrades and wants the footer indicator back must now ask for it.
Whenever the next release is cut, that is what makes it a major version, and it needs calling out in the notes
rather than burying.

## Runtime egress from the app container

There are **exactly two** outbound call sites in `src/main/java`. Both are off in a default deployment. Nothing else
in the application opens a socket to anywhere but the database.

### 1. The GitHub release check — OFF by default

| Aspect | Detail |
| ------ | ------ |
| **Endpoint** | `https://api.github.com/repos/{owner}/{repo}/releases`, derived from `app.repository.url` (`APP_REPOSITORY_URL`), so a fork or internal mirror can point it at itself. A non-GitHub URL disables the check outright — `UpdateCheck.githubReleasesApi` returns empty and no lookup is made, logged once at **WARN** so the silence is explained rather than mysterious |
| **Call site** | `update/GitHubLatestReleaseClient.java`, invoked from `UpdateCheckService.onStartup` |
| **When** | Once per boot, on a virtual thread, never refreshed |
| **Flag** | `APP_UPDATE_CHECK_ENABLED` (default `false`), timeout `APP_UPDATE_CHECK_TIMEOUT` (default `PT3S`) |
| **Sent** | An anonymous `GET`. `Accept: application/vnd.github+json`, `User-Agent: diurnal-update-check`. No body, no token, no identifier of the deployment or its users |
| **Received** | The releases JSON, from which one `tag_name` is read |

**What the remote end learns when it is enabled:** the deployment's source IP address, that the host runs Diurnal
(the `User-Agent` says so explicitly), and — because the call is once per boot — its restart cadence. For a
self-hoster that source IP is typically a residential one, and it is stable enough across restarts to correlate.

**Why it is now opt-in.** The check is useful: a self-hosted app silently running a version with a fixed
vulnerability is a real harm, and the footer arrow is the only update channel Diurnal has. But it was on for every
deployment, and the lookup fires **whether or not anybody ever looks at the footer** — the *indicator* is
admin-gated (`isAdmin && updateAvailable` in `partials/footer.html`), the *call* is not. A single-user instance
whose owner never opens an admin page was still announcing itself to a third party on every boot. That is not a
decision the software should make for an operator who chose to self-host.

> **The trade made.** Most deployments will now never learn about a release. That is the accepted cost of not
> making the choice on an operator's behalf.

### 2. OIDC — OFF by default

Only when `OIDC_ENABLED=true`. The host is whatever the operator sets as `OIDC_ISSUER_URL`, and in the common
self-hosted case (Authelia, Keycloak, LLDAP) it is on the same LAN — so enabling OIDC is usually not internet
egress at all. Two distinct calls:

- **The startup discovery probe** — `AppLifecycle.verifyOidcDiscovery()`. Fetches `{issuer}/.well-known/openid-configuration`,
  three attempts with back-off, and **fails the boot** when the provider does not answer as a healthy one. Gated
  additionally by `OIDC_VERIFY_ON_STARTUP` (default `true`). It exists so a misconfigured issuer fails the
  deployment rather than the first user's login, which is when Quarkus would otherwise discover it.
- **The sign-in flow itself** — owned by the Quarkus OIDC extension: discovery, the token endpoint, the UserInfo
  endpoint (`user-info-required=true`, because Authelia and others return the email there rather than in the ID
  token), token refresh, and RP-initiated logout at `OIDC_LOGOUT_URL`.

When disabled, `quarkus.oidc.tenant-enabled=false` and the issuer URL defaults to the placeholder
`http://disabled.invalid` — `.invalid` is an RFC 2606 reserved TLD, so it can never resolve to a real host.

> **Unverified:** whether a disabled tenant emits a DNS query for `disabled.invalid` to the local resolver before
> giving up. `application.properties` asserts Quarkus skips discovery entirely when the tenant is disabled, and the
> log filters for `OidcCommonUtils` suggest the eager probe only happens for an *enabled* tenant. Nothing routable
> is contacted either way, but if this is ever confirmed to emit a lookup, say so here.

## The browser makes no external request

**This is the strongest guarantee in the application and the one most worth stating to a security-conscious user.**
A page served by Diurnal fetches every byte it needs from the app's own origin.

- **The CSP enforces it, rather than merely describing it.** `auth/security/CspPolicy.java` sends
  `default-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; object-src 'none'` on every response,
  with only two `sha256` hashes allowed for the inline FOUC-prevention script and style. A CDN request added by
  accident is *blocked by the browser*, not silently made.
- **Fonts are self-hosted.** All of them, in `META-INF/resources/fonts/` — the Nova family, OpenDyslexic, and the
  Noto Arabic/JP subsets generated by `scripts/generate-noto-fonts.py`. No Google Fonts, no `fonts.gstatic.com`.
- **htmx is vendored**, copied out of `node_modules` into `META-INF/resources/js/` at build time by
  `scripts/vendor-assets.cjs`. No unpkg, no jsdelivr, no `cdn.tailwindcss.com` (Tailwind is compiled and purged at
  build time, which is what `frontend/tailwind.config.js` exists to do).
- **Swagger UI is bundled by Quarkus, and its phone-home is switched off.** `quarkus.swagger-ui.validator-url=none`
  suppresses the spec "validator badge", which by default renders an `<img>` from the public `validator.swagger.io`
  with the spec URL attached. Note the docs paths get a *relaxed* CSP (`'unsafe-inline' 'unsafe-eval'`) — still
  `'self'`-scoped for network, but it is the one place the strict policy does not apply.
- **Every `fetch`/`XMLHttpRequest` in the served scripts is same-origin**, built through `Diurnal.url(...)`.
  `AppPathsAreCentralisedTest` requires that helper for every path, but note its purpose is `BASE_PATH`
  correctness, not origin: it would not catch an absolute third-party URL. **The CSP's `connect-src 'self'` is
  what actually enforces same-origin here** — the test only keeps the paths tidy.
- **A note cannot become a tracking vector.** Note content is escaped plain text — no Markdown, no HTML, no
  embedded image URLs — so one user cannot cause another's browser (or the server) to fetch anything.
- **Every outbound link carries `rel="noopener noreferrer"`**, so clicking the footer's repository/release links
  sends GitHub no `Referer` at all — not even the bare origin that `Referrer-Policy: strict-origin-when-cross-origin`
  would otherwise allow. Keep `noreferrer` on any new external link; `noopener` alone is not enough.
- `robots.txt` and the `<meta name="robots">` in `layout.html` disallow indexing; the meta tag exists because a
  `BASE_PATH` sub-path deployment never gets its `robots.txt` read.

**There is no telemetry, no analytics, no crash or error reporting, no Gravatar or remote avatar, no outbound mail,
no password-breach lookup** (password4j is configured local-only in `psw4j.properties`), **and no metrics or
management endpoint exposed.**

## Deploy-time and build-time

Not runtime egress, but the first thing a careful operator asks about, so it belongs in the same inventory.

- **Deploying** pulls `zodac/diurnal` and `postgres` from Docker Hub. Unavoidable for a container deployment;
  an operator wanting an air-gapped install mirrors both images.
- **Building from source** additionally reaches Maven Central (including `net.zodac:parent-pom`), the npm registry,
  the `code-quality-config` git submodule, Playwright's browser download, and Docker Hub for the several base
  images in the `Dockerfile`.
- **Maintainer-only, never part of a deployment:** `.github/scripts/update_dependency_versions.sh` (Docker Hub,
  the GitHub API, Adoptium, npm, Maven Central) and the Crowdin translation workflow.

## Known leaks and accepted gaps

Documented so a report does not need to be written, and so a future change knows they are known.

- **There is no single "offline mode" switch.** Zero egress is the default now, but it is the *sum* of two
  independent flags being off rather than one guarantee an operator can assert.
- **The committed `docker-compose.yml` is the maintainer's own deployment file** and binds to a literal LAN address
  by default. `docs/docker-compose.example.yml` is the one an operator should copy.
- **`README.md` hotlinks its screenshots** from the repository's release assets. That affects someone *reading* the
  README on a mirror, not anyone deploying.

## How to run with zero egress

Out of the box, nothing needs doing — **both flags are off by default.** To assert it rather than trust it, an
operator can remove the app's egress network entirely:

```yaml
networks:
  diurnal-internal:
    driver: bridge
    internal: true          # no egress, for the app AND the database

services:
  diurnal:
    networks:
      - diurnal-internal    # the only network; nothing can leave
```

Both compose files currently attach the app to an egress network with the comment *"egress for OIDC discovery + the
GitHub update-check"*. With the update check off and OIDC unused, that network has nothing left to carry. The
database is already correctly isolated on an `internal: true` network in both files.

> **Enabling OIDC re-introduces egress**, and the app must then reach the issuer. If the IdP is on the LAN, a
> second internal network to the IdP is enough; only a public IdP needs real egress.

## Rules for adding anything new

> **Any new outbound call must be off by default, flagged, and added to this document in the same change.** There
> are two call sites today and that is a number worth keeping small. A third one that ships on by default undoes
> the promise at the top of this file for every existing deployment at once.

> **Never add a browser-side asset from a third-party host** — no CDN script, no remote font, no external image.
> The CSP will block it, so the failure mode is a broken page rather than a quiet leak, but the fix is always to
> vendor the asset (see `scripts/vendor-assets.cjs`) rather than to widen the policy.

> **A new `app.*` setting that names a host belongs here**, whether or not the app calls it — an operator reading
> this file should not have to grep `application.properties` to find out what might phone home.
