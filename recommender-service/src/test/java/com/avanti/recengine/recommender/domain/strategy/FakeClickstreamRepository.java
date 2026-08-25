package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.CatalogEntry;
import com.avanti.recengine.recommender.domain.UserProfile;
import com.avanti.recengine.recommender.port.out.ClickstreamRepositoryPort;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** In-memory fake for strategy unit tests — no CSV, no Spring, no I/O. */
final class FakeClickstreamRepository implements ClickstreamRepositoryPort {

    private final Map<String, UserProfile> profiles = new HashMap<>();
    private final Map<String, Double> popularity = new HashMap<>();
    private final Map<String, Map<String, Long>> coOccurrence = new HashMap<>();
    /** Defaults to 1 when unset, so itemSimilarity reduces to plain raw
     *  co-occurrence for tests that don't care about normalization
     *  (denominator sqrt(1*1) == 1) — keeps older co-occurrence-only tests
     *  valid without every test needing to set marginal counts explicitly. */
    private final Map<String, Long> marginalCounts = new HashMap<>();
    private final List<String> popularOrder;
    private final Map<String, CatalogEntry> catalog = new HashMap<>();

    FakeClickstreamRepository(List<String> popularOrder) {
        this.popularOrder = popularOrder;
    }

    void withProfile(UserProfile profile) {
        profiles.put(profile.userId(), profile);
    }

    void withPopularity(String productId, double score) {
        popularity.put(productId, score);
    }

    void withCoOccurrence(String productId, String other, long count) {
        coOccurrence.computeIfAbsent(productId, k -> new HashMap<>()).put(other, count);
    }

    void withMarginalCount(String productId, long count) {
        marginalCounts.put(productId, count);
    }

    void withCatalogEntry(CatalogEntry entry) {
        catalog.put(entry.productId(), entry);
    }

    @Override
    public UserProfile userProfile(String userId) {
        return profiles.getOrDefault(userId, UserProfile.empty(userId));
    }

    @Override
    public double popularityScore(String productId) {
        return popularity.getOrDefault(productId, 0.0);
    }

    @Override
    public long coOccurrenceCount(String productId, Set<String> anyOf) {
        Map<String, Long> partners = coOccurrence.get(productId);
        if (partners == null) {
            return 0;
        }
        long total = 0;
        for (String other : anyOf) {
            total += partners.getOrDefault(other, 0L);
        }
        return total;
    }

    @Override
    public double itemSimilarity(String productIdA, String productIdB) {
        if (productIdA.equals(productIdB)) {
            return 1.0;
        }
        long marginalA = marginalCounts.getOrDefault(productIdA, 1L);
        long marginalB = marginalCounts.getOrDefault(productIdB, 1L);
        Map<String, Long> partners = coOccurrence.get(productIdA);
        long raw = partners == null ? 0L : partners.getOrDefault(productIdB, 0L);
        if (raw == 0L) {
            return 0.0;
        }
        return raw / Math.sqrt((double) marginalA * (double) marginalB);
    }

    @Override
    public List<String> relatedProducts(String productId, int limit) {
        Map<String, Long> partners = coOccurrence.getOrDefault(productId, Map.of());
        return partners.keySet().stream()
                .sorted(Comparator.comparingDouble((String other) -> itemSimilarity(productId, other)).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<String> mostPopularProducts(int limit) {
        return popularOrder.subList(0, Math.min(limit, popularOrder.size()));
    }

    @Override
    public Optional<CatalogEntry> catalogEntry(String productId) {
        return Optional.ofNullable(catalog.get(productId));
    }
}
