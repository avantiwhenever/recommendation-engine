package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.RecommendationStrategy;
import com.avanti.recengine.recommender.domain.ScoredProduct;
import com.avanti.recengine.recommender.domain.Strategy;
import com.avanti.recengine.recommender.port.out.VectorSimilarityPort;
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

    @Test
    void embeddingSimilarityCanFlagDifferentCategoryItemsAsSimilarWhenTheCategoryProxyWouldMiss() {
        // Two "chair"s in different categories/classes — the category proxy alone would score
        // this pair as unrelated (0.0). A vector port that says they're each other's nearest
        // neighbor should still make diversity avoid putting both above the fold.
        List<ScoredProduct> base = List.of(
                new ScoredProduct("desk-chair", 10.0, "desk chair", "Office Chairs", "Furniture / Office", 4.0, 10),
                new ScoredProduct("gaming-chair", 9.5, "gaming chair", "Gaming Chairs", "Electronics / Gaming", 4.0, 10),
                new ScoredProduct("rug-1", 9.0, "rug", "Rugs", "Home Decor / Rugs", 4.0, 10)
        );
        VectorSimilarityPort port = (productId, topK) -> switch (productId) {
            case "desk-chair" -> List.of("gaming-chair", "rug-1");
            case "gaming-chair" -> List.of("desk-chair", "rug-1");
            default -> List.of();
        };

        // lambda=0.3 (diversity-favoring) with no vector port: category proxy sees desk-chair
        // and gaming-chair as unrelated (different category/class), so they're not penalized
        // against each other.
        RecommendationStrategy withoutEmbeddings = new DiversityAwareStrategy(new PassthroughStrategy(), 0.3);
        List<ScoredProduct> withoutEmbeddingsResult = withoutEmbeddings.apply(CONTEXT, base);
        assertThat(withoutEmbeddingsResult.get(0).productId()).isEqualTo("desk-chair");
        assertThat(withoutEmbeddingsResult.get(1).productId()).isEqualTo("gaming-chair");

        // Same lambda, with the vector port: desk-chair and gaming-chair are each other's
        // top (rank 0) neighbor — embedding similarity 1.0 - 0/2 = 1.0, well above the
        // category proxy's 0.0 — so gaming-chair should now be penalized relative to rug-1.
        RecommendationStrategy withEmbeddings = new DiversityAwareStrategy(new PassthroughStrategy(), 0.3, port);
        List<ScoredProduct> withEmbeddingsResult = withEmbeddings.apply(CONTEXT, base);
        assertThat(withEmbeddingsResult.get(0).productId()).isEqualTo("desk-chair");
        assertThat(withEmbeddingsResult.get(1).productId()).isEqualTo("rug-1");
        assertThat(withEmbeddingsResult.get(2).productId()).isEqualTo("gaming-chair");
    }

    @Test
    void missingEmbeddingDataFallsBackToCategoryProxyRatherThanTreatingThePairAsConfirmedDissimilar() {
        // Same category/class pair, but the fake port returns nothing for either product
        // (simulating a product absent from the Pinecone index) — similarity should still
        // come from the category proxy, not collapse to 0.0.
        List<ScoredProduct> base = List.of(
                new ScoredProduct("chair-1", 10.0, "chair 1", "Chairs", "Furniture / Seating", 4.0, 10),
                new ScoredProduct("chair-2", 9.0, "chair 2", "Chairs", "Furniture / Seating", 4.0, 10),
                new ScoredProduct("rug-1", 8.0, "rug", "Rugs", "Home Decor / Rugs", 4.0, 10)
        );
        VectorSimilarityPort emptyPort = (productId, topK) -> List.of();

        RecommendationStrategy diversified = new DiversityAwareStrategy(new PassthroughStrategy(), 0.3, emptyPort);
        List<ScoredProduct> result = diversified.apply(CONTEXT, base);

        // Same outcome as the no-port case: chair-2 (same category as chair-1) is penalized
        // relative to rug-1, driven entirely by the category proxy since the port had nothing.
        assertThat(result.get(0).productId()).isEqualTo("chair-1");
        assertThat(result.get(1).productId()).isEqualTo("rug-1");
        assertThat(result.get(2).productId()).isEqualTo("chair-2");
    }

    @Test
    void nullVectorSimilarityPortBehavesIdenticallyToTheNoPortConstructors() {
        List<ScoredProduct> base = List.of(
                new ScoredProduct("chair-1", 10.0, "chair 1", "Chairs", "Furniture / Seating", 4.0, 10),
                new ScoredProduct("chair-2", 9.0, "chair 2", "Chairs", "Furniture / Seating", 4.0, 10),
                new ScoredProduct("rug-1", 8.0, "rug", "Rugs", "Home Decor / Rugs", 4.0, 10)
        );

        RecommendationStrategy withoutPortArg = new DiversityAwareStrategy(new PassthroughStrategy(), 0.3);
        RecommendationStrategy withNullPort = new DiversityAwareStrategy(new PassthroughStrategy(), 0.3, null);

        assertThat(withNullPort.apply(CONTEXT, base)).isEqualTo(withoutPortArg.apply(CONTEXT, base));
    }
}
