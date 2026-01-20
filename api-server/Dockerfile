# Multi-stage build for api-server

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

# Build the api-server using gradle wrapper
RUN ./gradlew :api-server:bootJar -x test --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy the JAR from builder stage
COPY --from=builder /app/api-server/build/libs/*.jar app.jar

# Expose api-server port
EXPOSE 8080

# Run the Spring Boot application
ENTRYPOINT ["java", "-jar", "app.jar"]
