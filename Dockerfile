# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

# Copy pom and source
COPY pom.xml .
COPY src ./src

# Compile and package application fat JAR
ENV MAVEN_OPTS="-Djava.net.preferIPv4Stack=true"
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -DskipTests -B \
    -Dmaven.wagon.http.pool=false \
    -Dmaven.wagon.http.retryHandler.count=5

# Stage 2: Production Runtime
FROM eclipse-temurin:21-jre-alpine

# Run as unprivileged non-root user (UID 10001)
RUN addgroup -g 10001 -S appgroup && adduser -u 10001 -S appuser -G appgroup
USER appuser
WORKDIR /app

# Copy artifact from build stage
COPY --from=builder --chown=appuser:appgroup /workspace/target/*.jar app.jar

EXPOSE 8080

# Container health probe hitting Actuator health endpoint
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Container JVM flags for memory bounds and container awareness
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
