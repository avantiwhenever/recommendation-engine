package com.avanti.recengine.gateway.application;

import com.avanti.recengine.gateway.domain.Product;
import com.avanti.recengine.gateway.domain.RecommendResult;
import com.avanti.recengine.gateway.domain.RecommenderStrategy;
import com.avanti.recengine.gateway.domain.SearchResult;
import com.avanti.recengine.gateway.port.out.RecommenderPort;
import com.avanti.recengine.gateway.port.out.SearchPort;

import java.util.List;

/**
 * The gateway's only real logic: call search-service for baseline
 * candidates, then (unless the strategy is {@code NONE}) call
 * recommender-service to add/remove/re-rank them. Framework-free — no
 * Spring, no gRPC, no GraphQL types — so it's testable with fake ports.
 *
 * <p>{@code NONE} skips the recommender-service call entirely rather than
 * routing through a server-side passthrough strategy — one fewer network
 * hop for the common "just show me raw search results" case, and it means
 * this use case (not recommender-service) is the single place that decides
 * whether personalization happens at all.
 */
public final class SearchOrchestrationUseCase {

    private static final String SEARCH_ONLY_SOURCE = "search";

    private final SearchPort searchPort;
    private final RecommenderPort recommenderPort;

    public SearchOrchestrationUseCase(SearchPort searchPort, RecommenderPort recommenderPort) {
        this.searchPort = searchPort;
        this.recommenderPort = recommenderPort;
    }

    public SearchResult search(String query, int topK, RecommenderStrategy strategy, String userId) {
        List<Product> candidates = searchPort.search(query, topK);

        if (strategy == RecommenderStrategy.NONE) {
            List<Product> results = candidates.stream().map(p -> p.withSource(SEARCH_ONLY_SOURCE)).toList();
            return new SearchResult(query, strategy, results);
        }

        RecommendResult recommended = recommenderPort.recommend(query, userId, strategy, candidates);
        List<Product> results = recommended.products().stream()
                .map(p -> p.withSource(recommended.source()))
                .toList();
        return new SearchResult(query, strategy, results);
    }
}
