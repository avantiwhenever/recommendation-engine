package com.avanti.recengine.recommender.adapter.out.clickstream;

import com.avanti.recengine.recommender.domain.EventType;

import java.time.OffsetDateTime;
import java.util.Optional;

/** One row of the synthetic {@code clickstream.csv}, limited to the columns this service reads. */
public record ClickstreamEvent(
        String sessionId,
        String userId,
        OffsetDateTime timestamp,
        String queryId,
        int position,
        String productId,
        String eventType
) {
    public Optional<EventType> type() {
        return EventType.fromCsvName(eventType);
    }

    public double weight() {
        return type().map(EventType::weight).orElse(0.0);
    }

    public int level() {
        return type().map(EventType::level).orElse(0);
    }

    public boolean isInteraction() {
        return type().filter(EventType::isInteraction).isPresent();
    }
}
