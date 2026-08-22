package com.avanti.recengine.recommender.domain;

import java.util.List;

/**
 * Mirrors the sibling {@code search} project's {@code SearchStrategy}
 * pattern: a pluggable, framework-free interface that can rerank, filter,
 * or inject candidates. Implementations live in {@code domain.strategy}.
 */
public interface RecommendationStrategy {
    List<ScoredProduct> apply(RecommendationContext context, List<ScoredProduct> baseResults);

    /** Short display name, surfaced to the frontend as the result's "source" badge. */
    String name();
}
