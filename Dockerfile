# syntax = docker/dockerfile:1.2

# ── Stage 1: compile the CSS + vendor and minify the front-end scripts ────────
# Scans the templates + Java sources and emits a minified app.css, copies the pinned third-party
# browser libraries (htmx) out of node_modules into resources/js, and esbuild-minifies the app's own
# hand-written scripts in place (the committed sources stay readable; dev mode serves them as-is —
# only the image ships the minified form, which the smoke tier then exercises). Kept in its own
# stage so the Node toolchain never reaches the build or runtime images; the build stage copies the
# outputs in below.
FROM node:26.5.0-alpine AS css
WORKDIR /css
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tailwind.config.js ./
COPY scripts/vendor-assets.cjs ./scripts/
COPY src/main/css ./src/main/css
COPY src/main/resources/templates ./src/main/resources/templates
COPY src/main/java ./src/main/java
# The served front-end scripts add Tailwind utility classes at runtime (e.g. classList.add('opacity-100')),
# so Tailwind must scan them here too or those utilities are purged from the image's stylesheet and the
# class silently does nothing (the committed *.js only — htmx.min.js is vendored by `npm run vendor` below).
COPY src/main/resources/META-INF/resources/js ./src/main/resources/META-INF/resources/js
RUN npm run css && npm run vendor && npm run js:min

# ── Stage 2: generate the favicon raster assets ──────────────────────────────
# Rasterises the committed favicon SVG (the single-letter "d" mark, itself generated from the brand
# font by generate-brand.py) into the PNG favicons + multi-res .ico the site links from <head>. Kept
# in its own stage so ImageMagick / librsvg / optipng never reach the build or runtime images. librsvg
# is ImageMagick's SVG-rendering backend; imagemagick also packs the .ico. PNGs land in
# src/main/resources/META-INF/resources/img; favicon.ico lands at the web root (one level up).
FROM node:26.5.0-alpine AS icons

# BEGIN ALPINE PACKAGES
RUN apk add --no-cache  \
    imagemagick="7.1.2.24-r0" \
    librsvg="2.62.3-r0" \
    optipng="7.9.1-r1"
# END ALPINE PACKAGES

WORKDIR /icons
COPY scripts/generate-favicons.cjs ./scripts/
COPY src/main/resources/META-INF/resources/img/favicon.svg \
     ./src/main/resources/META-INF/resources/img/favicon.svg
RUN node scripts/generate-favicons.cjs

# ── Stage 3: build the Quarkus app ───────────────────────────────────────────
FROM maven:3.9.16-eclipse-temurin-26 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -q
# VERSION lives at the repo root (outside src), but the POM packages it onto the classpath so AppInfo
# can read the release version for the footer at runtime — so it must be present in the build context.
COPY VERSION .
COPY src ./src
# Drop the freshly-compiled stylesheet into the static web root so Quarkus bundles it
# into quarkus-app and serves it at /css/app.css (overwriting any committed copy).
COPY --from=css /css/src/main/resources/META-INF/resources/css/app.css \
                ./src/main/resources/META-INF/resources/css/app.css
# Likewise the whole /js directory from the css stage: the vendored htmx (a .gitignored build
# artifact, absent from the committed tree) plus the esbuild-minified copies of the app's own
# scripts, which overwrite the readable committed sources for the image only.
COPY --from=css /css/src/main/resources/META-INF/resources/js/ \
                ./src/main/resources/META-INF/resources/js/
# Drop the freshly-rendered raster favicons into the static web root, overwriting the committed copies.
# The SVGs themselves (favicon.svg + wordmark.svg) are committed and come in via `COPY src` above.
# favicon.ico is at the web root (not /img/) so browsers that request /favicon.ico directly find it.
COPY --from=icons /icons/src/main/resources/META-INF/resources/favicon.ico \
                  ./src/main/resources/META-INF/resources/
COPY --from=icons /icons/src/main/resources/META-INF/resources/img/apple-touch-icon.png \
                  /icons/src/main/resources/META-INF/resources/img/icon-192.png \
                  /icons/src/main/resources/META-INF/resources/img/icon-512.png \
                  ./src/main/resources/META-INF/resources/img/
