package com.avanti.recengine.gateway.adapter.in.graphql;

import com.avanti.recengine.gateway.application.SearchOrchestrationUseCase;
import com.avanti.recengine.gateway.domain.Product;
import com.avanti.recengine.gateway.domain.RecommenderStrategy;
import com.avanti.recengine.gateway.domain.SearchResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.graphql.test.tester.GraphQlTester;

import java.util.List;

import static org.mockito.Mockito.when;

/**
 * A GraphQL-layer-only slice test: loads just the GraphQL infrastructure
 * and {@link SearchGraphqlController}, with {@link SearchOrchestrationUseCase}
 * mocked — never touches gRPC, Pinecone, or a running backend.
 */
@GraphQlTest(SearchGraphqlController.class)
class SearchGraphqlControllerTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockBean
    private SearchOrchestrationUseCase searchOrchestrationUseCase;

    @Test
    void searchQueryReturnsProductsFromTheUseCase() {
        Product product = new Product("42", "recliner", "Chairs", "Furniture > Chairs", 4.7, 88, 0.87, "collaborative-filtering");
        SearchResult result = new SearchResult("chair", RecommenderStrategy.COLLABORATIVE, List.of(product));
        when(searchOrchestrationUseCase.search(
                ArgumentMatchers.eq("chair"), ArgumentMatchers.eq(5),
                ArgumentMatchers.eq(RecommenderStrategy.COLLABORATIVE), ArgumentMatchers.isNull(),
                ArgumentMatchers.eq(List.of()), ArgumentMatchers.isNull(), ArgumentMatchers.eq(0.0)))
                .thenReturn(result);

        graphQlTester.document("""
                        query {
                          search(query: "chair", topK: 5, strategy: COLLABORATIVE) {
                            query
                            strategy
                            products {
                              productId
                              name
                              score
                              source
                            }
                          }
                        }
                        """)
                .execute()
                .path("search.query").entity(String.class).isEqualTo("chair")
                .path("search.strategy").entity(String.class).isEqualTo("COLLABORATIVE")
                .path("search.products[0].productId").entity(String.class).isEqualTo("42")
                .path("search.products[0].name").entity(String.class).isEqualTo("recliner")
                .path("search.products[0].source").entity(String.class).isEqualTo("collaborative-filtering");
    }

    @Test
    void strategyDefaultsToCollaborativeWhenOmitted() {
        when(searchOrchestrationUseCase.search(
                ArgumentMatchers.eq("lamp"), ArgumentMatchers.eq(10),
                ArgumentMatchers.eq(RecommenderStrategy.COLLABORATIVE), ArgumentMatchers.isNull(),
                ArgumentMatchers.eq(List.of()), ArgumentMatchers.isNull(), ArgumentMatchers.eq(0.0)))
                .thenReturn(new SearchResult("lamp", RecommenderStrategy.COLLABORATIVE, List.of()));

        graphQlTester.document("""
                        query {
                          search(query: "lamp") {
                            strategy
                          }
                        }
                        """)
                .execute()
                .path("search.strategy").entity(String.class).isEqualTo("COLLABORATIVE");
    }

    @Test
    void recentProductIdsArgumentIsPassedThroughToTheUseCase() {
        when(searchOrchestrationUseCase.search(
                ArgumentMatchers.eq("chair"), ArgumentMatchers.eq(10),
                ArgumentMatchers.eq(RecommenderStrategy.COLLABORATIVE), ArgumentMatchers.isNull(),
                ArgumentMatchers.eq(List.of("p1", "p2")), ArgumentMatchers.isNull(), ArgumentMatchers.eq(0.0)))
                .thenReturn(new SearchResult("chair", RecommenderStrategy.COLLABORATIVE, List.of()));

        graphQlTester.document("""
                        query {
                          search(query: "chair", recentProductIds: ["p1", "p2"]) {
                            strategy
                          }
                        }
                        """)
                .execute()
                .path("search.strategy").entity(String.class).isEqualTo("COLLABORATIVE");
    }

    @Test
    void categoryFilterAndMinRatingArgumentsArePassedThroughToTheUseCase() {
        when(searchOrchestrationUseCase.search(
                ArgumentMatchers.eq("chair"), ArgumentMatchers.eq(10),
                ArgumentMatchers.eq(RecommenderStrategy.COLLABORATIVE), ArgumentMatchers.isNull(),
                ArgumentMatchers.eq(List.of()), ArgumentMatchers.eq("Furniture"), ArgumentMatchers.eq(4.0)))
                .thenReturn(new SearchResult("chair", RecommenderStrategy.COLLABORATIVE, List.of()));

        graphQlTester.document("""
                        query {
                          search(query: "chair", categoryFilter: "Furniture", minRating: 4.0) {
                            strategy
                          }
                        }
                        """)
                .execute()
                .path("search.strategy").entity(String.class).isEqualTo("COLLABORATIVE");
    }
}
