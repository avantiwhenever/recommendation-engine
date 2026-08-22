package com.avanti.recengine.gateway.port.out;

import com.avanti.recengine.gateway.domain.Product;
import com.avanti.recengine.gateway.domain.RecommendResult;
import com.avanti.recengine.gateway.domain.RecommenderStrategy;

import java.util.List;

/** Outbound port to recommender-service — applies a strategy to search-service's candidates. */
public interface RecommenderPort {
    RecommendResult recommend(String query, String userId, RecommenderStrategy strategy, List<Product> candidates);
}
