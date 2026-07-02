<!-- markdownlint-disable MD033 MD041 -- centered wordmark banner: intentional inline HTML in place of a text H1 -->
<p align="center">
  <img src="scripts/assets/wordmark-readme.svg" alt="Diurnal — Make every day count" width="380">
</p>
<!-- markdownlint-enable MD033 MD041 -->

> *[diurnal](https://www.dictionary.com/browse/diurnal), / daɪˈɜr nl /, adjective*
>
> "of or relating to a day or each day; daily."

A personal habit-tracking web app. Log actions against a calendar, then query stats like streaks and frequency.

Trying to use AI to build this application, with guidance as needed. Expect several iterations.

## Stack

| Layer     | Technology                                                              |
|-----------|-------------------------------------------------------------------------|
| Backend   | Quarkus 3 (Java 21), RESTEasy Reactive, Hibernate ORM Panache, Flyway   |
| UI        | Qute (server-side templates), HTMX, Tailwind CSS (CDN)                  |
| Auth      | Form-based sessions (web UI); JWT Bearer (REST API / future OIDC)       |
| Database  | PostgreSQL                                                              |
| Packaging | Single Docker image — everything in one Quarkus JAR                     |

> **Note on templating:** Quarkus uses **Qute** as its native template engine. It is conceptually identical to Thymeleaf (server-side HTML rendering
> with Java variables), with a slightly different syntax (`{variable}` instead of`th:text`).
> HTMX handles dynamic partial updates so there is no need for a JavaScript framework. The dashboard
> calendar (all styles) is a small hand-rolled vanilla-JS month grid — no calendar library.

## Deployment

```bash
# 1. Create and edit the environment file
cp .env.example .env
$EDITOR .env          # set DB_PASSWORD and SESSION_ENCRYPTION_KEY at minimum

# 2. Build and start. JWT signing keys are generated into secrets/ on first
#    start if absent — no manual step required.
docker-compose up -d --build

# View logs
docker-compose logs -f app
```

The application is served at `http://localhost:${APP_PORT}` (default 8080).

## Environment variables

### Required in production

| Variable                 | Description                                                                            |
|--------------------------|----------------------------------------------------------------------------------------|
| `DB_PASSWORD`            | PostgreSQL password. No default — compose will refuse to start without it.             |
| `SESSION_ENCRYPTION_KEY` | Encrypts the session cookie. Must be at least 16 characters. No default in production. |

### Database

| Variable      | Default              | Description                                       |
|---------------|----------------------|---------------------------------------------------|
| `DB_HOST`     | `localhost`          | PostgreSQL hostname (`db` inside Docker Compose). |
| `DB_PORT`     | `5432`               | PostgreSQL port.                                  |
| `DB_NAME`     | `diurnal`            | Database name.                                    |
| `DB_USER`     | `diurnal`            | Database user.                                    |
| `DB_PASSWORD` | `diurnal` (dev only) | Database password.                                |

### Session

| Variable                 | Default (dev)                    | Description                                                                           |
|--------------------------|----------------------------------|---------------------------------------------------------------------------------------|
| `SESSION_ENCRYPTION_KEY` | `devkey_change_for_production!!` | AES key for the encrypted session cookie. Use a random 32+ char string in production. |

### JWT signing keys (REST API / future OIDC)

Keys are RSA-2048 PEM files. In the production environment they live in `secrets/` (mounted at `/run/secrets/`) and are
**generated automatically on first start** if absent, then reused on every subsequent start. In the dev environment they are loaded from the
classpath (`src/main/resources/jwt-keys/`).

| Variable                   | Default (dev)          | Description                                      |
|----------------------------|------------------------|--------------------------------------------------|
| `JWT_PUBLIC_KEY_LOCATION`  | `jwt-keys/public.pem`  | Path to PEM public key for token verification.   |
| `JWT_PRIVATE_KEY_LOCATION` | `jwt-keys/private.pem` | Path to PKCS8 PEM private key for token signing. |

No manual step is required — the app provisions the keypair on first start (see `JwtKeyProvisioner`) and reuses it thereafter. To use a specific
key (e.g. to share one across multiple replicas), drop your own `private.pem` / `public.pem` into `secrets/` before starting.

### Application

| Variable   | Default | Description                           |
|------------|---------|---------------------------------------|
| `APP_PORT` | `8080`  | Host port the application listens on. |

### OIDC (optional — Phase 5)

OIDC is disabled by default. When enabled, the app connects to the provider's discovery endpoint at startup and validates Bearer tokens issued by that
provider alongside form-session auth.

| Variable             | Default   | Description                                                                      |
|----------------------|-----------|----------------------------------------------------------------------------------|
| `OIDC_ENABLED`       | `false`   | Set to `true` to activate OIDC. The three variables below must also be provided. |
| `OIDC_ISSUER_URL`    |           | Base URL of the OIDC provider (e.g. `https://auth.example.com`).                 |
| `OIDC_CLIENT_ID`     | `diurnal` | Client ID registered with the OIDC provider.                                     |
| `OIDC_CLIENT_SECRET` |           | Client secret for the registered client.                                         |

**Authelia example** — add to your Authelia `configuration.yml`:

```yaml
identity_providers:
  oidc:
    clients:
      - id: diurnal
        secret: '<bcrypt hash of your OIDC_CLIENT_SECRET>'
        authorization_policy: one_factor
        redirect_uris:
          - https://diurnal.example.com/oidc-callback
        scopes: [ openid, profile, email ]
        response_types: [ code ]
        grant_types: [ authorization_code ]
```

## Calendar month cache (frontend tuning)

The dashboard calendar (minimal/stacked styles) fetches each month's activity dots from `/logs/minimal-events`
once and caches them client-side, so navigating between months reads from memory instead of making a round-trip
each time. This matters most behind a reverse proxy / CDN, where every request carries the edge latency. Two
constants at the top of the calendar script in `src/main/resources/templates/dashboard.html` tune the behaviour:

| Constant          | Default | Description                                                                                         |
|-------------------|---------|-----------------------------------------------------------------------------------------------------|
| `PREFETCH_RADIUS` | `2`     | Months either side of the visible one to warm in the background (on idle) so prev/next is instant.  |
| `CACHE_LIMIT`     | `12`    | Maximum number of resolved months retained in memory; the oldest are evicted (least-recently-used). |

> Keep `CACHE_LIMIT` comfortably above the live window of `2 * PREFETCH_RADIUS + 1` months (5 at the defaults),
> otherwise hopping between adjacent months can evict a month that's still on screen and force a needless refetch.
> Raise `PREFETCH_RADIUS` for smoother multi-month jumps at the cost of more idle background requests; raise
> `CACHE_LIMIT` to retain more history at the cost of memory.
