# syntax = docker/dockerfile:1.2
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -q
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache tzdata
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build /build/target/quarkus-app/ ./
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
