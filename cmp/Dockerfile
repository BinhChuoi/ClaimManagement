# ── Stage 1: Build ────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

# Copy Gradle wrapper + dependency files first (layer cache)
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q 2>/dev/null || true

# Copy source and build the fat JAR
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ── Stage 2: Runtime ───────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# gcompat: glibc compatibility layer for Alpine (musl)
# Needed by Snappy native lib (libsnappyjava.so) used by Parquet for compression
RUN apk add --no-cache gcompat

# Non-root user for security
RUN addgroup -S cmp && adduser -S cmp -G cmp
COPY --from=builder /build/build/libs/cmp-0.0.1-SNAPSHOT.jar app.jar
RUN chown cmp:cmp app.jar
USER cmp

EXPOSE 8080

# Liveness probe — Docker's job: "Is the JVM alive? Restart if not."
# Does NOT check DB or downstream deps — only that the app itself is running
# start-period=90s gives Spring Boot time to fully start before checks begin
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -qO- http://localhost:${SERVER_PORT:-8080}/actuator/health/liveness | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
