package com.avanti.recengine.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "search-service")
public record SearchServiceProperties(Pinecone pinecone, Models models) {

    public record Pinecone(String controlPlaneHost, String apiKey, boolean tlsEnabled, String index) {
    }

    public record Models(Path embeddingDir) {
    }
}
