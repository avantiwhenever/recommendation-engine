package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.RecommendationStrategy;
import com.avanti.recengine.recommender.domain.ScoredProduct;
import com.avanti.recengine.recommender.domain.UserProfile;
import com.avanti.recengine.recommender.port.out.ClickstreamRepositoryPort;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * A contextual multi-armed bandit, reading {@link RecommendationContext} to
 * condition its exploration on the requesting user:
 *
 * <ul>
 *   <li><b>Arms are top-level product categories</b> (e.g. "Furniture"),
 *       not raw items or positions — the same arm-per-attribute pattern
 *       Etsy describes for OPAR (Online Personalized Attribute-based
 *       Re-ranker; see
 *       <a href="https://www.etsy.com/codeascraft/building-a-platform-for-serving-recommendations-at-etsy">
 *       "Building a Platform for Serving Recommendations at Etsy"</a>),
 *       which bounds the arm space to a tractable number of categories
 *       instead of one arm per catalog item.</li>
 *   <li><b>Selection is Thompson Sampling</b> over a Beta(&alpha;, &beta;)
 *       posterior per arm, not epsilon-greedy — chosen specifically because
 *       it gives real per-request stochastic exploration (a stronger prior
 *       wins on average but not always, which is the actual mechanism of
 *       "explore vs. exploit"), unlike a static UCB1 score, which would be
 *       fully deterministic given fixed priors and therefore untestable as
 *       a distribution.</li>
 *   <li><b>Priors are seeded from real historical clickstream engagement</b>
 *       ({@link ClickstreamRepositoryPort#mostPopularProducts}/{@link
 *       ClickstreamRepositoryPort#popularityScore}/{@link
 *       ClickstreamRepositoryPort#catalogEntry}, aggregated by category),
 *       computed once at construction, not fabricated.</li>
 *   <li><b>Context-conditioned</b>: the requesting user's own most-frequent
 *       interacted category ({@link ClickstreamRepositoryPort#userProfile})
 *       gets an explicit boost to that arm's prior for this request — the
 *       "condition arm selection on context, not a static epsilon" pattern
 *       from Spotify Research's
 *       <a href="https://research.atspotify.com/2025/9/calibrated-recommendations-with-contextual-bandits-on-spotify-homepage">
 *       "Calibrated Recommendations with Contextual Bandits on Spotify
 *       Homepage"</a> (2025).</li>
 *   <li><b>Calibrated</b>: no single arm may claim more than {@link
 *       #MAX_ARM_SHARE} of the first {@link #CALIBRATION_WINDOW} positions,
 *       even if it wins every Thompson draw — the same Spotify article's
 *       calibration idea (don't just maximize expected reward per slot;
 *       bound how much one category can dominate the page). This is
 *       deliberately a <i>windowed</i> cap, not a whole-response fraction: a
 *       whole-response percentage cap over a fixed, closed candidate pool is
 *       a pigeonhole non-starter (whatever one arm doesn't take, the
 *       complement can never have enough of its own items to fill the rest,
 *       since both counts sum to the same fixed total by construction — the
 *       cap could never actually bind). Capping only the top window and
 *       letting a capped arm's overflow fall later in the <em>same</em> list
 *       sidesteps that: nothing is lost, and the property is still a real,
 *       hard, directly testable constraint on what appears above the
 *       fold.</li>
 * </ul>
 *
 * <p><b>What's honestly still a simplification, and why</b>: the per-arm
 * Beta priors are computed <em>once</em>, from historical data, at
 * construction time, and never updated from observed reward during
 * serving. A complete online bandit continuously updates its posteriors
 * from real feedback (a click, a purchase) as it arrives. This project has
 * no live traffic loop feeding such feedback back in — building a fake
 * online-update path with no real reward source to update from would be
 * worse than being explicit that this is "Thompson Sampling warm-started
 * from real historical priors, re-sampled stochastically per request," not
 * "a bandit that learns online." That's the same honesty standard {@link
 * NeuralRankingStrategy}'s Javadoc applies to its own train/serve skew —
 * see this class's test for how the resulting behavior is verified
 * statistically (a single fixed-seed snapshot can't validate a
 * probabilistic selection mechanism).
 */
public final class BanditExploreStrategy implements RecommendationStrategy {

    /** How many globally-popular products to sample when building each category's historical prior. */
    private static final int PRIOR_SAMPLE_SIZE = 500;
    /** Pseudo-count scale for the historical Beta prior — higher means the prior more strongly shapes early draws. */
    private static final double PRIOR_STRENGTH = 20.0;
    /** Extra alpha pseudo-count added to the requesting user's own most-frequent category, for this request only. */
    private static final double CONTEXT_BOOST = 5.0;
    /** No single category arm may take more than this fraction of {@link #CALIBRATION_WINDOW}'s positions. */
    private static final double MAX_ARM_SHARE = 0.6;
    /** How many leading positions the calibration cap applies to — the "above the fold" visible slots. */
    private static final int CALIBRATION_WINDOW = 5;
    private static final String UNCATEGORIZED_ARM = "Uncategorized";

    private final ClickstreamRepositoryPort clickstream;
    private final Random random;
    private final Map<String, double[]> historicalPriorByCategory;

    public BanditExploreStrategy(ClickstreamRepositoryPort clickstream) {
        this(clickstream, new Random());
    }

    public BanditExploreStrategy(ClickstreamRepositoryPort clickstream, Random random) {
        this.clickstream = clickstream;
        this.random = random;
        this.historicalPriorByCategory = computeHistoricalPriors(clickstream);
    }

    @Override
    public List<ScoredProduct> apply(RecommendationContext context, List<ScoredProduct> baseResults) {
        if (baseResults.size() < 2) {
            return List.copyOf(baseResults);
        }

        // Group candidates by arm, preserving each arm's original relative
        // (base-ranking) order — a Thompson draw picks which arm gets
        // priority this request, but never reshuffles within an arm.
        Map<String, Deque<ScoredProduct>> queueByArm = new LinkedHashMap<>();
        for (ScoredProduct product : baseResults) {
            queueByArm.computeIfAbsent(topLevelSegment(product.categoryHierarchy()), a -> new ArrayDeque<>())
                    .addLast(product);
        }

        Optional<String> contextArm = contextTopCategory(context);
        Map<String, Double> sampledValueByArm = new LinkedHashMap<>();
        for (String arm : queueByArm.keySet()) {
            double[] prior = historicalPriorByCategory.getOrDefault(arm, new double[]{1.0, 1.0});
            double alpha = prior[0];
            double beta = prior[1];
            if (contextArm.isPresent() && contextArm.get().equalsIgnoreCase(arm)) {
                alpha += CONTEXT_BOOST;
            }
            sampledValueByArm.put(arm, sampleBeta(alpha, beta, random));
        }

        List<ScoredProduct> result = new ArrayList<>(baseResults.size());
        int windowSize = Math.min(baseResults.size(), CALIBRATION_WINDOW);
        int maxSlotsPerArmInWindow = Math.max(1, (int) Math.ceil(windowSize * MAX_ARM_SHARE));
        Map<String, Integer> slotsTakenInWindow = new LinkedHashMap<>();

        // Phase 1: fill the calibration window, respecting the per-arm cap.
        while (result.size() < windowSize) {
            String arm = pickBestArm(queueByArm, sampledValueByArm, a -> slotsTakenInWindow.getOrDefault(a, 0) < maxSlotsPerArmInWindow);
            if (arm == null) {
                // Every arm with items left is at its window cap (only
                // possible if too few distinct arms exist to fill the
                // window under the cap) — relax the cap rather than
                // stalling; still bounded to at most this many extra slots.
                arm = pickBestArm(queueByArm, sampledValueByArm, a -> true);
            }
            result.add(queueByArm.get(arm).pollFirst());
            slotsTakenInWindow.merge(arm, 1, Integer::sum);
        }

        // Phase 2: fill whatever's left, in arm-value order, no cap — a
        // capped arm's overflow lands here instead of being dropped.
        while (result.size() < baseResults.size()) {
            String arm = pickBestArm(queueByArm, sampledValueByArm, a -> true);
            result.add(queueByArm.get(arm).pollFirst());
        }

        return result;
    }

    /** Highest sampled-value arm among those with items left and satisfying {@code eligible}; null if none. */
    private static String pickBestArm(Map<String, Deque<ScoredProduct>> queueByArm, Map<String, Double> sampledValueByArm,
                                       java.util.function.Predicate<String> eligible) {
        String best = null;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Deque<ScoredProduct>> entry : queueByArm.entrySet()) {
            String arm = entry.getKey();
            if (entry.getValue().isEmpty() || !eligible.test(arm)) {
                continue;
            }
            double value = sampledValueByArm.get(arm);
            if (value > bestValue) {
                bestValue = value;
                best = arm;
            }
        }
        return best;
    }

    private Optional<String> contextTopCategory(RecommendationContext context) {
        if (context.userId() == null || context.userId().isBlank()) {
            return Optional.empty();
        }
        UserProfile profile = clickstream.userProfile(context.userId());
        return profile.topCategory().map(BanditExploreStrategy::topLevelSegment);
    }

    /**
     * Aggregates {@link #PRIOR_SAMPLE_SIZE} globally-popular products' scores
     * by top-level category into a Beta(&alpha;, &beta;) pseudo-count pair
     * per category, normalized against the strongest category so alpha/beta
     * always sum to {@code 2 + PRIOR_STRENGTH} (keeping every arm's prior on
     * a comparable scale regardless of the catalog's absolute popularity
     * numbers). Both parameters are always &gt;= 1, which the Gamma-based
     * Beta sampler below requires.
     */
    private static Map<String, double[]> computeHistoricalPriors(ClickstreamRepositoryPort clickstream) {
        Map<String, Double> engagementByCategory = new LinkedHashMap<>();
        for (String productId : clickstream.mostPopularProducts(PRIOR_SAMPLE_SIZE)) {
            clickstream.catalogEntry(productId).ifPresent(entry -> {
                String arm = topLevelSegment(entry.categoryHierarchy());
                engagementByCategory.merge(arm, clickstream.popularityScore(productId), Double::sum);
            });
        }
        double maxEngagement = engagementByCategory.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        if (maxEngagement <= 0.0) {
            maxEngagement = 1.0;
        }

        Map<String, double[]> priors = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : engagementByCategory.entrySet()) {
            double normalized = Math.min(1.0, entry.getValue() / maxEngagement);
            double alpha = 1.0 + normalized * PRIOR_STRENGTH;
            double beta = 1.0 + (1.0 - normalized) * PRIOR_STRENGTH;
            priors.put(entry.getKey(), new double[]{alpha, beta});
        }
        return priors;
    }

    /** Same convention as {@link NeuralRankingStrategy}'s category parsing, null/blank-safe. */
    private static String topLevelSegment(String categoryHierarchy) {
        if (categoryHierarchy == null || categoryHierarchy.isBlank()) {
            return UNCATEGORIZED_ARM;
        }
        return categoryHierarchy.split("/")[0].trim();
    }

    /** Beta(alpha, beta) via two independent Gamma draws — {@code X/(X+Y)}, {@code X~Gamma(alpha)}, {@code Y~Gamma(beta)}. */
    private static double sampleBeta(double alpha, double beta, Random random) {
        double x = sampleGamma(alpha, random);
        double y = sampleGamma(beta, random);
        return x / (x + y);
    }

    /** Marsaglia &amp; Tsang's method (2000) — valid for shape &gt;= 1, which every caller here guarantees. */
    private static double sampleGamma(double shape, Random random) {
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x;
            double v;
            do {
                x = random.nextGaussian();
                v = 1.0 + c * x;
            } while (v <= 0);
            v = v * v * v;
            double u = random.nextDouble();
            double x2 = x * x;
            if (u < 1 - 0.0331 * x2 * x2) {
                return d * v;
            }
            if (Math.log(u) < 0.5 * x2 + d * (1 - v + Math.log(v))) {
                return d * v;
            }
        }
    }

    @Override
    public String name() {
        return "Bandit Exploration";
    }
}
