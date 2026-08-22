package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.CatalogEntry;
import com.avanti.recengine.recommender.domain.UserProfile;
import com.avanti.recengine.recommender.port.out.ClickstreamRepositoryPort;

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
    public List<String> relatedProducts(String productId, int limit) {
        Map<String, Long> partners = coOccurrence.getOrDefault(productId, Map.of());
        return partners.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
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
