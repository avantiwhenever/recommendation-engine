package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.ScoredProduct;
import com.avanti.recengine.recommender.domain.Strategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class BanditExploreStrategyTest {

    @Test
    void neverExploringLeavesOrderUnchanged() {
        // Random.nextDouble() always returning >= epsilon means the strategy
        // never swaps — an injected Random(seed) that always returns 1.0
        // isn't directly possible, so instead assert on a seed known to
        // produce zero epsilon-triggering draws isn't robust; simplest
        // deterministic proof is a custom Random subclass forcing no swaps.
        Random neverExplore = new Random() {
            @Override
            public double nextDouble() {
                return 1.0; // always >= EPSILON, so the swap branch never fires
            }
        };
        List<ScoredProduct> base = List.of(
                new ScoredProduct("1", 5.0, "a", "Chairs", "Furniture / Chairs", 4.0, 10),
                new ScoredProduct("2", 4.0, "b", "Chairs", "Furniture / Chairs", 4.0, 10),
                new ScoredProduct("3", 3.0, "c", "Chairs", "Furniture / Chairs", 4.0, 10)
        );

        List<ScoredProduct> result = new BanditExploreStrategy(neverExplore)
                .apply(new RecommendationContext("chair", "u1", Strategy.BANDIT), base);

        assertThat(result).isEqualTo(base);
    }

    @Test
    void alwaysExploringChangesOrder() {
        Random alwaysExplore = new Random() {
            @Override
            public double nextDouble() {
                return 0.0; // always < EPSILON, so the swap branch always fires
            }

            @Override
            public int nextInt(int bound) {
                return bound - 1; // deterministically swap with the last eligible position
            }
        };
        List<ScoredProduct> base = List.of(
                new ScoredProduct("1", 5.0, "a", "Chairs", "Furniture / Chairs", 4.0, 10),
                new ScoredProduct("2", 4.0, "b", "Chairs", "Furniture / Chairs", 4.0, 10),
                new ScoredProduct("3", 3.0, "c", "Chairs", "Furniture / Chairs", 4.0, 10)
        );

        List<ScoredProduct> result = new BanditExploreStrategy(alwaysExplore)
                .apply(new RecommendationContext("chair", "u1", Strategy.BANDIT), base);

        assertThat(result).isNotEqualTo(base);
        assertThat(result).extracting(ScoredProduct::productId).containsExactlyInAnyOrder("1", "2", "3");
    }

    @Test
    void fewerThanTwoCandidatesIsUnchanged() {
        List<ScoredProduct> single = List.of(new ScoredProduct("1", 5.0, "a", "Chairs", "Furniture / Chairs", 4.0, 10));
        List<ScoredProduct> result = new BanditExploreStrategy()
                .apply(new RecommendationContext("chair", "u1", Strategy.BANDIT), single);
        assertThat(result).isEqualTo(single);
    }
}
