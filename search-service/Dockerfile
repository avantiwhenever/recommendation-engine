# Build stage: matches the local toolchain (Maven 3.9.x, JDK 26) exactly.
FROM maven:3.9.16-eclipse-temurin-26 AS build
WORKDIR /workspace

COPY pom.xml .
COPY rec-support/pom.xml rec-support/
COPY search-service/pom.xml search-service/
COPY recommender-service/pom.xml recommender-service/
COPY graphql-gateway/pom.xml graphql-gateway/
COPY proto proto
RUN mvn -q -B -pl search-service -am dependency:go-offline

COPY rec-support/src rec-support/src
COPY search-service/src search-service/src
RUN mvn -q -B -pl search-service -am package -DskipTests

# Runtime stage: JRE only, glibc-based (jammy, not alpine) — ONNX Runtime's
# and the tokenizers JNI binding's native libraries are glibc-linked.
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
COPY --from=build /workspace/search-service/target/search-service.jar app.jar
# Also include the shaded ingestion CLI jar, so `docker compose run
# --entrypoint java search-service -jar ingestion-cli.jar` can populate
# Pinecone from inside the compose network — the CLI needs to resolve
# `pinecone-local` as a hostname, which only works from within the network,
# not from the host (Pinecone Local advertises its container-internal
# hostname as the data-plane host regardless of caller).
COPY --from=build /workspace/search-service/target/search-service-*-ingestion-cli.jar ingestion-cli.jar
RUN chown recengine:recengine /app/app.jar /app/ingestion-cli.jar
USER 1000:1000
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
