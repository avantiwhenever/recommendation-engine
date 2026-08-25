package com.avanti.recengine.gateway.adapter.in.graphql;

import com.avanti.recengine.gateway.application.SearchOrchestrationUseCase;
import com.avanti.recengine.gateway.domain.RecommenderStrategy;
import com.avanti.recengine.gateway.domain.SearchResult;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * The only GraphQL (and only HTTP) inbound adapter in the whole system —
 * every other Java-to-Java edge here is gRPC. Spring GraphQL binds
 * {@code @Argument} parameters, including the {@code RecommenderStrategy}
 * enum, by matching the schema argument name; the schema default
 * ({@code strategy: RecommenderStrategy = COLLABORATIVE}) applies when the
 * client omits it.
 */
@Controller
public class SearchGraphqlController {

    private final SearchOrchestrationUseCase searchOrchestrationUseCase;

    public SearchGraphqlController(SearchOrchestrationUseCase searchOrchestrationUseCase) {
        this.searchOrchestrationUseCase = searchOrchestrationUseCase;
    }

    @QueryMapping
    public SearchResult search(@Argument String query, @Argument int topK,
                                @Argument RecommenderStrategy strategy, @Argument String userId,
                                @Argument List<String> recentProductIds,
                                @Argument String categoryFilter, @Argument Double minRating) {
        return searchOrchestrationUseCase.search(query, topK, strategy, userId, recentProductIds,
                categoryFilter, minRating == null ? 0.0 : minRating);
    }
}
