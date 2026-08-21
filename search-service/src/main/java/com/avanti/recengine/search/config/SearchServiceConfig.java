package com.avanti.recengine.search.config;

import com.avanti.recengine.search.adapter.out.embedding.OnnxEmbeddingAdapter;
import com.avanti.recengine.search.adapter.out.pinecone.PineconeVectorIndexAdapter;
import com.avanti.recengine.search.application.SearchProductsService;
import com.avanti.recengine.search.port.in.SearchProductsUseCase;
import com.avanti.recengine.search.port.out.EmbeddingPort;
import com.avanti.recengine.search.port.out.VectorIndexPort;
import com.avanti.recengine.support.embedding.EmbeddingService;
import com.avanti.recengine.support.pinecone.PineconeVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
@EnableConfigurationProperties(SearchServiceProperties.class)
public class SearchServiceConfig {

    private static final Logger log = LoggerFactory.getLogger(SearchServiceConfig.class);
    private static final int MAX_CONNECT_ATTEMPTS = 10;
    private static final long RETRY_DELAY_MILLIS = 2000;

    @Bean
    public EmbeddingService embeddingService(SearchServiceProperties properties) throws IOException {
        return new EmbeddingService(properties.models().embeddingDir());
    }

    @Bean
    public PineconeVectorStore pineconeVectorStore(SearchServiceProperties properties) {
        SearchServiceProperties.Pinecone config = properties.pinecone();

        // Pinecone Local (docker-compose's pinecone-local service) takes a
        // few seconds to accept connections after container start; retry
        // with backoff rather than requiring an explicit wait-for step.
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_CONNECT_ATTEMPTS; attempt++) {
            try {
                PineconeVectorStore.ensureServerlessIndex(
                        config.controlPlaneHost(), config.apiKey(), config.tlsEnabled(),
                        config.index(), EmbeddingService.DIMENSIONS);
                return new PineconeVectorStore(config.controlPlaneHost(), config.apiKey(), config.tlsEnabled(), config.index());
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("Pinecone not ready yet (attempt {}/{}): {}", attempt, MAX_CONNECT_ATTEMPTS, e.getMessage());
                sleep(RETRY_DELAY_MILLIS);
            }
        }
        throw new IllegalStateException("Could not connect to Pinecone at " + config.controlPlaneHost()
                + " after " + MAX_CONNECT_ATTEMPTS + " attempts", lastFailure);
    }

    @Bean
    public EmbeddingPort embeddingPort(EmbeddingService embeddingService) {
        return new OnnxEmbeddingAdapter(embeddingService);
    }

    @Bean
    public VectorIndexPort vectorIndexPort(PineconeVectorStore pineconeVectorStore) {
        return new PineconeVectorIndexAdapter(pineconeVectorStore);
    }

    @Bean
    public SearchProductsUseCase searchProductsUseCase(VectorIndexPort vectorIndexPort, EmbeddingPort embeddingPort) {
        return new SearchProductsService(vectorIndexPort, embeddingPort);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry Pinecone connection", e);
        }
    }
}
