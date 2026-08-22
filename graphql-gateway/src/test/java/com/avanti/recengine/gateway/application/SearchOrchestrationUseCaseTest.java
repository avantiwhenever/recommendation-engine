package com.avanti.recengine.gateway.application;

import com.avanti.recengine.gateway.domain.Product;
import com.avanti.recengine.gateway.domain.RecommendResult;
import com.avanti.recengine.gateway.domain.RecommenderStrategy;
import com.avanti.recengine.gateway.domain.SearchResult;
import com.avanti.recengine.gateway.port.out.RecommenderPort;
import com.avanti.recengine.gateway.port.out.SearchPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchOrchestrationUseCaseTest {

    private static final Product RAW = new Product("1", "chair", "Chairs", "Furniture > Chairs", 4.5, 10, 0.9, null);

    @Test
    void noneStrategySkipsRecommenderAndTagsSourceAsSearch() {
        FakeSearchPort search = new FakeSearchPort(List.of(RAW));
        FakeRecommenderPort recommender = new FakeRecommenderPort(new RecommendResult(List.of(), "should-not-be-called"));
        SearchOrchestrationUseCase useCase = new SearchOrchestrationUseCase(search, recommender);

        SearchResult result = useCase.search("chair", 10, RecommenderStrategy.NONE, "u1");

        assertThat(recommender.calls).isZero();
        assertThat(result.products()).hasSize(1);
        assertThat(result.products().get(0).source()).isEqualTo("search");
        assertThat(result.strategy()).isEqualTo(RecommenderStrategy.NONE);
    }

    @Test
    void nonNoneStrategyCallsRecommenderAndTagsSourceFromResponse() {
        Product boosted = new Product("2", "armchair", "Chairs", "Furniture > Chairs", 4.8, 20, 0.95, null);
        FakeSearchPort search = new FakeSearchPort(List.of(RAW));
        FakeRecommenderPort recommender = new FakeRecommenderPort(new RecommendResult(List.of(boosted), "collaborative-filtering"));
        SearchOrchestrationUseCase useCase = new SearchOrchestrationUseCase(search, recommender);

        SearchResult result = useCase.search("chair", 10, RecommenderStrategy.COLLABORATIVE, "u1");

        assertThat(recommender.calls).isEqualTo(1);
        assertThat(recommender.lastCandidates).containsExactly(RAW);
        assertThat(recommender.lastStrategy).isEqualTo(RecommenderStrategy.COLLABORATIVE);
        assertThat(recommender.lastUserId).isEqualTo("u1");
        assertThat(result.products()).hasSize(1);
        assertThat(result.products().get(0).productId()).isEqualTo("2");
        assertThat(result.products().get(0).source()).isEqualTo("collaborative-filtering");
    }

    private static final class FakeSearchPort implements SearchPort {
        private final List<Product> results;

        FakeSearchPort(List<Product> results) {
            this.results = results;
        }

        @Override
        public List<Product> search(String query, int topK) {
            return results;
        }
    }

    private static final class FakeRecommenderPort implements RecommenderPort {
        private final RecommendResult toReturn;
        int calls = 0;
        List<Product> lastCandidates;
        RecommenderStrategy lastStrategy;
        String lastUserId;

        FakeRecommenderPort(RecommendResult toReturn) {
            this.toReturn = toReturn;
        }

        @Override
        public RecommendResult recommend(String query, String userId, RecommenderStrategy strategy, List<Product> candidates) {
            calls++;
            lastCandidates = candidates;
            lastStrategy = strategy;
            lastUserId = userId;
            return toReturn;
        }
    }
}
