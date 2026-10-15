package com.avanti.recengine.search.adapter.out.pinecone;

import com.avanti.recengine.search.domain.Product;
import com.avanti.recengine.search.domain.ScoredResult;
import com.avanti.recengine.search.port.out.VectorIndexPort;
import com.avanti.recengine.support.pinecone.PineconeVectorStore;
import com.avanti.recengine.support.pinecone.ProductVector;
import com.avanti.recengine.support.pinecone.ScoredMatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PineconeVectorIndexAdapter implements VectorIndexPort {

    private final PineconeVectorStore store;

    public PineconeVectorIndexAdapter(PineconeVectorStore store) {
        this.store = store;
    }

    @Override
    public List<ScoredResult> query(float[] queryVector, int topK) {
        return store.query(queryVector, topK).stream()
                .map(this::toScoredResult)
                .toList();
    }

    @Override
    public void upsertProduct(Product product, float[] embedding) {
        upsertProducts(List.of(product), List.of(embedding));
    }

    @Override
    public void upsertProducts(List<Product> products, List<float[]> embeddings) {
        List<ProductVector> vectors = new ArrayList<>(products.size());
        for (int i = 0; i < products.size(); i++) {
            vectors.add(toProductVector(products.get(i), embeddings.get(i)));
        }
        store.upsertBatch(vectors);
    }

    private ProductVector toProductVector(Product product, float[] embedding) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, ProductMetadataKeys.PRODUCT_NAME, product.productName());
        putIfNotNull(metadata, ProductMetadataKeys.PRODUCT_CLASS, product.productClass());
        putIfNotNull(metadata, ProductMetadataKeys.CATEGORY_HIERARCHY, product.categoryHierarchy());
        putIfNotNull(metadata, ProductMetadataKeys.AVERAGE_RATING, product.averageRating());
        putIfNotNull(metadata, ProductMetadataKeys.RATING_COUNT, product.ratingCount());
        return new ProductVector(product.productId(), embedding, metadata);
    }

    private ScoredResult toScoredResult(ScoredMatch match) {
        Map<String, Object> metadata = match.metadata();
        Product product = new Product(
                match.id(),
                stringOrNull(metadata.get(ProductMetadataKeys.PRODUCT_NAME)),
                stringOrNull(metadata.get(ProductMetadataKeys.PRODUCT_CLASS)),
                stringOrNull(metadata.get(ProductMetadataKeys.CATEGORY_HIERARCHY)),
                doubleOrNull(metadata.get(ProductMetadataKeys.AVERAGE_RATING)),
                intOrNull(metadata.get(ProductMetadataKeys.RATING_COUNT))
        );
        return new ScoredResult(product, match.score());
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private static Double doubleOrNull(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    private static Integer intOrNull(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }
}
