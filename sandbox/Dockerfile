# syntax=docker/dockerfile:1.7
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
#
# ── LAYER ORDER IS DELIBERATE: THIS FILE IS OPTIMISED FOR REBUILD TIME ────────
# sandbox.sh rebuilds this image before EVERY launch, so a cache miss is not a
# one-off cost — a contributor pays it on every single session until something
# else invalidates the layer. The dominant source of churn (see `git log -p
# -- sandbox/Dockerfile`) is version-pin bumps from update_dependency_versions.sh:
# the apt pins and the node tag move on almost every update run, while the JDK
# and Maven tags, the user setup and the Claude Code install are near-static.
#
# Layers are therefore ordered STABLE -> VOLATILE, and each step sits as late as
# its real dependencies allow:
#
#   1. apt cache config            static
#   2. bootstrap apt (3 packages)  rarely bumped; only what the Docker repo needs
#   3. Docker repo + engine        volatile pins, but `usermod -aG docker` needs it
#   4. COPY jdk / maven            large but near-static tags
#   5. COPY node                   large, volatile tag -> below the static COPYs
#   6. user creation               UID/GID build args -> below every large COPY
#   7. Claude Code install         needs node + the dev user
#   8. tooling apt (the big one)   MOST volatile; nothing below depends on it
#   9. COPY scripts                most-edited files, but the cheapest layer
#
# The rule when adding a step: put it as far DOWN as its dependencies permit, and
# never above a large COPY unless something below genuinely needs it. In
# particular do NOT fold new packages into the bootstrap block just because it
# comes first — that is the block whose invalidation is most expensive.
#
# BuildKit cache mounts back the apt and npm downloads, so even a genuine cache
# miss re-installs from local disk instead of re-fetching over the network.

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

# Every ENV in one static block at the top. These are free layers that never
# change, so grouping them here keeps them from sitting between (and needlessly
# invalidating) the expensive steps below.
#
# PLAYWRIGHT_BROWSERS_PATH — let Playwright manage its own Chromium, cached under
#   $HOME and persisted via a volume rather than baked into the image.
# CLAUDE_CONFIG_DIR — keep ALL of Claude Code's config in the persisted ~/.claude
#   volume. By default Claude splits its state: the ~/.claude/ directory (history,
#   OAuth credentials) AND a separate ~/.claude.json file in the home root
#   (onboarding/terminal-setup state + login). Only the directory is volume-mounted,
#   so without this the .claude.json file is recreated empty on every run — forcing
#   terminal reconfiguration + re-login each time. This redirects .claude.json (and
#   credentials) INTO the mounted dir, so it all persists. (entrypoint.sh already
#   forwards this var to the `dev` user via --preserve-env.)
# PATH — the dev-owned npm prefix goes FIRST so the self-updated `claude` wins over
#   any other copy on the path.
ENV DEBIAN_FRONTEND=noninteractive \
    JAVA_HOME=/opt/java/openjdk \
    MAVEN_HOME=/opt/maven \
    PLAYWRIGHT_BROWSERS_PATH=/home/dev/.cache/ms-playwright \
    CLAUDE_CONFIG_DIR=/home/dev/.claude \
    PATH=/home/dev/.npm-global/bin:/opt/java/openjdk/bin:/opt/maven/bin:$PATH

# pipefail for every RUN in this stage so a failure in the middle of a `getent … | cut` pipe (or any
# other) aborts the layer instead of being masked by the pipe's last command (debian base has bash).
SHELL ["/bin/bash", "-o", "pipefail", "-c"]

# ── apt cache configuration (static) ─────────────────────────────────────────
# Debian base images ship /etc/apt/apt.conf.d/docker-clean, which deletes every
# downloaded .deb the moment apt finishes — that would empty the BuildKit cache
# mounts below on each run and defeat the point. Removing it and setting
# Keep-Downloaded-Packages lets the mounts retain the archives between builds, so
# a bumped pin re-installs from local disk instead of re-downloading.
#
# The apt RUNs below therefore deliberately do NOT `apt-get clean` or
# `rm -rf /var/lib/apt/lists/*` (that is what DL3009 asks for): both paths ARE the
# cache mounts, so cleaning them would throw away exactly what we want to keep.
# Nothing is left in the image layer either way — a cache mount is not part of the
# filesystem it is mounted over.
#
# FORMATTING CONSTRAINT: keep BOTH --mount flags on the same physical line as `RUN`.
# update_dependency_versions.sh harvests package names from each PACKAGES block with
# `grep -oP '^\s*[a-z0-9.+-]+(?==)'`, so a continuation line beginning with `--mount=`
# is picked up as a package called "--mount"; the version lookup for that section then
# fails and the whole block silently stops being updated.
RUN rm -f /etc/apt/apt.conf.d/docker-clean \
    && echo 'Binary::apt::APT::Keep-Downloaded-Packages "true";' > /etc/apt/apt.conf.d/keep-cache

