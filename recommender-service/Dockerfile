# Build stage: matches the local toolchain (Maven 3.9.x, JDK 26) exactly.
FROM maven:3.9.16-eclipse-temurin-26 AS build
WORKDIR /workspace

COPY pom.xml .
COPY rec-support/pom.xml rec-support/
COPY search-service/pom.xml search-service/
COPY recommender-service/pom.xml recommender-service/
COPY graphql-gateway/pom.xml graphql-gateway/
COPY proto proto
RUN mvn -q -B -pl recommender-service -am dependency:go-offline

COPY rec-support/src rec-support/src
COPY recommender-service/src recommender-service/src
RUN mvn -q -B -pl recommender-service -am package -DskipTests

# Runtime stage: JRE only, glibc-based (jammy, not alpine) — ONNX Runtime's
# native libraries (used by the neural ranking strategy) are glibc-linked.
FROM eclipse-temurin:26-jre-jammy
# CVE-2026-84782 (OpenSSL information disclosure): the base image ships
# libssl3 3.0.2-0ubuntu1.29, patched in 1.30, which is already in Ubuntu's
# security index but not yet rebuilt into the published base image. Upgrade
# explicitly rather than waiting on an upstream rebuild. Deliberately
# unpinned (DL3008): Ubuntu's index only carries the latest patch, so an
# exact pin would break the build as soon as the next fix ships.
# hadolint ignore=DL3008
RUN apt-get update && \
    apt-get install -y --no-install-recommends --only-upgrade libssl3 && \
    rm -rf /var/lib/apt/lists/*
RUN groupadd --system --gid 1000 recengine && \
    useradd --system --uid 1000 --gid recengine --no-create-home recengine
WORKDIR /app
COPY --from=build /workspace/recommender-service/target/recommender-service.jar app.jar
RUN chown recengine:recengine /app/app.jar
USER 1000:1000
EXPOSE 9091
ENTRYPOINT ["java", "-jar", "app.jar"]
