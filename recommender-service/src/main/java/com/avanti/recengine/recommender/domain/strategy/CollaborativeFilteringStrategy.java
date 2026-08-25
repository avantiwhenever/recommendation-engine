package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.RecommendationStrategy;
import com.avanti.recengine.recommender.domain.ScoredProduct;
import com.avanti.recengine.recommender.domain.UserProfile;
import com.avanti.recengine.recommender.port.out.ClickstreamRepositoryPort;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Item-item collaborative filtering: boosts candidates whose normalized
 * similarity to products the requesting user has already interacted with is
 * highest, and injects related products for the user's history if
 * search-service didn't already surface them.
 *
 * <p>Classic neighborhood-based item-item CF using an adjusted-cosine-style
 * similarity over session co-occurrence — {@link ClickstreamRepositoryPort#itemSimilarity}
 * divides raw co-occurrence by the geometric mean of each item's own
 * marginal interaction count, so two items that are each independently
 * popular don't score as "similar" just from base rates the way raw
 * co-occurrence counting would. This is the standard normalization from
 * Sarwar, Karypis, Konstan &amp; Riedl, "Item-Based Collaborative Filtering
 * Recommendation Algorithms" (WWW 2001) — a static, offline technique. The
 * relationship to {@link BanditExploreStrategy} in this codebase is
 * architectural, not algorithmic: this strategy supplies a static
 * similarity signal, and bandit exploration is a separate, independent
 * mechanism a production system could compose with it.
 *
 * <p><b>Session recency</b>: {@link RecommendationContext#recentProductIds()}
 * — products the user interacted with in the <em>current</em> session, as
 * opposed to {@link ClickstreamRepositoryPort}'s all-time history — gets a
 * separately, more heavily weighted similarity boost ({@link
 * #SESSION_SIMILARITY_WEIGHT}, {@value #SESSION_WEIGHT_MULTIPLIER}x {@link
 * #SIMILARITY_WEIGHT}). This is a fixed multiplier, not a real time-decay
 * function — the proto only carries an unordered ID list, not per-event
 * timestamps, so "recency-weighted" here means "derived from the
 * session-scoped list," not an actual decay curve. See Pinterest's
 * <a href="https://medium.com/pinterest-engineering/real-time-user-signal-serving-for-feature-engineering-ead9a01e5b">
 * "Real-time User Signal Serving for Feature Engineering"</a> and Etsy's
 * <a href="https://www.etsy.com/codeascraft/leveraging-real-time-user-actions-to-personalize-etsy-ads">
 * "Leveraging Real-Time User Actions to Personalize Etsy Ads (ADPM)"</a> for
 * the short-term-vs-long-term-interest distinction this mirrors. Session
 * and all-time boosts are summed, not maxed — they're independent evidence
 * (a live session and a persisted history describe different things), and
 * in practice the CSV-backed {@code ClickstreamRepositoryPort} never
 * contains the current live session's events, so there's no double-counting
 * risk from the same interaction contributing to both terms.
 *
 * <p><b>Cold start</b>: a user with no all-time history
 * <em>and</em> no session signal is a genuine cold start — this strategy
 * has nothing to collaboratively filter on, and explicitly, by name, falls
 * back to {@link PopularityBoostStrategy}'s output rather than silently
 * returning the unmodified base ranking. A user with session signal but no persisted history is
 * <em>not</em> treated as cold-start: {@link #apply} runs normally, and the
 * session-similarity term alone drives personalization until their history
 * is persisted. See Netflix's
 * <a href="http://techblog.netflix.com/2012/04/netflix-recommendations-beyond-5-stars.html">
 * "Recommendations: Beyond the 5 stars"</a> for the general pattern of
 * blending a popularity/explicit-preference signal for new users and
 * transitioning to personalized signal as data accumulates.
 */
public final class CollaborativeFilteringStrategy implements RecommendationStrategy {

    private static final int MAX_INJECTED = 2;
    // Normalized similarity is bounded in [0,1] and typically much smaller
    // in practice (session co-occurrence is sparse over a 43K-item
    // catalog), unlike the old log1p(rawCount) boost this replaced — this
    // weight is a reasonable starting point, not empirically tuned against
    // real score distributions; recalibrate if boosts turn out too weak or
    // too strong relative to search-service's base scores.
    private static final double SIMILARITY_WEIGHT = 4.0;
    /** How much more heavily a same-session similarity match counts vs. all-time history — a starting point, not tuned. */
    private static final double SESSION_WEIGHT_MULTIPLIER = 2.5;
    private static final double SESSION_SIMILARITY_WEIGHT = SIMILARITY_WEIGHT * SESSION_WEIGHT_MULTIPLIER;

    private final ClickstreamRepositoryPort clickstream;
    private final PopularityBoostStrategy coldStartFallback;

    public CollaborativeFilteringStrategy(ClickstreamRepositoryPort clickstream) {
        this(clickstream, new PopularityBoostStrategy(clickstream));
    }

    /** Lets {@code RecommenderConfig} share one {@link PopularityBoostStrategy} instance as both the POPULARITY strategy and this strategy's cold-start fallback. */
    public CollaborativeFilteringStrategy(ClickstreamRepositoryPort clickstream, PopularityBoostStrategy coldStartFallback) {
        this.clickstream = clickstream;
        this.coldStartFallback = coldStartFallback;
    }

    @Override
    public List<ScoredProduct> apply(RecommendationContext context, List<ScoredProduct> baseResults) {
        UserProfile profile = clickstream.userProfile(context.userId());
        List<String> recentProductIds = context.recentProductIds();
        if (profile.interactedProductIds().isEmpty() && recentProductIds.isEmpty()) {
            // Genuine cold start: no all-time history AND no session signal
            // to personalize on — named, documented policy (see class
            // Javadoc), not a silent passthrough.
            return coldStartFallback.apply(context, baseResults);
        }

        List<ScoredProduct> reranked = new ArrayList<>(baseResults.size());
        for (ScoredProduct product : baseResults) {
            // Max, not mean, across each history: a candidate strongly
            // similar to just one thing the user liked is a good
            // recommendation on its own merits — averaging in the rest of a
            // possibly-diverse profile would dilute that signal rather than
            // sharpen it.
            double maxSessionSimilarity = 0.0;
            for (String recentId : recentProductIds) {
                maxSessionSimilarity = Math.max(maxSessionSimilarity, clickstream.itemSimilarity(product.productId(), recentId));
            }
            double maxHistorySimilarity = 0.0;
            for (String interactedId : profile.interactedProductIds()) {
                maxHistorySimilarity = Math.max(maxHistorySimilarity, clickstream.itemSimilarity(product.productId(), interactedId));
            }
            double boost = SESSION_SIMILARITY_WEIGHT * maxSessionSimilarity + SIMILARITY_WEIGHT * maxHistorySimilarity;
            reranked.add(product.withScore(product.score() + boost));
        }
        reranked.sort((a, b) -> Double.compare(b.score(), a.score()));

        Set<String> present = new LinkedHashSet<>();
        for (ScoredProduct p : reranked) {
            present.add(p.productId());
        }

        int injected = 0;
        Set<String> injectionSeeds = new LinkedHashSet<>(profile.interactedProductIds());
        injectionSeeds.addAll(recentProductIds);
        for (String seedProductId : injectionSeeds) {
            if (injected >= MAX_INJECTED) {
                break;
            }
            for (String relatedId : clickstream.relatedProducts(seedProductId, MAX_INJECTED)) {
                if (injected >= MAX_INJECTED || present.contains(relatedId)) {
                    continue;
                }
                clickstream.catalogEntry(relatedId).ifPresent(entry -> reranked.add(entry.toScoredProduct(0.0)));
                present.add(relatedId);
                injected++;
            }
        }

        return reranked;
    }

    @Override
    public String name() {
        return "Collaborative Filtering";
    }
}
