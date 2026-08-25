package com.avanti.recengine.recommender.config;

import com.avanti.recengine.recommender.adapter.out.clickstream.CsvClickstreamRepositoryAdapter;
import com.avanti.recengine.recommender.adapter.out.onnx.OnnxRankingModelAdapter;
import com.avanti.recengine.recommender.application.RecommendService;
import com.avanti.recengine.recommender.domain.RecommendationStrategy;
import com.avanti.recengine.recommender.domain.Strategy;
import com.avanti.recengine.recommender.domain.strategy.BanditExploreStrategy;
import com.avanti.recengine.recommender.domain.strategy.CollaborativeFilteringStrategy;
import com.avanti.recengine.recommender.domain.strategy.NeuralRankingStrategy;
import com.avanti.recengine.recommender.domain.strategy.PassthroughStrategy;
import com.avanti.recengine.recommender.domain.strategy.PopularityBoostStrategy;
import com.avanti.recengine.recommender.port.in.RecommendUseCase;
import com.avanti.recengine.recommender.port.out.ClickstreamRepositoryPort;
import com.avanti.recengine.recommender.port.out.RankingModelPort;
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

    @Bean
    public Map<Strategy, RecommendationStrategy> strategiesByType(
            ClickstreamRepositoryPort clickstream, RankingModelPort rankingModel) {
        return Map.of(
                Strategy.NONE, new PassthroughStrategy(),
                Strategy.POPULARITY, new PopularityBoostStrategy(clickstream),
                Strategy.COLLABORATIVE, new CollaborativeFilteringStrategy(clickstream),
                Strategy.BANDIT, new BanditExploreStrategy(clickstream),
                Strategy.NEURAL, new NeuralRankingStrategy(clickstream, rankingModel)
        );
    }

    @Bean
    public RecommendUseCase recommendUseCase(Map<Strategy, RecommendationStrategy> strategiesByType) {
        return new RecommendService(strategiesByType);
    }
}
