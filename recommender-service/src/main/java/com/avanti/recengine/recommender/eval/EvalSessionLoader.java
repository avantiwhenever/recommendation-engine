package com.avanti.recengine.recommender.eval;

import com.avanti.recengine.recommender.adapter.out.clickstream.ClickstreamCsvReader;
import com.avanti.recengine.recommender.adapter.out.clickstream.ClickstreamEvent;
import com.avanti.recengine.recommender.adapter.out.clickstream.WandsCatalog;
import com.avanti.recengine.recommender.domain.CatalogEntry;
import com.avanti.recengine.recommender.domain.ScoredProduct;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reconstructs {@link EvalSession}s from the raw clickstream CSV — separate
 * from {@code CsvClickstreamRepositoryAdapter}, which builds aggregate
 * lookups (popularity, co-occurrence) rather than exposing per-session
 * candidate lists, since serving-time strategies never need raw sessions.
 *
 * <p>Also attaches each session's independent, WANDS-{@code label.csv}-derived
 * relevance grades (via {@link WandsLabelLoader}) alongside the clickstream-
 * derived implicit ones — see {@link EvalSession}'s Javadoc for why both
 * exist. {@code labelCsv} is optional (nullable): callers that only need the
 * (circular) clickstream-derived grades can pass {@code null} and every
 * session's {@code independentRelevanceGrades} will be an empty map.
 */
public final class EvalSessionLoader {

    private record RawSession(String userId, String queryId, TreeMap<Integer, String> productByPosition,
                              Map<String, Integer> grades) {
    }

    private EvalSessionLoader() {
    }

    public static List<EvalSession> load(Path clickstreamCsv, Path productCsv, Path labelCsv) {
        Map<String, CatalogEntry> catalog = WandsCatalog.load(productCsv);
        Map<String, Map<String, Integer>> labelsByQuery =
                labelCsv == null ? Map.of() : WandsLabelLoader.load(labelCsv);

        List<EvalSession> sessions = new ArrayList<>();
        for (Map.Entry<String, RawSession> entry : groupBySession(ClickstreamCsvReader.read(clickstreamCsv)).entrySet()) {
            RawSession raw = entry.getValue();
            List<ScoredProduct> candidates = toCandidates(raw.productByPosition(), catalog);
            if (!candidates.isEmpty()) {
                Map<String, Integer> independentGrades = labelsByQuery.getOrDefault(raw.queryId(), Map.of());
                sessions.add(new EvalSession(entry.getKey(), raw.userId(), raw.queryId(), candidates, raw.grades(), independentGrades));
            }
        }
        return sessions;
    }

    private static Map<String, RawSession> groupBySession(List<ClickstreamEvent> events) {
        Map<String, RawSession> bySession = new LinkedHashMap<>();
        for (ClickstreamEvent event : events) {
            RawSession raw = bySession.computeIfAbsent(event.sessionId(),
                    id -> new RawSession(event.userId(), event.queryId(), new TreeMap<>(), new LinkedHashMap<>()));
            raw.productByPosition().putIfAbsent(event.position(), event.productId());
            raw.grades().merge(event.productId(), event.level(), Math::max);
        }
        return bySession;
    }

    /**
     * Candidates in their originally-shown order, scored {@code 1/position}
     * as a base-score proxy; products missing from the catalog are dropped.
     */
    private static List<ScoredProduct> toCandidates(TreeMap<Integer, String> productByPosition,
                                                    Map<String, CatalogEntry> catalog) {
        List<ScoredProduct> candidates = new ArrayList<>();
        for (Map.Entry<Integer, String> positioned : productByPosition.entrySet()) {
            CatalogEntry product = catalog.get(positioned.getValue());
            if (product != null) {
                candidates.add(product.toScoredProduct(1.0 / positioned.getKey()));
            }
        }
        return candidates;
    }
}
