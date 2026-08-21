package com.avanti.recengine.support.wands;

/**
 * A raw row from WANDS {@code product.csv}. This is parsing output, not a
 * service's domain model — under hexagonal architecture, search-service's
 * ingestion adapter maps this into its own domain {@code Product} type.
 * {@code productClass}, {@code categoryHierarchy}, {@code productDescription},
 * and the rating fields are frequently blank in the source data and are left
 * null rather than defaulted.
 */
public record WandsProductRow(
        String productId,
        String productName,
        String productClass,
        String categoryHierarchy,
        String productDescription,
        String productFeatures,
        Integer ratingCount,
        Double averageRating,
        Integer reviewCount
) {
}
