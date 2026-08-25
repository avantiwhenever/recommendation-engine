package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.CatalogEntry;
import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.ScoredProduct;
import com.avanti.recengine.recommender.domain.Strategy;
import com.avanti.recengine.recommender.domain.UserProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This is a probabilistic strategy (Thompson Sampling), so a single
 * fixed-seed snapshot only proves the code runs, not that the selection
 * mechanism behaves correctly — most tests here run many trials and assert
 * on the observed distribution instead.
 */
class BanditExploreStrategyTest {

    @Test
    void fewerThanTwoCandidatesIsUnchanged() {
        FakeClickstreamRepository repo = new FakeClickstreamRepository(List.of());
        List<ScoredProduct> single = List.of(new ScoredProduct("1", 5.0, "a", "Chairs", "Furniture / Chairs", 4.0, 10));

        List<ScoredProduct> result = new BanditExploreStrategy(repo)
                .apply(new RecommendationContext("chair", "u1", Strategy.BANDIT), single);

        assertThat(result).isEqualTo(single);
    }

    @Test
    void resultIsAlwaysAPermutationOfTheInput() {
        FakeClickstreamRepository repo = strongVsWeakRepo();
        List<ScoredProduct> base = strongVsWeakCandidates();

        List<ScoredProduct> result = new BanditExploreStrategy(repo, new Random(42))
                .apply(new RecommendationContext("chair", "unknown-user", Strategy.BANDIT), base);

        assertThat(result).extracting(ScoredProduct::productId)
                .containsExactlyInAnyOrder("s1", "s2", "w1", "w2");
    }

    @Test
    void historicalPopularityBiasesSelectionTowardStrongerCategoryArm() {
        FakeClickstreamRepository repo = strongVsWeakRepo();
        List<ScoredProduct> base = strongVsWeakCandidates();
        BanditExploreStrategy strategy = new BanditExploreStrategy(repo, new Random(7));
        RecommendationContext noContext = new RecommendationContext("chair", "", Strategy.BANDIT);

        int trials = 1000;
        int strongWinsFirstSlot = 0;
        for (int i = 0; i < trials; i++) {
            List<ScoredProduct> result = strategy.apply(noContext, base);
            if (result.get(0).categoryHierarchy().startsWith("Strong")) {
                strongWinsFirstSlot++;
            }
        }

        // Strong's historical prior (alpha~21, beta~1) is overwhelmingly
        // stronger than Weak's (alpha~1.2, beta~20.8) — Strong should win
        // the top slot in the large majority of trials, not just "more
        // often than chance."
        assertThat(strongWinsFirstSlot).isGreaterThan((int) (trials * 0.85));
    }

    @Test
    void userContextIncreasesTheFavoredArmsWinRateRelativeToNoContext() {
        // Deliberately NOT reusing strongVsWeakRepo(): the top-popularity
        // category always normalizes to the maximal alpha=21/beta=1 prior
        // (mean ~0.955, tightly concentrated — variance ~0.002), which no
        // realistic context boost can out-compete for "wins slot 0." This
        // scenario instead makes both candidate arms moderately (not
        // maximally) popular relative to a third, uninvolved "Dominant"
        // category that only exists to anchor the normalization — so
        // neither ArmA nor ArmB starts pinned to the extreme edge, and the
        // context boost has room to move the outcome.
        FakeClickstreamRepository repo = new FakeClickstreamRepository(List.of("dominant", "a1", "a2", "b1", "b2"));
        repo.withPopularity("dominant", 1000.0);
        repo.withPopularity("a1", 100.0);
        repo.withPopularity("a2", 100.0);
        repo.withPopularity("b1", 50.0);
        repo.withPopularity("b2", 50.0);
        repo.withCatalogEntry(new CatalogEntry("dominant", "Dominant item", "Class", "Dominant / Sub", 4.5, 100));
        repo.withCatalogEntry(new CatalogEntry("a1", "A item 1", "Class", "ArmA / Sub", 4.5, 100));
        repo.withCatalogEntry(new CatalogEntry("a2", "A item 2", "Class", "ArmA / Sub", 4.5, 100));
        repo.withCatalogEntry(new CatalogEntry("b1", "B item 1", "Class", "ArmB / Sub", 4.5, 100));
        repo.withCatalogEntry(new CatalogEntry("b2", "B item 2", "Class", "ArmB / Sub", 4.5, 100));
        repo.withProfile(new UserProfile("b-fan", Set.of(), Map.of("ArmB", 10L)));

        List<ScoredProduct> base = List.of(
                new ScoredProduct("a1", 4.0, "A item 1", "Class", "ArmA / Sub", 4.5, 100),
                new ScoredProduct("b1", 3.0, "B item 1", "Class", "ArmB / Sub", 4.5, 100),
                new ScoredProduct("a2", 2.0, "A item 2", "Class", "ArmA / Sub", 4.5, 100),
                new ScoredProduct("b2", 1.0, "B item 2", "Class", "ArmB / Sub", 4.5, 100)
        );

        BanditExploreStrategy baselineStrategy = new BanditExploreStrategy(repo, new Random(99));
        RecommendationContext noContext = new RecommendationContext("chair", "", Strategy.BANDIT);
        BanditExploreStrategy contextStrategy = new BanditExploreStrategy(repo, new Random(99));
        RecommendationContext bFanContext = new RecommendationContext("chair", "b-fan", Strategy.BANDIT);

        int trials = 1000;
        int armBWinsNoContext = 0;
        int armBWinsWithContext = 0;
        for (int i = 0; i < trials; i++) {
            if (baselineStrategy.apply(noContext, base).get(0).categoryHierarchy().startsWith("ArmB")) {
                armBWinsNoContext++;
            }
            if (contextStrategy.apply(bFanContext, base).get(0).categoryHierarchy().startsWith("ArmB")) {
                armBWinsWithContext++;
            }
        }

        // The real claim is relative: the context boost measurably raises
        // ArmB's win rate over the no-context baseline, proving
        // contextTopCategory() actually affects selection (the old
        // implementation never read RecommendationContext at all).
        assertThat(armBWinsWithContext).isGreaterThan(armBWinsNoContext);
    }

