package com.avanti.recengine.recommender.adapter.out.clickstream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the synthetic clickstream CSV plus WANDS product.csv (for catalog
 * metadata and category lookups) once at construction time into in-memory
 * aggregates. ~171K clickstream rows and ~43K products comfortably fit in
 * memory — no need for a real database here.
 */
public final class CsvClickstreamRepositoryAdapter extends ClickstreamAggregates {

    private static final Logger log = LoggerFactory.getLogger(CsvClickstreamRepositoryAdapter.class);

    public CsvClickstreamRepositoryAdapter(Path clickstreamCsv, Path productCsv) {
        super(WandsCatalog.load(productCsv));
        groupBySession(ClickstreamCsvReader.read(clickstreamCsv)).forEach(this::applySession);
        log.info("Loaded clickstream aggregates: {}", aggregateSummary());
    }

    private static Collection<List<ClickstreamEvent>> groupBySession(List<ClickstreamEvent> events) {
        Map<String, List<ClickstreamEvent>> bySession = new LinkedHashMap<>();
        for (ClickstreamEvent event : events) {
            bySession.computeIfAbsent(event.sessionId(), id -> new ArrayList<>()).add(event);
        }
        return bySession.values();
    }
}
