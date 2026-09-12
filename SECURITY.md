# Security Policy

## Supported Versions

Diurnal is developed on a single release line. Security fixes are made against the latest release; there are no long-term support branches.

| Version        | Supported          |
|----------------|--------------------|
| Latest release | Yes                |
| Anything older | No - please update |

## Reporting a Vulnerability

**Please do not open a public issue for a security problem.**

Report it privately through GitHub's [private vulnerability reporting](https://github.com/zodac/diurnal/security/advisories/new) on this repository.
That opens a draft advisory visible only to you and the maintainer.

Helpful things to include, as far as you have them:

- The version you are running (the footer shows it, as does `GET /api/v1/status`)
- Whether the deployment is behind a reverse proxy or Cloudflare, and whether `TRUST_X_FORWARDED_HEADERS` and `TRUST_CLOUDFLARE_HEADER`
  are on - between them they decide which client IP the per-IP auth lockout counts against
- Which sign-in methods are enabled (`PASSWORD_AUTH_ENABLED`, `OIDC_ENABLED`)
- The steps to reproduce, and what an attacker gains

Please give a reasonable window for a fix before disclosing publicly. This is a personal project maintained in spare time, so an initial reply may
take a few days.

## Scope

In scope: anything reachable from the application itself - authentication and session handling, the note encryption, the public `/api/v1` surface,
the `/internal` endpoints, the admin console, the import/export, and the shipped container image.

Out of scope:

- Findings that require an operator to have already misconfigured the deployment in a way the README warns against - notably
  `TRUST_X_FORWARDED_HEADERS=true` on a container exposed directly to the internet, or `TRUST_CLOUDFLARE_HEADER=true` on an origin that is
  reachable other than through Cloudflare. Either lets a client spoof its own IP address, and so choose the key the auth lockout counts
  against. The application warns about the second one in its own log, once, on the first request that arrives without the header.
- Missing `Strict-Transport-Security`. Diurnal serves plaintext HTTP by design and expects a TLS-terminating reverse proxy to own every TLS concern,
  HSTS included.
- The version string on the unauthenticated `GET /api/v1/status` probe. It is there so a health check and a bug report can both name a version.

## Design Limits (Not Vulnerabilities)

These are deliberate properties of the design, documented so a report does not need to be written:

- **Notes are encrypted at rest, not end-to-end.** Content is sealed under a per-account data key, which is itself wrapped by
  `NOTE_ENCRYPTION_KEY`. That key lives in the application's configuration, not the database, so a stolen dump, backup or replica opens nothing. It
  does **not** hide notes from whoever runs the server - they hold the key. Diurnal is not, and does not claim to be, zero-knowledge.
- **An administrator is trusted.** The first account is an administrator and can manage other accounts. There is no privilege boundary that protects
  a user's data from the person operating the deployment.
- **Losing `NOTE_ENCRYPTION_KEY` is unrecoverable**, by design. See the Backups section of the [README](README.md).
