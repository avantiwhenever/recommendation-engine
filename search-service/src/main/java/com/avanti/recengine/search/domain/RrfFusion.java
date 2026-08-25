package com.avanti.recengine.search.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion: combines multiple ranked lists into one by summing,
 * per product, {@code 1/(k + rank)} over every list it appears in (1-based
 * rank; a product absent from a list simply contributes nothing for that
 * list). Standard practice for combining a lexical and a semantic ranker
 * without needing their raw scores to be on comparable scales — this is the
 * same fusion technique (same formula, same {@code k} role) as the sibling
 * {@code search} project's {@code search-retrieval/RrfFusionService}, ported
 * here as this project's own copy rather than a shared dependency between
 * two otherwise-independent repos.
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    public static List<ScoredResult> fuse(List<List<ScoredResult>> rankedLists, int k) {
        Map<String, Double> fusedScoreByProductId = new LinkedHashMap<>();
        Map<String, Product> productById = new LinkedHashMap<>();

        for (List<ScoredResult> rankedList : rankedLists) {
            for (int i = 0; i < rankedList.size(); i++) {
                ScoredResult result = rankedList.get(i);
                String productId = result.product().productId();
                double contribution = 1.0 / (k + i + 1);
                fusedScoreByProductId.merge(productId, contribution, Double::sum);
                productById.putIfAbsent(productId, result.product());
            }
        }

        return fusedScoreByProductId.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> new ScoredResult(productById.get(e.getKey()), e.getValue()))
                .toList();
    }
}
