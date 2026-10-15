package com.avanti.recengine.recommender.domain;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Aggregated view of a user's clickstream history. Never null — an unknown
 * or userId-less request gets {@link #empty(String)}, so strategies don't
 * need null-checks scattered through their ranking logic.
 *
 * <p>{@code interactedProductIds} and {@code categoryCounts} iterate in
 * first-interaction order (see {@code ClickstreamAggregates}), which is what
 * makes {@link #topCategory()}'s tie-break deterministic.
 */
public record UserProfile(String userId, Set<String> interactedProductIds, Map<String, Long> categoryCounts) {

    public static UserProfile empty(String userId) {
        return new UserProfile(userId, Set.of(), Map.of());
    }

    /**
     * The most-interacted category; on a tie, whichever of the tied
     * categories the user interacted with first. That matches
     * {@code training/train_neural_ranker.py}, where Python's {@code max()}
     * over an insertion-ordered dict returns the first maximal key — so
     * NeuralRankingStrategy's category-match feature agrees with training.
     */
    public Optional<String> topCategory() {
        String top = null;
        long topCount = Long.MIN_VALUE;
        for (Map.Entry<String, Long> entry : categoryCounts.entrySet()) {
            if (entry.getValue() > topCount) {
                top = entry.getKey();
                topCount = entry.getValue();
            }
        }
        return Optional.ofNullable(top);
    }

    public boolean hasInteracted(String productId) {
        return interactedProductIds.contains(productId);
    }
}
