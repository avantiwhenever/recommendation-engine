package com.avanti.recengine.recommender.domain;

public record RecommendationContext(String query, String userId, Strategy strategy) {
}
