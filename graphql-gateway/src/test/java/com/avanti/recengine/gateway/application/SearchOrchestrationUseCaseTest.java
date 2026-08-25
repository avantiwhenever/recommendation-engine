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

    @Test
    void recentProductIdsArePassedThroughToTheRecommenderPort() {
        FakeSearchPort search = new FakeSearchPort(List.of(RAW));
        FakeRecommenderPort recommender = new FakeRecommenderPort(new RecommendResult(List.of(), "collaborative-filtering"));
        SearchOrchestrationUseCase useCase = new SearchOrchestrationUseCase(search, recommender);

        useCase.search("chair", 10, RecommenderStrategy.COLLABORATIVE, "u1", List.of("session-product-1"));

        assertThat(recommender.lastRecentProductIds).containsExactly("session-product-1");
    }

    @Test
    void fourArgOverloadDefaultsToNoSessionSignal() {
        FakeSearchPort search = new FakeSearchPort(List.of(RAW));
        FakeRecommenderPort recommender = new FakeRecommenderPort(new RecommendResult(List.of(), "collaborative-filtering"));
        SearchOrchestrationUseCase useCase = new SearchOrchestrationUseCase(search, recommender);

        useCase.search("chair", 10, RecommenderStrategy.COLLABORATIVE, "u1");

        assertThat(recommender.lastRecentProductIds).isEmpty();
    }

    @Test
    void widensTheSearchRequestBeyondTheCallersTopK() {
        FakeSearchPort search = new FakeSearchPort(List.of(RAW));
        FakeRecommenderPort recommender = new FakeRecommenderPort(new RecommendResult(List.of(), "collaborative-filtering"));
        SearchOrchestrationUseCase useCase = new SearchOrchestrationUseCase(search, recommender);

        useCase.search("chair", 5, RecommenderStrategy.COLLABORATIVE, "u1");

        assertThat(search.lastTopK).isEqualTo(SearchOrchestrationUseCase.WIDE_POOL_SIZE);
    }

    @Test
    void selectionStageDropsZeroSocialProofCandidatesForEveryStrategyIncludingNone() {
        Product noSignal = new Product("3", "stool", "Chairs", "Furniture > Chairs", 0.0, 0, 0.5, null);
        FakeSearchPort search = new FakeSearchPort(List.of(RAW, noSignal));
        FakeRecommenderPort recommender = new FakeRecommenderPort(new RecommendResult(List.of(), "collaborative-filtering"));
        SearchOrchestrationUseCase useCase = new SearchOrchestrationUseCase(search, recommender);

        useCase.search("chair", 10, RecommenderStrategy.COLLABORATIVE, "u1");
        assertThat(recommender.lastCandidates).containsExactly(RAW);

        SearchResult noneResult = useCase.search("chair", 10, RecommenderStrategy.NONE, "u1");
        assertThat(noneResult.products()).extracting(Product::productId).containsExactly("1");
    }

    @Test
    void selectionStageCutsToEligiblePoolSizeByScore() {
        List<Product> manyCandidates = java.util.stream.IntStream.range(0, SearchOrchestrationUseCase.ELIGIBLE_POOL_SIZE + 20)
                .mapToObj(i -> new Product(String.valueOf(i), "item" + i, "Chairs", "Furniture > Chairs", 4.0, 5, 1.0 - (i * 0.001), null))
                .toList();
        FakeSearchPort search = new FakeSearchPort(manyCandidates);
        FakeRecommenderPort recommender = new FakeRecommenderPort(new RecommendResult(List.of(), "collaborative-filtering"));
        SearchOrchestrationUseCase useCase = new SearchOrchestrationUseCase(search, recommender);

        useCase.search("chair", 10, RecommenderStrategy.COLLABORATIVE, "u1");

        assertThat(recommender.lastCandidates).hasSize(SearchOrchestrationUseCase.ELIGIBLE_POOL_SIZE);
        assertThat(recommender.lastCandidates.get(0).productId()).isEqualTo("0");
    }

    @Test
    void finalResultsAreTruncatedToTheCallersTopKAfterTheStrategyRuns() {
        List<Product> strategyOutput = List.of(
                new Product("1", "a", "Chairs", "Furniture > Chairs", 4.5, 10, 0.9, null),
                new Product("2", "b", "Chairs", "Furniture > Chairs", 4.5, 10, 0.8, null),
                new Product("3", "c", "Chairs", "Furniture > Chairs", 4.5, 10, 0.7, null)
        );
        FakeSearchPort search = new FakeSearchPort(strategyOutput);
        FakeRecommenderPort recommender = new FakeRecommenderPort(new RecommendResult(strategyOutput, "collaborative-filtering"));
        SearchOrchestrationUseCase useCase = new SearchOrchestrationUseCase(search, recommender);

        SearchResult result = useCase.search("chair", 2, RecommenderStrategy.COLLABORATIVE, "u1");

        assertThat(result.products()).hasSize(2);
    }

    private static final class FakeSearchPort implements SearchPort {
        private final List<Product> results;
        int lastTopK;

        FakeSearchPort(List<Product> results) {
            this.results = results;
        }

        @Override
        public List<Product> search(String query, int topK) {
            return search(query, topK, null, 0.0);
        }

        @Override
        public List<Product> search(String query, int topK, String categoryFilter, double minRating) {
            lastTopK = topK;
            return results;
        }
    }

    private static final class FakeRecommenderPort implements RecommenderPort {
        private final RecommendResult toReturn;
        int calls = 0;
        List<Product> lastCandidates;
        RecommenderStrategy lastStrategy;
        String lastUserId;
        List<String> lastRecentProductIds;

        FakeRecommenderPort(RecommendResult toReturn) {
            this.toReturn = toReturn;
        }

        @Override
        public RecommendResult recommend(String query, String userId, RecommenderStrategy strategy,
                                          List<Product> candidates, List<String> recentProductIds) {
            calls++;
            lastCandidates = candidates;
            lastStrategy = strategy;
            lastUserId = userId;
            lastRecentProductIds = recentProductIds;
            return toReturn;
        }
    }
}