# ── Bootstrap packages (kept deliberately minimal) ───────────────────────────
# ONLY what later BUILD steps need — everything required merely at runtime lives in
# the tooling block at the BOTTOM of this file. This block is the most expensive one
# to invalidate (every layer below rebuilds), so it stays as small as possible: do
# not add a package here unless a RUN below genuinely cannot proceed without it.
#
#   curl + gnupg + ca-certificates → fetch and trust the Docker APT repo signing key
#     in the next step; ca-certificates also provides the TLS chain for the npm
#     install further down.
#   libatomic1 → the Node binary copied from the node stage is dynamically linked
#     against libatomic.so.1, which the bare Debian base does NOT ship. Without it
#     `node` exits 127 ("error while loading shared libraries") and the Claude Code
#     install fails. This used to be satisfied by accident — it arrives as a
#     transitive dependency of the tooling block, which previously ran first — so it
#     is now pinned explicitly rather than relying on install order. The main
#     Dockerfile's screenshots stage carries the same package for the same reason.
# hadolint ignore=DL3009
# BEGIN DEBIAN PACKAGES
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get update && apt-get install -yqq --no-install-recommends \
        ca-certificates="20250419" \
        curl="8.14.1-2+deb13u4" \
        gnupg="2.4.7-21+deb13u1" \
        libatomic1="14.2.0-19"
# END DEBIAN PACKAGES

# ── Docker engine + compose plugin (the nested daemon) ───────────────────────
# The base is pinned to Debian, so the repo distro is hardcoded as `debian`; only the
# release codename is derived (from /etc/os-release, via sed rather than a sourced shell
# var) so a base point/major bump follows automatically.
RUN install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc \
    && chmod a+r /etc/apt/keyrings/docker.asc \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(sed -n 's/^VERSION_CODENAME=//p' /etc/os-release) stable" \
         > /etc/apt/sources.list.d/docker.list

# Sits this high only because the `docker` group it creates is required by the
# `usermod -aG docker dev` below; nothing else above depends on it.
# hadolint ignore=DL3009
# BEGIN DEBIAN PACKAGES
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get update && apt-get install -yqq --no-install-recommends \
        docker-ce="5:29.6.1-1~debian.13~trixie" \
        docker-ce-cli="5:29.6.1-1~debian.13~trixie" \
        containerd.io="2.2.6-1~debian.13~trixie" \
        docker-buildx-plugin="0.35.0-1~debian.13~trixie" \
        docker-compose-plugin="5.3.1-1~debian.13~trixie"
# END DEBIAN PACKAGES

# ── JDK 26 / Maven / Node (copied from the toolchain stages above) ───────────
# The three toolchains are self-contained directory trees; drop them into the
# glibc base at the paths JAVA_HOME / MAVEN_HOME / PATH already point at.
#
# Three separate COPY layers, ordered by how often their source tag moves: the JDK
# and Maven tags are near-static, whereas node is bumped on most update runs — so a
# node bump re-copies only node, leaving the (larger) JDK layer cached. Keep node
# LAST of the three, and keep all three ABOVE the user setup so a contributor whose
# host UID/GID differs from the 1000/1000 default does not re-copy ~550MB.
COPY --from=jdk   /opt/java/openjdk /opt/java/openjdk
COPY --from=maven /usr/share/maven  /opt/maven
COPY --from=node  /usr/local        /usr/local

