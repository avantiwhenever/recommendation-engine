package com.avanti.recengine.gateway.domain;

import java.util.List;

/**
 * Result of a recommender-service call: the (possibly added/removed/
 * re-ranked) product list plus which strategy produced it. The wire
 * contract (RecommendResponse) carries one {@code source} string per whole
 * response, not per product, so every product in {@code products} shares
 * this same source label.
 */
public record RecommendResult(List<Product> products, String source) {
}
