package com.avanti.recengine.recommender.adapter.out.clickstream;

import com.avanti.recengine.recommender.domain.CatalogEntry;
import com.avanti.recengine.recommender.domain.CategoryHierarchy;
import com.avanti.recengine.recommender.domain.UserProfile;
import com.avanti.recengine.recommender.port.out.ClickstreamRepositoryPort;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory clickstream aggregates (popularity, item-item co-occurrence,
 * user profiles) built up one session at a time via {@link #applySession},
 * and the {@link ClickstreamRepositoryPort} queries over them. Subclasses
 * decide <i>when</i> sessions are applied: all up front for serving
 * ({@link CsvClickstreamRepositoryAdapter}), or incrementally in timestamp
 * order for point-in-time-correct offline eval ({@code TemporalClickstreamIndex}).
 *
 * <p>"Interacted" (for user profiles and co-occurrence) means
 * click/add_to_cart/purchase — see {@link com.avanti.recengine.recommender.domain.EventType#isInteraction}.
 */
public abstract class ClickstreamAggregates implements ClickstreamRepositoryPort {

    private final Map<String, CatalogEntry> catalogByProduct;
    private final Map<String, Double> popularityByProduct = new HashMap<>();
    private final Map<String, Map<String, Long>> coOccurrence = new HashMap<>();
    /**
     * Marginal interaction count per product: number of distinct sessions
     * in which the product had >=1 interaction event. This is the
     * denominator input for {@link #itemSimilarity} — deliberately not the
     * same thing as {@code popularityByProduct} above, which mixes in
     * weighted view events and isn't session-counted, so it wouldn't
     * normalize {@code coOccurrence} (itself built from per-session
     * interaction sets) consistently.
     */
    private final Map<String, Long> sessionInteractionCount = new HashMap<>();
    /** Per-user sets and counts iterate in first-interaction order — see {@link UserProfile#topCategory()}. */
    private final Map<String, Set<String>> interactedProductsByUser = new HashMap<>();
    private final Map<String, Map<String, Long>> categoryCountsByUser = new HashMap<>();
    /** Lazily rebuilt; {@code null} whenever {@link #applySession} has changed popularity since the last build. */
    private List<String> mostPopularCache;

    protected ClickstreamAggregates(Map<String, CatalogEntry> catalogByProduct) {
        this.catalogByProduct = catalogByProduct;
    }

    /** Folds one session's events into the running aggregates. */
    protected final void applySession(List<ClickstreamEvent> sessionEvents) {
        Set<String> interactedThisSession = new HashSet<>();
        for (ClickstreamEvent event : sessionEvents) {
            popularityByProduct.merge(event.productId(), event.weight(), Double::sum);
            if (event.isInteraction()) {
                interactedThisSession.add(event.productId());
                recordUserInteraction(event.userId(), event.productId());
            }
        }
        recordCoOccurrence(interactedThisSession);
        mostPopularCache = null;
    }

    private void recordUserInteraction(String userId, String productId) {
        interactedProductsByUser.computeIfAbsent(userId, k -> new LinkedHashSet<>()).add(productId);
        CatalogEntry entry = catalogByProduct.get(productId);
        if (entry != null && entry.categoryHierarchy() != null) {
            String topLevel = CategoryHierarchy.topLevelSegment(entry.categoryHierarchy());
            categoryCountsByUser.computeIfAbsent(userId, k -> new LinkedHashMap<>()).merge(topLevel, 1L, Long::sum);
        }
    }

    private void recordCoOccurrence(Set<String> sessionProducts) {
        for (String a : sessionProducts) {
            sessionInteractionCount.merge(a, 1L, Long::sum);
            for (String b : sessionProducts) {
                if (!a.equals(b)) {
                    coOccurrence.computeIfAbsent(a, k -> new HashMap<>()).merge(b, 1L, Long::sum);
                }
            }
        }
    }

    protected final String aggregateSummary() {
        return "%d products with popularity signal, %d users, %d catalog entries".formatted(
                popularityByProduct.size(), interactedProductsByUser.size(), catalogByProduct.size());
    }

    @Override
    public UserProfile userProfile(String userId) {
        if (userId == null || userId.isBlank()) {
            return UserProfile.empty(userId);
        }
        Set<String> interacted = interactedProductsByUser.get(userId);
        if (interacted == null) {
            return UserProfile.empty(userId);
        }
        // Order-preserving copies, not Set.copyOf/Map.copyOf: those iterate
        // in an order randomized per JVM, which made topCategory()'s
        // tie-break (and CollaborativeFilteringStrategy's injection seeds)
        // differ from one run to the next.
        return new UserProfile(userId,
                Collections.unmodifiableSet(new LinkedHashSet<>(interacted)),
                Collections.unmodifiableMap(new LinkedHashMap<>(categoryCountsByUser.getOrDefault(userId, Map.of()))));
    }

    @Override
    public double popularityScore(String productId) {
        return popularityByProduct.getOrDefault(productId, 0.0);
    }

    @Override
    public long coOccurrenceCount(String productId, Set<String> anyOf) {
        Map<String, Long> partners = coOccurrence.get(productId);
        if (partners == null || anyOf.isEmpty()) {
            return 0;
        }
        long total = 0;
        for (String other : anyOf) {
            if (!other.equals(productId)) {
                total += partners.getOrDefault(other, 0L);
            }
        }
        return total;
    }

    @Override
    public double itemSimilarity(String productIdA, String productIdB) {
        if (productIdA.equals(productIdB)) {
            return 1.0;
        }
        long marginalA = sessionInteractionCount.getOrDefault(productIdA, 0L);
        long marginalB = sessionInteractionCount.getOrDefault(productIdB, 0L);
        if (marginalA == 0L || marginalB == 0L) {
            return 0.0;
        }
        Map<String, Long> partners = coOccurrence.get(productIdA);
        long raw = partners == null ? 0L : partners.getOrDefault(productIdB, 0L);
        if (raw == 0L) {
            return 0.0;
        }
        return raw / Math.sqrt((double) marginalA * (double) marginalB);
    }

    @Override
    public List<String> relatedProducts(String productId, int limit) {
        Map<String, Long> partners = coOccurrence.get(productId);
        if (partners == null) {
            return List.of();
        }
        // Ranked by normalized similarity, not raw co-occurrence count — a
        // partner that's merely globally popular (and so co-occurs often
        // with everything) shouldn't outrank a partner with a smaller raw
        // count but much stronger relative affinity. See itemSimilarity.
        return partners.keySet().stream()
                .sorted(Comparator.comparingDouble((String other) -> itemSimilarity(productId, other)).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<String> mostPopularProducts(int limit) {
        if (mostPopularCache == null) {
            mostPopularCache = popularityByProduct.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .map(Map.Entry::getKey)
                    .toList();
        }
        return mostPopularCache.subList(0, Math.min(limit, mostPopularCache.size()));
    }

    @Override
    public Optional<CatalogEntry> catalogEntry(String productId) {
        return Optional.ofNullable(catalogByProduct.get(productId));
    }
}
