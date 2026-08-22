package com.avanti.recengine.recommender.eval;

import java.util.List;

/** Mean per-session metrics across all held-out sessions, for one strategy. */
public record StrategySummary(
        String strategyName,
        double ndcgAt5,
        double mrr,
        double recallAt5,
        double precisionAt5,
        long p95LatencyMs
) {
    public static StrategySummary aggregate(String strategyName, List<QueryMetrics> perSession, List<Long> latenciesMs) {
        int n = perSession.size();
        double ndcg = perSession.stream().mapToDouble(QueryMetrics::ndcgAt5).sum() / n;
        double mrr = perSession.stream().mapToDouble(QueryMetrics::mrr).sum() / n;
        double recall = perSession.stream().mapToDouble(QueryMetrics::recallAt5).sum() / n;
        double precision = perSession.stream().mapToDouble(QueryMetrics::precisionAt5).sum() / n;
        return new StrategySummary(strategyName, ndcg, mrr, recall, precision, p95(latenciesMs));
    }

    private static long p95(List<Long> latenciesMs) {
        if (latenciesMs.isEmpty()) {
            return 0;
        }
        List<Long> sorted = latenciesMs.stream().sorted().toList();
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(0.95 * sorted.size()) - 1);
        return sorted.get(Math.max(0, index));
    }
}
