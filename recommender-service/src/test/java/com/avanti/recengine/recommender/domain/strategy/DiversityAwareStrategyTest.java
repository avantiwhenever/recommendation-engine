package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.RecommendationStrategy;
import com.avanti.recengine.recommender.domain.ScoredProduct;
import com.avanti.recengine.recommender.domain.Strategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DiversityAwareStrategyTest {

    private static final RecommendationContext CONTEXT =
            new RecommendationContext("chair", "u1", Strategy.NONE);

    @Test
    void diversityFavoringLambdaSpreadsCategoriesMoreThanUndiversifiedInput() {
        // 5 chairs (same category/class, descending score) then 2 lower-scored, distinct items.
        List<ScoredProduct> base = List.of(
                new ScoredProduct("chair-1", 10.0, "chair 1", "Chairs", "Furniture / Seating", 4.0, 10),
                new ScoredProduct("chair-2", 9.0, "chair 2", "Chairs", "Furniture / Seating", 4.0, 10),
                new ScoredProduct("chair-3", 8.0, "chair 3", "Chairs", "Furniture / Seating", 4.0, 10),
                new ScoredProduct("chair-4", 7.0, "chair 4", "Chairs", "Furniture / Seating", 4.0, 10),
                new ScoredProduct("chair-5", 6.0, "chair 5", "Chairs", "Furniture / Seating", 4.0, 10),
                new ScoredProduct("table-1", 5.0, "table", "Tables", "Furniture / Tables", 4.0, 10),
                new ScoredProduct("rug-1", 4.0, "rug", "Rugs", "Home Decor / Rugs", 4.0, 10)
        );

        List<ScoredProduct> undiversifiedTop5 = base.subList(0, 5);
        long undiversifiedDistinctCategories = undiversifiedTop5.stream()
                .map(p -> p.categoryHierarchy().split("/")[0].trim())
                .distinct()
                .count();
        assertThat(undiversifiedDistinctCategories).isEqualTo(1);

        RecommendationStrategy diversified = new DiversityAwareStrategy(new PassthroughStrategy(), 0.3);
        List<ScoredProduct> result = diversified.apply(CONTEXT, base);

        assertThat(result).hasSize(base.size());
        Set<String> distinctCategoriesInTop5 = result.subList(0, 5).stream()
                .map(p -> p.categoryHierarchy().split("/")[0].trim())
                .collect(Collectors.toSet());
        assertThat(distinctCategoriesInTop5.size()).isGreaterThan((int) undiversifiedDistinctCategories);
    }

    @Test
    void lambdaOneDegeneratesToDelegateOrderUnchanged() {
        List<ScoredProduct> base = List.of(
                new ScoredProduct("chair-1", 10.0, "chair 1", "Chairs", "Furniture / Seating", 4.0, 10),
                new ScoredProduct("chair-2", 9.0, "chair 2", "Chairs", "Furniture / Seating", 4.0, 10),
                new ScoredProduct("chair-3", 8.0, "chair 3", "Chairs", "Furniture / Seating", 4.0, 10),
                new ScoredProduct("table-1", 5.0, "table", "Tables", "Furniture / Tables", 4.0, 10)
        );

        RecommendationStrategy diversified = new DiversityAwareStrategy(new PassthroughStrategy(), 1.0);
        List<ScoredProduct> result = diversified.apply(CONTEXT, base);

        assertThat(result).isEqualTo(base);
    }

    @Test
    void fewerThanTwoResultsPassThroughUnchanged() {
        List<ScoredProduct> empty = List.of();
        List<ScoredProduct> single = List.of(
                new ScoredProduct("chair-1", 10.0, "chair 1", "Chairs", "Furniture / Seating", 4.0, 10)
        );

        RecommendationStrategy diversified = new DiversityAwareStrategy(new PassthroughStrategy());

        assertThat(diversified.apply(CONTEXT, empty)).isEmpty();
        assertThat(diversified.apply(CONTEXT, single)).isEqualTo(single);
    }

    @Test
    void nameReflectsDelegateAndDiversification() {
        RecommendationStrategy diversified = new DiversityAwareStrategy(new PassthroughStrategy());
        assertThat(diversified.name()).isEqualTo("None (diversified)");
    }

    @Test
    void rejectsOutOfRangeLambda() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new DiversityAwareStrategy(new PassthroughStrategy(), 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new DiversityAwareStrategy(new PassthroughStrategy(), -0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
