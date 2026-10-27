
# Stage 1: Build stage
FROM dhi.io/eclipse-temurin:21-jdk-debian-dev AS builder

WORKDIR /app

# Install the downloader required by the Kotlin wrapper in the DHI dev image.
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl gzip ca-certificates && \
    rm -rf /var/lib/apt/lists/*

COPY kotlin kotlin.bat project.yaml module.yaml libs.versions.toml ./
COPY src ./src
COPY resources ./resources

RUN chmod +x ./kotlin && \
    ./kotlin package -f executable-jar && \
    find build/tasks -name "*-executable.jar" \
      -exec cp {} /app/app.jar \; && \
    test -s /app/app.jar

# Stage 2: Minimal Java runtime
FROM dhi.io/eclipse-temurin:21-alpine3.23

WORKDIR /app

COPY --from=builder --chown=65532:65532 /app/app.jar /app/app.jar

# DHI runtime images use a non-root user by default.
USER 65532:65532

EXPOSE 80

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