    @Test
    void calibrationCapLimitsOneArmsShareOfTheVisibleWindow() {
        FakeClickstreamRepository repo = new FakeClickstreamRepository(List.of());
        List<ScoredProduct> base = new java.util.ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            base.add(new ScoredProduct("popular-" + i, 5.0, "p" + i, "Popular", "Popular / Sub", 4.0, 10));
        }
        for (int i = 1; i <= 2; i++) {
            base.add(new ScoredProduct("rare-" + i, 5.0, "r" + i, "Rare", "Rare / Sub", 4.0, 10));
        }
        // "Rare" has exactly enough items (2) to cover the calibration
        // window's complement (windowSize=5, cap=ceil(5*0.6)=3, so the
        // window's other 2 slots need exactly 2 items from elsewhere) — the
        // scenario this class's Javadoc explains is required for the cap to
        // actually bind rather than relax.

        for (int seed = 0; seed < 200; seed++) {
            BanditExploreStrategy strategy = new BanditExploreStrategy(repo, new Random(seed));
            List<ScoredProduct> result = strategy.apply(new RecommendationContext("chair", "", Strategy.BANDIT), base);

            long popularInWindow = result.subList(0, 5).stream()
                    .filter(p -> p.categoryHierarchy().startsWith("Popular")).count();

            assertThat(popularInWindow)
                    .as("seed %d: Popular arm's share of the first 5 positions", seed)
                    .isLessThanOrEqualTo(3);
        }
    }

    private static FakeClickstreamRepository strongVsWeakRepo() {
        FakeClickstreamRepository repo = new FakeClickstreamRepository(List.of("s1", "s2", "w1", "w2"));
        repo.withPopularity("s1", 100.0);
        repo.withPopularity("s2", 90.0);
        repo.withPopularity("w1", 1.0);
        repo.withPopularity("w2", 1.0);
        repo.withCatalogEntry(new CatalogEntry("s1", "Strong item 1", "Class", "Strong / Sub", 4.5, 100));
        repo.withCatalogEntry(new CatalogEntry("s2", "Strong item 2", "Class", "Strong / Sub", 4.5, 100));
        repo.withCatalogEntry(new CatalogEntry("w1", "Weak item 1", "Class", "Weak / Sub", 4.5, 100));
        repo.withCatalogEntry(new CatalogEntry("w2", "Weak item 2", "Class", "Weak / Sub", 4.5, 100));
        return repo;
    }

    private static List<ScoredProduct> strongVsWeakCandidates() {
        return List.of(
                new ScoredProduct("w1", 5.0, "Weak item 1", "Class", "Weak / Sub", 4.5, 100),
                new ScoredProduct("s1", 4.0, "Strong item 1", "Class", "Strong / Sub", 4.5, 100),
                new ScoredProduct("w2", 3.0, "Weak item 2", "Class", "Weak / Sub", 4.5, 100),
                new ScoredProduct("s2", 2.0, "Strong item 2", "Class", "Strong / Sub", 4.5, 100)
        );
    }
}
