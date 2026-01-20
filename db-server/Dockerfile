# Multi-stage build for db-server
# syntax=docker/dockerfile:1.4

# Stage 1: Build
FROM gradle:8.5-jdk17 AS builder

WORKDIR /app

# Layer 1: Gradle wrapper (거의 변경 안됨 - 캐시 재사용률 높음)
COPY gradle/ ./gradle/
COPY gradlew ./
COPY gradlew.bat ./

# Layer 2: 프로젝트 설정 파일 (가끔 변경됨)
COPY settings.gradle.kts ./
COPY build.gradle.kts ./

# Layer 3: 각 모듈의 build.gradle.kts만 먼저 복사 (의존성 정의)
COPY common/build.gradle.kts ./common/
COPY db-server/build.gradle.kts ./db-server/
COPY api-server/build.gradle.kts ./api-server/

# Layer 4: 의존성 다운로드 (위 설정 파일 변경 없으면 캐시됨 - 2분 절약!)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :db-server:dependencies --no-daemon || true

# Layer 5: 소스 코드 복사 (자주 변경됨 - 여기부터 재빌드)
COPY common/src ./common/src
COPY db-server/src ./db-server/src

# Layer 6: 빌드 (소스 변경 시에만 재실행)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :db-server:bootJar -x test --no-daemon

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
