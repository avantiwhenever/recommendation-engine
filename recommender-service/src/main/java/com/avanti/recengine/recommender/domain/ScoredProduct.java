package com.avanti.recengine.recommender.domain;

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
    public ScoredProduct withScore(double newScore) {
        return new ScoredProduct(productId, newScore, productName, productClass, categoryHierarchy, averageRating, ratingCount);
    }
}
