package com.avanti.recengine.recommender.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Off-policy (Inverse Propensity Scoring) estimator — the two tables
 * {@link EvalCli} already reports (implicit clickstream, independent
 * WANDS relevance) both evaluate a strategy's reranking as if it were
 * <i>presented</i> to users,
 * i.e. as a static list scored against a grade. They cannot estimate what
 * the observed <i>reward</i> (click/cart/purchase) would actually have been
 * had a strategy's ranking been the one shown, since no user was ever shown
 * that ranking — only the original clickstream logging policy's ranking was.
 *
 * <p>IPS makes that estimate possible here specifically because, unlike
 * almost every real production system, this project's synthetic clickstream
 * (<a href="https://github.com/avantiwhenever/WANDS/blob/main/CLICKSTREAM.md">
 * WANDS' {@code CLICKSTREAM.md}</a>) has a <b>fully known, documented logging
 * policy</b> — a cascade click model with closed-form probabilities, not an
 * opaque production ranker. That known policy is what {@link #outcomePropensity}
 * below implements verbatim (the exact constants from that doc, not
 * estimated or guessed).
 *
 * <p><b>The estimator, in one line</b>: for each held-out session, for each
 * item a strategy places in its top-K that was also an <i>original</i>
 * candidate the user was actually shown (injected items have no logged
 * outcome to reweight — see the caveat below), reweight that item's
 * <i>already-observed</i> reward by the inverse of the probability the
 * logging policy would have produced that exact outcome at the position it
 * actually showed the item — {@code reward / P(observed outcome | logged
 * position, item's WANDS relevance)}. Averaged per session, this is an
 * unbiased estimate of the reward a strategy's ranking would have earned,
 * <i>under the item-level independence assumption below</i>, without any
 * new live traffic — the standard off-policy evaluation trick (Li et al.,
 * "Counterfactual Estimation and Optimization of Click Metrics for Search
 * Engines"; Criteo, <a href="https://arxiv.org/pdf/1801.07030">"Offline A/B
 * Testing for Recommender Systems"</a>; Spotify Research's
 * <a href="https://research.atspotify.com/publications/towards-a-fair-marketplace-counterfactual-evaluation-of-the-trade-off-between-relevance-fairness-satisfaction-in-recommendation-systems">
 * counterfactual-evaluation work</a>).
 *
 * <p><b>Simplifying assumption, stated honestly</b>: this is an
 * <i>item-level</i> IPS estimator, not a full listwise/ranking IPS estimator
 * (which would require importance-sampling over the combinatorial space of
 * full permutations — intractable here and in most real systems). Each
 * item's exposure-in-top-K is treated as an independent binary action. This
 * is the same simplifying assumption used as the base case in the
 * literature above before considering cross-item interaction effects (e.g.
 * one item's presence changing another's click probability) — a real
 * limitation, not swept under the rug.
 *
 * <p><b>IPS's known failure mode, and how this class handles it honestly</b>:
 * when a rare, high-reward outcome (a purchase) occurs for an item the
 * logging policy was unlikely to produce that outcome for (an Irrelevant
 * item purchased from a deep position), its propensity is tiny and its
 * inverse weight enormous — a single such event can dominate the whole
 * estimate and blow out its variance. This class reports <b>both</b> the raw
 * (unclipped) and a propensity-clipped (floored at {@link #MIN_PROPENSITY})
 * estimate, plus a Hájek/Kish <b>effective sample size</b> ({@code (Σw)² /
 * Σw²} over the clipped weights) — a diagnostic for how much of the estimate
 * is really being carried by a handful of extreme-weight events, so a reader
 * can judge trustworthiness rather than take a single clean-looking number
 * at face value. This project's whole eval ethos (see {@link EvalCli}'s and
 * every strategy's Javadoc) is reporting honest uncertainty, not hiding it.
 */
public final class IpsEvaluator {

    /** {@code position_decay(p) = 1 / p^0.8} — WANDS/CLICKSTREAM.md's cascade attention-decay model. */
    private static final double POSITION_DECAY_EXPONENT = 0.8;

    /** Indexed by WANDS grade: 0=Irrelevant, 1=Partial, 2=Exact (matches {@link WandsLabelLoader}'s encoding). */
    private static final double[] CLICK_GIVEN_VIEW = {0.04, 0.22, 0.55};
    private static final double[] CART_GIVEN_CLICK = {0.02, 0.12, 0.35};
    private static final double[] PURCHASE_GIVEN_CART = {0.05, 0.15, 0.40};

    /** Matches {@link TemporalClickstreamIndex}'s EVENT_WEIGHT, indexed by observed event level 0=view..3=purchase. */
    private static final double[] REWARD_BY_LEVEL = {0.2, 0.5, 0.8, 1.0};

    /**
     * Propensity floor for the clipped estimate. Chosen so that even a
     * worst-case Irrelevant-grade purchase at a deep position (propensity on
     * the order of 1e-6 in this dataset — see class Javadoc) gets bounded to
     * a maximum inverse weight of 1,000 rather than ~1,000,000, without
     * flooring the vast majority of ordinary click/view outcomes, whose
     * propensities are almost always well above this.
     */
    public static final double MIN_PROPENSITY = 1e-3;

    private IpsEvaluator() {
    }

    /** {@code 1 / position^0.8} — never 0 or negative for {@code position >= 1}. */
    public static double positionDecay(int position) {
        if (position < 1) {
            throw new IllegalArgumentException("position must be >= 1, was " + position);
        }
        return 1.0 / Math.pow(position, POSITION_DECAY_EXPONENT);
    }

    /**
     * Propensity of the logging policy producing exactly the observed event
     * level (0=view-only, 1=click-only, 2=cart-no-purchase, 3=purchase) for
     * an item of the given WANDS grade shown at the given position — the
     * product down the observed funnel chain, matching exactly how
     * WANDS/CLICKSTREAM.md documents the generator constructing each event
     * (click conditioned on view, cart conditioned on click, purchase
     * conditioned on cart, each independently probabilistic).
     */
    public static double outcomePropensity(int wandsGrade, int position, int observedEventLevel) {
        double pClick = positionDecay(position) * CLICK_GIVEN_VIEW[wandsGrade];
        if (observedEventLevel == 0) {
            return 1.0 - pClick;
        }
        double pCart = CART_GIVEN_CLICK[wandsGrade];
        if (observedEventLevel == 1) {
            return pClick * (1.0 - pCart);
        }
        double pPurchase = PURCHASE_GIVEN_CART[wandsGrade];
        if (observedEventLevel == 2) {
            return pClick * pCart * (1.0 - pPurchase);
        }
        return pClick * pCart * pPurchase;
    }

    public static double reward(int observedEventLevel) {
        return REWARD_BY_LEVEL[observedEventLevel];
    }

    /** Accumulates one strategy's IPS terms across every held-out session it's evaluated against. */
    public static final class Accumulator {
        private double rawWeightedRewardSum;
        private double clippedWeightedRewardSum;
        private final List<Double> clippedWeights = new ArrayList<>();
        private int sessionCount;

        /**
         * Records one (strategy, session) observation. {@code rankedIds} is
         * the strategy's full reranked output for this session; only the top
         * {@code k} are scored, matching the K used by {@link EvalCli}'s
         * other two tables. Items in the top-K that weren't among the
         * session's original candidates (an injected product — Popularity/
         * Collaborative Filtering can add these) are skipped: they have no
         * logged position or observed outcome to reweight, the same honest
         * gap {@link EvalCli}'s class Javadoc already documents for the
         * other two tables.
         */
        public void recordSession(EvalSession session, List<String> rankedIds, int k) {
            sessionCount++;
            int limit = Math.min(k, rankedIds.size());
            for (int i = 0; i < limit; i++) {
                String productId = rankedIds.get(i);
                Integer position = originalPosition(session, productId);
                if (position == null) {
                    continue; // injected product — no logged outcome to reweight
                }
                int observedEventLevel = session.relevanceGrades().getOrDefault(productId, 0);
                int wandsGrade = session.independentRelevanceGrades().getOrDefault(productId, 0);
                double propensity = outcomePropensity(wandsGrade, position, observedEventLevel);
                double reward = reward(observedEventLevel);

                rawWeightedRewardSum += reward / propensity;

                double clippedPropensity = Math.max(propensity, MIN_PROPENSITY);
                double clippedWeight = 1.0 / clippedPropensity;
                clippedWeightedRewardSum += reward * clippedWeight;
                clippedWeights.add(clippedWeight);
            }
        }

        /** Recovers the item's actual logged 1-indexed position from {@code baseScoreProxy = 1/position} — see {@link EvalSessionLoader}. */
        private static Integer originalPosition(EvalSession session, String productId) {
            for (var candidate : session.baseCandidates()) {
                if (candidate.productId().equals(productId)) {
                    return (int) Math.round(1.0 / candidate.score());
                }
            }
            return null;
        }

        public IpsResult result() {
            if (sessionCount == 0) {
                return new IpsResult(0.0, 0.0, 0.0, 0, 0);
            }
            double rawEstimate = rawWeightedRewardSum / sessionCount;
            double clippedEstimate = clippedWeightedRewardSum / sessionCount;
            double ess = effectiveSampleSize(clippedWeights);
            return new IpsResult(rawEstimate, clippedEstimate, ess, sessionCount, clippedWeights.size());
        }

        /** Hájek/Kish effective sample size: {@code (Σw)² / Σw²}. Equals the item count when weights are uniform; shrinks toward 1 as a few weights dominate. */
        private static double effectiveSampleSize(List<Double> weights) {
            if (weights.isEmpty()) {
                return 0.0;
            }
            double sum = 0.0;
            double sumSquares = 0.0;
            for (double w : weights) {
                sum += w;
                sumSquares += w * w;
            }
            return sumSquares == 0.0 ? 0.0 : (sum * sum) / sumSquares;
        }
    }

    /**
     * @param rawEstimate      IPS-weighted reward-per-session estimate, unclipped propensities. Can be dominated by rare tiny-propensity events — see {@link #clippedEstimate}.
     * @param clippedEstimate  Same estimate with propensities floored at {@link #MIN_PROPENSITY} — the more trustworthy number when {@link #effectiveSampleSize} is low relative to {@link #scoredItemCount}.
     * @param effectiveSampleSize Hájek/Kish ESS over the clipped weights — how many "effective" independent samples the estimate is really based on.
     * @param sessionCount     held-out sessions this strategy was evaluated against.
     * @param scoredItemCount  items actually contributing a term (top-K minus injected/unscoreable products).
     */
    public record IpsResult(
            double rawEstimate,
            double clippedEstimate,
            double effectiveSampleSize,
            int sessionCount,
            int scoredItemCount
    ) {
        public String formatted() {
            return String.format(Locale.ROOT, "raw=%.4f clipped=%.4f ess=%.1f (n=%d, items=%d)",
                    rawEstimate, clippedEstimate, effectiveSampleSize, sessionCount, scoredItemCount);
        }
    }
}
