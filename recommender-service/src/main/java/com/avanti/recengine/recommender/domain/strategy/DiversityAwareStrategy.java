package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.RecommendationStrategy;
import com.avanti.recengine.recommender.domain.ScoredProduct;

import java.util.ArrayList;
import java.util.List;

/**
 * A decorator, not a standalone strategy: wraps another {@link
 * RecommendationStrategy}, takes its output as-is, then re-ranks it with a
 * Maximal Marginal Relevance (MMR) pass so a top-5 isn't just the 5
 * highest-scored near-duplicates of each other — see Netflix's
 * <a href="http://techblog.netflix.com/2012/04/netflix-recommendations-beyond-5-stars.html">
 * "Netflix Recommendations: Beyond the 5 stars, Part 1"</a> and
 * <a href="http://techblog.netflix.com/2012/06/netflix-recommendations-beyond-5-stars.html">
 * Part 2</a>, which name diversity as a first-class ranking objective
 * distinct from raw predicted relevance.
 *
 * <p>Standard MMR: greedily picks the next item maximizing
 * {@code lambda * relevance(item) - (1 - lambda) * maxSimilarity(item, selected)},
 * where {@code relevance} is the item's own score exactly as the wrapped
 * strategy produced it (this decorator never invents a new relevance
 * signal, only trades some of it off against diversity) and
 * {@code maxSimilarity} is its highest pairwise similarity to any item
 * already selected.
 *
 * <p><b>Similarity signal, and an honest limitation</b>: similarity here is
 * a category proxy — 1.0 if two products share the same top-level category
 * segment (same convention as {@link BanditExploreStrategy}'s arm
 * grouping), plus an extra 0.5 if they also share the exact {@code
 * productClass}, capped at 1.0. This is cheap and has no external
 * dependency, but it's coarse: two products in the same top-level category
 * that are genuinely dissimilar (e.g. a floor lamp and a dining table,
 * both under "Furniture") are still scored as fully similar. This service
 * also has a {@code VectorSimilarityPort} backed by search-service's
 * Pinecone embeddings; swapping in real embedding cosine distance as the
 * similarity signal here would be a natural, low-risk follow-on — not
 * implemented here, which uses only the category/productClass proxy.
 */
public final class DiversityAwareStrategy implements RecommendationStrategy {

    /** Mostly-relevance, some-diversity — a documented starting point, not empirically tuned. */
    public static final double DEFAULT_LAMBDA = 0.7;

    /** Similarity contribution from sharing the same top-level category segment. */
    private static final double CATEGORY_MATCH_SIMILARITY = 0.5;
    /** Additional similarity contribution from sharing the exact product class, on top of category. */
    private static final double PRODUCT_CLASS_MATCH_SIMILARITY = 0.5;

    private final RecommendationStrategy delegate;
    private final double lambda;

    public DiversityAwareStrategy(RecommendationStrategy delegate) {
        this(delegate, DEFAULT_LAMBDA);
    }

    /**
     * @param lambda relevance/diversity tradeoff in {@code [0, 1]}; {@code 1.0} degenerates to
     *               the delegate's own ranking unchanged, lower values weight diversity more.
     */
    public DiversityAwareStrategy(RecommendationStrategy delegate, double lambda) {
        if (lambda < 0.0 || lambda > 1.0) {
            throw new IllegalArgumentException("lambda must be in [0, 1], got " + lambda);
        }
        this.delegate = delegate;
        this.lambda = lambda;
    }

    @Override
    public List<ScoredProduct> apply(RecommendationContext context, List<ScoredProduct> baseResults) {
        List<ScoredProduct> ranked = delegate.apply(context, baseResults);
        if (ranked.size() < 2) {
            return ranked;
        }

        double minScore = ranked.stream().mapToDouble(ScoredProduct::score).min().orElse(0.0);
        double maxScore = ranked.stream().mapToDouble(ScoredProduct::score).max().orElse(0.0);
        double range = maxScore - minScore;

        List<ScoredProduct> remaining = new ArrayList<>(ranked);
        List<ScoredProduct> selected = new ArrayList<>(ranked.size());

        // First pick is always the delegate's top result — nothing to diversify against yet.
        selected.add(remaining.remove(0));

        while (!remaining.isEmpty()) {
            ScoredProduct best = null;
            double bestMmr = Double.NEGATIVE_INFINITY;
            for (ScoredProduct candidate : remaining) {
                double relevance = range > 0.0 ? (candidate.score() - minScore) / range : 1.0;
                double maxSimilarity = 0.0;
                for (ScoredProduct alreadySelected : selected) {
                    maxSimilarity = Math.max(maxSimilarity, similarity(candidate, alreadySelected));
                }
                double mmr = lambda * relevance - (1.0 - lambda) * maxSimilarity;
                if (mmr > bestMmr) {
                    bestMmr = mmr;
                    best = candidate;
                }
            }
            selected.add(best);
            remaining.remove(best);
        }

        return selected;
    }

    private static double similarity(ScoredProduct a, ScoredProduct b) {
        double similarity = 0.0;
        if (topLevelSegment(a.categoryHierarchy()).equalsIgnoreCase(topLevelSegment(b.categoryHierarchy()))) {
            similarity += CATEGORY_MATCH_SIMILARITY;
            if (sameProductClass(a.productClass(), b.productClass())) {
                similarity += PRODUCT_CLASS_MATCH_SIMILARITY;
            }
        }
        return Math.min(1.0, similarity);
    }

    private static boolean sameProductClass(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        return a.equalsIgnoreCase(b);
    }

    /** Same convention as {@link BanditExploreStrategy}'s category parsing, null/blank-safe. */
    private static String topLevelSegment(String categoryHierarchy) {
        if (categoryHierarchy == null || categoryHierarchy.isBlank()) {
            return "Uncategorized";
        }
        return categoryHierarchy.split("/")[0].trim();
    }

    @Override
    public String name() {
        return delegate.name() + " (diversified)";
    }
}
