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
