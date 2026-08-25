package com.avanti.recengine.recommender.port.out;

import com.avanti.recengine.recommender.domain.CatalogEntry;
import com.avanti.recengine.recommender.domain.UserProfile;

import java.util.List;
import java.util.Optional;

public interface ClickstreamRepositoryPort {

    /** Never null — {@link UserProfile#empty} for an unknown/blank userId. */
    UserProfile userProfile(String userId);

    /** Log-scaled popularity signal (view/click/cart/purchase, weighted) for a product. 0 if never seen. */
    double popularityScore(String productId);

    /** Raw co-occurrence count: how often productId was interacted with in the same session as anyOf. */
    long coOccurrenceCount(String productId, java.util.Set<String> anyOf);

    /**
     * Normalized item-item similarity: raw co-occurrence divided by the
     * geometric mean of each item's own marginal interaction count (the
     * standard adjusted-cosine / binary-cosine normalization for item-item
     * CF — see Sarwar, Karypis, Konstan &amp; Riedl, "Item-Based
     * Collaborative Filtering Recommendation Algorithms," WWW 2001). Unlike
     * {@link #coOccurrenceCount}, two independently popular-but-unrelated
     * items don't score high here just from base rates. 1.0 for a product
     * compared to itself; 0.0 if either has no recorded interactions or
     * they never co-occurred.
     */
    double itemSimilarity(String productIdA, String productIdB);

    /** Top globally co-occurring products with productId, most-frequent first, excluding productId itself. */
    List<String> relatedProducts(String productId, int limit);

    /** Globally most popular products, most-popular first — used to inject "trending" candidates. */
    List<String> mostPopularProducts(int limit);

    Optional<CatalogEntry> catalogEntry(String productId);
}