# ── Non-root user `dev` matching the host UID/GID, with passwordless sudo ────
# The Debian base ships no user at ${UID}/${GID}, so this normally just creates
# `dev`. The getent guards keep it robust if a base ever does pre-populate the
# UID/GID: rename whatever owns them to `dev` and point its home at /home/dev.
#
# This is the first layer to consume the UID/GID build args, so it is also the
# cache-invalidation boundary for a contributor on a non-1000 UID (macOS hosts are
# typically 501). Everything expensive and UID-independent is deliberately above it.
#
# /etc/sudoers.d is created explicitly rather than relying on the `sudo` package —
# sudo now installs in the tooling block BELOW this one, so the directory would not
# otherwise exist yet.
RUN set -eux; \
    if ! getent group "${GID}" >/dev/null; then groupadd -g "${GID}" dev; \
    else groupmod -n dev "$(getent group ${GID} | cut -d: -f1)" || true; fi; \
    if getent passwd "${UID}" >/dev/null; then \
      usermod -l dev -d /home/dev -m -s /bin/bash "$(getent passwd ${UID} | cut -d: -f1)"; \
    else \
      useradd -l -m -u "${UID}" -g "${GID}" -s /bin/bash dev; \
    fi; \
    usermod -aG docker dev; \
    install -d -m 0755 /etc/sudoers.d; \
    echo 'dev ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/dev; \
    chmod 0440 /etc/sudoers.d/dev

# ── Claude Code CLI into a dev-owned npm prefix (so auto-update works) ────────
# Installing `-g` into npm's default /usr prefix makes the package and the
# /usr/bin/claude launcher root-owned, so the unprivileged `dev` user can't
# self-update — /doctor reports "npm global folder isn't writable". Instead,
# point npm's global prefix at a dev-owned dir (persisted in the image layer,
# baked via .npmrc so it needs no runtime env) and install AS dev, so updates
# write without sudo. The prefix's bin dir is already on PATH (see the ENV block).
# runuser (util-linux) drops to `dev` for the install without invoking sudo (hadolint DL3004); like
# `sudo -H` it resets HOME to /home/dev, so npm writes its config to the dev-owned ~/.npmrc.
#
# Deliberately UNPINNED: Claude Code self-updates at runtime, so a pin would go
# stale immediately and add churn to a layer that should stay cached for months.
# The npm cache is a BuildKit cache mount owned by the dev UID, so re-running this
# after an unrelated invalidation resolves from disk rather than the registry.
RUN --mount=type=cache,target=/home/dev/.npm,uid=${UID},gid=${GID},sharing=locked \
    install -d -o dev -g dev /home/dev/.npm-global \
    && runuser -u dev -- npm config set prefix /home/dev/.npm-global \
    && runuser -u dev -- npm install -g @anthropic-ai/claude-code

# ── Project + brand tooling (the most churn-prone block: kept last) ──────────
# Everything the sandbox needs at RUNTIME but nothing above needs at BUILD time.
# These pins are bumped by update_dependency_versions.sh more often than anything
# else in this file, so the block sits at the bottom where its invalidation costs
# only itself and the script COPY below — NOT the ~550MB of toolchain COPYs, the
# Docker engine install or the Claude Code download.
#
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
# sudo provides the runtime `sudo` binary; /etc/sudoers.d/dev is already written above.
#
# Chromium's own shared-library deps are NOT listed here (the package names drift
# across Debian releases); install them once inside the sandbox with the
# version-proof `npx playwright install --with-deps chromium` (see README).
# hadolint ignore=DL3009
# BEGIN DEBIAN PACKAGES
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get update && apt-get install -yqq --no-install-recommends \
        bash="5.2.37-2+b9" \
        fontconfig="2.15.0-2.3" \
        git="1:2.47.3-0+deb13u1" \
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
        unzip="6.0-29"
# END DEBIAN PACKAGES

# ── Entrypoint + helper scripts (most-edited files, cheapest layer) ──────────
# Last on purpose: these three are edited far more often than any pin, and landing
# them at the bottom means an edit rebuilds nothing but this COPY. --chmod sets the
# mode inline, avoiding a second layer for a `chmod +x`.
COPY --chmod=0755 entrypoint.sh /usr/local/bin/entrypoint.sh
COPY --chmod=0755 launch.sh     /usr/local/bin/sandbox-launch.sh
COPY --chmod=0755 setup.sh      /usr/local/bin/sandbox-setup.sh

WORKDIR /work
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
