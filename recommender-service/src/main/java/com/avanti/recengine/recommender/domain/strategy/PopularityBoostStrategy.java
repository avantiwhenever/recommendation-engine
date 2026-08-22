package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.CatalogEntry;
import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.RecommendationStrategy;
import com.avanti.recengine.recommender.domain.ScoredProduct;
import com.avanti.recengine.recommender.port.out.ClickstreamRepositoryPort;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reranks by blending search-service's own score with clickstream
 * popularity, then injects globally popular "trending" products absent from
 * the original candidates — a real add, not just a rerank, per the "add/
 * remove results from search-service" requirement.
 */
public final class PopularityBoostStrategy implements RecommendationStrategy {

    private static final double SEARCH_SCORE_WEIGHT = 0.6;
    private static final double POPULARITY_WEIGHT = 0.4;
    private static final int MAX_INJECTED = 2;

    private final ClickstreamRepositoryPort clickstream;

    public PopularityBoostStrategy(ClickstreamRepositoryPort clickstream) {
        this.clickstream = clickstream;
    }

    @Override
    public List<ScoredProduct> apply(RecommendationContext context, List<ScoredProduct> baseResults) {
        if (baseResults.isEmpty()) {
            return List.of();
        }

        Map<ScoredProduct, Double> normalizedSearchScore = Normalization.minMax(baseResults, ScoredProduct::score);
        Map<ScoredProduct, Double> normalizedPopularity =
                Normalization.minMax(baseResults, p -> clickstream.popularityScore(p.productId()));

        List<ScoredProduct> reranked = new ArrayList<>(baseResults.size());
        for (ScoredProduct product : baseResults) {
            double blended = SEARCH_SCORE_WEIGHT * normalizedSearchScore.get(product)
                    + POPULARITY_WEIGHT * normalizedPopularity.get(product);
            reranked.add(product.withScore(blended));
        }
        reranked.sort((a, b) -> Double.compare(b.score(), a.score()));

        Set<String> present = new LinkedHashSet<>();
        for (ScoredProduct p : reranked) {
            present.add(p.productId());
        }
        int injected = 0;
        for (String candidateId : clickstream.mostPopularProducts(MAX_INJECTED + present.size())) {
            if (injected >= MAX_INJECTED) {
                break;
            }
            if (present.contains(candidateId)) {
                continue;
            }
            clickstream.catalogEntry(candidateId).ifPresent(entry -> reranked.add(entry.toScoredProduct(0.0)));
            injected++;
        }

        return reranked;
    }

    @Override
    public String name() {
        return "Popularity";
    }
}
