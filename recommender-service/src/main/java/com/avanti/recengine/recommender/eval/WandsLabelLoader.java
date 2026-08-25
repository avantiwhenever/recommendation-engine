package com.avanti.recengine.recommender.eval;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Loads WANDS {@code label.csv} (tab-delimited, like {@code product.csv} —
 * see {@code rec-support}'s {@code WandsProductCsvLoader}) into
 * {@code query_id -> product_id -> grade} (Exact=2/Partial=1/Irrelevant=0).
 *
 * <p>This is the <b>independent</b> ground truth used to break the offline
 * eval's circularity: {@code label.csv} is Wayfair's original human
 * relevance annotation, entirely independent of the synthetic clickstream
 * generator's click-probability model — unlike the clickstream-derived
 * implicit grades ({@link EvalSessionLoader}'s {@code relevanceGrades}),
 * which are themselves a probabilistic function of this same label data
 * (see {@code WANDS/scripts/generate_clickstream.py}'s
 * {@code build_shown_results}), making a clickstream-only eval circular.
 */
public final class WandsLabelLoader {

    private WandsLabelLoader() {
    }

    /** {@code queryId -> (productId -> grade)}. A query with no judged products maps to an empty map. */
    public static Map<String, Map<String, Integer>> load(Path labelCsv) {
        Map<String, Map<String, Integer>> byQuery = new HashMap<>();

        CSVFormat format = CSVFormat.Builder.create(CSVFormat.TDF)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setQuote(null)
                .build();

        try (Reader reader = Files.newBufferedReader(labelCsv, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            for (CSVRecord record : parser) {
                String queryId = record.get("query_id");
                String productId = record.get("product_id");
                int grade = gradeOf(record.get("label"));
                byQuery.computeIfAbsent(queryId, id -> new HashMap<>()).put(productId, grade);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + labelCsv, e);
        }

        return byQuery;
    }

    private static int gradeOf(String label) {
        return switch (label.trim().toLowerCase(Locale.ROOT)) {
            case "exact" -> 2;
            case "partial" -> 1;
            case "irrelevant" -> 0;
            default -> throw new IllegalArgumentException("Unknown WANDS relevance label: " + label);
        };
    }
}
