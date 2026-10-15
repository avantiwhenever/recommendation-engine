package com.avanti.recengine.search.adapter.out.pinecone;

/**
 * Metadata field names stored alongside each product vector in Pinecone —
 * shared by the writer ({@code IngestionCli}) and the reader
 * ({@link PineconeVectorIndexAdapter}) so the two can't drift apart.
 */
public final class ProductMetadataKeys {

    public static final String PRODUCT_NAME = "product_name";
    public static final String PRODUCT_CLASS = "product_class";
    public static final String CATEGORY_HIERARCHY = "category_hierarchy";
    public static final String AVERAGE_RATING = "average_rating";
    public static final String RATING_COUNT = "rating_count";
    public static final String REVIEW_COUNT = "review_count";

    private ProductMetadataKeys() {
    }
}
