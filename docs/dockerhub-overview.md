<!--
    Source for the "Repository overview" field on https://hub.docker.com/r/zodac/diurnal - paste this file's
    contents into that box when it needs updating.

    Every image and link here is an ABSOLUTE URL on purpose. Docker Hub does not resolve relative paths
    against the GitHub repository, so the README's `docs/screenshots/...` form renders as a broken image
    there. The raw.githubusercontent.com URLs serve the correct content types (`image/svg+xml` for the
    wordmark, `image/webp` for the screenshots), so both embed correctly.

    Pure Markdown image syntax, no inline HTML: Docker Hub sanitises raw tags out of this field, so the
    README's `<p align="center">` wrappers and `width=` attributes would be dropped or shown as text.
-->
<!-- markdownlint-disable MD041 -- the wordmark leads the page in place of a heading; Docker Hub prints the repository name above it -->

![Diurnal - Make every day count](https://raw.githubusercontent.com/zodac/diurnal/master/assets/wordmark-readme.svg)

<!-- markdownlint-enable MD041 -->

Diurnal is a small, self-hosted web application for tracking daily habits. You define actions (the things you want to do or avoid each day) and log
them as you go. Diurnal keeps a running calendar of everything you've logged and turns that history into meaningful statistics: current and longest
streaks, weekly averages, month-over-month trends, and more.

![The Diurnal dashboard in both light and dark themes](https://raw.githubusercontent.com/zodac/diurnal/master/docs/screenshots/dashboard-system.webp)

## Features

- **Actions and daily logging** - define the habits you want to track, and log them day by day
- **Notes** - a private note for any date, past or future, encrypted at rest under a per-account key
- **Calendar views** - three calendar styles over everything you have logged
- **Statistics and streaks** - current and longest streaks, weekly averages and month-over-month trends
- **Themes and fonts** - light and dark themes (or follow the system setting), and three font choices
- **Languages** - a choice of languages with translated text and locale-aware dates, including right-to-left ones such as Arabic

![The Stats page, showing per-action statistic tiles](https://raw.githubusercontent.com/zodac/diurnal/master/docs/screenshots/stats-dark.webp)

## Quick start

Diurnal runs with Docker Compose alongside a PostgreSQL container. Download the example Compose file:

```bash
curl -o docker-compose.yml https://raw.githubusercontent.com/zodac/diurnal/master/docs/docker-compose.example.yml
```

Edit it and fill in the values marked **TODO**, then start it:

```bash
docker compose up -d
```

Diurnal will be available at <http://localhost:8080> and will walk you through creating the initial admin account.

The example file pins an explicit image tag rather than `:latest`, so upgrading is something you choose - bump the tag when you want the newer
release, and check the versioning notes first if it is a MAJOR one.

## Documentation

Full documentation, including every environment variable, backups, the REST API and the reverse-proxy setup, lives in the GitHub repository:

- **Source and documentation**: <https://github.com/zodac/diurnal>
- **Environment variables**: <https://github.com/zodac/diurnal#environment-variables>
- **Versioning policy**: <https://github.com/zodac/diurnal#versioning>
- **Licence**: [0BSD](https://github.com/zodac/diurnal/blob/master/LICENSE)
