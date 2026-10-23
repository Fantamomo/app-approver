# Stage 1: Build stage
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app

# Install prerequisites for Amper / Kotlin wrapper script
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# Copy build definition files
COPY kotlin kotlin.bat project.yaml module.yaml libs.versions.toml ./
RUN chmod +x kotlin

# Copy source code and resources
COPY src ./src
COPY resources ./resources

# Package executable JAR
RUN ./kotlin package -f executable-jar && \
    find build/tasks -name "*-executable.jar" -exec cp {} /app/app.jar \;

# Stage 2: Runtime stage
FROM eclipse-temurin:21-jre-jammy

# Create non-root user for security
RUN groupadd -r appuser && useradd -r -g appuser -d /app -s /sbin/nologin appuser

WORKDIR /app
RUN chown -R appuser:appuser /app

# Copy executable JAR from builder stage
COPY --from=builder --chown=appuser:appuser /app/app.jar app.jar

USER appuser

EXPOSE 80

ENTRYPOINT ["java", "-jar", "app.jar"]
