package com.avanti.recengine.search.config;

import com.avanti.recengine.search.adapter.out.embedding.OnnxEmbeddingAdapter;
import com.avanti.recengine.search.adapter.out.lexical.InMemoryLexicalIndexAdapter;
import com.avanti.recengine.search.adapter.out.pinecone.PineconeVectorIndexAdapter;
import com.avanti.recengine.search.application.SearchProductsService;
import com.avanti.recengine.search.port.in.SearchProductsUseCase;
import com.avanti.recengine.search.port.out.EmbeddingPort;
import com.avanti.recengine.search.port.out.LexicalIndexPort;
import com.avanti.recengine.search.port.out.VectorIndexPort;
import com.avanti.recengine.support.embedding.EmbeddingService;
import com.avanti.recengine.support.pinecone.PineconeVectorStore;
import com.avanti.recengine.support.retry.Retry;
import com.avanti.recengine.support.wands.WandsProductCsvLoader;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(SearchServiceProperties.class)
public class SearchServiceConfig {

    private static final int MAX_CONNECT_ATTEMPTS = 10;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);

    @Bean
    public EmbeddingService embeddingService(SearchServiceProperties properties) throws IOException {
        return new EmbeddingService(properties.models().embeddingDir());
    }

    @Bean
    public PineconeVectorStore pineconeVectorStore(SearchServiceProperties properties) {
        SearchServiceProperties.Pinecone config = properties.pinecone();

        // Pinecone Local (docker-compose's pinecone-local service) takes a
        // few seconds to accept connections after container start; retry
        // rather than requiring an explicit wait-for step.
        return Retry.withFixedDelay("Pinecone at " + config.controlPlaneHost(), MAX_CONNECT_ATTEMPTS, RETRY_DELAY, () -> {
            PineconeVectorStore.ensureServerlessIndex(
                    config.controlPlaneHost(), config.apiKey(), config.tlsEnabled(),
                    config.index(), EmbeddingService.DIMENSIONS);
            return new PineconeVectorStore(config.controlPlaneHost(), config.apiKey(), config.tlsEnabled(), config.index());
        });
    }

    @Bean
    public EmbeddingPort embeddingPort(EmbeddingService embeddingService) {
        return new OnnxEmbeddingAdapter(embeddingService);
    }

    @Bean
    public VectorIndexPort vectorIndexPort(PineconeVectorStore pineconeVectorStore) {
        return new PineconeVectorIndexAdapter(pineconeVectorStore);
    }

    /**
     * Loads {@code product.csv} once at startup and builds a full in-memory
     * BM25 index from it (see {@link InMemoryLexicalIndexAdapter}'s
     * Javadoc) — the served application reads the raw catalog CSV
     * in-process, not just the ingestion CLI, so the {@code ./data} volume
     * mount in {@code docker-compose.yml} is a runtime dependency for this
     * bean, not only a batch-tool convenience.
     */
    @Bean
    public LexicalIndexPort lexicalIndexPort(SearchServiceProperties properties) {
        return new InMemoryLexicalIndexAdapter(WandsProductCsvLoader.load(properties.data().productCsv()));
    }

    @Bean
    public SearchProductsUseCase searchProductsUseCase(VectorIndexPort vectorIndexPort, EmbeddingPort embeddingPort,
                                                         LexicalIndexPort lexicalIndexPort,
                                                         SearchServiceProperties properties) {
        SearchServiceProperties.Hybrid hybrid = properties.hybrid();
        return new SearchProductsService(vectorIndexPort, embeddingPort, lexicalIndexPort,
                hybrid.candidatePoolSize(), hybrid.rrfK());
    }
}
