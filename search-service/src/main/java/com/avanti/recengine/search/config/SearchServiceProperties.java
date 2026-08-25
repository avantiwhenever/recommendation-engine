package com.avanti.recengine.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "search-service")
public record SearchServiceProperties(Pinecone pinecone, Models models, Data data, Hybrid hybrid) {

    public record Pinecone(String controlPlaneHost, String apiKey, boolean tlsEnabled, String index) {
    }

    public record Models(Path embeddingDir) {
    }

    /** Where the lexical (BM25) index loads its catalog text from at startup — see TODO.md #6. */
    public record Data(Path productCsv) {
    }

    /** Tuning for the hybrid dense+lexical fusion in {@link com.avanti.recengine.search.application.SearchProductsService}. */
    public record Hybrid(int candidatePoolSize, int rrfK) {
    }
}
