# Life Tracker

A personal habit-tracking web app. Log actions against a calendar, then query stats like streaks and frequency.

Trying to use AI to build this application, with guidance as needed. Expect several iterations.

## Stack

| Layer     | Technology                                                              |
|-----------|-------------------------------------------------------------------------|
| Backend   | Quarkus 3 (Java 21), RESTEasy Reactive, Hibernate ORM Panache, Flyway   |
| UI        | Qute (server-side templates), HTMX, FullCalendar.js, Tailwind CSS (CDN) |
| Auth      | Form-based sessions (web UI); JWT Bearer (REST API / future OIDC)       |
| Database  | PostgreSQL                                                              |
| Packaging | Single Docker image — everything in one Quarkus JAR                     |

> **Note on templating:** Quarkus uses **Qute
** as its native template engine. It is conceptually identical to Thymeleaf (server-side HTML rendering with Java variables), with a slightly different syntax (
`{variable}` instead of
`th:text`). HTMX handles dynamic partial updates so there is no need for a JavaScript framework. FullCalendar.js is loaded from CDN for the calendar widget (Phase 3).

## Development setup

Prerequisites: Java 21, Maven 3.9+, Docker.

```bash
# 1. Generate JWT signing keys (required before first start)
./scripts/generate-jwt-keys.sh

# 2. Start the dev database
docker compose -f docker-compose.dev.yml up -d

# 3. Start the API with hot reload
cd api && mvn quarkus:dev

# App:     http://localhost:8080
# Swagger: http://localhost:8080/q/swagger-ui
```

No separate frontend build step — templates are rendered server-side by Quarkus, CSS/JS loaded from CDN. Quarkus hot-reloads both Java classes and Qute templates on save.

## Production (Docker Compose)

```bash
# 1. Create and edit the environment file
cp .env.example .env
$EDITOR .env          # set DB_PASSWORD and SESSION_ENCRYPTION_KEY at minimum

# 2. Generate production JWT keys (stored in secrets/, git ignored)
./scripts/generate-jwt-keys.sh --prod

# 3. Build and start
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

| Variable      | Default                  | Description                                       |
|---------------|--------------------------|---------------------------------------------------|
| `DB_HOST`     | `localhost`              | PostgreSQL hostname (`db` inside Docker Compose). |
| `DB_PORT`     | `5432`                   | PostgreSQL port.                                  |
| `DB_NAME`     | `lifetracker`            | Database name.                                    |
| `DB_USER`     | `lifetracker`            | Database user.                                    |
| `DB_PASSWORD` | `lifetracker` (dev only) | Database password.                                |

### Session

| Variable                 | Default (dev)                    | Description                                                                           |
|--------------------------|----------------------------------|---------------------------------------------------------------------------------------|
| `SESSION_ENCRYPTION_KEY` | `devkey_change_for_production!!` | AES key for the encrypted session cookie. Use a random 32+ char string in production. |

### JWT signing keys (REST API / future OIDC)

Keys are RSA-2048 PEM files. In dev, they are loaded from classpath (`api/src/main/resources/jwt-keys/`). In production, they are mounted from
`secrets/` as read-only volumes.

| Variable                   | Default (dev)          | Description                                      |
|----------------------------|------------------------|--------------------------------------------------|
| `JWT_PUBLIC_KEY_LOCATION`  | `jwt-keys/public.pem`  | Path to PEM public key for token verification.   |
| `JWT_PRIVATE_KEY_LOCATION` | `jwt-keys/private.pem` | Path to PKCS8 PEM private key for token signing. |

Generate with `./scripts/generate-jwt-keys.sh` (dev) or `./scripts/generate-jwt-keys.sh --prod` (production).

### Application

| Variable   | Default | Description                           |
|------------|---------|---------------------------------------|
| `APP_PORT` | `8080`  | Host port the application listens on. |

### OIDC (optional — Phase 5)

OIDC is disabled by default. When enabled, the app connects to the provider's discovery endpoint at startup and validates Bearer tokens issued by that
provider alongside form-session auth.

| Variable             | Default        | Description                                                                                                       |
|----------------------|----------------|-------------------------------------------------------------------------------------------------------------------|
| `OIDC_ENABLED`       | `false`        | Set to `true` to activate OIDC. The three variables below must also be provided.                                  |
| `OIDC_ISSUER_URL`    | _(none)_       | Base URL of the OIDC provider (e.g. `https://auth.example.com`). Must expose `/.well-known/openid-configuration`. |
| `OIDC_CLIENT_ID`     | `life-tracker` | Client ID registered with the OIDC provider.                                                                      |
| `OIDC_CLIENT_SECRET` | _(none)_       | Client secret for the registered client.                                                                          |

**Authelia example** — add to your Authelia `configuration.yml`:

```yaml
identity_providers:
  oidc:
    clients:
      - id: life-tracker
        secret: '<bcrypt hash of your OIDC_CLIENT_SECRET>'
        authorization_policy: one_factor
        redirect_uris:
          - https://life-tracker.example.com/oidc-callback
        scopes: [ openid, profile, email ]
        response_types: [ code ]
        grant_types: [ authorization_code ]
```

## Project phases

- **Phase 1** ✅ — Scaffold, DB schema, form auth (password), Qute UI, Docker/Compose
- **Phase 2** — Actions CRUD (user-definable actions with name and colour)
- **Phase 3** — Calendar view with per-day action logging (FullCalendar + HTMX)
- **Phase 4** — Stats page (streaks, frequency, last performed)
- **Phase 5** — OIDC login via Authelia
