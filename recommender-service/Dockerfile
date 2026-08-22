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
RUN groupadd --system --gid 1000 recengine && \
    useradd --system --uid 1000 --gid recengine --no-create-home recengine
WORKDIR /app
COPY --from=build /workspace/recommender-service/target/recommender-service.jar app.jar
RUN chown recengine:recengine /app/app.jar
USER 1000:1000
EXPOSE 9091
ENTRYPOINT ["java", "-jar", "app.jar"]
