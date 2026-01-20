# Multi-stage build for db-server

# Stage 1: Build
FROM gradle:8.5-jdk17 AS builder

WORKDIR /app

# Copy gradle wrapper and configuration files
COPY gradle/ ./gradle/
COPY gradlew ./
COPY gradlew.bat ./
COPY settings.gradle.kts ./
COPY build.gradle.kts ./

# Copy all subprojects
COPY common/ ./common/
COPY db-server/ ./db-server/
COPY api-server/ ./api-server/

# Build the db-server using gradle wrapper
RUN ./gradlew :db-server:bootJar -x test --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:17-jre

WORKDIR /app

# Install netcat for health checks
RUN apt-get update && apt-get install -y netcat-openbsd && rm -rf /var/lib/apt/lists/*

# Copy the JAR from builder stage
COPY --from=builder /app/db-server/build/libs/*.jar app.jar

# Create data directory
RUN mkdir -p /app/data

# Expose db-server port
EXPOSE 9000

# Run the Spring Boot application (prefer IPv4 stack for network connections)
ENTRYPOINT ["java", "-Djava.net.preferIPv4Stack=true", "-jar", "app.jar"]
