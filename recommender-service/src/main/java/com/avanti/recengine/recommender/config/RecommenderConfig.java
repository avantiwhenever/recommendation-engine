package com.avanti.recengine.recommender.config;

import com.avanti.recengine.recommender.adapter.out.clickstream.CsvClickstreamRepositoryAdapter;
import com.avanti.recengine.recommender.adapter.out.onnx.OnnxRankingModelAdapter;
import com.avanti.recengine.recommender.adapter.out.pinecone.PineconeVectorSimilarityAdapter;
import com.avanti.recengine.recommender.application.RecommendService;
import com.avanti.recengine.recommender.domain.RecommendationStrategy;
import com.avanti.recengine.recommender.domain.Strategy;
import com.avanti.recengine.recommender.domain.strategy.BanditExploreStrategy;
import com.avanti.recengine.recommender.domain.strategy.CollaborativeFilteringStrategy;
import com.avanti.recengine.recommender.domain.strategy.DiversityAwareStrategy;
import com.avanti.recengine.recommender.domain.strategy.NeuralRankingStrategy;
import com.avanti.recengine.recommender.domain.strategy.PassthroughStrategy;
import com.avanti.recengine.recommender.domain.strategy.PopularityBoostStrategy;
import com.avanti.recengine.recommender.port.in.RecommendUseCase;
import com.avanti.recengine.recommender.port.out.ClickstreamRepositoryPort;
import com.avanti.recengine.recommender.port.out.RankingModelPort;
import com.avanti.recengine.recommender.port.out.VectorSimilarityPort;
import com.avanti.recengine.support.pinecone.PineconeVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Manually builds each {@link RecommendationStrategy} bean and exposes a
 * single enum-keyed map — the same pattern as the sibling {@code search}
 * project's {@code SearchConfig}/{@code StrategyType}, kept here so
 * {@code domain}/{@code application} never need a Spring dependency to
 * express "pick a strategy by enum."
 */
@Configuration
public class RecommenderConfig {

    private static final Logger log = LoggerFactory.getLogger(RecommenderConfig.class);
    private static final int MAX_CONNECT_ATTEMPTS = 10;
    private static final long RETRY_DELAY_MILLIS = 2000;

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
     * not the index's owner. Same retry-with-backoff story as
     * search-service's bean, since {@code pinecone-local} takes a few
     * seconds to accept connections after container start.
     */
    @Bean
    public PineconeVectorStore pineconeVectorStore(RecommenderServiceProperties properties) {
        RecommenderServiceProperties.Pinecone config = properties.pinecone();
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_CONNECT_ATTEMPTS; attempt++) {
            try {
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
    public VectorSimilarityPort vectorSimilarityPort(PineconeVectorStore pineconeVectorStore) {
        return new PineconeVectorSimilarityAdapter(pineconeVectorStore);
    }

    @Bean
    public Map<Strategy, RecommendationStrategy> strategiesByType(
            ClickstreamRepositoryPort clickstream, RankingModelPort rankingModel) {
        // Shared instance: also CollaborativeFilteringStrategy's named
        // cold-start fallback — one popularity blend implementation, not
        // two independently-constructed copies.
        PopularityBoostStrategy popularityBoost = new PopularityBoostStrategy(clickstream);
        return Map.of(
                Strategy.NONE, new PassthroughStrategy(),
                Strategy.POPULARITY, popularityBoost,
                Strategy.COLLABORATIVE, new CollaborativeFilteringStrategy(clickstream, popularityBoost),
                Strategy.BANDIT, new BanditExploreStrategy(clickstream),
                Strategy.NEURAL, new NeuralRankingStrategy(clickstream, rankingModel),
                // DiversityAwareStrategy is a decorator, not a
                // from-scratch strategy — wraps the same popularity
                // instance above with an MMR re-rank pass rather than
                // constructing a second, independent PopularityBoostStrategy.
                Strategy.DIVERSE_POPULARITY, new DiversityAwareStrategy(popularityBoost)
        );
    }

    @Bean
    public RecommendUseCase recommendUseCase(Map<Strategy, RecommendationStrategy> strategiesByType) {
        return new RecommendService(strategiesByType);
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
