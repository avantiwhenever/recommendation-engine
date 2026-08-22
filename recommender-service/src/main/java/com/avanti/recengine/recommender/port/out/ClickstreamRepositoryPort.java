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

    /** Top globally co-occurring products with productId, most-frequent first, excluding productId itself. */
    List<String> relatedProducts(String productId, int limit);

    /** Globally most popular products, most-popular first — used to inject "trending" candidates. */
    List<String> mostPopularProducts(int limit);

    Optional<CatalogEntry> catalogEntry(String productId);
}
