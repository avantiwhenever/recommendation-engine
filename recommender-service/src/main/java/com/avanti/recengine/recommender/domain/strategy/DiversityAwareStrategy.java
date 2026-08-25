package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.RecommendationStrategy;
import com.avanti.recengine.recommender.domain.ScoredProduct;
import com.avanti.recengine.recommender.port.out.VectorSimilarityPort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p><b>Similarity signal: embedding-based when available, category proxy
 * otherwise</b>. The category/{@code productClass} proxy — 1.0 if two
 * products share the same top-level category segment (same convention as
 * {@link BanditExploreStrategy}'s arm grouping), plus an extra 0.5 if they
 * also share the exact {@code productClass}, capped at 1.0 — is cheap and
 * has no external dependency, but it's coarse: two products in the same
 * top-level category that are genuinely dissimilar (e.g. a floor lamp and
 * a dining table, both under "Furniture") score as fully similar. When a
 * {@link VectorSimilarityPort} is supplied, its real Pinecone embedding
 * distance is blended in via {@code max(embeddingSimilarity,
 * categoryProxySimilarity)} rather than replacing the proxy outright — see
 * {@link #similarity} for why.
 *
 * <p><b>Why "max," not a straight swap</b>: {@link VectorSimilarityPort}
 * only exposes "the top-K nearest neighbors of product X," not "the
 * similarity score between two specific products A and B" — there's no
 * pairwise-distance call to make directly. This class approximates the
 * pairwise score from the neighbor list instead: at the start of {@link
 * #apply}, for every candidate it makes <em>one</em> {@code
 * similarProductIds} call requesting as many neighbors as there are
 * candidates, so every other candidate has a chance to appear somewhere in
 * the ranked result. A pair's embedding similarity is then {@code 1.0 -
 * (rank / candidateCount)} if one appears in the other's neighbor list
 * (whichever direction found it first), or {@code 0.0} if neither
 * direction found it — which happens either because the pair is
 * genuinely dissimilar, or because one product has no embedding in the
 * index at all (e.g. it was never ingested). Those two cases are
 * indistinguishable from this port alone, so treating an absent-from-both-
 * lists pair as "no embedding signal" and falling back to {@code
 * max(...)} with the category proxy — rather than trusting a bare 0.0 as
 * "confirmed dissimilar" — avoids a missing-embedding candidate silently
 * losing all diversity credit it would otherwise get from sharing a
 * category with an already-selected item.
 *
 * <p><b>Cost, stated honestly</b>: one {@code similarProductIds} call per
 * candidate per {@link #apply} invocation (bounded by the caller's
 * candidate-pool size — 50 in {@code SearchOrchestrationUseCase}'s
 * multi-stage pipeline, so at most 50 Pinecone lookups per request), done
 * once up front and cached in {@link #neighborRanksByProductId} for the
 * rest of the MMR loop — not one call per pairwise comparison, which would
 * be quadratic in the pool size and far too slow for a live request.
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
    /** Null means "no embedding signal available" — falls back to the category/productClass proxy only. */
    private final VectorSimilarityPort vectorSimilarityPort;

    public DiversityAwareStrategy(RecommendationStrategy delegate) {
        this(delegate, DEFAULT_LAMBDA, null);
    }

    /**
     * @param lambda relevance/diversity tradeoff in {@code [0, 1]}; {@code 1.0} degenerates to
     *               the delegate's own ranking unchanged, lower values weight diversity more.
     */
    public DiversityAwareStrategy(RecommendationStrategy delegate, double lambda) {
        this(delegate, lambda, null);
    }

    /** Same as {@link #DiversityAwareStrategy(RecommendationStrategy)}, plus real embedding similarity. */
    public DiversityAwareStrategy(RecommendationStrategy delegate, VectorSimilarityPort vectorSimilarityPort) {
        this(delegate, DEFAULT_LAMBDA, vectorSimilarityPort);
    }

    /**
     * @param lambda relevance/diversity tradeoff in {@code [0, 1]}; {@code 1.0} degenerates to
     *               the delegate's own ranking unchanged, lower values weight diversity more.
     * @param vectorSimilarityPort real embedding similarity, blended with the category proxy — see
     *                             this class's Javadoc for why it's blended rather than substituted.
     *                             {@code null} uses the category/productClass proxy alone.
     */
    public DiversityAwareStrategy(RecommendationStrategy delegate, double lambda, VectorSimilarityPort vectorSimilarityPort) {
        if (lambda < 0.0 || lambda > 1.0) {
            throw new IllegalArgumentException("lambda must be in [0, 1], got " + lambda);
        }
        this.delegate = delegate;
        this.lambda = lambda;
        this.vectorSimilarityPort = vectorSimilarityPort;
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

        // One similarProductIds call per candidate, up front — see the class
        // Javadoc's "Cost, stated honestly" section for why this is done once
        // per apply(), not once per pairwise comparison.
        Map<String, List<String>> neighborRanksByProductId = precomputeEmbeddingNeighbors(ranked);

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
                    maxSimilarity = Math.max(maxSimilarity, similarity(candidate, alreadySelected, neighborRanksByProductId));
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

    /**
     * One embedding-neighbor lookup per candidate, requesting as many
     * neighbors as there are candidates so every other candidate has a
     * chance to appear in the ranked result — see the class Javadoc. Empty
     * map (no lookups) when no {@link VectorSimilarityPort} was supplied.
     */
    private Map<String, List<String>> precomputeEmbeddingNeighbors(List<ScoredProduct> ranked) {
        if (vectorSimilarityPort == null) {
            return Map.of();
        }
        Map<String, List<String>> neighbors = new HashMap<>();
        for (ScoredProduct product : ranked) {
            neighbors.put(product.productId(), vectorSimilarityPort.similarProductIds(product.productId(), ranked.size()));
        }
        return neighbors;
    }

    private static double similarity(ScoredProduct a, ScoredProduct b, Map<String, List<String>> neighborRanksByProductId) {
        double categorySimilarity = 0.0;
        if (topLevelSegment(a.categoryHierarchy()).equalsIgnoreCase(topLevelSegment(b.categoryHierarchy()))) {
            categorySimilarity += CATEGORY_MATCH_SIMILARITY;
            if (sameProductClass(a.productClass(), b.productClass())) {
                categorySimilarity += PRODUCT_CLASS_MATCH_SIMILARITY;
            }
        }
        categorySimilarity = Math.min(1.0, categorySimilarity);

        double embeddingSimilarity = embeddingSimilarity(a, b, neighborRanksByProductId);
        return Math.max(categorySimilarity, embeddingSimilarity);
    }

    /** {@code 1.0 - (rank / candidateCount)} from whichever direction (a→b or b→a) finds the other first; 0.0 if neither does. */
    private static double embeddingSimilarity(ScoredProduct a, ScoredProduct b, Map<String, List<String>> neighborRanksByProductId) {
        double fromA = rankBasedSimilarity(b.productId(), neighborRanksByProductId.get(a.productId()));
        double fromB = rankBasedSimilarity(a.productId(), neighborRanksByProductId.get(b.productId()));
        return Math.max(fromA, fromB);
    }

    private static double rankBasedSimilarity(String targetProductId, List<String> neighborIds) {
        if (neighborIds == null || neighborIds.isEmpty()) {
            return 0.0;
        }
        int rank = neighborIds.indexOf(targetProductId);
        if (rank < 0) {
            return 0.0;
        }
        return 1.0 - ((double) rank / neighborIds.size());
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
