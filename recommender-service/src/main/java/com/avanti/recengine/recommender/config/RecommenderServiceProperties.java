package com.avanti.recengine.recommender.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "recommender-service")
public record RecommenderServiceProperties(Clickstream clickstream, NeuralRanker neuralRanker, Pinecone pinecone) {

    public record Clickstream(Path dataPath, Path productCatalogPath) {
    }

    public record NeuralRanker(Path modelPath) {
    }

    /** Same three settings as search-service's own Pinecone config — same index, same local-vs-hosted swap story. */
    public record Pinecone(String controlPlaneHost, String apiKey, boolean tlsEnabled, String index) {
    }
}
