package com.avanti.recengine.recommender.eval;

import com.avanti.recengine.recommender.domain.ScoredProduct;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Hand-computed values verified independently in Python against the exact
 * constants from WANDS/CLICKSTREAM.md (position_decay(p) = 1/p^0.8,
 * click/cart/purchase probabilities per grade) before writing these
 * assertions — see the class Javadoc on {@link IpsEvaluator} for the
 * formulas being tested.
 */
class IpsEvaluatorTest {

    @Test
    void positionDecayMatchesTheDocumentedFormula() {
        assertThat(IpsEvaluator.positionDecay(1)).isEqualTo(1.0);
        assertThat(IpsEvaluator.positionDecay(4)).isCloseTo(1.0 / Math.pow(4, 0.8), within(1e-9));
    }

    @Test
    void positionDecayRejectsNonPositivePosition() {
        assertThatThrownBy(() -> IpsEvaluator.positionDecay(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outcomePropensityForAClickOnAnExactGradeItemAtPositionOne() {
        // pClick = 1/1^0.8 * 0.55 = 0.55; propensity of "click, no cart" = 0.55 * (1 - 0.35) = 0.3575
        assertThat(IpsEvaluator.outcomePropensity(2, 1, 1)).isCloseTo(0.3575, within(1e-9));
    }

    @Test
    void outcomePropensityForAViewOnlyOutcomeIsOneMinusClickProbability() {
        // Irrelevant grade, position 1: pClick = 1 * 0.04 = 0.04; view-only propensity = 0.96
        assertThat(IpsEvaluator.outcomePropensity(0, 1, 0)).isCloseTo(0.96, within(1e-9));
    }

    @Test
    void outcomePropensityForARarePurchaseDeepInTheListIsTiny() {
        // Irrelevant grade, position 10: pClick = 10^-0.8 * 0.04 ≈ 0.006339,
        // pCart = 0.02, pPurchase = 0.05 -> propensity ≈ 6.3396e-6 (hand-verified in Python).
        double propensity = IpsEvaluator.outcomePropensity(0, 10, 3);
        assertThat(propensity).isCloseTo(6.339572769844453e-6, within(1e-12));
        // Its raw inverse weight is enormous — exactly the instability MIN_PROPENSITY clipping exists to bound.
        assertThat(1.0 / propensity).isGreaterThan(150_000.0);
    }

    @Test
    void rewardScaleMatchesTemporalClickstreamIndexsEventWeights() {
        assertThat(IpsEvaluator.reward(0)).isEqualTo(0.2);
        assertThat(IpsEvaluator.reward(1)).isEqualTo(0.5);
        assertThat(IpsEvaluator.reward(2)).isEqualTo(0.8);
        assertThat(IpsEvaluator.reward(3)).isEqualTo(1.0);
    }

    @Test
    void recordSessionSkipsInjectedProductsWithNoLoggedPosition() {
        // P1 was an original candidate at position 1 (score = 1/1); "INJECTED" was never
        // shown, so it has no recoverable position and must contribute nothing.
        EvalSession session = session(
                List.of(candidate("P1", 1)),
                Map.of("P1", 1), // click
                Map.of("P1", 2)  // Exact
        );

        var acc = new IpsEvaluator.Accumulator();
        acc.recordSession(session, List.of("INJECTED", "P1"), 5);
        var result = acc.result();

        assertThat(result.scoredItemCount()).isEqualTo(1);
        assertThat(result.sessionCount()).isEqualTo(1);
        assertThat(result.rawEstimate()).isGreaterThan(0.0);
    }

    @Test
    void clippingMeaningfullyChangesTheEstimateWhenARarePropensityEventIsPresent() {
        // A single Irrelevant-grade "purchase" at position 10 — propensity ~6.34e-6,
        // so its raw contribution to the sum is reward(1.0) / 6.34e-6 ≈ 157,739; clipped
        // at MIN_PROPENSITY (1e-3) its contribution is bounded to 1.0 / 1e-3 = 1,000.
        EvalSession session = session(
                List.of(candidate("P1", 10)),
                Map.of("P1", 3), // purchase
                Map.of("P1", 0)  // Irrelevant
        );

        var acc = new IpsEvaluator.Accumulator();
        acc.recordSession(session, List.of("P1"), 5);
        var result = acc.result();

        assertThat(result.rawEstimate()).isGreaterThan(150_000.0);
        assertThat(result.clippedEstimate()).isCloseTo(1000.0, within(1.0));
        // One extreme-weight item: ESS collapses to ~1 (can't exceed the item count either way).
        assertThat(result.effectiveSampleSize()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void effectiveSampleSizeStaysNearItemCountWhenWeightsAreUniform() {
        // Two items with identical (grade, position, outcome) -> identical propensity -> identical weight -> ESS ≈ 2.
        EvalSession session = session(
                List.of(candidate("P1", 2), candidate("P2", 2)),
                Map.of("P1", 1, "P2", 1),
                Map.of("P1", 2, "P2", 2)
        );

        var acc = new IpsEvaluator.Accumulator();
        acc.recordSession(session, List.of("P1", "P2"), 5);
        var result = acc.result();

        assertThat(result.scoredItemCount()).isEqualTo(2);
        assertThat(result.effectiveSampleSize()).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void onlyTopKRankedItemsAreScored() {
        EvalSession session = session(
                List.of(candidate("P1", 1), candidate("P2", 2), candidate("P3", 3)),
                Map.of("P1", 1, "P2", 1, "P3", 1),
                Map.of("P1", 2, "P2", 2, "P3", 2)
        );

        var acc = new IpsEvaluator.Accumulator();
        acc.recordSession(session, List.of("P1", "P2", "P3"), 2);
        var result = acc.result();

        assertThat(result.scoredItemCount()).isEqualTo(2);
    }

    @Test
    void unscoredSessionsProduceAZeroedResultRatherThanDivideByZero() {
        var result = new IpsEvaluator.Accumulator().result();
        assertThat(result.rawEstimate()).isZero();
        assertThat(result.clippedEstimate()).isZero();
        assertThat(result.effectiveSampleSize()).isZero();
        assertThat(result.sessionCount()).isZero();
    }

    private static ScoredProduct candidate(String productId, int position) {
        return new ScoredProduct(productId, 1.0 / position, "name", "class", "category", 4.0, 10);
    }

    private static EvalSession session(List<ScoredProduct> candidates, Map<String, Integer> relevanceGrades,
                                        Map<String, Integer> independentRelevanceGrades) {
        return new EvalSession("s1", "u1", "q1", candidates, relevanceGrades, independentRelevanceGrades);
    }
}
