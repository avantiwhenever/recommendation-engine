package com.avanti.recengine.recommender.config;

import com.avanti.recengine.recommender.adapter.out.clickstream.CsvClickstreamRepositoryAdapter;
import com.avanti.recengine.recommender.adapter.out.onnx.OnnxRankingModelAdapter;
import com.avanti.recengine.recommender.adapter.out.pinecone.PineconeVectorSimilarityAdapter;
import com.avanti.recengine.recommender.application.RecommendService;
import com.avanti.recengine.recommender.domain.RecommendationStrategy;
import com.avanti.recengine.recommender.domain.Strategy;
import com.avanti.recengine.recommender.domain.strategy.Strategies;
import com.avanti.recengine.recommender.port.in.RecommendUseCase;
import com.avanti.recengine.recommender.port.out.ClickstreamRepositoryPort;
import com.avanti.recengine.recommender.port.out.RankingModelPort;
import com.avanti.recengine.recommender.port.out.VectorSimilarityPort;
import com.avanti.recengine.support.pinecone.PineconeVectorStore;
import com.avanti.recengine.support.retry.Retry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.Random;

/**
 * Manually builds each {@link RecommendationStrategy} bean and exposes a
 * single enum-keyed map — the same pattern as the sibling {@code search}
 * project's {@code SearchConfig}/{@code StrategyType}, kept here so
 * {@code domain}/{@code application} never need a Spring dependency to
 * express "pick a strategy by enum."
 */
@Configuration
public class RecommenderConfig {

    private static final int MAX_CONNECT_ATTEMPTS = 10;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);

    @Bean
    public ClickstreamRepositoryPort clickstreamRepositoryPort(RecommenderServiceProperties properties) {
        return new CsvClickstreamRepositoryAdapter(
                properties.clickstream().dataPath(),
                properties.clickstream().productCatalogPath());
    }

    @Bean
    public RankingModelPort rankingModelPort(RecommenderServiceProperties properties) {
        return new OnnxRankingModelAdapter(properties.neuralRanker().modelPath());
    }

    /**
     * Connects to the same {@code wands-products} index
     * {@code search-service} owns and populates — unlike search-service's
     * own bean, this one never calls {@code ensureServerlessIndex}; this
     * service is a read-only consumer of an index another service creates,
     * not the index's owner. Same retry story as
     * search-service's bean, since {@code pinecone-local} takes a few
     * seconds to accept connections after container start.
     */
    @Bean
    public PineconeVectorStore pineconeVectorStore(RecommenderServiceProperties properties) {
        RecommenderServiceProperties.Pinecone config = properties.pinecone();
        return Retry.withFixedDelay("Pinecone at " + config.controlPlaneHost(), MAX_CONNECT_ATTEMPTS, RETRY_DELAY,
                () -> new PineconeVectorStore(config.controlPlaneHost(), config.apiKey(), config.tlsEnabled(), config.index()));
    }

    @Bean
    public VectorSimilarityPort vectorSimilarityPort(PineconeVectorStore pineconeVectorStore) {
        return new PineconeVectorSimilarityAdapter(pineconeVectorStore);
    }

    @Bean
    public Map<Strategy, RecommendationStrategy> strategiesByType(
            ClickstreamRepositoryPort clickstream, RankingModelPort rankingModel, VectorSimilarityPort vectorSimilarityPort) {
        return Strategies.create(clickstream, rankingModel, vectorSimilarityPort, new Random());
    }

    @Bean
    public RecommendUseCase recommendUseCase(Map<Strategy, RecommendationStrategy> strategiesByType) {
        return new RecommendService(strategiesByType);
    }
}
