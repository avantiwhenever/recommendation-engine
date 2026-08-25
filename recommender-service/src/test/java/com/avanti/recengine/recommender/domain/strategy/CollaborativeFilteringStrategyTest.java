package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.CatalogEntry;
import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.ScoredProduct;
import com.avanti.recengine.recommender.domain.Strategy;
import com.avanti.recengine.recommender.domain.UserProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class CollaborativeFilteringStrategyTest {

    @Test
    void coldStartUserWithNoHistoryAndNoSessionSignalFallsBackToPopularityBlend() {
        // Genuine cold start (TODO.md item #10): no all-time profile AND no
        // session signal. Policy is a named, explicit fallback to
        // PopularityBoostStrategy, not a silent unmodified passthrough —
        // assert the result matches calling that strategy directly, not
        // just "the result differs from base" (which a bug could also do).
        FakeClickstreamRepository repo = new FakeClickstreamRepository(List.of());
        List<ScoredProduct> base = List.of(
                new ScoredProduct("1", 5.0, "a", "Chairs", "Furniture / Chairs", 4.0, 10),
                new ScoredProduct("2", 1.0, "b", "Lamps", "Home / Lamps", 4.0, 10)
        );
        RecommendationContext context = new RecommendationContext("chair", "unknown-user", Strategy.COLLABORATIVE);

        List<ScoredProduct> result = new CollaborativeFilteringStrategy(repo).apply(context, base);
        List<ScoredProduct> expectedFallback = new PopularityBoostStrategy(repo).apply(context, base);

        assertThat(result).isEqualTo(expectedFallback);
        assertThat(result).isNotEqualTo(base); // sanity: the fallback actually did something, not a no-op
    }

    @Test
    void sessionSignalAloneWithNoAllTimeHistoryIsNotTreatedAsColdStart() {
        // A user with live session signal but no persisted all-time
        // history should NOT hit the cold-start branch — the session
        // similarity term alone should drive personalization.
        FakeClickstreamRepository repo = new FakeClickstreamRepository(List.of());
        repo.withCoOccurrence("high-similarity", "session-seed", 50);
        repo.withCoOccurrence("low-similarity", "session-seed", 1);
        List<ScoredProduct> base = List.of(
                new ScoredProduct("low-similarity", 5.0, "a", "Chairs", "Furniture / Chairs", 4.0, 10),
                new ScoredProduct("high-similarity", 5.0, "b", "Chairs", "Furniture / Chairs", 4.0, 10)
        );
        RecommendationContext context = new RecommendationContext(
                "chair", "brand-new-user", Strategy.COLLABORATIVE, List.of("session-seed"));

        List<ScoredProduct> result = new CollaborativeFilteringStrategy(repo).apply(context, base);

        assertThat(result).extracting(ScoredProduct::productId)
                .containsExactly("high-similarity", "low-similarity");
    }

    @Test
    void sessionSignalWeighsMoreHeavilyThanAllTimeHistoryForTheSameSimilarity() {
        // Two users each have the same itemSimilarity(candidate, seed) to a
        // single seed product — one via context.recentProductIds() (this
        // session), the other via the all-time ClickstreamRepositoryPort
        // profile. The resulting boost should be measurably larger for the
        // session-sourced match (TODO.md item #11's "weighted more
        // heavily" requirement).
        FakeClickstreamRepository repo = new FakeClickstreamRepository(List.of());
        repo.withCoOccurrence("candidate", "seed", 10);
        List<ScoredProduct> base = List.of(new ScoredProduct("candidate", 0.0, "a", "Chairs", "Furniture / Chairs", 4.0, 10));

        RecommendationContext sessionContext = new RecommendationContext(
                "chair", "user-a", Strategy.COLLABORATIVE, List.of("seed"));
        double sessionBoost = new CollaborativeFilteringStrategy(repo).apply(sessionContext, base).get(0).score();

        repo.withProfile(new UserProfile("user-b", Set.of("seed"), Map.of()));
        RecommendationContext historyContext = new RecommendationContext("chair", "user-b", Strategy.COLLABORATIVE);
        double historyBoost = new CollaborativeFilteringStrategy(repo).apply(historyContext, base).get(0).score();

        assertThat(sessionBoost).isGreaterThan(historyBoost);
        assertThat(sessionBoost).isCloseTo(historyBoost * 2.5, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void boostsCandidatesCoOccurringWithUserHistory() {
        FakeClickstreamRepository repo = new FakeClickstreamRepository(List.of());
        repo.withProfile(new UserProfile("u1", Set.of("seed-product"), Map.of()));
        repo.withCoOccurrence("high-co-occurrence", "seed-product", 50);
        repo.withCoOccurrence("low-co-occurrence", "seed-product", 1);

        List<ScoredProduct> base = List.of(
                new ScoredProduct("low-co-occurrence", 5.0, "a", "Chairs", "Furniture / Chairs", 4.0, 10),
                new ScoredProduct("high-co-occurrence", 5.0, "b", "Chairs", "Furniture / Chairs", 4.0, 10)
        );

        List<ScoredProduct> result = new CollaborativeFilteringStrategy(repo)
                .apply(new RecommendationContext("chair", "u1", Strategy.COLLABORATIVE), base);

        assertThat(result).extracting(ScoredProduct::productId)
                .containsExactly("high-co-occurrence", "low-co-occurrence");
    }

    @Test
    void normalizedSimilarityCorrectsForPopularityBiasThatRawCoOccurrenceCountMisses() {
        // A and "seed-product" are both hugely popular (10,000 sessions
        // each) but only co-occur 40 times — no higher than chance for
        // items that popular. C is far less popular (80 sessions) but
        // co-occurs with "seed-product" in 30 of them — a genuinely strong
        // relative affinity. Raw co-occurrence count would rank A (40)
        // above C (30); normalized similarity must rank C above A.
        FakeClickstreamRepository repo = new FakeClickstreamRepository(List.of());
        repo.withProfile(new UserProfile("u1", Set.of("seed-product"), Map.of()));
        repo.withMarginalCount("seed-product", 10_000);
        repo.withMarginalCount("popular-but-unrelated", 10_000);
        repo.withMarginalCount("niche-but-affinitive", 80);
        repo.withCoOccurrence("seed-product", "popular-but-unrelated", 40);
        repo.withCoOccurrence("popular-but-unrelated", "seed-product", 40);
        repo.withCoOccurrence("seed-product", "niche-but-affinitive", 30);
        repo.withCoOccurrence("niche-but-affinitive", "seed-product", 30);

        // Sanity-check the raw counts really do favor the "wrong" ranking,
        // so this test is actually exercising the fix, not a tautology.
        assertThat(repo.coOccurrenceCount("popular-but-unrelated", Set.of("seed-product"))).isEqualTo(40);
        assertThat(repo.coOccurrenceCount("niche-but-affinitive", Set.of("seed-product"))).isEqualTo(30);

        double popularSimilarity = repo.itemSimilarity("popular-but-unrelated", "seed-product");
        double nicheSimilarity = repo.itemSimilarity("niche-but-affinitive", "seed-product");
        // popular: 40 / sqrt(10000*10000) = 0.0040
        // niche:   30 / sqrt(80*10000)     = 0.0335
        assertThat(popularSimilarity).isCloseTo(0.0040, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(nicheSimilarity).isCloseTo(0.0335, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(nicheSimilarity).isGreaterThan(popularSimilarity);

        List<ScoredProduct> base = List.of(
                new ScoredProduct("popular-but-unrelated", 5.0, "a", "Chairs", "Furniture / Chairs", 4.0, 10),
                new ScoredProduct("niche-but-affinitive", 5.0, "b", "Chairs", "Furniture / Chairs", 4.0, 10)
        );

        List<ScoredProduct> result = new CollaborativeFilteringStrategy(repo)
                .apply(new RecommendationContext("chair", "u1", Strategy.COLLABORATIVE), base);

        assertThat(result).extracting(ScoredProduct::productId)
                .containsExactly("niche-but-affinitive", "popular-but-unrelated");
    }

    @Test
    void injectsRelatedProductsFromUserHistoryNotInCandidates() {
        FakeClickstreamRepository repo = new FakeClickstreamRepository(List.of());
        repo.withProfile(new UserProfile("u1", Set.of("seed-product"), Map.of()));
        repo.withCoOccurrence("seed-product", "related-item", 10);
        repo.withCatalogEntry(new CatalogEntry("related-item", "Related Lamp", "Lamps", "Home / Lamps", 4.5, 20));

        List<ScoredProduct> base = List.of(
                new ScoredProduct("other", 5.0, "a", "Chairs", "Furniture / Chairs", 4.0, 10)
        );

        List<ScoredProduct> result = new CollaborativeFilteringStrategy(repo)
                .apply(new RecommendationContext("chair", "u1", Strategy.COLLABORATIVE), base);

        assertThat(result).extracting(ScoredProduct::productId, ScoredProduct::productName)
                .contains(tuple("related-item", "Related Lamp"));
    }
}
