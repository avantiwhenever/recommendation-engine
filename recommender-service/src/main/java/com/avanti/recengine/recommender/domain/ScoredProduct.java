package com.avanti.recengine.recommender.domain;

import java.util.Comparator;

/**
 * This service's own domain type for a candidate/result product — separate
 * from the generated proto {@code ScoredProduct}, which the gRPC adapter
 * maps to/from at the boundary.
 */
public record ScoredProduct(
        String productId,
        double score,
        String productName,
        String productClass,
        String categoryHierarchy,
        double averageRating,
        int ratingCount
) {
    public static final Comparator<ScoredProduct> BY_SCORE_DESCENDING =
            Comparator.comparingDouble(ScoredProduct::score).reversed();

    public ScoredProduct withScore(double newScore) {
        return new ScoredProduct(productId, newScore, productName, productClass, categoryHierarchy, averageRating, ratingCount);
    }
}