# Content-hash the compiled stylesheet for cache-busting. Rename app.css -> app.<hash>.css so every
# build that changes the CSS yields a new URL (defeating reverse-proxy/CDN caches, which key on path
# and may ignore query strings), then bake the resulting filename into the build-time config source
# that AppInfo reads — so layout.html's <link> and the served file always agree. The hashed file is
# then served `immutable` (application.properties). A non-Docker `mvn package` skips this and keeps
# the un-hashed app.css default. The hash is folded straight into the rename (no intermediate shell
# var) and read back from the glob for the config line — after the mv, only the hashed file matches.
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
RUN cd src/main/resources/META-INF/resources/css \
    && mv app.css "app.$(sha256sum app.css | cut -c1-12).css" \
    && printf '\napp.assets.css-file=%s\n' app.*.css \
       >> /build/src/main/resources/META-INF/microprofile-config.properties
# Content-hash the self-hosted htmx script for cache-busting, mirroring the stylesheet rename above:
# htmx.min.js -> htmx.<hash>.min.js, then bake the resulting filename into the build-time config source
# that AppInfo.jsFile reads — so layout.html's <script src> and the served file always agree. Served
# `immutable` (application.properties). A non-Docker `mvn package` skips this and keeps the un-hashed
# htmx.min.js default. htmx is a committed vendored file (arrives via `COPY src`), so unlike the CSS it
# needs no build stage — only the rename.
RUN cd src/main/resources/META-INF/resources/js \
    && mv htmx.min.js "htmx.$(sha256sum htmx.min.js | cut -c1-12).min.js" \
    && printf '\napp.assets.js-file=%s\n' htmx.*.min.js \
       >> /build/src/main/resources/META-INF/microprofile-config.properties
# Content-hash the app's own hand-written scripts, extracted from the templates so they ride the
# immutable cache instead of being re-parsed on every no-cache navigation: app.js (shared, every
# page), dashboard.js (the calendar engine, dashboard only), actions.js (actions page), admin-users.js
# (admin users page), admin-api-docs.js (admin API-docs page) and settings.js (settings page). Same
# rename+bake as htmx/CSS above; all are committed files (arrive via `COPY src`), so they need only
# the rename. The globs are anchored (app.*.js / dashboard.*.js / …) so each matches ONLY its own
# hashed output after the mv.
RUN cd src/main/resources/META-INF/resources/js \
    && mv app.js "app.$(sha256sum app.js | cut -c1-12).js" \
    && mv dashboard.js "dashboard.$(sha256sum dashboard.js | cut -c1-12).js" \
    && mv actions.js "actions.$(sha256sum actions.js | cut -c1-12).js" \
    && mv admin-users.js "admin-users.$(sha256sum admin-users.js | cut -c1-12).js" \
    && mv admin-api-docs.js "admin-api-docs.$(sha256sum admin-api-docs.js | cut -c1-12).js" \
    && mv settings.js "settings.$(sha256sum settings.js | cut -c1-12).js" \
    && printf '\napp.assets.js-app-file=%s\n' app.*.js \
       >> /build/src/main/resources/META-INF/microprofile-config.properties \
    && printf '\napp.assets.js-dashboard-file=%s\n' dashboard.*.js \
       >> /build/src/main/resources/META-INF/microprofile-config.properties \
    && printf '\napp.assets.js-actions-file=%s\n' actions.*.js \
       >> /build/src/main/resources/META-INF/microprofile-config.properties \
    && printf '\napp.assets.js-admin-file=%s\n' admin-users.*.js \
       >> /build/src/main/resources/META-INF/microprofile-config.properties \
    && printf '\napp.assets.js-api-docs-file=%s\n' admin-api-docs.*.js \
       >> /build/src/main/resources/META-INF/microprofile-config.properties \
    && printf '\napp.assets.js-settings-file=%s\n' settings.*.js \
       >> /build/src/main/resources/META-INF/microprofile-config.properties
# -Dcss.build.skip=true: the stylesheet is already compiled by the `css` stage and copied in above,
# and this maven image has no Node toolchain — so skip the POM's `css-build` exec.
RUN --mount=type=cache,target=/root/.m2 mvn package -DskipTests -Dcss.build.skip=true -q

# ── Stage 4: build a minimal custom JRE with jlink ───────────────────────────
FROM eclipse-temurin:26.0.1_8-jdk AS jre

