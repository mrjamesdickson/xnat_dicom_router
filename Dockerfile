# XNAT DICOM Router
# Copyright (c) 2025 XNATWorks.
# Multi-stage build for minimal production image

# ============================================
# Stage 1: Build
# ============================================
FROM eclipse-temurin:18-jdk AS builder

WORKDIR /build

# Copy gradle wrapper and build files
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./

# Copy source code
COPY src src
COPY ui ui

# Install Node.js for UI build
RUN apt-get update && apt-get install -y nodejs npm && rm -rf /var/lib/apt/lists/*

# Make gradlew executable and build
RUN chmod +x gradlew && ./gradlew shadowJar --no-daemon

# ============================================
# Stage 2: Runtime
# ============================================
FROM eclipse-temurin:18-jre

LABEL maintainer="XNATWorks <support@xnatworks.com>"
LABEL org.opencontainers.image.title="XNAT DICOM Router"
LABEL org.opencontainers.image.description="DICOM routing and de-identification service for XNAT"
LABEL org.opencontainers.image.vendor="XNATWorks"
LABEL org.opencontainers.image.version="2.0.0"

# Create non-root user
RUN groupadd -r dicom && useradd -r -g dicom dicom

# Create directories
RUN mkdir -p /app /data /config /scripts && \
    chown -R dicom:dicom /app /data /config /scripts

WORKDIR /app

# Copy built jar from builder stage
COPY --from=builder /build/build/libs/dicom-router-2.0.0.jar /app/dicom-router.jar

# Copy default configuration (can be overridden with volume mount)
COPY config.yaml.example /config/config.yaml.example

# Set ownership
RUN chown -R dicom:dicom /app /data /config /scripts

# Switch to non-root user
USER dicom

# Environment variables
ENV JAVA_OPTS="-Xmx512m -Xms256m" \
    CONFIG_FILE="/config/config.yaml" \
    DATA_DIR="/data" \
    SCRIPTS_DIR="/scripts" \
    TZ="UTC"

# Expose ports
# Admin UI/API
EXPOSE 9090
# DICOM ports (configure additional ports in config.yaml)
EXPOSE 11112 11113 11114 11115

# Volumes
VOLUME ["/data", "/config", "/scripts"]

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:9090/api/health || exit 1

# Entry point
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/dicom-router.jar --config=$CONFIG_FILE"]
