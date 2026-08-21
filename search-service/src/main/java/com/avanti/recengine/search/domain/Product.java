package com.avanti.recengine.search.domain;

/** This hexagon's own product model — distinct from rec-support's raw CSV parsing output and the generated proto wire type. */
public record Product(
        String productId,
        String productName,
        String productClass,
        String categoryHierarchy,
        Double averageRating,
        Integer ratingCount
) {
}
