package com.avanti.recengine.recommender.port.in;

import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.ScoredProduct;

import java.util.List;

public interface RecommendUseCase {

    /** The result plus which strategy actually produced it — the frontend's "source" badge. */
    record Recommendation(List<ScoredProduct> results, String source) {
    }

    Recommendation recommend(RecommendationContext context, List<ScoredProduct> baseResults);
}
