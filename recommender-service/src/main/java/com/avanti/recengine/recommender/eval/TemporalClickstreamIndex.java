package com.avanti.recengine.recommender.eval;

import com.avanti.recengine.recommender.domain.CatalogEntry;
import com.avanti.recengine.recommender.domain.UserProfile;
import com.avanti.recengine.recommender.port.out.ClickstreamRepositoryPort;
import com.avanti.recengine.support.wands.WandsProductCsvLoader;
import com.avanti.recengine.support.wands.WandsProductRow;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
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
 * A {@link ClickstreamRepositoryPort} whose aggregate state (popularity,
 * co-occurrence, user profiles) is built up incrementally by replaying
 * sessions in timestamp order, rather than loading the full clickstream
 * history at once like {@code CsvClickstreamRepositoryAdapter}.
 *
 * <p>Exists specifically to fix a point-in-time-correctness leak in the
 * offline eval: without this, a held-out session's popularity/co-occurrence
 * features would be computed using data that includes events happening
 * <i>after</i> that session's own timestamp — the same category of temporal
 * leak {@code training/TRAINING.md} already documents finding and fixing
 * in the training pipeline. Usage: call {@link #advance(String)} for a
 * session only after evaluating that session against this index's
 * <i>current</i> (pre-advance) state, then move to the next session in
 * {@link #sessionIdsInTimeOrder()} order. This does not change the
 * <i>served</i> {@code recommender-service}'s behavior, which reasonably
 * loads full history at startup — this is specifically about the eval
 * being honest, per the class's use in {@link EvalCli} only.
 *
 * <p>Session order is by each session's <i>earliest</i> event timestamp
 * (its first view). A session's own events are applied to the running
 * aggregates only once evaluation against that session has used the
 * pre-advance state — so a held-out session's own interactions still
 * become visible to <i>later</i> sessions (including later sessions from
 * other held-out users), matching how a real online system's aggregates
 * would evolve: a user's past actions inform future feature computation,
 * just never future-looking data.
 */
public final class TemporalClickstreamIndex implements ClickstreamRepositoryPort {

    private static final Map<String, Double> EVENT_WEIGHT = Map.of(
            "view", 0.2,
            "click", 0.5,
            "add_to_cart", 0.8,
            "purchase", 1.0
    );
    private static final Set<String> INTERACTION_EVENT_TYPES = Set.of("click", "add_to_cart", "purchase");

    private record Event(String userId, String productId, String eventType) {
    }

    private record SessionEvents(String sessionId, String userId, OffsetDateTime startTime, List<Event> events) {
    }

    private final Map<String, CatalogEntry> catalogByProduct = new HashMap<>();
    private final List<SessionEvents> sessionsInTimeOrder;

    private final Map<String, Double> popularityByProduct = new HashMap<>();
    private final Map<String, Map<String, Long>> coOccurrence = new HashMap<>();
    /** Denominator input for {@link #itemSimilarity} — see the identical field in CsvClickstreamRepositoryAdapter. */
    private final Map<String, Long> sessionInteractionCount = new HashMap<>();
    private final Map<String, Set<String>> interactedProductsByUser = new HashMap<>();
    private final Map<String, Map<String, Long>> categoryCountsByUser = new HashMap<>();
    private List<String> mostPopularCacheDirty; // invalidated on every advance()

    private TemporalClickstreamIndex(List<SessionEvents> sessionsInTimeOrder) {
        this.sessionsInTimeOrder = sessionsInTimeOrder;
    }

    public static TemporalClickstreamIndex load(Path clickstreamCsv, Path productCsv) {
        Map<String, CatalogEntry> catalog = new HashMap<>();
        for (WandsProductRow row : WandsProductCsvLoader.load(productCsv)) {
            catalog.put(row.productId(), new CatalogEntry(
                    row.productId(),
                    row.productName(),
                    row.productClass(),
                    row.categoryHierarchy(),
                    row.averageRating() == null ? 0.0 : row.averageRating(),
                    row.ratingCount() == null ? 0 : row.ratingCount()
            ));
        }

        record RawSession(String userId, OffsetDateTime startTime, List<Event> events) {
        }
        Map<String, RawSession> bySession = new HashMap<>();

        CSVFormat format = CSVFormat.Builder.create(CSVFormat.DEFAULT).setHeader().setSkipHeaderRecord(true).build();
        try (Reader reader = Files.newBufferedReader(clickstreamCsv, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            for (CSVRecord record : parser) {
                String sessionId = record.get("session_id");
                String userId = record.get("user_id");
                String productId = record.get("product_id");
                String eventType = record.get("event_type");
                OffsetDateTime timestamp = OffsetDateTime.parse(record.get("timestamp"));

                bySession.compute(sessionId, (id, existing) -> {
                    RawSession session = existing == null
                            ? new RawSession(userId, timestamp, new ArrayList<>())
                            : new RawSession(userId, existing.startTime().isBefore(timestamp) ? existing.startTime() : timestamp, existing.events());
                    session.events().add(new Event(userId, productId, eventType));
                    return session;
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + clickstreamCsv, e);
        }

        List<SessionEvents> sorted = bySession.entrySet().stream()
                .map(e -> new SessionEvents(e.getKey(), e.getValue().userId(), e.getValue().startTime(), e.getValue().events()))
                .sorted(Comparator.comparing(SessionEvents::startTime))
                .collect(Collectors.toList());

        TemporalClickstreamIndex index = new TemporalClickstreamIndex(sorted);
        index.catalogByProduct.putAll(catalog);
        return index;
    }

    /** Session ids in ascending start-time order — walk this to get point-in-time-correct eval. */
    public List<String> sessionIdsInTimeOrder() {
        return sessionsInTimeOrder.stream().map(SessionEvents::sessionId).toList();
    }

    /** Applies this session's events to the running aggregates. Call only after evaluating against the pre-advance state. */
    public void advance(String sessionId) {
        SessionEvents session = sessionsInTimeOrder.stream()
                .filter(s -> s.sessionId().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown session: " + sessionId));

        Set<String> interactedThisSession = new HashSet<>();
        for (Event event : session.events()) {
            popularityByProduct.merge(event.productId(), EVENT_WEIGHT.getOrDefault(event.eventType(), 0.0), Double::sum);

            if (!INTERACTION_EVENT_TYPES.contains(event.eventType())) {
                continue;
            }
            interactedThisSession.add(event.productId());
            interactedProductsByUser.computeIfAbsent(event.userId(), k -> new HashSet<>()).add(event.productId());

            CatalogEntry entry = catalogByProduct.get(event.productId());
            if (entry != null && entry.categoryHierarchy() != null) {
                String topLevel = entry.categoryHierarchy().split("/")[0].trim();
                categoryCountsByUser.computeIfAbsent(event.userId(), k -> new HashMap<>()).merge(topLevel, 1L, Long::sum);
            }
        }
        for (String a : interactedThisSession) {
            sessionInteractionCount.merge(a, 1L, Long::sum);
            for (String b : interactedThisSession) {
                if (!a.equals(b)) {
                    coOccurrence.computeIfAbsent(a, k -> new HashMap<>()).merge(b, 1L, Long::sum);
                }
            }
        }
        mostPopularCacheDirty = null;
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
        return new UserProfile(userId, Set.copyOf(interacted), Map.copyOf(categoryCountsByUser.getOrDefault(userId, Map.of())));
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
        return partners.keySet().stream()
                .sorted(Comparator.comparingDouble((String other) -> itemSimilarity(productId, other)).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<String> mostPopularProducts(int limit) {
        if (mostPopularCacheDirty == null) {
            mostPopularCacheDirty = popularityByProduct.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        }
        return mostPopularCacheDirty.subList(0, Math.min(limit, mostPopularCacheDirty.size()));
    }

    @Override
    public Optional<CatalogEntry> catalogEntry(String productId) {
        return Optional.ofNullable(catalogByProduct.get(productId));
    }
}
