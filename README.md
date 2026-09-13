<!-- markdownlint-disable MD033 MD041 -- centered wordmark banner: intentional inline HTML in place of a text H1 -->
<p align="center">
  <img src="assets/wordmark-readme.svg" alt="Diurnal - Make every day count" width="380">
</p>
<!-- markdownlint-enable MD033 MD041 -->

> *[diurnal](https://www.dictionary.com/browse/diurnal), / daɪˈɜr nl /, adjective*
>
> "of or relating to a day or each day; daily"

## Table of contents

- [Introduction](#introduction)
- [Features](#features)
    - [Actions and Daily Logging](#actions-and-daily-logging)
    - [Notes](#notes)
    - [Calendar Views](#calendar-views)
    - [Statistics and Streaks](#statistics-and-streaks)
    - [Themes and Fonts](#themes-and-fonts)
    - [Languages](#languages)
        - [Contributing a Translation](#contributing-a-translation)
- [Quick Start](#quick-start)
- [Environment Variables](#environment-variables)
    - [Required](#required)
    - [Database](#database)
    - [Application](#application)
    - [Note Configuration](#note-configuration)
        - [Rotating the Key](#rotating-the-key)
    - [Authentication](#authentication)
        - [Password Sign-in](#password-sign-in)
        - [Password Hashing](#password-hashing)
        - [OIDC](#oidc)
        - [Login Throttling](#login-throttling)
        - [Sessions](#sessions)
    - [Reverse Proxy](#reverse-proxy)
- [Performance Tuning](#performance-tuning)
    - [Application Memory](#application-memory)
    - [Password Hashing Cost](#password-hashing-cost)
    - [PostgreSQL](#postgresql)
- [Backups](#backups)
    - [Backing Up the Database](#backing-up-the-database)
    - [Import/Export](#importexport)
- [User Settings](#user-settings)
    - [Account](#account)
    - [Preferences](#preferences)
    - [Statistics](#statistics)
    - [Appearance](#appearance)
    - [Note Preferences](#note-preferences)
- [Text Input](#text-input)
    - [Length Limits](#length-limits)
    - [Accepted Characters](#accepted-characters)
    - [Rejected Characters](#rejected-characters)
    - [Emoji](#emoji)
- [Administrator Users](#administrator-users)
- [REST API](#rest-api)
- [Versioning](#versioning)
- [Contributing](#contributing)
- [License](#license)

## Introduction

Diurnal is a small, self-hosted web application for tracking daily habits. You define actions (the things you want to do or avoid each day) and log
them as you go. Diurnal keeps a running calendar of everything you've logged and turns that history into meaningful statistics: current and longest
streaks, weekly averages, month-over-month trends, and more.

<!-- markdownlint-disable MD013 MD033 -- centered dashboard screenshots: intentional inline HTML -->
<p align="center">
  <img src="https://github.com/zodac/diurnal/releases/download/screenshots/dashboard-system.webp" alt="The Diurnal dashboard in both light and dark themes" width="600">
  &emsp;&emsp;
  <img src="https://github.com/zodac/diurnal/releases/download/screenshots/dashboard-mobile.webp" alt="The Diurnal dashboard on a phone, in both light and dark themes" width="170">
</p>
<!-- markdownlint-enable MD013 MD033 -->

## Features

- **User-defined actions**: Define any habit/activities you want to track, each with its own name and colour
- **Daily logging**: Log the occurrences of an action for a day
- **Notes**: Write a free-text note or journal entry for a day
- **Calendar views**: Your whole history on a calendar, with a choice of different styles
- **Statistics**: Streaks, totals, averages and trends per action
- **Mobile view**: Styled for both web browser and mobile usage
- **OIDC**: Can be integrated with an external identity provider (Authelia, Keycloak, etc.)

### Actions and Daily Logging

An action is anything you want to track, with its own name and colour. From the dashboard you can increment an action for a day, add ten at a time,
set an exact count, or erase the day entirely.

<details>
<summary>Screenshot: the Actions page</summary>

<img src="https://github.com/zodac/diurnal/releases/download/screenshots/actions-dark.webp" alt="The Actions page, listing tracked habits" width="600">

</details>

### Notes

Alongside the daily log, each day can carry a note, a free-text entry of up to 10,000 characters by default - whoever runs your Diurnal can set a
different limit with [`NOTE_MAX_LENGTH`](#note-configuration). Unlike logging an action, a note can be written for any date, including ones in the
future. The **Notes** page lists everything you have written, (most recent first) with the ability to search your notes.

Notes are encrypted at rest, so a database dump, backup or replica carries only sealed text - see [Note Configuration](#note-configuration).

<!-- markdownlint-disable MD013 MD033 -- centered note-box screenshot: intentional inline HTML -->
<p align="center">
  <img src="https://github.com/zodac/diurnal/releases/download/screenshots/note-box-dark.webp" alt="The note box on the dashboard, holding a written note" width="420">
</p>
<!-- markdownlint-enable MD013 MD033 -->

<details>
<summary>Screenshot: notes as a statistics subject</summary>

<!-- markdownlint-disable MD013 MD033 -- notes statistics screenshot: intentional inline HTML -->
<img src="https://github.com/zodac/diurnal/releases/download/screenshots/stats-notes-dark.webp" alt="The Notes card on the Stats page, showing streaks, gaps and totals" width="320">
<!-- markdownlint-enable MD013 MD033 -->

</details>

### Calendar Views

The dashboard calendar can be drawn in one of three styles, chosen per user in [Settings](#appearance):

- **Full**: a cell-based calendar, with event text per action
- **Minimal**: a coloured dot per action
- **Stacked**: horizontal bars per action

|                                                                  Full                                                                   |                                                                    Minimal                                                                    |                                                                    Stacked                                                                    |
|:---------------------------------------------------------------------------------------------------------------------------------------:|:---------------------------------------------------------------------------------------------------------------------------------------------:|:---------------------------------------------------------------------------------------------------------------------------------------------:|
| <img src="https://github.com/zodac/diurnal/releases/download/screenshots/cal-full-dark.webp" alt="The full calendar style" width="280"> | <img src="https://github.com/zodac/diurnal/releases/download/screenshots/cal-minimal-dark.webp" alt="The minimal calendar style" width="280"> | <img src="https://github.com/zodac/diurnal/releases/download/screenshots/cal-stacked-dark.webp" alt="The stacked calendar style" width="280"> |

### Statistics and Streaks

Every action gets a full set of statistics, including

- Current streak
- Longest streak
- Biggest gap
- Total count
- Weekly average
- Last performed
- Best month / best year
- Comparisons to last month / last year
- And more...

These can be enabled/disabled, renamed, or re-ordered in user settings (see [Statistics](#statistics) below).

Each subject also has a frequency graph, opened from the chart icon on its card: a bar per day over a month, or a bar per month over a year, with the
exact figures on hover. Up to three subjects can be charted together.

|                                                                           Stats page                                                                            |                                                                                Frequency graph                                                                                 |
|:---------------------------------------------------------------------------------------------------------------------------------------------------------------:|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|
| <img src="https://github.com/zodac/diurnal/releases/download/screenshots/stats-dark.webp" alt="The Stats page, showing per-action statistic tiles" width="400"> | <img src="https://github.com/zodac/diurnal/releases/download/screenshots/stats-graph-dark.webp" alt="The frequency graph, comparing three actions over one month" width="400"> |

### Themes and Fonts

Diurnal ships light and dark themes (or follow the system setting), and three font choices.

|                                                                    Dark                                                                     |                                                                     Light                                                                     |
|:-------------------------------------------------------------------------------------------------------------------------------------------:|:---------------------------------------------------------------------------------------------------------------------------------------------:|
| <img src="https://github.com/zodac/diurnal/releases/download/screenshots/dashboard-dark.webp" alt="The dashboard in dark mode" width="400"> | <img src="https://github.com/zodac/diurnal/releases/download/screenshots/dashboard-light.webp" alt="The dashboard in light mode" width="400"> |

### Languages

Diurnal can be used in a choice of languages, each with its own translated text, correctly-formatted dates and numbers. Before you sign in, the app
picks a language from your browser; once signed in, you can instead choose your own from [Settings](#preferences). Right-to-left languages, like
Arabic, mirror the layout to match.

|                                                                                                Language picker                                                                                                |                                                                            Right-to-left (Arabic)                                                                             |
|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|
| <img src="https://github.com/zodac/diurnal/releases/download/screenshots/language-dropdown-dark.webp" alt="The Settings language picker, open, showing the offered languages by their own name" height="256"> | <img src="https://github.com/zodac/diurnal/releases/download/screenshots/dashboard-arabic-dark.webp" alt="The dashboard shown in Arabic, mirrored right-to-left" width="400"> |

#### Contributing a Translation

Diurnal's translations are managed on [Crowdin](https://crowdin.com/project/diurnal), not in this repository - Crowdin opens a pull request here
automatically once strings are translated. [CONTRIBUTING.md](CONTRIBUTING.md) covers the other ways to contribute.

## Quick Start

Diurnal is distributed as a Docker image ([`zodac/diurnal`](https://hub.docker.com/r/zodac/diurnal)) and is intended to be run with Docker Compose
alongside a PostgreSQL container. Quick start is below:

**1. Configure the Docker Compose file:**

Download [`docker-compose.example.yml`](docs/docker-compose.example.yml) from this repository and save it as `docker-compose.yml`:

```bash
curl -o docker-compose.yml https://raw.githubusercontent.com/zodac/diurnal/master/docs/docker-compose.example.yml
```

Edit `docker-compose.yml` and update the values marked as **TODO**. Other settings are documented in [Environment Variables](#environment-variables)
below.

The example pins an explicit image tag rather than `:latest`, so upgrading is something you choose: bump the tag when you want the newer release, and
check [Versioning](#versioning) first if it is a MAJOR one.

**2. Start the application:**

```bash
docker compose up -d
```

Diurnal will be available at **<http://localhost:8080>** and will walk you through creating the initial admin account. This first account becomes the
**administrator**. Even if using `OIDC_ENABLED`, this account is created locally. It may later be linked to your OIDC provider, though I would suggest
keeping it as a super-user in case of any IdP issues.

## Environment Variables

Diurnal is configured entirely through environment variables on the `diurnal` container.

### Required

| Variable              | Description                                                                      |
|-----------------------|----------------------------------------------------------------------------------|
| `DB_PASSWORD`         | PostgreSQL password (must match the password on the database container)          |
| `NOTE_ENCRYPTION_KEY` | The key notes are encrypted with (see [Note Configuration](#note-configuration)) |

### Database

| Variable  | Default        | Description                       |
|-----------|----------------|-----------------------------------|
| `DB_HOST` | `diurnal-db`   | Hostname of the PostgreSQL server |
| `DB_PORT` | `5432`         | PostgreSQL port                   |
| `DB_NAME` | `diurnal_db`   | Database name                     |
| `DB_USER` | `diurnal_user` | Database user                     |

The Compose files also tune PostgreSQL itself; those knobs live in [Performance Tuning](#performance-tuning).

### Application

| Variable                   | Default | Description                                                                                                 |
|----------------------------|---------|-------------------------------------------------------------------------------------------------------------|
| `APP_UPDATE_CHECK_ENABLED` | `true`  | Check GitHub once at startup for a newer release                                                            |
| `APP_UPDATE_CHECK_TIMEOUT` | `PT3S`  | How long that one lookup may take before it is abandoned                                                    |
| `DB_LOG_LEVEL`             | `WARN`  | Set to `TRACE` to log every SQL statement + bound parameters (verbose; may expose parameter values)         |
| `EXPORT_CSV_BOM`           | `true`  | Lead each exported CSV with a UTF-8 byte-order mark (Excel-friendly); `false` for plain UTF-8 (LibreOffice) |
| `LOG_LEVEL`                | `INFO`  | One of `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `FATAL`, `OFF`                                            |
| `MAX_CONCURRENT_IMPORTS`   | `2`     | How many data imports may run at once; further ones get a `429`. `0` removes the bound                      |
| `MAX_REQUEST_BODY`         | `1M`    | Largest body accepted on every endpoint *except* data import; `0` removes the cap                           |
| `MAX_UPLOAD_SIZE`          | `64M`   | Hard ceiling on any request body, in binary units (`100M`, `512K`, `1G`); used by import/export             |
| `TZ`                       | `UTC`   | IANA timezone (e.g. `Europe/London`) used for day boundaries                                                |

### Note Configuration

Your [notes](#notes) are encrypted. Each account gets its own randomly-generated key when it is created, and every note is sealed under that key. The
account keys are themselves stored only in encrypted form, protected by `NOTE_ENCRYPTION_KEY`.

| Variable                        | Default | Description                                                                       |
|---------------------------------|---------|-----------------------------------------------------------------------------------|
| `NOTE_ENCRYPTION_PREVIOUS_KEYS` |         | Comma-separated retired keys, set only while [rotating](#rotating-the-key)        |
| `NOTE_MAX_LENGTH`               | `10000` | The longest note a user may save, in characters. Must be between `1` and `100000` |

#### Rotating the Key

If you choose to rotate your key, generate a new one, and put the new key in `NOTE_ENCRYPTION_KEY`. Then copy the old one into
`NOTE_ENCRYPTION_PREVIOUS_KEYS`, and restart:

```yaml
environment:
  NOTE_ENCRYPTION_KEY: <the new key>
  NOTE_ENCRYPTION_PREVIOUS_KEYS: <the old key>
```

On start, every account key that no longer opens under the new key is re-encrypted with it (no notes are rewritten). Once this has run, clear
`NOTE_ENCRYPTION_PREVIOUS_KEYS` and restart again. For `NOTE_MAX_LENGTH`, lowering it does not touch notes you have already written. Such a note
simply cannot be *saved* again until you shorten it.

### Authentication

Diurnal supports two sign-in methods (local password accounts and **[OIDC](#oidc)** accounts) which can run separately or together; at least one must
be enabled or the app refuses to start. Regardless of how it is configured, the first account is always created locally through the setup page.

<details>
<summary>Screenshot: the login page</summary>

Shown with both sign-in methods enabled.

<img src="https://github.com/zodac/diurnal/releases/download/screenshots/login-dark.webp" alt="The Diurnal login page" width="600">

</details>

#### Password Sign-in

| Variable                       | Default | Description                                                                                                  |
|--------------------------------|---------|--------------------------------------------------------------------------------------------------------------|
| `ENABLE_LOCAL_REGISTRATION`    | `true`  | Set to `false` to close the `/register` page and `POST /api/v1/auth/register` (local accounts only)          |
| `PASSWORD_AUTH_ENABLED`        | `true`  | Set to `false` to disable password login entirely (requires OIDC to be enabled)                              |
| `PASSWORD_AUTH_UNIFORM_TIMING` | `true`  | Keep login response time constant whether or not the email exists, so accounts can't be enumerated by timing |

Please note that if `PASSWORD_AUTH_ENABLED` is changed to **false**, previously password-only accounts will be converted to OIDC accounts upon login.
If both `PASSWORD_AUTH_ENABLED` and `OIDC_ENABLED` are **true**, this auto-conversion is not done. Users must explicitly link to an OIDC account in
the user settings page.

#### Password Hashing

Passwords are stored as [Argon2id](https://en.wikipedia.org/wiki/Argon2) hashes, at OWASP's recommended cost. They are tuned together with the
container's memory budget in [Performance Tuning](#performance-tuning).

#### OIDC

OIDC is disabled by default. When enabled, users can sign in through your identity provider alongside (or instead of) password login. Register
`{your-base-url}/oauth2/callback/oidc` as the redirect URI with your IdP (including any `BASE_PATH` prefix, e.g.
`https://example.com/diurnal/oauth2/callback/oidc`).

| Variable                 | Default                  | Description                                                           |
|--------------------------|--------------------------|-----------------------------------------------------------------------|
| `OIDC_ADMIN_GROUP`       |                          | IdP group whose members are granted the `Administrator` role          |
| `OIDC_AUTO_REDIRECT`     | `false`                  | If `true`, `/login` redirects straight to the provider                |
| `OIDC_CLIENT_ID`         | `diurnal`                | Client ID registered with the provider                                |
| `OIDC_CLIENT_SECRET`     |                          | Client secret for the registered client                               |
| `OIDC_ENABLED`           | `false`                  | Set to `true` to activate OIDC                                        |
| `OIDC_ISSUER_URL`        |                          | Base URL of the OIDC provider (e.g. `https://auth.example.com`)       |
| `OIDC_LOGOUT_URL`        |                          | OIDC users are redirected here after logging out                      |
| `OIDC_PKCE_ENABLED`      | `true`                   | PKCE on the code flow; disable only if the provider rejects it        |
| `OIDC_PROVIDER_NAME`     | `your identity provider` | Name shown on the login button ("Log in with your identity provider") |
| `OIDC_SCOPES`            | `email,groups,profile`   | Extra scopes requested with `openid` (use `email,profile` for Google) |
| `OIDC_USER_GROUP`        |                          | IdP group whose members are granted the `User` role                   |
| `OIDC_VERIFY_ON_STARTUP` | `true`                   | Probe the IdP at startup and refuse to boot if it is unreachable      |

<!-- markdownlint-disable MD033 -- collapsible example: intentional <strong> inside <summary> -->
<details>
<summary><strong>Authelia example</strong></summary>

Add a client to your Authelia `configuration.yml`:

```yaml
identity_providers:
  oidc:
    authorization_policies:
      diurnal_auth_policy:
        default_policy: 'deny'
        rules:
          - policy: 'one_factor'
            subject:
              - [ "group:diurnal_admins" ]
              - [ "group:diurnal_users" ]
    claims_policies:
      diurnal_claim_policy:
        id_token: [
          'alt_emails',
          'email',
          'email_verified',
          'groups',
          'name',
          'preferred_username'
        ]
    clients:
      - client_name: Diurnal OIDC Client
        client_id: 'Diurnal'
        client_secret: '<hash of OIDC_CLIENT_SECRET>'
        authorization_policy: 'diurnal_auth_policy'
        claims_policy: 'diurnal_claim_policy'
        jwks_uri: 'https://auth.example.com/jwks.json'
        public: 'false'
        grant_types:
          - 'authorization_code'
        redirect_uris:
          - 'https://diurnal.example.com/oauth2/callback/oidc'
        response_types:
          - 'code'
        scopes:
          - 'email'
          - 'groups'
          - 'openid'
          - 'profile'
        access_token_signed_response_alg: 'none'
        userinfo_signed_response_alg: 'none'
        token_endpoint_auth_method: 'client_secret_post'
        introspection_endpoint_auth_method: 'client_secret_post'
```

</details>
<!-- markdownlint-enable MD033 -->

#### Login Throttling

Failed login and registration attempts are rate-limited per client IP address. Once an IP exceeds `AUTH_IP_THROTTLE_MAX_ATTEMPTS` failures within
`AUTH_IP_THROTTLE_LOCKOUT_DURATION` window, it is locked out of **both** logging in and registering. The client IP is read from either
`CF-Connecting-IP` or from the connection (depending on the value of `TRUST_CLOUDFLARE_HEADER` and/or `TRUST_X_FORWARDED_HEADERS`).

| Variable                            | Default | Description                                  |
|-------------------------------------|---------|----------------------------------------------|
| `AUTH_IP_THROTTLE_CLEANUP_INTERVAL` | `PT1H`  | How often decayed counters are forgotten     |
| `AUTH_IP_THROTTLE_ENABLED`          | `true`  | Set to `false` to disable throttling         |
| `AUTH_IP_THROTTLE_LOCKOUT_DURATION` | `PT15M` | How long an IP stays locked                  |
| `AUTH_IP_THROTTLE_MAX_ATTEMPTS`     | `15`    | Failures from one IP before it is locked out |

#### Sessions

Both the web UI and the REST API authenticate against a **server-side session store** (the `sessions` table). Logging in mints a random opaque token,
delivered as the `diurnal_session` cookie (web) or a Bearer token (API); only its hash is stored, and every session is **revocable**. Logging out,
changing your password (which signs out every *other* device), or "Log out from everywhere" in Settings all delete session rows. No keys or secrets to
manage.

A session ends at whichever comes first: `SESSION_IDLE_TIMEOUT` since it was last used, or `SESSION_ABSOLUTE_LIFETIME` since it was created. Both are
[ISO-8601](https://en.wikipedia.org/wiki/ISO_8601#Durations) durations (e.g. `P30D` = 30 days, `P7D` = 7 days, `PT12H` = 12 hours).

| Variable                    | Default | Description                                                       |
|-----------------------------|---------|-------------------------------------------------------------------|
| `SESSION_ABSOLUTE_LIFETIME` | `P90D`  | Hard cap on a session's age regardless of activity                |
| `SESSION_CLEANUP_INTERVAL`  | `PT1H`  | How often expired sessions are swept from the database            |
| `SESSION_IDLE_TIMEOUT`      | `P30D`  | Sliding idle timeout; a session dies this long after its last use |

### Reverse Proxy

Diurnal serves plaintext HTTP and is designed to run behind a TLS-terminating reverse proxy. The proxy should handle everything TLS-related: the
certificate, any HTTP→HTTPS redirect, and the `Strict-Transport-Security` (HSTS) header.

| Variable                    | Default | Description                                                                           |
|-----------------------------|---------|---------------------------------------------------------------------------------------|
| `BASE_PATH`                 |         | URL prefix the app is served under (if unset the application is served at `/`)        |
| `CORS_ALLOWED_ORIGINS`      |         | Comma-separated list of origins allowed to call the API from a browser (unset = none) |
| `TRUST_CLOUDFLARE_HEADER`   | `false` | Trust `CF-Connecting-IP` as the client IP (only behind Cloudflare)                    |
| `TRUST_X_FORWARDED_HEADERS` | `false` | Trust `X-Forwarded-*` headers from the reverse proxy                                  |

## Performance Tuning

Diurnal runs comfortably on a small machine, and the defaults below are sized for a personal deployment of a handful of users. In general, scaling
should only be needed if there are a large number of concurrent users (total user count doesn't have much of an impact). There is also some tuning
possible for the password hashing, seen below.

### Application Memory

The container's memory budget is set on the `diurnal` service in the Compose file itself, rather than through the environment:

```yaml
deploy:
  resources:
    limits:
      memory: "2G"
memswap_limit: "2G"
```

The JVM sizes its heap at 65% of whatever that limit is; the remaining 35% is metaspace, the code cache, thread stacks and the collector's own
structures. If no limit is set at all, the entrypoint caps the heap at `1330m` (the same heap a `2G` limit produces) and says so in the log.

| Variable             | Default | Description                                                                                                     |
|----------------------|---------|-----------------------------------------------------------------------------------------------------------------|
| `JDK_JAVA_OPTIONS`   |         | Standard JDK variable; a heap flag (for example `-Xms256m -Xmx1g`) replaces the docker ENTRYPOINT configuration |
| `WORKER_MAX_THREADS` | `32`    | Concurrent blocking requests                                                                                    |

### Password Hashing Cost

Passwords are stored as Argon2id hashes. The three cost parameters below are
[OWASP's primary recommendation](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html) and take roughly **54 ms** per
login on a modern desktop CPU. For resource-constrained hardware, you may need to tune these values.

| Variable                           | Default | Description                                                                      |
|------------------------------------|---------|----------------------------------------------------------------------------------|
| `PASSWORD_HASH_ARGON2_ITERATIONS`  | `2`     | Number of passes over that memory                                                |
| `PASSWORD_HASH_ARGON2_MEMORY_KIB`  | `19456` | Memory cost in KiB (19 MiB). The main defence against GPU/ASIC cracking          |
| `PASSWORD_HASH_ARGON2_PARALLELISM` | `1`     | Number of lanes; cuts latency at the cost of occupying that many cores per login |

Increasing any value is safe to do at any time (each account is re-hashed on next login).

### PostgreSQL

The bundled Compose files start PostgreSQL with a tuned configuration rather than the stock defaults, which are sized for a much smaller machine. The
values below are the only ones that depend on your machine. The defaults are safe from roughly 1GB of RAM, but on a larger host, you can raise them.

These are `command:` flags on the `diurnal-db` service, edited in the Compose file directly rather than through the environment:

```yaml
command:
  - -cshared_buffers=256MB
  - -cwork_mem=8MB
```

| Setting                | Default | Description                                                                               |
|------------------------|---------|-------------------------------------------------------------------------------------------|
| `effective_cache_size` | `768MB` | What the planner assumes is cached overall. Around 50-75% of RAM; reserves nothing        |
| `maintenance_work_mem` | `128MB` | Working memory for `VACUUM` and index builds                                              |
| `max_connections`      | `25`    | Not a user limit - Diurnal's pool maxes out at 10. It is what makes `work_mem` safe       |
| `random_page_cost`     | `1.1`   | Planner's cost for a random read. `1.1` assumes SSD/NVMe; set to `4` for a spinning disk  |
| `shared_buffers`       | `256MB` | PostgreSQL's own page cache. Around 25% of the host's RAM                                 |
| `work_mem`             | `8MB`   | Per-sort working memory. Applies per sort node, so raise it alongside `max_connections`   |

## Backups

An application backup must make sure to cover two things:

1. The database (every account, action, log and note)
2. `NOTE_ENCRYPTION_KEY` (the only way to read the notes)

> **Losing `NOTE_ENCRYPTION_KEY` loses every note, permanently.** There is no second copy and no recovery - not from the database, not from a backup,
> not by an administrator. Keep it wherever you keep `DB_PASSWORD`, and make sure it survives a container rebuild.

### Backing Up the Database

`pg_dump` in the custom format, which `pg_restore` can verify and stops on error (a plain SQL dump does not):

```bash
docker compose exec -T diurnal-db pg_dump -U diurnal_user -d diurnal_db -Fc > diurnal-backup.dump
```

Restoring replaces every table in the database with the contents of the dump:

```bash
docker compose exec -T diurnal-db pg_restore -U diurnal_user -d diurnal_db --clean --if-exists --exit-on-error < diurnal-backup.dump
```

The repository wraps both commands in [`scripts/db-backup.sh`](scripts/db-backup.sh) and
[`scripts/db-restore.sh`](scripts/db-restore.sh), which add the checks the raw commands leave to you - that the database container is actually
running, that the dump file exists, and a confirmation prompt before a restore overwrites your data (`--yes` skips it):

```bash
scripts/db-backup.sh                       # writes backup_diurnal_<UTC timestamp>.dump
scripts/db-restore.sh diurnal-backup.dump  # prompts before replacing every table
```

### Import/Export

Separately from the whole-instance backup above, each user can export their own actions, logs and notes from **Settings → Data**, and import them
back. That export holds note content **in the clear**, so treat the file as you would the notes themselves. It is the right tool for moving one
account between deployments; it is not a substitute for a database backup.

## User Settings

Each user can customise Diurnal from the **Settings** page (top-right menu).

<details>
<summary>Screenshot: the Settings page</summary>

<img src="https://github.com/zodac/diurnal/releases/download/screenshots/settings-dark.webp" alt="The Settings page" width="600">

</details>

### Account

- **Email**: Your login identity (cannot be changed)
- **Display name**: The name shown in the app
- **Password**: Change your password if it's a local account (changing your password logs the account out of every other device)
- **Identity provider**: Shown when [OIDC](#oidc) is configured. Links to the IdP or allows a user to connect a password-only account
- **Sessions**: **Log out everywhere** revokes every session forcing a fresh sign-in on all devices (including the current device)

### Preferences

- **Timezone**: The timezone used to decide what "today" is, so day boundaries line up with a user's local time
- **Language**: The user's [language](#languages)
- **Week starts on**: The day of the dashboard calendar's first column. **Automatic** follows the [language](#languages)
- **Statistics summary**: Whether to show the selected day's top actions on the dashboard
- **Decimal places**: Precision of the averages and abbreviated totals shown on the Statistics page and dashboard summary
- **Items per page**: Page size for lists, like actions, day panel, stats, etc. (`1`-`100`, default `5`)

### Statistics

An orderable list which allows the user to choose which [statistics](#statistics-and-streaks) appear for each action on the Stats page. The **Last
performed** statistic is always shown, but can still be renamed/reordered.

### Appearance

| Setting            | Options                                                        |
|--------------------|----------------------------------------------------------------|
| **Theme**          | System, Light, Dark                                            |
| **Calendar style** | Full, Minimal, Stacked (see [Calendar views](#calendar-views)) |
| **Font**           | Nova, Standard, OpenDyslexic                                   |

### Note Preferences

- **Note colour**: The colour days with a note are marked in on the calendar, and the colour of the Notes statistics. Any colour - picked, randomised,
  or reset to the default
- **Character count**: Whether the note box on the dashboard shows a note's length against its limit - `1,234 / 10,000`, for example. Turning it off
  does not change the limit, and the count still appears if a note goes over it - otherwise there would be nothing explaining why **Save** is
  unavailable

## Text Input

Every free-text value you type - an action name, your display name, a renamed statistic, a day's note, your email, your password - goes through the
same validation below.

### Length Limits

Limits are counted in **characters as a reader counts them**, not bytes: an accented letter, a Chinese character and an emoji each count as one.

| Field          | Limit                                                                   |
|----------------|-------------------------------------------------------------------------|
| Action name    | 1-100 characters                                                        |
| Display name   | 2-50 characters                                                         |
| Email          | 3-254 characters, and must contain an `@`                               |
| Note           | Up to 10,000 characters by default* (leave it blank to remove the note) |
| Password       | 1-128 characters                                                        |
| Statistic name | Up to 25 characters (leave it blank to restore the built-in name)       |

### Accepted Characters

Text in **any language or script** is accepted - Latin, Cyrillic, Greek, Arabic, Hebrew, Chinese, Japanese, Korean, Thai, Devanagari and the rest,
including right-to-left text, accents and combining marks. Punctuation, symbols, currency signs and mathematical characters are all fine, as are
characters that merely resemble others (a Cyrillic `а` in an otherwise Latin word is accepted, not policed).

Decorative and stylised text is accepted too - upside-down text (`˙ɐnbᴉlɐ`), the mathematical alphabets (`𝕿𝖍𝖊`, `𝕋𝕙𝕖`), small capitals (`ᴛʜᴇ`),
enclosed letters (`🅃🄷🄴`), full-width forms (`ＡＢＣ`), runes and Unicode digits (`١٢٣`). They are all real characters that display correctly; they simply
count towards the length limits like any other.

Characters that look like code - `<script>`, `'; DROP TABLE`, `{7*7}`, `%s`, `../../` - are treated as **ordinary text**. They are stored exactly as
typed and displayed exactly as typed; they are never executed, and they are never rewritten on the way in so that what you get back is what you
entered.

### Rejected Characters

A value is rejected, with a message, if it contains:

- **Invisible characters** - zero-width spaces, the byte-order mark, soft hyphens, word joiners, tag characters, unpaired surrogates and private-use
  characters. These render as nothing, which means two different names could look completely identical on screen (`admin` and `ad<zero-width>min`) -
  so one could be used to impersonate the other, and the duplicate-name check could not catch it. The two zero-width **joiners** are the exception,
  because they are real spelling rather than a trick - they hold multipart emoji together, and are mandatory in Persian, Urdu and Pashto - so they are
  accepted between two characters, and rejected anywhere they are joining nothing.
- **Characters that are invisible despite being letters** - the Hangul fillers, the Khmer inherent vowels and the blank Braille pattern. These are the
  characters behind the "blank name" trick, and a name made only of them would appear completely empty.
- **Unicode noncharacters** (U+FDD0-U+FDEF and the last two code points of each plane), which are permanently reserved and display as a fallback box.
- **Text-direction characters** - the bidirectional overrides, isolates and marks. These reverse the text that follows them, so a name could be made
  to display as something other than what it actually is.
- **More than eight stacked combining marks** on a single character (the "zalgo" effect), which renders as a column of glyphs that overflows the row it
  is shown in. Ordinary accented text, and scripts that legitimately stack marks, are unaffected.

A value made up **entirely of spaces or invisible whitespace** is rejected as empty, rather than being stored as a name that cannot be seen.

### Emoji

Emoji are **fully supported** in action names, display names and statistic names - `Gym 💪` is a perfectly good action name. They are stored and
displayed exactly as entered, including multi-person emoji, skin-tone variants, flags and keycaps.

One thing worth knowing: an emoji made of several joined parts counts as **more than one character** against the limits above. A family emoji
(👩‍👩‍👧‍👦), for example, is built from four people joined together and counts as seven characters. Simple emoji count as one each.

## Administrator Users

The first account to register is an **administrator**. Administrators get two extra sections:

- **Admin → Users**: View and manage user accounts (delete user or edit role)
- **API**: The Swagger UI for the REST API, useful for scripting or integrating with other tools

<details>
<summary>Screenshot: the admin user-management page</summary>

<img src="https://github.com/zodac/diurnal/releases/download/screenshots/admin-dark.webp" alt="The admin user-management page" width="600">

</details>

## REST API

Diurnal exposes a versioned public REST API at **`/api/v1`** for building integrations and mobile apps. Administrators can open the Swagger UI from
the **API** link in the navbar. Authenticate by exchanging credentials for a session token, then send it as a Bearer header:

```bash
TOKEN=$(curl -s -X POST https://diurnal.example.com/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"correct horse battery staple"}' | jq -r .token)

curl -s https://diurnal.example.com/api/v1/actions -H "Authorization: Bearer ${TOKEN}"
```

## Versioning

This project follows [Semantic Versioning](https://semver.org/) (`MAJOR.MINOR.PATCH`). Generally, if a user must change something it's a **MAJOR**
update, if they *can* use something new it's a **MINOR**, else it's a **PATCH**.

- **MAJOR**: A change that breaks an existing deployment or integration on upgrade.
    - Database migration that cannot be applied to an existing database
    - Incompatible changes to the public REST API (`/api/v1/*`)
    - Removed or renamed configuration options / environment variables
    - Removal of a user-facing feature that existing users actively rely on
- **MINOR**: Backwards-compatible new functionality.
    - Additive database migrations
    - New REST endpoints or fields, new configuration options (with safe defaults)
    - New settings, calendar views, links, or pages
    - Major visual/styling updates, like new branding, re-theming the application, etc.
- **PATCH**: Backwards-compatible fixes and internal changes.
    - Bug fixes
    - Codebase refactoring
    - Dependency bumps
    - Minor visual/styling updates and behaviours, like better resizing for mobile views, etc.

## Contributing

Bug reports and feature requests go to the [issue tracker](https://github.com/zodac/diurnal/issues); [CONTRIBUTING.md](CONTRIBUTING.md) covers
building locally, the quality gate and the commit-message format.

Found a security problem? Please **do not** open an issue - follow [SECURITY.md](SECURITY.md) and report it privately.

## License

Diurnal is released under the [BSD Zero Clause License](LICENSE).
