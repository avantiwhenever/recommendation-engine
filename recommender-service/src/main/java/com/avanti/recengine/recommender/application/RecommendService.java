package com.avanti.recengine.recommender.application;

import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.RecommendationStrategy;
import com.avanti.recengine.recommender.domain.ScoredProduct;
import com.avanti.recengine.recommender.domain.Strategy;
import com.avanti.recengine.recommender.port.in.RecommendUseCase;

import java.util.List;
import java.util.Map;

/**
 * Dispatches to the requested {@link RecommendationStrategy} by a manually
 * wired enum-keyed map — same pattern as the sibling {@code search}
 * project's {@code SearchConfig}/{@code StrategyType}, chosen there (and
 * here) so strategy selection logic stays out of the strategies themselves
 * and the map can be built without Spring's own bean-map auto-population.
 */
public final class RecommendService implements RecommendUseCase {

    private final Map<Strategy, RecommendationStrategy> strategiesByType;

    public RecommendService(Map<Strategy, RecommendationStrategy> strategiesByType) {
        this.strategiesByType = strategiesByType;
    }

    @Override
    public Recommendation recommend(RecommendationContext context, List<ScoredProduct> baseResults) {
        RecommendationStrategy strategy = strategiesByType.get(context.strategy());
        if (strategy == null) {
            throw new IllegalArgumentException("No strategy registered for " + context.strategy());
        }
        List<ScoredProduct> results = strategy.apply(context, baseResults);
        return new Recommendation(results, strategy.name());
    }
}
