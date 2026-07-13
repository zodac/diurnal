# Sandbox image for running Claude Code against the `diurnal` project with
# --dangerously-skip-permissions, fully isolated from the host.
#
# The runtime is a plain Debian 13 (trixie) base. The JDK 26, Maven and Node
# toolchains are pulled from their official images as multi-stage build stages
# and the relevant directories copied straight in — so we track upstream image
# tags (maintained by .github/scripts/update_dependency_versions.sh) instead of
# hand-rolling tarball / NodeSource downloads. On top we also add: a NESTED
# Docker engine (so the project's dev DB / Testcontainers / Playwright all run
# inside this throwaway container, never touching the host daemon) and the
# system libraries Playwright's Chromium needs.
#
# The nested Docker engine is the one toolchain NOT copied from an image: the
# binaries in the official `docker`/`docker:dind` images are musl-linked (Alpine),
# so they can't be dropped onto this glibc Debian base — Docker's own Debian APT
# repo is the portable path, so it stays a normal apt install (see below).

# ── Toolchain source stages (their payload is copied into the Debian base) ────
# Each of these is only mined for its self-contained toolchain directory; none of
# their base OS reaches the final image.
#   JDK 26  — self-contained under /opt/java/openjdk, portable onto glibc Debian.
#   Maven   — pure Java + shell under /usr/share/maven; the image's own JDK is unused.
#   Node 26 — installed under /usr/local; the prebuilt binaries are glibc-portable
#             (the -trixie tag matches this base's glibc exactly).
FROM eclipse-temurin:26.0.1_8-jdk AS jdk
FROM maven:3.9.16-eclipse-temurin-26 AS maven
FROM node:26.5.0-trixie AS node

# ── Runtime base ─────────────────────────────────────────────────────────────
FROM debian:13.5

ARG UID=1000
ARG GID=1000

ENV DEBIAN_FRONTEND=noninteractive \
    JAVA_HOME=/opt/java/openjdk \
    MAVEN_HOME=/opt/maven \
    PATH=/opt/java/openjdk/bin:/opt/maven/bin:$PATH

# pipefail for every RUN in this stage so a failure in the middle of a `getent … | cut` pipe (or any
# other) aborts the layer instead of being masked by the pipe's last command (debian base has bash).
SHELL ["/bin/bash", "-o", "pipefail", "-c"]

# ── Base tooling + brand-script tooling ──────────────────────────────────────
# The brand pipeline (`npm run brand`) needs these:
#   - python3 + python3-fonttools  → scripts/generate-brand.py (reads NovaFlat-Book.ttf and renders
#                                    the wordmark.svg / favicon.svg / footer-mark.svg "d" glyphs)
#   - imagemagick + librsvg2-bin + optipng → scripts/generate-favicons.cjs (rasterises favicon.svg
#                                    into favicon.ico / favicon-16/32.png / apple-touch-icon.png)
# fonttools is installed as the distro `python3-fonttools` package, NOT via pip: the base image's
# Python is PEP-668 "externally managed", so `pip install fonttools` is refused.
#
# lsof + iproute2 (`ss`) back scripts/dev-teardown.sh, which frees the dev/test port 8081 by finding
# and killing whatever LISTENs on it (the forked quarkus:dev JVM, or a stale -Dall E2E jar). Without
# them the teardown silently no-ops and then falsely reports the port free, so a leftover process can
# poison the next `mvn -Dall` IT phase with "Port already bound: 8081".
#
# fontconfig + tzdata back the copied-in Temurin JDK: the temurin base image normally supplies these
# (the JDK itself only bundles freetype), so on this bare Debian base we add them explicitly — tzdata
# so `app.timezone` / TZ date boundaries resolve, fontconfig for the reflective java.desktop paths.
#
# curl + gnupg + ca-certificates also bootstrap the Docker APT repo configured further down.
#
# Chromium's own shared-library deps are NOT listed here (the package names drift
# across Debian releases); install them once inside the sandbox with the
# version-proof `npx playwright install --with-deps chromium` (see README).

# BEGIN DEBIAN PACKAGES
RUN apt-get update && apt-get install -yqq --no-install-recommends \
        bash="5.2.37-2+b9" \
        ca-certificates="20250419" \
        curl="8.14.1-2+deb13u4" \
        fontconfig="2.15.0-2.3" \
        git="1:2.47.3-0+deb13u1" \
        gnupg="2.4.7-21+deb13u1" \
        imagemagick="8:7.1.1.43+dfsg1-1+deb13u11" \
        iproute2="6.15.0-1" \
        jq="1.7.1-6+deb13u2" \
        librsvg2-bin="2.60.0+dfsg-1" \
        lsof="4.99.4+dfsg-2" \
        optipng="0.7.8+ds-1+b1" \
        python3="3.13.5-1" \
        python3-fonttools="4.57.0-1+deb13u1" \
        python3-pip="25.1.1+dfsg-1" \
        python3-venv="3.13.5-1" \
        sudo="1.9.16p2-3+deb13u2" \
        tzdata="2026b-0+deb13u1" \
        unzip="6.0-29" \
    && \
    apt-get autoremove && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*
