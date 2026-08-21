package com.avanti.recengine.support.wands;

/**
 * Builds the text passed to the embedding model for a product. Deliberately
 * excludes {@code productFeatures} (pipe-delimited attribute:value pairs) —
 * that attribute noise dilutes sentence-embedding quality more than it helps;
 * it's still stored as separate Pinecone metadata. Ported from the sibling
 * {@code search} project's {@code EmbeddingTextBuilder} — same field
 * composition, so the two projects' embeddings stay comparable in spirit
 * even though the models and vector stores differ.
 */
public final class EmbeddingTextBuilder {

    private static final int MAX_DESCRIPTION_CHARS = 1200;

    private EmbeddingTextBuilder() {
    }

    public static String build(WandsProductRow product) {
        return build(product.productName(), product.productClass(), product.categoryHierarchy(), product.productDescription());
    }

    public static String build(String productName, String productClass, String categoryHierarchy, String productDescription) {
        StringBuilder text = new StringBuilder(productName);
        if (productClass != null) {
            text.append(". ").append(productClass);
        }
        if (categoryHierarchy != null) {
            // Source category hierarchies are inconsistently spaced around "/" (e.g. "Furniture / Beds"
            // vs "Furniture/Beds"), so collapse any surrounding whitespace rather than a plain replace.
            text.append(". ").append(categoryHierarchy.replaceAll("\\s*/\\s*", " > "));
        }
        if (productDescription != null) {
            text.append(". ").append(truncate(productDescription, MAX_DESCRIPTION_CHARS));
        }
        return text.toString();
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
