package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.RecommendationStrategy;
import com.avanti.recengine.recommender.domain.Strategy;
import com.avanti.recengine.recommender.port.out.ClickstreamRepositoryPort;
import com.avanti.recengine.recommender.port.out.RankingModelPort;
import com.avanti.recengine.recommender.port.out.VectorSimilarityPort;

import java.util.Map;
import java.util.Random;

/** Builds the full {@link Strategy} → implementation map, shared by the served app and the offline eval. */
public final class Strategies {

    private Strategies() {
    }

    /**
     * @param vectorSimilarityPort embedding similarity for {@link DiversityAwareStrategy}; {@code null}
     *                             falls back to its category proxy alone
     * @param random               randomness source for {@link BanditExploreStrategy}'s Thompson draws
     */
    public static Map<Strategy, RecommendationStrategy> create(ClickstreamRepositoryPort clickstream,
                                                               RankingModelPort rankingModel,
                                                               VectorSimilarityPort vectorSimilarityPort,
                                                               Random random) {
        // Shared instance: also CollaborativeFilteringStrategy's named
        // cold-start fallback and the base DiversityAwareStrategy decorates —
        // one popularity blend implementation, not several
        // independently-constructed copies.
        PopularityBoostStrategy popularityBoost = new PopularityBoostStrategy(clickstream);
        return Map.of(
                Strategy.NONE, new PassthroughStrategy(),
                Strategy.POPULARITY, popularityBoost,
                Strategy.COLLABORATIVE, new CollaborativeFilteringStrategy(clickstream, popularityBoost),
                Strategy.BANDIT, new BanditExploreStrategy(clickstream, random),
                Strategy.NEURAL, new NeuralRankingStrategy(clickstream, rankingModel),
                // vectorSimilarityPort gives the diversity metric real
                // embedding similarity, blended with the category proxy —
                // see DiversityAwareStrategy's class Javadoc for why blended,
                // not substituted, and why "one lookup per candidate, not
                // per pairwise comparison" is what keeps this affordable.
                Strategy.DIVERSE_POPULARITY, new DiversityAwareStrategy(popularityBoost, vectorSimilarityPort)
        );
    }
}
