package com.avanti.recengine.recommender.eval;

/** Per-session metric values for one strategy. */
public record QueryMetrics(
        String sessionId,
        double ndcgAt5,
        double mrr,
        double recallAt5,
        double precisionAt5
) {
}