# END DEBIAN PACKAGES

# ── JDK 26 / Maven / Node (copied from the toolchain stages above) ───────────
# The three toolchains are self-contained directory trees; drop them into the
# glibc base at the paths JAVA_HOME / MAVEN_HOME / PATH already point at.
COPY --from=jdk   /opt/java/openjdk /opt/java/openjdk
COPY --from=maven /usr/share/maven  /opt/maven
COPY --from=node  /usr/local        /usr/local

# ── Docker engine + compose plugin (the nested daemon) ───────────────────────
# The base is pinned to Debian, so the repo distro is hardcoded as `debian`; only the
# release codename is derived (from /etc/os-release, via sed rather than a sourced shell
# var) so a base point/major bump follows automatically.
RUN install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc \
    && chmod a+r /etc/apt/keyrings/docker.asc \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(sed -n 's/^VERSION_CODENAME=//p' /etc/os-release) stable" \
         > /etc/apt/sources.list.d/docker.list

# BEGIN DEBIAN PACKAGES
RUN apt-get update && apt-get install -yqq --no-install-recommends \
        docker-ce="5:29.6.1-1~debian.13~trixie" \
        docker-ce-cli="5:29.6.1-1~debian.13~trixie" \
        containerd.io="2.2.6-1~debian.13~trixie" \
        docker-buildx-plugin="0.35.0-1~debian.13~trixie" \
        docker-compose-plugin="5.3.1-1~debian.13~trixie" \
    && \
    apt-get autoremove && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*
# END DEBIAN PACKAGES

# ── Non-root user `dev` matching the host UID/GID, with passwordless sudo ────
# The Debian base ships no user at ${UID}/${GID}, so this normally just creates
# `dev`. The getent guards keep it robust if a base ever does pre-populate the
# UID/GID: rename whatever owns them to `dev` and point its home at /home/dev.
RUN set -eux; \
    if ! getent group "${GID}" >/dev/null; then groupadd -g "${GID}" dev; \
    else groupmod -n dev "$(getent group ${GID} | cut -d: -f1)" || true; fi; \
    if getent passwd "${UID}" >/dev/null; then \
      usermod -l dev -d /home/dev -m -s /bin/bash "$(getent passwd ${UID} | cut -d: -f1)"; \
    else \
      useradd -l -m -u "${UID}" -g "${GID}" -s /bin/bash dev; \
    fi; \
    usermod -aG docker dev; \
    echo 'dev ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/dev; \
    chmod 0440 /etc/sudoers.d/dev

# ── Claude Code CLI into a dev-owned npm prefix (so auto-update works) ────────
# Installing `-g` into npm's default /usr prefix makes the package and the
# /usr/bin/claude launcher root-owned, so the unprivileged `dev` user can't
# self-update — /doctor reports "npm global folder isn't writable". Instead,
# point npm's global prefix at a dev-owned dir (persisted in the image layer,
# baked via .npmrc so it needs no runtime env) and install AS dev, so updates
# write without sudo. The prefix's bin dir goes on PATH for both build & runtime.
# runuser (util-linux) drops to `dev` for the install without invoking sudo (hadolint DL3004); like
# `sudo -H` it resets HOME to /home/dev, so npm writes its config to the dev-owned ~/.npmrc.
ENV PATH=/home/dev/.npm-global/bin:$PATH
RUN install -d -o dev -g dev /home/dev/.npm-global \
    && runuser -u dev -- npm config set prefix /home/dev/.npm-global \
    && runuser -u dev -- npm install -g @anthropic-ai/claude-code

# Let Playwright manage its own Chromium (cached under $HOME, persisted via volume).
ENV PLAYWRIGHT_BROWSERS_PATH=/home/dev/.cache/ms-playwright

# Keep ALL of Claude Code's config in the persisted ~/.claude volume. By default Claude
# splits its state: the ~/.claude/ directory (history, OAuth credentials) AND a separate
# ~/.claude.json file in the home root (onboarding/terminal-setup state + login). Only the
# directory is volume-mounted, so without this the .claude.json file is recreated empty on
# every run — forcing terminal reconfiguration + re-login each time. CLAUDE_CONFIG_DIR
# redirects .claude.json (and credentials) INTO the mounted dir, so it all persists.
# (entrypoint.sh already forwards this var to the `dev` user via --preserve-env.)
ENV CLAUDE_CONFIG_DIR=/home/dev/.claude

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
COPY launch.sh     /usr/local/bin/sandbox-launch.sh
COPY setup.sh      /usr/local/bin/sandbox-setup.sh
RUN chmod +x /usr/local/bin/entrypoint.sh \
             /usr/local/bin/sandbox-launch.sh \
             /usr/local/bin/sandbox-setup.sh

WORKDIR /work
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
