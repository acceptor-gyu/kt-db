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

# Build the project using gradle wrapper
RUN ./gradlew :db-server:build -x test --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy the distribution from builder stage
COPY --from=builder /app/db-server/build/distributions/*.tar .

# Extract the distribution
RUN tar -xf *.tar && \
    rm *.tar && \
    mv db-server-*/* . && \
    rmdir db-server-*

# Expose db-server port
EXPOSE 9000

# Run the application using the start script
ENTRYPOINT ["./bin/db-server"]
