package com.avanti.recengine.recommender.adapter.out.clickstream;

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
import java.util.List;

/**
 * Reads the synthetic clickstream CSV (event_id,user_id,session_id,
 * timestamp,query_id,query,position,product_id,event_type,dwell_time_ms)
 * in file order.
 */
public final class ClickstreamCsvReader {

    private static final CSVFormat FORMAT = CSVFormat.Builder.create(CSVFormat.DEFAULT)
            .setHeader()
            .setSkipHeaderRecord(true)
            .build();

    private ClickstreamCsvReader() {
    }

    public static List<ClickstreamEvent> read(Path clickstreamCsv) {
        List<ClickstreamEvent> events = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(clickstreamCsv, StandardCharsets.UTF_8);
             CSVParser parser = FORMAT.parse(reader)) {
            for (CSVRecord record : parser) {
                events.add(toEvent(record));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + clickstreamCsv, e);
        }
        return events;
    }

    private static ClickstreamEvent toEvent(CSVRecord record) {
        return new ClickstreamEvent(
                record.get("session_id"),
                record.get("user_id"),
                OffsetDateTime.parse(record.get("timestamp")),
                record.get("query_id"),
                Integer.parseInt(record.get("position")),
                record.get("product_id"),
                record.get("event_type")
        );
    }
}
