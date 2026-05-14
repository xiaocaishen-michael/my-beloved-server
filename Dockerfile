# syntax=docker/dockerfile:1.7

# ---- Stage 1: build ----
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

# Copy Maven wrapper and pom files first to leverage Docker cache.
# Dep download only re-runs when poms change.
COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml ./
COPY mbw-shared/pom.xml mbw-shared/
COPY mbw-account/pom.xml mbw-account/
COPY mbw-app/pom.xml mbw-app/
RUN ./mvnw -B -ntp -s .mvn/settings.xml -pl mbw-app -am dependency:go-offline \
    -Dspotless.check.skip=true -Dcheckstyle.skip=true

# Copy source and build (skip lint/tests in container build; CI handles those).
COPY mbw-shared/src mbw-shared/src
COPY mbw-account/src mbw-account/src
COPY mbw-app/src mbw-app/src
COPY config config
RUN ./mvnw -B -ntp -s .mvn/settings.xml -pl mbw-app -am package -DskipTests \
    -Dspotless.check.skip=true -Dcheckstyle.skip=true

# Spring Boot layered jar extraction for cache-friendly final image.
WORKDIR /build/extracted
RUN java -Djarmode=layertools -jar /build/mbw-app/target/mbw-app-*-exec.jar extract

# ---- Stage 2: runtime ----
# Pinned to -noble (Ubuntu 24.04). Unsuffixed `21-jre` now resolves to
# the `resolute` variant (Ubuntu 26.04), which ships pebble as default
# entrypoint — its bundled Go stdlib triggers 5 HIGH CVEs in Trivy.
# Fix unreleased upstream (canonical/pebble#862 open); Adoptium does
# not own the base layer (adoptium/containers#906). Noble has no pebble.
FROM eclipse-temurin:21-jre-noble AS runtime
WORKDIR /app

# Run as non-root.
RUN addgroup --system mbw && adduser --system --ingroup mbw mbw
USER mbw

# Copy the four layers in order from least-to-most likely to change.
# This maximizes Docker layer reuse on rebuilds.
COPY --from=builder --chown=mbw:mbw /build/extracted/dependencies/ ./
COPY --from=builder --chown=mbw:mbw /build/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=mbw:mbw /build/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=mbw:mbw /build/extracted/application/ ./

EXPOSE 8080

# JVM tuning. Override at runtime: `docker run -e JAVA_OPTS=...`
ENV JAVA_OPTS="-Xmx1g -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
