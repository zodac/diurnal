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
RUN --mount=type=cache,target=/root/.m2 mvn package -DskipTests -q

# ── Stage 4: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:26-jre-alpine
RUN apk add --no-cache tzdata
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build /build/target/quarkus-app/ ./
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO /dev/null http://localhost:8080/health || exit 1
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
