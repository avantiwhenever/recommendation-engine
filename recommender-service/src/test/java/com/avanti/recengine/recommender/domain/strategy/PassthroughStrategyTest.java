package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.ScoredProduct;
import com.avanti.recengine.recommender.domain.Strategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PassthroughStrategyTest {

    @Test
    void returnsCandidatesUnchanged() {
        List<ScoredProduct> base = List.of(
                new ScoredProduct("1", 5.0, "chair", "Chairs", "Furniture / Chairs", 4.0, 10),
                new ScoredProduct("2", 3.0, "table", "Tables", "Furniture / Tables", 4.5, 20)
        );

        List<ScoredProduct> result = new PassthroughStrategy()
                .apply(new RecommendationContext("chair", "u1", Strategy.NONE), base);

        assertThat(result).isEqualTo(base);
    }
}
