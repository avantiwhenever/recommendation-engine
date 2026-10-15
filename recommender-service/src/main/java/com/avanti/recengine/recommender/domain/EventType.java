package com.avanti.recengine.recommender.domain;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A clickstream event's type, in ascending order of engagement strength.
 * The single source of truth for each event's popularity weight (also used
 * as the IPS reward scale) and its graded-relevance level, previously
 * duplicated across every class that read the clickstream.
 */
public enum EventType {
    VIEW("view", 0.2),
    CLICK("click", 0.5),
    ADD_TO_CART("add_to_cart", 0.8),
    PURCHASE("purchase", 1.0);

    private static final Map<String, EventType> BY_CSV_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(EventType::csvName, Function.identity()));

    private final String csvName;
    private final double weight;

    EventType(String csvName, double weight) {
        this.csvName = csvName;
        this.weight = weight;
    }

    /** The {@code event_type} column value in {@code clickstream.csv}. */
    public String csvName() {
        return csvName;
    }

    /** Relative engagement weight: popularity contribution and IPS reward. */
    public double weight() {
        return weight;
    }

    /** Graded relevance: view=0, click=1, add_to_cart=2, purchase=3. */
    public int level() {
        return ordinal();
    }

    /**
     * Whether this event counts as an interaction for user profiles and
     * item-item co-occurrence. Plain views don't: every product shown on a
     * results page would otherwise "co-occur," diluting the signal.
     */
    public boolean isInteraction() {
        return this != VIEW;
    }

    public static EventType fromLevel(int level) {
        return values()[level];
    }

    /** Empty for an unrecognized {@code event_type}, which carries no weight or relevance. */
    public static Optional<EventType> fromCsvName(String csvName) {
        return Optional.ofNullable(BY_CSV_NAME.get(csvName));
    }
}
