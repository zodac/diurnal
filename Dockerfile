# syntax = docker/dockerfile:1.2

# ── Stage 1: compile + purge the Tailwind CSS ────────────────────────────────
# Scans the templates + Java sources and emits a minified app.css. Kept in its own
# stage so the Node toolchain never reaches the build or runtime images.
FROM node:26-alpine AS css
WORKDIR /css
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tailwind.config.js ./
COPY src/main/css ./src/main/css
COPY src/main/resources/templates ./src/main/resources/templates
COPY src/main/java ./src/main/java
RUN npm run css

# ── Stage 2: generate the favicon raster assets ──────────────────────────────
# Rasterises the committed favicon SVG (the single-letter "d" mark, itself generated from the brand
# font by generate-brand.py) into the PNG favicons + multi-res .ico the site links from <head>. Kept
# in its own stage so ImageMagick / librsvg / optipng never reach the build or runtime images. librsvg
# is ImageMagick's SVG-rendering backend; imagemagick also packs the .ico. PNGs land in
# src/main/resources/META-INF/resources/img; favicon.ico lands at the web root (one level up).
FROM node:26-alpine AS icons
RUN apk add --no-cache imagemagick librsvg optipng
WORKDIR /icons
COPY scripts/generate-favicons.cjs ./scripts/
COPY src/main/resources/META-INF/resources/img/favicon.svg \
     ./src/main/resources/META-INF/resources/img/favicon.svg
RUN node scripts/generate-favicons.cjs

# ── Stage 3: build the Quarkus app ───────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -q
COPY src ./src
# Drop the freshly-compiled stylesheet into the static web root so Quarkus bundles it
# into quarkus-app and serves it at /css/app.css (overwriting any committed copy).
COPY --from=css /css/src/main/resources/META-INF/resources/css/app.css \
                ./src/main/resources/META-INF/resources/css/app.css
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
# the un-hashed app.css default.
RUN CSS_DIR=src/main/resources/META-INF/resources/css \
    && CSS_HASH="$(sha256sum "${CSS_DIR}/app.css" | cut -c1-12)" \
    && mv "${CSS_DIR}/app.css" "${CSS_DIR}/app.${CSS_HASH}.css" \
    && printf '\napp.assets.css-file=app.%s.css\n' "${CSS_HASH}" \
       >> src/main/resources/META-INF/microprofile-config.properties
# -Dcss.build.skip=true: the stylesheet is already compiled by the `css` stage and copied in above,
# and this maven image has no Node toolchain — so skip the POM's `css-build` exec.
RUN --mount=type=cache,target=/root/.m2 mvn package -DskipTests -Dcss.build.skip=true -q

# ── Stage 4: build a minimal custom JRE with jlink ───────────────────────────
# Trims the ~330 MB JDK down to a ~70 MB modular runtime carrying only the modules this app uses,
# shrinking both the image and the attack surface. Built on a *glibc* Temurin JDK (the default,
# non-alpine tag) so the result is binary-compatible with the glibc distroless runtime base below —
# a musl/alpine JRE would not run there. binutils supplies `strip` for the libjvm debug-symbol purge.
#
# Module set (keep in sync with the app's needs; a missing module surfaces only at runtime):
#   java.base                          – always required
#   java.logging                       – JBoss LogManager / JUL bridge
#   java.xml                           – config + persistence XML parsing
#   java.sql / java.naming             – JDBC (Agroal + PostgreSQL driver), JNDI lookups
#   java.rmi                           – RemoteException, referenced by SmallRye Context Propagation
#   java.management / jdk.management    – metrics, MXBeans
#   java.net.http                      – OIDC token refresh + REST client (java.net.http.HttpClient)
#   jdk.naming.dns                     – DNS resolution for OIDC discovery
#   java.security.jgss / .sasl          – GSS/SASL chains pulled in by TLS + auth
#   jdk.crypto.cryptoki / jdk.crypto.ec – PKCS#11 + EC crypto (modern TLS handshakes, ES* JWT)
#   java.desktop                       – java.beans, required reflectively by Hibernate / Jackson
#   java.instrument                    – bytecode instrumentation agents
#   jdk.unsupported                    – sun.misc.Unsafe (Netty, Hibernate, et al.)
#   jdk.zipfs                          – zip filesystem provider used when opening nested jars
FROM eclipse-temurin:26-jdk AS jre
RUN apt-get update && apt-get install -yqq --no-install-recommends binutils \
    && jlink --compress=zip-9 \
        --no-header-files \
        --no-man-pages \
        --strip-debug \
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
ENV JAVA_HOME="/opt/jdk" \
    PATH="/opt/jdk/bin:${PATH}"

EXPOSE 8080

# App health lives at /health over plain HTTP on 8080 (TLS is terminated at the reverse proxy).
# Exec-form CMD invokes busybox-wget directly so no shell is required; --spider makes it a HEAD-style
# probe that exits non-zero on any non-2xx response.
COPY --from=shell /bin/busybox /bin/wget
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD ["/bin/wget", "--quiet", "--tries=1", "--spider", "http://127.0.0.1:8080/health"]

# Quarkus fast-jar layout: quarkus-run.jar alongside lib/ app/ quarkus/. Deploy the whole directory.
# Files land root-owned but world-readable, so UID 65532 can read/exec them; the app never writes here
# (JWT keys go to the mounted /run/secrets volume, logs go to stdout).
WORKDIR /app
COPY --from=build /build/target/quarkus-app/ ./

ENTRYPOINT ["/opt/jdk/bin/java", "-jar", "quarkus-run.jar"]
