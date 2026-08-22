package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.CatalogEntry;
import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.ScoredProduct;
import com.avanti.recengine.recommender.domain.Strategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class PopularityBoostStrategyTest {

    @Test
    void rerankByBlendedSearchAndPopularityScore() {
        FakeClickstreamRepository repo = new FakeClickstreamRepository(List.of());
        repo.withPopularity("low-search-high-pop", 100.0);
        repo.withPopularity("high-search-low-pop", 1.0);

        List<ScoredProduct> base = List.of(
                new ScoredProduct("high-search-low-pop", 10.0, "a", "Chairs", "Furniture / Chairs", 4.0, 10),
                new ScoredProduct("low-search-high-pop", 1.0, "b", "Chairs", "Furniture / Chairs", 4.0, 10)
        );

        List<ScoredProduct> result = new PopularityBoostStrategy(repo)
                .apply(new RecommendationContext("chair", null, Strategy.POPULARITY), base);

        // Blended 0.6*search + 0.4*popularity (both min-max normalized to
        // [0,1] within this pair): high-search-low-pop gets 0.6*1.0+0.4*0.0=0.6,
        // low-search-high-pop gets 0.6*0.0+0.4*1.0=0.4 — search score's larger
        // weight keeps it on top even though popularity favors the other item.
        assertThat(result).extracting(ScoredProduct::productId)
                .containsExactly("high-search-low-pop", "low-search-high-pop");
    }

    @Test
    void injectsTrendingProductsNotInOriginalCandidates() {
        FakeClickstreamRepository repo = new FakeClickstreamRepository(List.of("trending-1", "already-present"));
        repo.withCatalogEntry(new CatalogEntry("trending-1", "Trending Lamp", "Lamps", "Home / Lamps", 4.7, 500));

        List<ScoredProduct> base = List.of(
                new ScoredProduct("already-present", 5.0, "x", "Chairs", "Furniture / Chairs", 4.0, 10)
        );

        List<ScoredProduct> result = new PopularityBoostStrategy(repo)
                .apply(new RecommendationContext("chair", null, Strategy.POPULARITY), base);

        assertThat(result).extracting(ScoredProduct::productId, ScoredProduct::productName)
                .contains(tuple("trending-1", "Trending Lamp"));
        assertThat(result).hasSize(2);
    }

    @Test
    void emptyBaseResultsStayEmpty() {
        FakeClickstreamRepository repo = new FakeClickstreamRepository(List.of());
        List<ScoredProduct> result = new PopularityBoostStrategy(repo)
                .apply(new RecommendationContext("chair", null, Strategy.POPULARITY), List.of());
        assertThat(result).isEmpty();
    }
}
