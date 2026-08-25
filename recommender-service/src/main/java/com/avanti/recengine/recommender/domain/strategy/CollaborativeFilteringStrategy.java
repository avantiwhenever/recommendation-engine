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
 * Recommendation Algorithms" (WWW 2001) — a static, offline technique, not
 * the online/bandit-flavored CF this project's arXiv:1708.03058
 * ("Online Interactive Collaborative Filtering Using Multi-Armed Bandit
 * with Dependent Arms") and arXiv:2106.10898 ("BanditMF") citations
 * describe; those papers assume per-arm reward updating this strategy
 * doesn't do. The actual relationship to bandit-flavored CF in this
 * codebase is architectural, not algorithmic: this strategy supplies a
 * static similarity signal, and {@link BanditExploreStrategy} is a
 * separate, independent exploration mechanism a production system could
 * compose with it — not an implementation of the online CF those papers
 * describe.
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

    private final ClickstreamRepositoryPort clickstream;

    public CollaborativeFilteringStrategy(ClickstreamRepositoryPort clickstream) {
        this.clickstream = clickstream;
    }

    @Override
    public List<ScoredProduct> apply(RecommendationContext context, List<ScoredProduct> baseResults) {
        UserProfile profile = clickstream.userProfile(context.userId());
        if (profile.interactedProductIds().isEmpty()) {
            // No history for this user (or no userId given) — nothing to
            // collaboratively filter on, fall back to the base ranking.
            return List.copyOf(baseResults);
        }

        List<ScoredProduct> reranked = new ArrayList<>(baseResults.size());
        for (ScoredProduct product : baseResults) {
            // Max, not mean, across the user's history: a candidate strongly
            // similar to just one thing the user liked is a good
            // recommendation on its own merits — averaging in the rest of a
            // possibly-diverse profile would dilute that signal rather than
            // sharpen it.
            double maxSimilarity = 0.0;
            for (String interactedId : profile.interactedProductIds()) {
                maxSimilarity = Math.max(maxSimilarity, clickstream.itemSimilarity(product.productId(), interactedId));
            }
            double boost = SIMILARITY_WEIGHT * maxSimilarity;
            reranked.add(product.withScore(product.score() + boost));
        }
        reranked.sort((a, b) -> Double.compare(b.score(), a.score()));

        Set<String> present = new LinkedHashSet<>();
        for (ScoredProduct p : reranked) {
            present.add(p.productId());
        }

        int injected = 0;
        for (String seedProductId : profile.interactedProductIds()) {
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
