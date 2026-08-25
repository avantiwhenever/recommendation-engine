package com.avanti.recengine.recommender.eval;

import com.avanti.recengine.recommender.domain.ScoredProduct;

import java.util.List;
import java.util.Map;

/**
 * One clickstream session reconstructed as an eval unit: the candidates
 * "shown" (every product with a view event, in position order) plus two
 * independent notions of ground truth for the same candidates:
 *
 * <ul>
 *   <li>{@code relevanceGrades} — implicit, clickstream-derived (0=view
 *       only, 1=click, 2=add_to_cart, 3=purchase — max per product per
 *       session). Circular: this grade is itself a probabilistic function
 *       of the same WANDS {@code label.csv} grade used to construct the
 *       session's candidate order in the first place — see
 *       {@code WANDS/scripts/generate_clickstream.py}.</li>
 *   <li>{@code independentRelevanceGrades} — explicit, from WANDS'
 *       original human annotation ({@code label.csv}), keyed by this
 *       session's {@code queryId} via {@link WandsLabelLoader}. Entirely
 *       independent of the clickstream generator's click-probability
 *       model, even though the candidate *ordering* was influenced by it.
 *       Products with no judgment for this query default to grade 0.</li>
 * </ul>
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
        String queryId,
        List<ScoredProduct> baseCandidates,
        Map<String, Integer> relevanceGrades,
        Map<String, Integer> independentRelevanceGrades
) {
}
