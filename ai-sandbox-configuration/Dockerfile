# syntax=docker/dockerfile:1.7
# Diurnal's layer of the sandbox image: the toolchains it needs on top of the shared base (Debian 13,
# nested Docker, Node, git, gh, jq, python3, lsof, iproute2, tzdata - see
# ai-sandbox/image/base/Dockerfile, whose pins belong to that repo). Claude Code and the sandbox scripts
# are added ABOVE this layer, so a Claude release never rebuilds anything here. Built by
# ai-sandbox/sandbox.sh with this directory as the build context.
#
# Adds JDK 27, Maven, Playwright's Chromium OS libraries and the brand tooling. The pins are maintained
# by .github/scripts/update_dependency_versions.sh, which matches the two `AS jdk` / `AS maven` stage
# lines below and the BEGIN/END DEBIAN PACKAGES block, so keep their shape.
#
# Layer order: the toolchain COPYs first (rarely move, and a change re-copies everything below), then
# the Playwright libs (rerun only when their list changes), the ENV block, and the most volatile apt
# pins last.

# The base image to build on - always passed by sandbox.sh.
ARG BASE_IMAGE

# ── Toolchain source stages (their payload is copied into the shared base) ────
# Each is only mined for its self-contained toolchain directory; none of their base OS reaches the image.
#   JDK   - self-contained under /opt/java/openjdk, portable onto glibc Debian.
#   Maven - pure Java + shell under /usr/share/maven; the image's own JDK is unused.
FROM eclipse-temurin:27_35-jdk AS jdk
FROM maven:3.10.0-eclipse-temurin-27 AS maven

# ── Playwright Chromium's OS shared libs: the package LIST (libnspr4, libnss3, …) ─
# Playwright's own `install-deps` resolves the correct set (the names drift across Debian releases), run
# on a copy of the shared base so the list is exactly what the runtime stage is missing. The browser
# BINARY stays out of the image, to avoid baking ~150MB of Chromium into every layer:
# setup.d/20-playwright.sh downloads it into the `pw` cache volume on first open. The pin below follows
# tests/package.json, kept in step by .github/scripts/update_dependency_versions.sh.
FROM ${BASE_IMAGE} AS playwright-deps
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
# hadolint ignore=DL3008,DL3009
RUN --mount=type=cache,id=playwright-apt-cache,target=/var/cache/apt,sharing=locked --mount=type=cache,id=playwright-npm,target=/root/.npm,sharing=locked \
    apt-get update \
    && apt-mark showmanual | sort > /tmp/before \
    && npx --yes playwright@1.65.0 install-deps chromium \
    && apt-mark showmanual | sort | comm -13 /tmp/before - > /playwright-packages.txt \
    && test -s /playwright-packages.txt

# ── Runtime: the shared base + this project's toolchains ──────────────────────
FROM ${BASE_IMAGE}
SHELL ["/bin/bash", "-o", "pipefail", "-c"]

COPY --from=jdk   /opt/java/openjdk /opt/java/openjdk
COPY --from=maven /usr/share/maven  /opt/maven

# hadolint ignore=DL3008,DL3009
RUN --mount=type=bind,from=playwright-deps,source=/playwright-packages.txt,target=/tmp/playwright-packages.txt --mount=type=cache,target=/var/cache/apt,sharing=locked --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get update \
    && xargs -a /tmp/playwright-packages.txt apt-get install -yqq --no-install-recommends

# These reach the session as-is (the entrypoint keeps the environment).
# PLAYWRIGHT_BROWSERS_PATH - let Playwright manage its own Chromium, cached under $HOME and persisted via
#   the `pw` volume rather than baked into the image.
ENV JAVA_HOME=/opt/java/openjdk \
    MAVEN_HOME=/opt/maven \
    PLAYWRIGHT_BROWSERS_PATH=/home/dev/.cache/ms-playwright \
    PATH=/opt/java/openjdk/bin:/opt/maven/bin:${PATH}

# ── Project + brand tooling (the most churn-prone block: kept last) ───────────
# The brand pipeline (`npm run brand`) needs these:
#   - python3-fonttools → scripts/generate-brand.py (reads NovaFlat-Book.ttf and renders the wordmark.svg /
#                         favicon.svg / footer-mark.svg "d" glyphs). The distro package, NOT pip: the base
#                         image's Python is PEP-668 "externally managed", so `pip install fonttools` is refused.
#   - imagemagick + librsvg2-bin + optipng → scripts/generate-favicons.cjs (rasterises favicon.svg into
#                         favicon.ico / favicon-16/32.png / apple-touch-icon.png)
#
# fontconfig backs the copied-in Temurin JDK's reflective java.desktop paths: the temurin base image normally
# supplies it (the JDK itself only bundles freetype).
# hadolint ignore=DL3009
# BEGIN DEBIAN PACKAGES
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get update && apt-get install -yqq --no-install-recommends \
        fontconfig="2.15.0-2.3" \
        imagemagick="8:7.1.1.43+dfsg1-1+deb13u14" \
        librsvg2-bin="2.60.0+dfsg-1+deb13u1" \
        optipng="0.7.8+ds-1+b1" \
        python3-fonttools="4.57.0-1+deb13u1"
# END DEBIAN PACKAGES
