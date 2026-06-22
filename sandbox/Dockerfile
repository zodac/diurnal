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
FROM eclipse-temurin:26-jdk-noble

ARG UID=1000
ARG GID=1000
ARG MAVEN_VERSION=3.9.9
ARG NODE_MAJOR=26

ENV DEBIAN_FRONTEND=noninteractive \
    MAVEN_HOME=/opt/maven \
    PATH=/opt/maven/bin:$PATH

# ── Base tooling + brand-script tooling ──────────────────────────────────────
# Chromium's own shared-library deps are NOT listed here (the package names drift
# across Ubuntu releases); install them once inside the sandbox with the
# version-proof `npx playwright install --with-deps chromium` (see README).
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl gnupg git bash sudo \
      python3 python3-pip python3-venv \
      imagemagick librsvg2-bin optipng \
    && rm -rf /var/lib/apt/lists/*

# ── Docker engine + compose plugin (the nested daemon) ───────────────────────
RUN install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/debian/gpg \
         -o /etc/apt/keyrings/docker.asc 2>/dev/null \
       || curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
            -o /etc/apt/keyrings/docker.asc \
    && chmod a+r /etc/apt/keyrings/docker.asc \
    && . /etc/os-release \
    && DOCKER_REPO="$ID" \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/${DOCKER_REPO} ${VERSION_CODENAME} stable" \
         > /etc/apt/sources.list.d/docker.list \
    && apt-get update && apt-get install -y --no-install-recommends \
         docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin \
    && rm -rf /var/lib/apt/lists/*

# ── Maven 3.9 (apt ships an older one; the parent-pom enforcer wants 3.9+) ────
RUN curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      -o /tmp/maven.tar.gz \
    && tar -xzf /tmp/maven.tar.gz -C /opt \
    && ln -s "/opt/apache-maven-${MAVEN_VERSION}" /opt/maven \
    && rm /tmp/maven.tar.gz

# ── Node 26 via NodeSource + Claude Code CLI ─────────────────────────────────
RUN curl -fsSL "https://deb.nodesource.com/setup_${NODE_MAJOR}.x" | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/* \
    && npm install -g @anthropic-ai/claude-code

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

# Let Playwright manage its own Chromium (cached under $HOME, persisted via volume).
ENV PLAYWRIGHT_BROWSERS_PATH=/home/dev/.cache/ms-playwright

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
COPY launch.sh     /usr/local/bin/sandbox-launch.sh
COPY setup.sh      /usr/local/bin/sandbox-setup.sh
RUN chmod +x /usr/local/bin/entrypoint.sh \
             /usr/local/bin/sandbox-launch.sh \
             /usr/local/bin/sandbox-setup.sh

WORKDIR /work
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
