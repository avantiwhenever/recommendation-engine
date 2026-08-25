package com.avanti.recengine.search.port.in;

import com.avanti.recengine.search.domain.ScoredResult;

import java.util.List;

public interface SearchProductsUseCase {
    List<ScoredResult> search(String query, int topK);

    /**
     * TODO.md item #12: a hard eligibility filter applied to the fused
     * candidate pool before the result is truncated to {@code topK} —
     * {@code categoryFilter} blank/null means "no category filter"
     * (otherwise a case-insensitive substring match against the
     * candidate's {@code categoryHierarchy}); {@code minRating <= 0} means
     * "no rating floor". Applied post-fusion, not pre-retrieval, so a
     * narrow filter can legitimately return fewer than {@code topK}
     * results — an honest limitation, not a bug; over-fetching until
     * {@code topK} eligible results are found is a documented follow-on,
     * not implemented here.
     *
     * <p>Default implementation ignores the filter and delegates to {@link
     * #search(String, int)} — kept as a default (not a second abstract
     * method) so existing single-method implementations/lambdas of this
     * interface (see {@code SearchGrpcServiceTest}'s fake) keep compiling
     * unchanged; {@link com.avanti.recengine.search.application.SearchProductsService}
     * overrides this with real filtering.
     */
    default List<ScoredResult> search(String query, int topK, String categoryFilter, double minRating) {
        return search(query, topK);
    }
}
