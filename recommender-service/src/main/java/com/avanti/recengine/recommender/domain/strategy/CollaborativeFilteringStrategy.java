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
 * Item-item collaborative filtering: boosts candidates that frequently
 * co-occur (same clickstream session) with products the requesting user has
 * already interacted with, and injects related products for the user's most
 * recent interaction if search-service didn't already surface them.
 *
 * <p>Classic neighborhood-based CF, using session co-occurrence rather than
 * a factorized user/item embedding — the simpler, more interpretable half
 * of the two approaches compared throughout the online/bandit-flavored CF
 * literature this project draws on (see arXiv:1708.03058, "Online
 * Interactive Collaborative Filtering Using Multi-Armed Bandit with
 * Dependent Arms", and arXiv:2106.10898, "BanditMF"): those papers layer
 * bandit-style exploration on top of a CF signal much like this class
 * provides, which is exactly the relationship between this strategy and
 * {@link BanditExploreStrategy} in this codebase — this strategy supplies
 * the collaborative signal; bandit exploration is what a production system
 * would compose with it, not a replacement for it.
 */
public final class CollaborativeFilteringStrategy implements RecommendationStrategy {

    private static final int MAX_INJECTED = 2;
    private static final double CO_OCCURRENCE_WEIGHT = 0.5;

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
            long coOccurrence = clickstream.coOccurrenceCount(product.productId(), profile.interactedProductIds());
            double boost = CO_OCCURRENCE_WEIGHT * Math.log1p(coOccurrence);
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
