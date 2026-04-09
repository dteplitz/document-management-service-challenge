# Multi-stage Dockerfile for document-management-service
# Stage 1: Builder
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build
COPY pom.xml .
COPY .mvn ./.mvn
COPY mvnw ./
COPY src ./src

# Build the application (skip tests for faster image build; tests run separately)
RUN ./mvnw clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# curl is needed for the HEALTHCHECK
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Create the multipart temp directory (used by Tomcat for streamed uploads)
RUN mkdir -p /tmp/multipart && chmod 755 /tmp/multipart

# Copy the built JAR from the builder stage
COPY --from=builder /build/target/document-management-service-challenge-*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --retries=3 --start-period=45s \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Strict memory budget — see ADR-006
#   -Xmx50m -Xms50m       : heap hard-capped at 50MB
#   -XX:MaxMetaspaceSize  : cap metaspace growth
#   -Xss256k              : reduce thread stack from 1MB default
ENTRYPOINT ["java", \
    "-Xmx50m", \
    "-Xms50m", \
    "-XX:MaxMetaspaceSize=64m", \
    "-Xss256k", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-jar", \
    "app.jar"]
