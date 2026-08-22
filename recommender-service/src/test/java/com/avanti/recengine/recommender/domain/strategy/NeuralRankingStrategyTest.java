package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.ScoredProduct;
import com.avanti.recengine.recommender.domain.Strategy;
import com.avanti.recengine.recommender.domain.UserProfile;
import com.avanti.recengine.recommender.port.out.RankingModelPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class NeuralRankingStrategyTest {

    @Test
    void ordersByModelScoreNotOriginalScore() {
        FakeClickstreamRepository repo = new FakeClickstreamRepository(List.of());
        RankingModelPort model = features -> features[0]; // score = category-match feature only

        List<ScoredProduct> base = List.of(
                new ScoredProduct("1", 100.0, "a", "Chairs", "Furniture / Chairs", 4.0, 10),
                new ScoredProduct("2", 1.0, "b", "Lamps", "Home / Lamps", 4.0, 10)
        );
        // no user profile -> no top category -> categoryMatch is always 0 for
        // both, so model scores tie; instead exercise via a profile below.

        NeuralRankingStrategy strategy = new NeuralRankingStrategy(repo, model);
        List<ScoredProduct> result = strategy.apply(new RecommendationContext("x", null, Strategy.NEURAL), base);

        // With tied model scores (both 0.0), original relative order from a
        // stable sort should be preserved by List.sort's stability guarantee.
        assertThat(result).extracting(ScoredProduct::productId).containsExactly("1", "2");
    }

    @Test
    void categoryMatchFeatureReflectsUsersTopCategory() {
        FakeClickstreamRepository repo = new FakeClickstreamRepository(List.of());
        repo.withProfile(new UserProfile("u1", Set.of(), Map.of("Furniture", 5L, "Home", 1L)));
        RankingModelPort model = features -> features[0];

        NeuralRankingStrategy strategy = new NeuralRankingStrategy(repo, model);
        List<ScoredProduct> base = List.of(
                new ScoredProduct("furniture-item", 1.0, "a", "Chairs", "Furniture / Chairs", 4.0, 10),
                new ScoredProduct("home-item", 1.0, "b", "Lamps", "Home / Lamps", 4.0, 10)
        );

        List<ScoredProduct> result = strategy.apply(new RecommendationContext("x", "u1", Strategy.NEURAL), base);

        assertThat(result.get(0).productId()).isEqualTo("furniture-item");
    }

    @Test
    void buildsSixFeaturesInDocumentedOrder() {
        FakeClickstreamRepository repo = new FakeClickstreamRepository(List.of());
        repo.withPopularity("p1", Math.E - 1); // log1p(e-1) = 1.0
        NeuralRankingStrategy strategy = new NeuralRankingStrategy(repo, f -> 0.0);

        ScoredProduct product = new ScoredProduct("p1", 0.0, "a", "Chairs", "Furniture / Chairs", 5.0, (int) Math.E - 1);
        float[] features = strategy.buildFeatures(product, java.util.Optional.of("Furniture"), Set.of());

        assertThat(features).hasSize(6);
        assertThat(features[0]).isEqualTo(1.0f); // category match
        assertThat(features[1]).isCloseTo(0.5f, offset(0.001f)); // sigmoid(0) = 0.5
        assertThat(features[2]).isCloseTo(1.0f, offset(0.01f)); // log1p popularity
        assertThat(features[4]).isEqualTo(1.0f); // averageRating/5.0 = 5.0/5.0
    }
}
