package com.avanti.recengine.gateway.domain;

/**
 * This service's own domain type — the GraphQL layer maps record accessors
 * directly to schema field names (productId/name/productClass/... match
 * the {@code Product} type in schema.graphqls), so no separate DTO layer.
 *
 * <p>{@code averageRating}/{@code ratingCount} are plain primitives, not
 * boxed/nullable, because the upstream proto {@code ScoredProduct} message
 * is proto3 (no field presence for these types) — a genuine "unset" rating
 * is indistinguishable from a real 0.0/0 rating. Documented limitation, not
 * fixed here since it would require a proto contract change owned jointly
 * with search-service/recommender-service.
 */
public record Product(
        String productId,
        String name,
        String productClass,
        String categoryHierarchy,
        double averageRating,
        int ratingCount,
        double score,
        String source
) {
    public Product withSource(String newSource) {
        return new Product(productId, name, productClass, categoryHierarchy, averageRating, ratingCount, score, newSource);
    }
}
