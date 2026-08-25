package com.avanti.recengine.recommender.adapter.out.clickstream;

import com.avanti.recengine.recommender.domain.CatalogEntry;
import com.avanti.recengine.recommender.domain.UserProfile;
import com.avanti.recengine.recommender.port.out.ClickstreamRepositoryPort;
import com.avanti.recengine.support.wands.WandsProductCsvLoader;
import com.avanti.recengine.support.wands.WandsProductRow;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads the synthetic clickstream CSV (event_id,user_id,session_id,
 * timestamp,query_id,query,position,product_id,event_type,dwell_time_ms)
 * plus WANDS product.csv (for catalog metadata and category lookups) once
 * at construction time into in-memory aggregates. ~171K clickstream rows and
 * ~43K products comfortably fit in memory — no need for a real database here.
 *
 * <p>"Interacted" (for user profiles and item-item co-occurrence) means
 * click/add_to_cart/purchase — plain views are excluded since every product
 * shown together on any results page would otherwise count as "co-occurring,"
 * diluting the signal down to near-meaninglessness (a session shows 8-15
 * items regardless of relevance).
 */
public final class CsvClickstreamRepositoryAdapter implements ClickstreamRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(CsvClickstreamRepositoryAdapter.class);

    private static final Map<String, Double> EVENT_WEIGHT = Map.of(
            "view", 0.2,
            "click", 0.5,
            "add_to_cart", 0.8,
            "purchase", 1.0
    );
    private static final Set<String> INTERACTION_EVENT_TYPES = Set.of("click", "add_to_cart", "purchase");

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
    private final Map<String, UserProfile> profilesByUser = new HashMap<>();
    private final Map<String, CatalogEntry> catalogByProduct = new HashMap<>();
    private List<String> mostPopularCache;

    public CsvClickstreamRepositoryAdapter(Path clickstreamCsv, Path productCsv) {
        loadCatalog(productCsv);
        loadClickstream(clickstreamCsv);
        log.info("Loaded clickstream aggregates: {} products with popularity signal, {} users, {} catalog entries",
                popularityByProduct.size(), profilesByUser.size(), catalogByProduct.size());
    }

    private void loadCatalog(Path productCsv) {
        List<WandsProductRow> rows = WandsProductCsvLoader.load(productCsv);
        for (WandsProductRow row : rows) {
            catalogByProduct.put(row.productId(), new CatalogEntry(
                    row.productId(),
                    row.productName(),
                    row.productClass(),
                    row.categoryHierarchy(),
                    row.averageRating() == null ? 0.0 : row.averageRating(),
                    row.ratingCount() == null ? 0 : row.ratingCount()
            ));
        }
    }

    private void loadClickstream(Path clickstreamCsv) {
        Map<String, Set<String>> interactedProductsBySession = new HashMap<>();
        Map<String, String> userBySession = new HashMap<>();
        Map<String, Map<String, Long>> categoryCountsByUser = new HashMap<>();
        Map<String, Set<String>> interactedProductsByUser = new HashMap<>();

        CSVFormat format = CSVFormat.Builder.create(CSVFormat.DEFAULT)
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();

        try (Reader reader = Files.newBufferedReader(clickstreamCsv, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            for (CSVRecord record : parser) {
                String userId = record.get("user_id");
                String sessionId = record.get("session_id");
                String productId = record.get("product_id");
                String eventType = record.get("event_type");

                popularityByProduct.merge(productId, EVENT_WEIGHT.getOrDefault(eventType, 0.0), Double::sum);

                if (!INTERACTION_EVENT_TYPES.contains(eventType)) {
                    continue;
                }

                userBySession.put(sessionId, userId);
                interactedProductsBySession.computeIfAbsent(sessionId, k -> new HashSet<>()).add(productId);
                interactedProductsByUser.computeIfAbsent(userId, k -> new HashSet<>()).add(productId);

                CatalogEntry entry = catalogByProduct.get(productId);
                if (entry != null && entry.categoryHierarchy() != null) {
                    String topLevel = entry.categoryHierarchy().split("/")[0].trim();
                    categoryCountsByUser.computeIfAbsent(userId, k -> new HashMap<>())
                            .merge(topLevel, 1L, Long::sum);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + clickstreamCsv, e);
        }

        for (Set<String> sessionProducts : interactedProductsBySession.values()) {
            for (String a : sessionProducts) {
                sessionInteractionCount.merge(a, 1L, Long::sum);
                for (String b : sessionProducts) {
                    if (!a.equals(b)) {
                        coOccurrence.computeIfAbsent(a, k -> new HashMap<>()).merge(b, 1L, Long::sum);
                    }
                }
            }
        }

        for (String userId : interactedProductsByUser.keySet()) {
            profilesByUser.put(userId, new UserProfile(
                    userId,
                    Set.copyOf(interactedProductsByUser.get(userId)),
                    Map.copyOf(categoryCountsByUser.getOrDefault(userId, Map.of()))
            ));
        }
    }

    @Override
    public UserProfile userProfile(String userId) {
        if (userId == null || userId.isBlank()) {
            return UserProfile.empty(userId);
        }
        return profilesByUser.getOrDefault(userId, UserProfile.empty(userId));
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
                .collect(Collectors.toList());
    }

    @Override
    public List<String> mostPopularProducts(int limit) {
        if (mostPopularCache == null) {
            mostPopularCache = popularityByProduct.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        }
        return mostPopularCache.subList(0, Math.min(limit, mostPopularCache.size()));
    }

    @Override
    public Optional<CatalogEntry> catalogEntry(String productId) {
        return Optional.ofNullable(catalogByProduct.get(productId));
    }
}
