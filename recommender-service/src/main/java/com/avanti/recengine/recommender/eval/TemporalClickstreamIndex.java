package com.avanti.recengine.recommender.eval;

import com.avanti.recengine.recommender.adapter.out.clickstream.ClickstreamAggregates;
import com.avanti.recengine.recommender.adapter.out.clickstream.ClickstreamCsvReader;
import com.avanti.recengine.recommender.adapter.out.clickstream.ClickstreamEvent;
import com.avanti.recengine.recommender.adapter.out.clickstream.WandsCatalog;
import com.avanti.recengine.recommender.domain.CatalogEntry;
import com.avanti.recengine.recommender.port.out.ClickstreamRepositoryPort;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
public final class TemporalClickstreamIndex extends ClickstreamAggregates {

    /** Iteration order is ascending session start time. */
    private final Map<String, List<ClickstreamEvent>> eventsBySessionInTimeOrder;

    private TemporalClickstreamIndex(Map<String, CatalogEntry> catalog,
                                     Map<String, List<ClickstreamEvent>> eventsBySessionInTimeOrder) {
        super(catalog);
        this.eventsBySessionInTimeOrder = eventsBySessionInTimeOrder;
    }

    public static TemporalClickstreamIndex load(Path clickstreamCsv, Path productCsv) {
        return new TemporalClickstreamIndex(
                WandsCatalog.load(productCsv),
                groupBySessionInTimeOrder(ClickstreamCsvReader.read(clickstreamCsv)));
    }

    private static Map<String, List<ClickstreamEvent>> groupBySessionInTimeOrder(List<ClickstreamEvent> events) {
        Map<String, List<ClickstreamEvent>> bySession = new LinkedHashMap<>();
        for (ClickstreamEvent event : events) {
            bySession.computeIfAbsent(event.sessionId(), id -> new ArrayList<>()).add(event);
        }
        Map<String, List<ClickstreamEvent>> inTimeOrder = new LinkedHashMap<>();
        bySession.entrySet().stream()
                .sorted(Comparator.comparing(entry -> startTime(entry.getValue())))
                .forEachOrdered(entry -> inTimeOrder.put(entry.getKey(), entry.getValue()));
        return inTimeOrder;
    }

    private static OffsetDateTime startTime(List<ClickstreamEvent> sessionEvents) {
        return sessionEvents.stream().map(ClickstreamEvent::timestamp).min(Comparator.naturalOrder()).orElseThrow();
    }

    /** Session ids in ascending start-time order — walk this to get point-in-time-correct eval. */
    public List<String> sessionIdsInTimeOrder() {
        return List.copyOf(eventsBySessionInTimeOrder.keySet());
    }

    /** Applies this session's events to the running aggregates. Call only after evaluating against the pre-advance state. */
    public void advance(String sessionId) {
        List<ClickstreamEvent> sessionEvents = eventsBySessionInTimeOrder.get(sessionId);
        if (sessionEvents == null) {
            throw new IllegalArgumentException("Unknown session: " + sessionId);
        }
        applySession(sessionEvents);
    }
}
