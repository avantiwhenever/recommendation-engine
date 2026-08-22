package com.avanti.recengine.recommender.eval;

import java.util.List;
import java.util.Map;

/**
 * Standard IR metrics, ported from the sibling {@code search} project's
 * {@code search-eval} module — same nDCG/MRR/Recall/Precision formulas
 * (graded DCG gain {@code 2^rel - 1}, log2 rank discount, "relevant" =
 * grade &gt;= 1) — computed here against implicit relevance grades derived
 * from clickstream event severity instead of WANDS' explicit judgments.
 * Sessions with no relevant product contribute 0 to every metric rather
 * than being excluded, consistent with trec_eval's default per-topic
 * treatment.
 */
public final class MetricsCalculator {

    private MetricsCalculator() {
    }

    public static double ndcgAtK(List<String> rankedProductIds, Map<String, Integer> grades, int k) {
        double dcg = dcgAtK(rankedProductIds, grades, k);

        List<String> idealOrder = grades.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        double idcg = dcgAtK(idealOrder, grades, k);

        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    private static double dcgAtK(List<String> rankedProductIds, Map<String, Integer> grades, int k) {
        double dcg = 0.0;
        int limit = Math.min(k, rankedProductIds.size());
        for (int i = 0; i < limit; i++) {
            int relevance = grades.getOrDefault(rankedProductIds.get(i), 0);
            dcg += (Math.pow(2, relevance) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        return dcg;
    }

    public static double reciprocalRank(List<String> rankedProductIds, Map<String, Integer> grades) {
        for (int i = 0; i < rankedProductIds.size(); i++) {
            if (grades.getOrDefault(rankedProductIds.get(i), 0) >= 1) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    public static double recallAtK(List<String> rankedProductIds, Map<String, Integer> grades, int k) {
        long totalRelevant = grades.values().stream().filter(g -> g >= 1).count();
        if (totalRelevant == 0) {
            return 0.0;
        }
        long retrievedRelevant = rankedProductIds.stream().limit(k).filter(id -> isRelevant(grades, id)).count();
        return (double) retrievedRelevant / totalRelevant;
    }

    public static double precisionAtK(List<String> rankedProductIds, Map<String, Integer> grades, int k) {
        int limit = Math.min(k, rankedProductIds.size());
        if (limit == 0) {
            return 0.0;
        }
        long retrievedRelevant = rankedProductIds.stream().limit(limit).filter(id -> isRelevant(grades, id)).count();
        return (double) retrievedRelevant / limit;
    }

    private static boolean isRelevant(Map<String, Integer> grades, String productId) {
        return grades.getOrDefault(productId, 0) >= 1;
    }
}
