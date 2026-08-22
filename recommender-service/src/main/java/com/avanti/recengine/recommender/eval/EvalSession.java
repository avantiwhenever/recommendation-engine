package com.avanti.recengine.recommender.eval;

import com.avanti.recengine.recommender.domain.ScoredProduct;

import java.util.List;
import java.util.Map;

/**
 * One clickstream session reconstructed as an eval unit: the candidates
 * "shown" (every product with a view event, in position order) plus the
 * implicit relevance grade observed for each (0=view only, 1=click,
 * 2=add_to_cart, 3=purchase — max per product per session).
 *
 * <p>{@code baseCandidates} carries a {@code 1/position} score proxy, the
 * same substitute used at training time by {@code train_neural_ranker.py}
 * for the "score search-service would have assigned" — see
 * {@code NeuralRankingStrategy}'s Javadoc and {@code training/TRAINING.md}
 * for why this is a documented, honest approximation rather than a real
 * search-service score.
 */
public record EvalSession(
        String sessionId,
        String userId,
        List<ScoredProduct> baseCandidates,
        Map<String, Integer> relevanceGrades
) {
}
