package com.avanti.recengine.recommender.domain;

import java.util.List;

/**
 * @param recentProductIds product IDs the user interacted with in the
 *     <em>current</em> browsing session, distinct from their all-time
 *     history in {@link com.avanti.recengine.recommender.port.out.ClickstreamRepositoryPort}.
 *     Never null (use {@link List#of()}, not null, for "no session
 *     signal") — an empty list here means "no session signal available,"
 *     not "new user"; a genuinely new user also has an empty
 *     {@code ClickstreamRepositoryPort} profile, which is a separate
 *     condition (see {@code CollaborativeFilteringStrategy}'s cold-start
 *     branch).
 */
public record RecommendationContext(String query, String userId, Strategy strategy, List<String> recentProductIds) {

    /** Convenience constructor for callers with no session signal to report. */
    public RecommendationContext(String query, String userId, Strategy strategy) {
        this(query, userId, strategy, List.of());
    }
}
