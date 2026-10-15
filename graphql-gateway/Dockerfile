# Build stage: matches the local toolchain (Maven 3.9.x, JDK 26) exactly.
FROM maven:3.9.16-eclipse-temurin-26 AS build
WORKDIR /workspace

COPY pom.xml .
COPY rec-support/pom.xml rec-support/
COPY search-service/pom.xml search-service/
COPY recommender-service/pom.xml recommender-service/
COPY graphql-gateway/pom.xml graphql-gateway/
COPY proto proto
RUN mvn -q -B -pl graphql-gateway -am dependency:go-offline

COPY graphql-gateway/src graphql-gateway/src
RUN mvn -q -B -pl graphql-gateway -am package -DskipTests

# Runtime stage: JRE only. Kept on the same jammy base as the other two
# services for consistency, even though this one has no native dependencies.
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
COPY --from=build /workspace/graphql-gateway/target/graphql-gateway.jar app.jar
RUN chown recengine:recengine /app/app.jar
USER 1000:1000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
