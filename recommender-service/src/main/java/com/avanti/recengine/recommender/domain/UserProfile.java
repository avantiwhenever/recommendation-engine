package com.avanti.recengine.recommender.domain;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Aggregated view of a user's clickstream history. Never null — an unknown
 * or userId-less request gets {@link #empty(String)}, so strategies don't
 * need null-checks scattered through their ranking logic.
 */
public record UserProfile(String userId, Set<String> interactedProductIds, Map<String, Long> categoryCounts) {

    public static UserProfile empty(String userId) {
        return new UserProfile(userId, Set.of(), Map.of());
    }

    public Optional<String> topCategory() {
        return categoryCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    public boolean hasInteracted(String productId) {
        return interactedProductIds.contains(productId);
    }
}
