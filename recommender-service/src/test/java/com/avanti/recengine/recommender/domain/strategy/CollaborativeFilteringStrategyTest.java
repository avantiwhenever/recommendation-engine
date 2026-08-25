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
    void fallsBackToBaseRankingForUnknownUser() {
        FakeClickstreamRepository repo = new FakeClickstreamRepository(List.of());
        List<ScoredProduct> base = List.of(
                new ScoredProduct("1", 5.0, "a", "Chairs", "Furniture / Chairs", 4.0, 10)
        );

        List<ScoredProduct> result = new CollaborativeFilteringStrategy(repo)
                .apply(new RecommendationContext("chair", "unknown-user", Strategy.COLLABORATIVE), base);

        assertThat(result).isEqualTo(base);
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
