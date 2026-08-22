package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.RecommendationStrategy;
import com.avanti.recengine.recommender.domain.ScoredProduct;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Epsilon-greedy exploration re-ranking, per arXiv:2207.00109 ("Ranking in
 * Contextual Multi-Armed Bandits") and arXiv:2106.10898 (BanditMF): with
 * probability {@code epsilon} per position, promotes a lower-ranked
 * candidate ahead of a higher-ranked one instead of always exploiting the
 * base ranking — trading a little precision for the ability to surface
 * under-exposed products, the standard explore/exploit tradeoff.
 *
 * <p>Takes an injectable {@link Random} so tests can assert deterministic
 * behavior instead of asserting on randomness itself.
 */
public final class BanditExploreStrategy implements RecommendationStrategy {

    private static final double EPSILON = 0.15;

    private final Random random;

    public BanditExploreStrategy() {
        this(new Random());
    }

    public BanditExploreStrategy(Random random) {
        this.random = random;
    }

    @Override
    public List<ScoredProduct> apply(RecommendationContext context, List<ScoredProduct> baseResults) {
        if (baseResults.size() < 2) {
            return List.copyOf(baseResults);
        }
        List<ScoredProduct> result = new ArrayList<>(baseResults);
        // Single left-to-right explore pass: for each position (except the
        // last), with probability epsilon swap in a candidate from later in
        // the list — bounded, non-destructive exploration rather than a full
        // shuffle, so the base ranking's ordering signal is still mostly honored.
        for (int i = 0; i < result.size() - 1; i++) {
            if (random.nextDouble() < EPSILON) {
                int swapWith = i + 1 + random.nextInt(result.size() - i - 1);
                ScoredProduct tmp = result.get(i);
                result.set(i, result.get(swapWith));
                result.set(swapWith, tmp);
            }
        }
        return result;
    }

    @Override
    public String name() {
        return "Bandit Exploration";
    }
}
