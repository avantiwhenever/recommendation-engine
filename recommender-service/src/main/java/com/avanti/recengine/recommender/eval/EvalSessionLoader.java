package com.avanti.recengine.recommender.eval;

import com.avanti.recengine.recommender.domain.ScoredProduct;
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

    private static final Map<String, Integer> EVENT_GRADE = Map.of(
            "view", 0,
            "click", 1,
            "add_to_cart", 2,
            "purchase", 3
    );

    private EvalSessionLoader() {
    }

    public static List<EvalSession> load(Path clickstreamCsv, Path productCsv, Path labelCsv) {
        Map<String, WandsProductRow> catalog = new LinkedHashMap<>();
        for (WandsProductRow row : WandsProductCsvLoader.load(productCsv)) {
            catalog.put(row.productId(), row);
        }

        Map<String, Map<String, Integer>> labelsByQuery =
                labelCsv == null ? Map.of() : WandsLabelLoader.load(labelCsv);

        record Raw(String userId, String queryId, TreeMap<Integer, String> productByPosition, Map<String, Integer> grades) {
        }
        Map<String, Raw> bySession = new LinkedHashMap<>();

        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
        try (Reader reader = Files.newBufferedReader(clickstreamCsv, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            for (CSVRecord record : parser) {
                String sessionId = record.get("session_id");
                String userId = record.get("user_id");
                String queryId = record.get("query_id");
                String productId = record.get("product_id");
                int position = Integer.parseInt(record.get("position"));
                int grade = EVENT_GRADE.getOrDefault(record.get("event_type"), 0);

                Raw raw = bySession.computeIfAbsent(sessionId, id -> new Raw(userId, queryId, new TreeMap<>(), new LinkedHashMap<>()));
                raw.productByPosition().putIfAbsent(position, productId);
                raw.grades().merge(productId, grade, Math::max);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + clickstreamCsv, e);
        }

        List<EvalSession> sessions = new ArrayList<>(bySession.size());
        for (Map.Entry<String, Raw> entry : bySession.entrySet()) {
            Raw raw = entry.getValue();
            List<ScoredProduct> candidates = new ArrayList<>();
            for (Map.Entry<Integer, String> positioned : raw.productByPosition().entrySet()) {
                WandsProductRow product = catalog.get(positioned.getValue());
                if (product == null) {
                    continue;
                }
                double baseScoreProxy = 1.0 / positioned.getKey();
                candidates.add(new ScoredProduct(
                        product.productId(),
                        baseScoreProxy,
                        product.productName(),
                        product.productClass(),
                        product.categoryHierarchy(),
                        product.averageRating() == null ? 0.0 : product.averageRating(),
                        product.ratingCount() == null ? 0 : product.ratingCount()
                ));
            }
            if (!candidates.isEmpty()) {
                Map<String, Integer> independentGrades = labelsByQuery.getOrDefault(raw.queryId(), Map.of());
                sessions.add(new EvalSession(entry.getKey(), raw.userId(), raw.queryId(), candidates, raw.grades(), independentGrades));
            }
        }
        return sessions;
    }
}
