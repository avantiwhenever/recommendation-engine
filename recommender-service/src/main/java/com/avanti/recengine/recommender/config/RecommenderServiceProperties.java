package com.avanti.recengine.recommender.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "recommender-service")
public record RecommenderServiceProperties(Clickstream clickstream, NeuralRanker neuralRanker) {

    public record Clickstream(Path dataPath, Path productCatalogPath) {
    }

    public record NeuralRanker(Path modelPath) {
    }
}
