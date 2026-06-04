FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY api/pom.xml .
RUN mvn dependency:go-offline -q
COPY api/src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build /build/target/quarkus-app/ ./
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
