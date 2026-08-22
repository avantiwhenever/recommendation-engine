package com.avanti.recengine.gateway.domain;

import java.util.List;

/** Maps directly to the GraphQL {@code SearchResult} type. */
public record SearchResult(String query, RecommenderStrategy strategy, List<Product> products) {
}
