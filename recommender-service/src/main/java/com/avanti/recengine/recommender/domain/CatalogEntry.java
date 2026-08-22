package com.avanti.recengine.recommender.domain;

/**
 * Minimal product catalog metadata (product_id -&gt; name/class/category/
 * rating), sourced from WANDS product.csv, used only so strategies can build
 * a complete {@link ScoredProduct} when injecting a product that wasn't in
 * search-service's original candidate list — the "add" half of "add/remove
 * results from the search service."
 */
public record CatalogEntry(
        String productId,
        String productName,
        String productClass,
        String categoryHierarchy,
        double averageRating,
        int ratingCount
) {
    public ScoredProduct toScoredProduct(double score) {
        return new ScoredProduct(productId, score, productName, productClass, categoryHierarchy, averageRating, ratingCount);
    }
}
