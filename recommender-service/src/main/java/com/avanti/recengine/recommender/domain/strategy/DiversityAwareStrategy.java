package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.RecommendationStrategy;
import com.avanti.recengine.recommender.domain.ScoredProduct;
import com.avanti.recengine.recommender.port.out.VectorSimilarityPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 * <p><b>Cost, stated honestly — and a real bug this cost caused</b>: one
 * {@code similarProductIds} call per candidate per {@link #apply}
 * invocation (bounded by the caller's candidate-pool size — 50 in {@code
 * SearchOrchestrationUseCase}'s multi-stage pipeline), done once up front
 * and cached for the rest of the MMR loop — not one call per pairwise
 * comparison, which would be quadratic in the pool size. Even linear in
 * the pool size was measured too slow when run <em>sequentially</em>: a
 * live 8-result {@code DIVERSE_POPULARITY} request against Pinecone Local
 * took ~12.8 seconds end to end (up to 50 blocking round trips, one after
 * another). {@link #precomputeEmbeddingNeighbors} therefore issues all of
 * a request's lookups concurrently instead, via {@link
 * #EMBEDDING_LOOKUP_EXECUTOR} (a small fixed-size pool, {@value
 * #EMBEDDING_LOOKUP_PARALLELISM} threads, shared across requests rather
 * than created per call).
 *
 * <p><b>The honest result of parallelizing</b>: real, but partial —
 * ~3-5 seconds measured after this change, not the near-linear {@code
 * poolSize / parallelism} speedup a client-side bottleneck would predict.
 * Raising {@link #EMBEDDING_LOOKUP_PARALLELISM} from 10 to 25 made no
 * measurable difference to a single request's latency (both landed in the
 * same 2.5-4.3 second range), which points at Pinecone Local's own
 * request-handling capacity — a single-process, in-memory emulator, not
 * built for concurrent-query throughput — as the real per-request ceiling,
 * not this class's thread count.
 *
 * <p><b>A second, more serious cost this surfaced</b>: at parallelism 10,
 * a tight back-to-back burst of several {@code DIVERSE_POPULARITY}
 * requests (e.g. {@code scripts/capture_demo_snapshots.py} capturing every
 * demo query in sequence) could transiently overload Pinecone Local badly
 * enough to fail an <em>unrelated</em> request — observed live: a plain
 * {@code COLLABORATIVE} query, which never calls {@link
 * VectorSimilarityPort} at all, failed with a GraphQL {@code
 * INTERNAL_ERROR} because {@code search-service}'s own baseline Pinecone
 * query (needed by every strategy, not just this one) couldn't get a
 * connection while this class's concurrent lookups from a prior request
 * were still saturating Pinecone Local. Pinecone Local recovered on its
 * own moments later with no restart needed — this is a transient capacity
 * ceiling, not a crash. Lowering {@link #EMBEDDING_LOOKUP_PARALLELISM}
 * from 10 to 5 made the failure stop reproducing across repeated full
 * capture runs (each firing 36 requests, 6 of them {@code
 * DIVERSE_POPULARITY}, with no pacing between them) — a real, measured
 * fix, not a guess. {@code scripts/capture_demo_snapshots.py} also gained
 * its own retry-with-backoff for exactly this scenario, since a one-off
 * batch tool firing requests in a tighter, more uniform burst than real
 * user traffic ever would is a reasonable thing to make resilient on its
 * own, independent of the server-side parallelism tuning. See
 * docs/PROJECT_STATE.md for the full account; a real fix beyond this point
 * would mean changing what Pinecone Local itself can do, not this
 * strategy's calling pattern.
 */
public final class DiversityAwareStrategy implements RecommendationStrategy {

    /** Mostly-relevance, some-diversity — a documented starting point, not empirically tuned. */
    public static final double DEFAULT_LAMBDA = 0.7;

    /** Similarity contribution from sharing the same top-level category segment. */
    private static final double CATEGORY_MATCH_SIMILARITY = 0.5;
    /** Additional similarity contribution from sharing the exact product class, on top of category. */
    private static final double PRODUCT_CLASS_MATCH_SIMILARITY = 0.5;

    /** How many embedding lookups run concurrently — see the class Javadoc's "Cost" section. */
    private static final int EMBEDDING_LOOKUP_PARALLELISM = 5;
    /**
     * Shared across every {@link #apply} call on every {@code
     * DiversityAwareStrategy} instance in this JVM (there's effectively one
     * long-lived instance per strategy per process via {@code
     * RecommenderConfig}) — a fixed-size pool created once, not spun up and
     * torn down per request. Daemon threads so an idle pool never blocks
     * JVM shutdown.
     */
    private static final ExecutorService EMBEDDING_LOOKUP_EXECUTOR = Executors.newFixedThreadPool(
            EMBEDDING_LOOKUP_PARALLELISM, DiversityAwareStrategy::newDaemonThread);

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
     * chance to appear in the ranked result — see the class Javadoc for why
     * these run concurrently rather than one after another. Empty map (no
     * lookups) when no {@link VectorSimilarityPort} was supplied.
     */
    private Map<String, List<String>> precomputeEmbeddingNeighbors(List<ScoredProduct> ranked) {
        if (vectorSimilarityPort == null) {
            return Map.of();
        }
        Map<String, List<String>> neighbors = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> lookups = new ArrayList<>(ranked.size());
        for (ScoredProduct product : ranked) {
            lookups.add(CompletableFuture.runAsync(
                    () -> neighbors.put(product.productId(), vectorSimilarityPort.similarProductIds(product.productId(), ranked.size())),
                    EMBEDDING_LOOKUP_EXECUTOR));
        }
        CompletableFuture.allOf(lookups.toArray(CompletableFuture[]::new)).join();
        return neighbors;
    }

    private static Thread newDaemonThread(Runnable task) {
        Thread thread = new Thread(task, "diversity-embedding-lookup");
        thread.setDaemon(true);
        return thread;
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
