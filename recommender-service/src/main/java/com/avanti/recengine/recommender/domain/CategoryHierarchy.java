package com.avanti.recengine.recommender.domain;

/**
 * Parsing for WANDS {@code category hierarchy} strings such as
 * {@code "Furniture / Living Room Furniture / Chairs"}.
 */
public final class CategoryHierarchy {

    public static final String UNCATEGORIZED = "Uncategorized";

    private CategoryHierarchy() {
    }

    /** The first {@code /}-separated segment, trimmed; {@link #UNCATEGORIZED} for a null or blank hierarchy. */
    public static String topLevelSegment(String categoryHierarchy) {
        if (categoryHierarchy == null || categoryHierarchy.isBlank()) {
            return UNCATEGORIZED;
        }
        return categoryHierarchy.split("/")[0].trim();
    }
}
