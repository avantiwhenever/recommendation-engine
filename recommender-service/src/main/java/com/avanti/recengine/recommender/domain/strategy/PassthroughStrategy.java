package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.RecommendationStrategy;
import com.avanti.recengine.recommender.domain.ScoredProduct;

import java.util.List;

/** Control-group baseline: returns search-service's candidates unchanged. */
public final class PassthroughStrategy implements RecommendationStrategy {

    @Override
    public List<ScoredProduct> apply(RecommendationContext context, List<ScoredProduct> baseResults) {
        return List.copyOf(baseResults);
    }

    @Override
    public String name() {
        return "None";
    }
}
