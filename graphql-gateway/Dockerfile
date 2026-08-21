# Build stage: matches the local toolchain (Maven 3.9.x, JDK 21) exactly.
FROM maven:3.9.9-eclipse-temurin-21 AS build
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
FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system --gid 1000 recengine && \
    useradd --system --uid 1000 --gid recengine --no-create-home recengine
WORKDIR /app
COPY --from=build /workspace/graphql-gateway/target/graphql-gateway.jar app.jar
RUN chown recengine:recengine /app/app.jar
USER 1000:1000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
