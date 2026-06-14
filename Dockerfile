# syntax = docker/dockerfile:1.2

# ── Stage 1: compile + purge the Tailwind CSS ────────────────────────────────
# Scans the templates + Java sources and emits a minified app.css. Kept in its own
# stage so the Node toolchain never reaches the build or runtime images.
FROM node:20-alpine AS css
WORKDIR /css
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tailwind.config.js ./
COPY src/main/css ./src/main/css
COPY src/main/resources/templates ./src/main/resources/templates
COPY src/main/java ./src/main/java
RUN npm run css

# ── Stage 2: build the Quarkus app ───────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -q
COPY src ./src
# Drop the freshly-compiled stylesheet into the static web root so Quarkus bundles it
# into quarkus-app and serves it at /css/app.css (overwriting any committed copy).
COPY --from=css /css/src/main/resources/META-INF/resources/css/app.css \
                ./src/main/resources/META-INF/resources/css/app.css
RUN --mount=type=cache,target=/root/.m2 mvn package -DskipTests -q

# ── Stage 3: runtime ─────────────────────────────────────────────────────────
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
