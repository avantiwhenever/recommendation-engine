package com.avanti.recengine.recommender.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class MetricsCalculatorTest {

    @Test
    void perfectRankingScoresNdcgOfOne() {
        // Ideal order is already b (grade 2), a (grade 1), c (grade 0).
        List<String> ranked = List.of("b", "a", "c");
        Map<String, Integer> grades = Map.of("a", 1, "b", 2, "c", 0);

        assertThat(MetricsCalculator.ndcgAtK(ranked, grades, 5)).isCloseTo(1.0, offset(1e-9));
    }

    @Test
    void worstRankingScoresLowerThanBest() {
        List<String> best = List.of("b", "a", "c");
        List<String> worst = List.of("c", "a", "b");
        Map<String, Integer> grades = Map.of("a", 1, "b", 2, "c", 0);

        double bestNdcg = MetricsCalculator.ndcgAtK(best, grades, 5);
        double worstNdcg = MetricsCalculator.ndcgAtK(worst, grades, 5);

        assertThat(worstNdcg).isLessThan(bestNdcg);
    }

    @Test
    void reciprocalRankFindsFirstRelevantPosition() {
        List<String> ranked = List.of("irrelevant", "alsoIrrelevant", "relevant", "relevant2");
        Map<String, Integer> grades = Map.of("relevant", 1, "relevant2", 2);

        assertThat(MetricsCalculator.reciprocalRank(ranked, grades)).isEqualTo(1.0 / 3);
    }

    @Test
    void reciprocalRankIsZeroWhenNothingRelevant() {
        List<String> ranked = List.of("a", "b");
        Map<String, Integer> grades = Map.of("a", 0, "b", 0);

        assertThat(MetricsCalculator.reciprocalRank(ranked, grades)).isEqualTo(0.0);
    }

    @Test
    void precisionAtKCountsRelevantWithinTopK() {
        List<String> ranked = List.of("a", "b", "c", "d");
        Map<String, Integer> grades = Map.of("a", 1, "b", 0, "c", 2, "d", 0);

        assertThat(MetricsCalculator.precisionAtK(ranked, grades, 2)).isEqualTo(0.5);
    }

    @Test
    void recallAtKIsFractionOfAllRelevantRetrieved() {
        List<String> ranked = List.of("a", "b", "c", "d");
        Map<String, Integer> grades = Map.of("a", 1, "b", 0, "c", 1, "d", 1);

        // 3 relevant total, only 1 ("a") within top 2.
        assertThat(MetricsCalculator.recallAtK(ranked, grades, 2)).isCloseTo(1.0 / 3, offset(1e-9));
    }

    @Test
    void emptyJudgmentsProduceZeroNotDivideByZeroCrash() {
        List<String> ranked = List.of("a", "b");
        Map<String, Integer> grades = Map.of();

        assertThat(MetricsCalculator.ndcgAtK(ranked, grades, 5)).isEqualTo(0.0);
        assertThat(MetricsCalculator.recallAtK(ranked, grades, 5)).isEqualTo(0.0);
    }
}
