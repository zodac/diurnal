# syntax = docker/dockerfile:1.2

# ── Stage 1: compile the CSS + vendor and minify the front-end scripts ────────
# Scans the templates + Java sources and emits a minified app.css, copies the pinned third-party
# browser libraries (htmx) out of node_modules into resources/js, and esbuild-minifies the app's own
# hand-written scripts in place (the committed sources stay readable; dev mode serves them as-is —
# only the image ships the minified form, which the smoke tier then exercises). Kept in its own
# stage so the Node toolchain never reaches the build or runtime images; the build stage copies the
# outputs in below.
FROM node:26.5.0-alpine AS css
# Mirror the repo layout (frontend/ next to src/ and scripts/) so the npm scripts' relative
# ../src and ../scripts paths resolve exactly as they do in the working tree.
WORKDIR /css/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/tailwind.config.js ./
COPY frontend/css ./css
COPY scripts/vendor-assets.cjs /css/scripts/
COPY src/main/resources/templates /css/src/main/resources/templates
COPY src/main/java /css/src/main/java
# The served front-end scripts add Tailwind utility classes at runtime (e.g. classList.add('opacity-100')),
# so Tailwind must scan them here too or those utilities are purged from the image's stylesheet and the
# class silently does nothing (the committed *.js only — htmx.min.js is vendored by `npm run vendor` below).
COPY src/main/resources/META-INF/resources/js /css/src/main/resources/META-INF/resources/js
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
# Content-hash EVERY served static asset for cache-busting in ONE place: scripts/hash-static-assets.sh
# renames each (app.css -> app.<hash>.css, htmx.min.js -> htmx.<hash>.min.js, the preview thumbnails and
# the vector marks likewise) and bakes the resulting filename into the build-time config source that
# AppConfig/AppInfo read — so every template reference / <link> and the served file always agree, and each
# hashed asset is served `immutable` (application.properties). A non-Docker `mvn package` never runs this,
# so the un-hashed default filenames / base names are served instead. All assets arrive via `COPY src`
# (plus the compiled CSS / vendored+minified JS copied from the `css` stage above). Fonts, the raster
# app-icons, favicon.ico and manifest.json are deliberately left un-hashed (see the script's header).
COPY scripts/hash-static-assets.sh ./scripts/hash-static-assets.sh
RUN bash scripts/hash-static-assets.sh \
        src/main/resources/META-INF/resources \
        /build/src/main/resources/META-INF/microprofile-config.properties
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
