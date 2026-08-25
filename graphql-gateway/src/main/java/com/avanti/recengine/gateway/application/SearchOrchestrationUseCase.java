package com.avanti.recengine.gateway.application;

import com.avanti.recengine.gateway.domain.Product;
import com.avanti.recengine.gateway.domain.RecommendResult;
import com.avanti.recengine.gateway.domain.RecommenderStrategy;
import com.avanti.recengine.gateway.domain.SearchResult;
import com.avanti.recengine.gateway.port.out.RecommenderPort;
import com.avanti.recengine.gateway.port.out.SearchPort;

import java.util.Comparator;
import java.util.List;

/**
 * The gateway's only real logic: call search-service for baseline
 * candidates, then (unless the strategy is {@code NONE}) call
 * recommender-service to add/remove/re-rank them. Framework-free — no
 * Spring, no gRPC, no GraphQL types — so it's testable with fake ports.
 *
 * <p>{@code NONE} skips the recommender-service call entirely rather than
 * routing through a server-side passthrough strategy — one fewer network
 * hop for the common "just show me raw search results" case, and it means
 * this use case (not recommender-service) is the single place that decides
 * whether personalization happens at all.
 *
 * <p><b>Multi-stage retrieval</b>: rather than asking
 * search-service for exactly {@code topK} candidates and reranking that
 * fixed, narrow list, this use case widens the initial request to {@link
 * #WIDE_POOL_SIZE} (a real retrieval pool an expensive rerank strategy can
 * work with, not just whatever the caller asked to display), then runs a
 * hard <b>selection</b> stage — a hand-rolled 3-line filter, not a second
 * ML pass — that drops candidates with zero social proof ({@code
 * ratingCount == 0}), distinct in kind from every strategy's <b>scoring</b>
 * pass below it (see DoorDash's "Powering Search &amp; Recommendations at
 * DoorDash" on separating hard eligibility filters from ranking). The
 * surviving candidates are cut to {@link #ELIGIBLE_POOL_SIZE} by the score
 * search-service already computed (free — no extra work) before being
 * handed to whichever strategy runs next, and the strategy's own (possibly
 * reordered, possibly item-added) output is truncated to the caller's
 * requested {@code topK} only at the very end. Grounded in LinkedIn's
 * "Making Your Feed More Relevant – Part I" (First Pass Ranker → Second
 * Pass Ranker) and DoorDash's selection/ranking separation.
 *
 * <p>One real behavior change from this: {@code NONE} is no longer a
 * byte-for-byte passthrough of whatever search-service returned — it's a
 * passthrough of whatever search-service returned <em>after</em> the same
 * hard eligibility filter every other strategy sees. That's deliberate:
 * the filter is a data-quality gate ("don't show something with zero
 * social proof"), not a personalization decision, so it applies uniformly
 * rather than being one more thing that changes when the strategy dropdown
 * changes.
 *
 * <p><b>Undocumented-elsewhere edge case, noted honestly here</b>: a
 * caller requesting {@code topK > }{@link #ELIGIBLE_POOL_SIZE} gets fewer
 * results than requested, silently — the selection stage caps the pool
 * every strategy works with at {@link #ELIGIBLE_POOL_SIZE} regardless of
 * how large {@code topK} is, and nothing downstream backfills past that
 * cap. Not a bug (a fixed-size candidate pool is the whole point of the
 * cheap-cut design), but worth knowing before assuming a large {@code
 * topK} request always returns that many results — today's callers
 * ({@code web}'s UI, the demo capture script) all request well under this
 * cap, so it hasn't surfaced in practice.
 */
public final class SearchOrchestrationUseCase {

    private static final String SEARCH_ONLY_SOURCE = "search";

    /** How many candidates to request from search-service — a real retrieval pool, not just the display count. */
    static final int WIDE_POOL_SIZE = 200;
    /** How many of the widened, eligibility-filtered candidates survive the cheap cut before an expensive strategy runs. */
    static final int ELIGIBLE_POOL_SIZE = 50;

    private final SearchPort searchPort;
    private final RecommenderPort recommenderPort;

    public SearchOrchestrationUseCase(SearchPort searchPort, RecommenderPort recommenderPort) {
        this.searchPort = searchPort;
        this.recommenderPort = recommenderPort;
    }

    /** Convenience overload for callers with no session signal to report. */
    public SearchResult search(String query, int topK, RecommenderStrategy strategy, String userId) {
        return search(query, topK, strategy, userId, List.of());
    }

    /** Convenience overload for callers with no filters to apply. */
    public SearchResult search(String query, int topK, RecommenderStrategy strategy, String userId,
                                List<String> recentProductIds) {
        return search(query, topK, strategy, userId, recentProductIds, null, 0.0);
    }

    public SearchResult search(String query, int topK, RecommenderStrategy strategy, String userId,
                                List<String> recentProductIds, String categoryFilter, double minRating) {
        List<Product> widePool = searchPort.search(query, Math.max(topK, WIDE_POOL_SIZE), categoryFilter, minRating);
        List<Product> eligible = selectEligible(widePool);

        if (strategy == RecommenderStrategy.NONE) {
            List<Product> results = eligible.stream()
                    .limit(topK)
                    .map(p -> p.withSource(SEARCH_ONLY_SOURCE))
                    .toList();
            return new SearchResult(query, strategy, results);
        }

        List<String> recentIds = recentProductIds == null ? List.of() : recentProductIds;
        RecommendResult recommended = recommenderPort.recommend(query, userId, strategy, eligible, recentIds);
        List<Product> results = recommended.products().stream()
                .limit(topK)
                .map(p -> p.withSource(recommended.source()))
                .toList();
        return new SearchResult(query, strategy, results);
    }

    /**
     * The selection stage: drops zero-social-proof candidates, then cuts to
     * {@link #ELIGIBLE_POOL_SIZE} by search-service's own score — cheap
     * (no new scoring pass), applied before any strategy runs, and applied
     * identically regardless of which strategy the caller picked.
     */
    private static List<Product> selectEligible(List<Product> widePool) {
        return widePool.stream()
                .filter(p -> p.ratingCount() > 0)
                .sorted(Comparator.comparingDouble(Product::score).reversed())
                .limit(ELIGIBLE_POOL_SIZE)
                .toList();
    }
}
