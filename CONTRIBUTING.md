# Contributing to Diurnal

Thanks for your interest in Diurnal! This document covers everything you need to build, run, and test the application locally. For a
high-level overview of what Diurnal is and how to deploy it, see the [README](README.md).

> **Code style & architecture:** detailed conventions and architecture notes for contributors live in
> [`.claude/CLAUDE.md`](.claude/CLAUDE.md). Read it before writing or editing code.

## Tech stack

| Layer     | Technology                                                            |
|-----------|-----------------------------------------------------------------------|
| Backend   | Quarkus 3 (Java 21), RESTEasy Reactive, Hibernate ORM Panache, Flyway |
| UI        | Qute (server-side templates), HTMX, Tailwind CSS (compiled)           |
| Auth      | Form-based sessions (web UI); JWT Bearer (REST API); optional OIDC    |
| Database  | PostgreSQL                                                            |
| Packaging | Single hardened Docker image (distroless, jlink JRE, non-root)        |

> **On templating:** Quarkus uses **Qute** as its native template engine — conceptually like Thymeleaf (server-side HTML with Java
> variables) but with `{variable}` syntax. HTMX handles dynamic partial updates, so there is no JavaScript framework. The dashboard
> calendar (all three styles) is a small hand-rolled vanilla-JS month grid — there is no calendar library.

## Prerequisites

- JDK 26
- Maven (or use the project's `mvnw` if present)
- Node.js + npm (for the Tailwind CSS build)
- Docker + Docker Compose v2 (for the dev database and image builds)

> Always use `docker compose` (the v2 plugin), never `docker-compose` (hyphenated).

## First-time setup

```bash
# Fetch the code-quality-config submodule (required for -Dlint / -Dall)
git submodule update --init

# Install Node dependencies (required for Maven to build the CSS)
npm install
```

## Running in dev mode

```bash
# 1. Start the dev PostgreSQL container
docker compose -f docker-compose.dev.yml up -d diurnal-db-dev

# 2. (Optional) hot-reload the CSS in a separate terminal
npm run css:watch

# 3. Run Quarkus in dev mode (hot reload, Swagger UI at /api, port 8081)
mvn quarkus:dev
```

Dev mode expects PostgreSQL on `localhost:5432` with database `diurnal_db`, user `diurnal_user`, and password `diurnal_password`.
Flyway migrations run automatically. Dev data is ephemeral (wiped on container recreate).

There are helper scripts for the full loop: `scripts/dev-up.sh` and `scripts/dev-teardown.sh`.

> **Always tear down when finished:** `pkill -f "quarkus:dev"`, then `docker compose -f docker-compose.dev.yml down`.

### Port map

| Port   | Used by                                                      |
|--------|--------------------------------------------------------------|
| `8080` | Production                                                   |
| `8081` | Dev mode, `@QuarkusTest`, and the E2E jar (not simultaneous) |
| `8082` | The deployment-smoke stack (isolated, coexists with prod)    |

## CSS build

Tailwind is compiled (not loaded from a CDN). The committed source is `src/main/css/app.css`; it is built into
`src/main/resources/META-INF/resources/css/app.css` (a git-ignored build artifact).

```bash
npm run css          # one-off build
npm run css:watch    # rebuild on change (pair with quarkus:dev)
```

Every Maven build regenerates the CSS via an `exec-maven-plugin` execution, so `package` / `*IT` / E2E always bundle a fresh
stylesheet — this needs `node_modules`, hence the one-time `npm install`. Rebuild after any class change in templates or Java, or
Tailwind will purge the class.

## Building

```bash
# Build the JAR (no tests by default)
mvn package

# Full Docker build + run
cp .env.example .env   # fill in DB_PASSWORD and SESSION_ENCRYPTION_KEY
docker compose up -d --build
docker compose logs -f app
```

## Quality gates

The build has opt-in quality profiles:

```bash
# Linters only (ErrorProne+NullAway, Checkstyle, PMD, SpotBugs, Javadoc, Enforcer, PITest, …)
mvn clean install -Dlint

# Unit tests only (no DB needed)
mvn test -Dtests
mvn test -Dtests -Dtest=MyTestClass

# Everything: unit + *IT + E2E + deployment-smoke + full linters
# Prerequisite: cd e2e && npx playwright install
mvn clean install -Dall
```

**All linters currently pass clean** (Checkstyle/PMD/SpotBugs = 0, PITest strength = 100%). Please keep them that way: code must be
NullAway-annotated (JSpecify `@Nullable`), every public/package method and type carries Javadoc, locals/params are `final`, and
unit-test assertions carry messages.

## Testing

Diurnal has a four-tier test pyramid:

1. **Unit tests** (`*Test`) — surefire, no database. `mvn test -Dtests`.
2. **Integration tests** (`*IT`) — extend `IntegrationTestBase`, run against a managed PostgreSQL. Time is frozen to a fixed "today" for determinism.
3. **E2E tests** — Playwright, in `e2e/`.

   ```bash
   cd e2e && npm test                                  # against :8080
   cd e2e && BASE_URL=http://localhost:8081 npm test   # against dev / -Dall port
   ```

4. **Deployment-smoke** — the only tier that runs the **actual production Docker image** (distroless, jlink JRE, non-root).
   Self-contained: builds the image, runs an isolated app+DB stack on `:8082`, runs the smoke specs, and tears it all down.

   ```bash
   bash e2e/run-smoke.sh 8082 "$(pwd)"
   ```

All four tiers run under `mvn clean install -Dall`.

## Database migrations

Flyway scripts live in `src/main/resources/db/migration/`, numbered sequentially (`V1__` … `Vn__`). **Never edit an applied
migration** — always add the next `V{n+1}__` script.

## Project layout

Application code is under `src/main/java/net/zodac/diurnal/`, split by domain:

| Package  | Contents                                                               |
|----------|------------------------------------------------------------------------|
| `action` | `Action` entity + CRUD web resource for user-defined habits            |
| `log`    | `ActionLog` entity, per-day increment/decrement, and the calendar feed |
| `stats`  | Streak / frequency / trend calculation and its template extensions     |
| `auth`   | Register/login → JWT, token service, identity providers                |
| `user`   | `User` entity, user settings, `/api/users/me`                          |
| `web`    | Top-level page routes (dashboard, login, settings, etc.)               |

See [`.claude/CLAUDE.md`](.claude/CLAUDE.md) for the full architecture reference, including the HTMX partial-response conventions,
the record/`*Extensions` split, the CSS colour-token system, and the settings-preview regeneration workflow.

## License

By contributing, you agree that your contributions will be licensed under the project's [BSD Zero Clause License](LICENSE).
