# Sandbox image for running Claude Code against the `diurnal` project with
# --dangerously-skip-permissions, fully isolated from the host.
#
# Base gives us a guaranteed JDK 26 (Alpine does not package JDK 26 reliably).
# On top we add: a NESTED Docker engine (so the project's dev DB / Testcontainers /
# Playwright all run inside this throwaway container, never touching the host daemon),
# Maven 3.9, Node 26, and the system libraries Playwright's Chromium needs.
# Pinned to the Noble (Ubuntu 24.04) variant: Playwright's `--with-deps` installer
# supports 24.04 but NOT the rolling latest (26.04 "resolute"), and the default tag
# tracks the latter.
FROM eclipse-temurin:26_35-jdk-noble

ARG UID=1000
ARG GID=1000
# TODO: Use muti-stage docker images instead, and update the update script
ARG MAVEN_VERSION=3.9.16
ARG NODE_MAJOR=26

ENV DEBIAN_FRONTEND=noninteractive \
    MAVEN_HOME=/opt/maven \
    PATH=/opt/maven/bin:$PATH

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
# Chromium's own shared-library deps are NOT listed here (the package names drift
# across Ubuntu releases); install them once inside the sandbox with the
# version-proof `npx playwright install --with-deps chromium` (see README).

# BEGIN UBUNTU PACKAGES
RUN apt-get update && apt-get install -yqq --no-install-recommends \
        bash="5.2.21-2ubuntu4" \
        ca-certificates="20260601~24.04.1" \
        curl="8.5.0-2ubuntu10.10" \
        git="1:2.43.0-1ubuntu7.3" \
        gnupg="2.4.4-2ubuntu17.4" \
        imagemagick="8:6.9.12.98+dfsg1-5.2build2" \
        iproute2="6.1.0-1ubuntu6.3" \
        jq="1.7.1-3ubuntu0.24.04.2" \
        librsvg2-bin="2.58.0+dfsg-1build1" \
        lsof="4.95.0-1build3" \
        optipng="0.7.8+ds-1build2" \
        python3="3.12.3-0ubuntu2.1" \
        python3-pip="24.0+dfsg-1ubuntu1.3" \
        python3-fonttools="4.46.0-1build2" \
        python3-venv="3.12.3-0ubuntu2.1" \
        sudo="1.9.15p5-3ubuntu5.24.04.2" \
        unzip="6.0-28ubuntu4.1" \
    && \
    apt-get autoremove && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*
# END UBUNTU PACKAGES

# ── Docker engine + compose plugin (the nested daemon) ───────────────────────
RUN install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc 2>/dev/null \
       || curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc \
    && chmod a+r /etc/apt/keyrings/docker.asc \
    && . /etc/os-release \
    && DOCKER_REPO="$ID" \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/${DOCKER_REPO} ${VERSION_CODENAME} stable" \
         > /etc/apt/sources.list.d/docker.list

# BEGIN UBUNTU PACKAGES
RUN apt-get update && apt-get install -yqq --no-install-recommends \
        docker-ce="5:29.6.1-1~ubuntu.24.04~noble" \
        docker-ce-cli="5:29.6.1-1~ubuntu.24.04~noble" \
        containerd.io="2.2.5-1~ubuntu.24.04~noble" \
        docker-buildx-plugin="0.35.0-1~ubuntu.24.04~noble" \
        docker-compose-plugin="5.2.0-1~ubuntu.24.04~noble" \
    && \
    apt-get autoremove && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*
# END UBUNTU PACKAGES

# ── Maven ────
RUN curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      -o /tmp/maven.tar.gz \
    && tar -xzf /tmp/maven.tar.gz -C /opt \
    && ln -s "/opt/apache-maven-${MAVEN_VERSION}" /opt/maven \
    && rm /tmp/maven.tar.gz

# ── Node 26 via NodeSource ───────────────────────────────────────────────────
# Claude Code itself is installed later, AFTER the `dev` user exists, into a
# dev-owned npm prefix (see below) so its self-update isn't blocked.
RUN curl -fsSL "https://deb.nodesource.com/setup_${NODE_MAJOR}.x" | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# ── Non-root user `dev` matching the host UID/GID, with passwordless sudo ────
# The base image may already ship a user/group at ${UID}/${GID} (Ubuntu's
# default "ubuntu" user is 1000). Reuse it rather than failing: rename whatever
# owns the UID/GID to `dev` and point its home at /home/dev.
RUN set -eux; \
    if ! getent group "${GID}" >/dev/null; then groupadd -g "${GID}" dev; \
    else groupmod -n dev "$(getent group ${GID} | cut -d: -f1)" || true; fi; \
    if getent passwd "${UID}" >/dev/null; then \
      OLD="$(getent passwd ${UID} | cut -d: -f1)"; \
      usermod -l dev -d /home/dev -m -s /bin/bash "$OLD"; \
    else \
      useradd -m -u "${UID}" -g "${GID}" -s /bin/bash dev; \
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
ENV PATH=/home/dev/.npm-global/bin:$PATH
RUN install -d -o dev -g dev /home/dev/.npm-global \
    && sudo -u dev -H npm config set prefix /home/dev/.npm-global \
    && sudo -u dev -H npm install -g @anthropic-ai/claude-code

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