# BEGIN UBUNTU PACKAGES
RUN apt-get update && apt-get install -yqq --no-install-recommends \
        binutils="2.46-3ubuntu2" \
    && \
    apt-get autoremove && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*
# END UBUNTU PACKAGES

# Module set
#   java.base                          – always required
#   java.logging                       – JBoss LogManager / JUL bridge
#   java.xml                           – config + persistence XML parsing
#   java.sql / java.naming             – JDBC (Agroal + PostgreSQL driver), JNDI lookups
#   java.rmi                           – RemoteException, referenced by SmallRye Context Propagation
#   java.management / jdk.management    – metrics, MXBeans
#   java.net.http                      – OIDC token refresh + REST client (java.net.http.HttpClient)
#   jdk.naming.dns                     – DNS resolution for OIDC discovery
#   java.security.jgss / .sasl          – GSS/SASL chains pulled in by TLS + auth
#   jdk.crypto.cryptoki / jdk.crypto.ec – PKCS#11 + EC crypto (modern TLS handshakes)
#   java.desktop                       – java.beans, required reflectively by Hibernate / Jackson
#   java.instrument                    – bytecode instrumentation agents
#   jdk.unsupported                    – sun.misc.Unsafe (Netty, Hibernate, et al.)
#   jdk.zipfs                          – zip filesystem provider used when opening nested jars
# --add-options bakes default JVM options into the JRE so every `java` launch includes them without
# cluttering the ENTRYPOINT. --enable-native-access=ALL-UNNAMED opts the app in to JNI/native-library
# loading, silencing the JDK's "restricted method ... System::loadLibrary" startup warning and keeping
# the app bootable on a future JDK that will block unauthorised native access by default (JEP 472). The
# only native load here is brotli4j (pulled transitively via quarkus-vertx-http), used for HTTP response
# compression.
RUN jlink --compress=zip-9 \
        --no-header-files \
        --no-man-pages \
        --strip-debug \
        --add-options="--enable-native-access=ALL-UNNAMED" \
        --add-modules java.base,java.logging,java.xml,java.sql,java.rmi,java.naming,java.management,java.net.http,jdk.naming.dns,java.security.jgss,java.security.sasl,jdk.crypto.cryptoki,jdk.crypto.ec,java.desktop,java.instrument,jdk.unsupported,jdk.management,jdk.zipfs \
        --output /opt/jdk \
    && strip -p --strip-unneeded /opt/jdk/lib/server/libjvm.so

# ── Stage 5: static busybox (single-binary wget for the healthcheck) ─────────
# busybox:*-musl is fully statically linked, so the binary runs unchanged on the glibc distroless
# base. It is the only "shell tool" we add — distroless ships no wget/curl/sh of its own.
FROM busybox:1.37.0-musl AS shell

# ── Stage 6: runtime (distroless, non-root) ──────────────────────────────────
# distroless/base-debian13 = glibc + libssl + ca-certificates + tzdata + /tmp, and nothing else
# (no shell, no package manager). The :nonroot tag defaults the process to UID 65532. No `apk add
# tzdata` is needed — tzdata is already present, so `app.timezone` / TZ resolve as on a full distro.
FROM gcr.io/distroless/base-debian13:nonroot AS runtime

# Custom jlink JRE (glibc, matches this debian base) + its tools on PATH.
COPY --from=jre /opt/jdk /opt/jdk
ENV JAVA_HOME="/opt/jdk"
ENV PATH="/opt/jdk/bin:${PATH}"

EXPOSE 8080

# App health lives at /health over plain HTTP on 8080 (TLS is terminated at the reverse proxy).
# Exec-form CMD invokes busybox-wget directly so no shell is required; --spider makes it a HEAD-style
# probe that exits non-zero on any non-2xx response.
COPY --from=shell /bin/busybox /bin/wget
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD ["/bin/wget", "--quiet", "--tries=1", "--spider", "http://127.0.0.1:8080/health"]

# Quarkus fast-jar layout: quarkus-run.jar alongside lib/ app/ quarkus/. Deploy the whole directory.
# Files land root-owned but world-readable, so UID 65532 can read/exec them; the app never writes here
# (session state lives in Postgres, logs go to stdout).
WORKDIR /app
COPY --from=build /build/target/quarkus-app/ ./

ENTRYPOINT ["/opt/jdk/bin/java", "-jar", "quarkus-run.jar"]
