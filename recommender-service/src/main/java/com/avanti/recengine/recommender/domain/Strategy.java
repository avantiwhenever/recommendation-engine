package com.avanti.recengine.recommender.domain;

/**
 * Mirrors the proto {@code Strategy} enum (NONE/POPULARITY/COLLABORATIVE/
 * BANDIT/NEURAL/DIVERSE_POPULARITY). Kept as a plain domain enum rather than
 * reusing the generated proto type directly in {@code domain}/{@code
 * application} — a deliberate boundary, even though proto enums are plain
 * data with no gRPC/Spring machinery attached, so the domain layer never
 * imports anything from the {@code grpc} generated-code package.
 */
public enum Strategy {
    NONE,
    POPULARITY,
    COLLABORATIVE,
    BANDIT,
    NEURAL,
    /** {@link com.avanti.recengine.recommender.domain.strategy.PopularityBoostStrategy} wrapped by {@link com.avanti.recengine.recommender.domain.strategy.DiversityAwareStrategy}. */
    DIVERSE_POPULARITY
}
