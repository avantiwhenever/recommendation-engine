package com.avanti.recengine.search.adapter.out.pinecone;

import com.avanti.recengine.search.domain.Product;
import com.avanti.recengine.search.domain.ScoredResult;
import com.avanti.recengine.search.port.out.VectorIndexPort;
import com.avanti.recengine.support.pinecone.PineconeVectorStore;
import com.avanti.recengine.support.pinecone.ProductVector;
import com.avanti.recengine.support.pinecone.ScoredMatch;

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
        List<ProductVector> vectors = new java.util.ArrayList<>(products.size());
        for (int i = 0; i < products.size(); i++) {
            vectors.add(toProductVector(products.get(i), embeddings.get(i)));
        }
        store.upsertBatch(vectors);
    }

    private ProductVector toProductVector(Product product, float[] embedding) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, "product_name", product.productName());
        putIfNotNull(metadata, "product_class", product.productClass());
        putIfNotNull(metadata, "category_hierarchy", product.categoryHierarchy());
        putIfNotNull(metadata, "average_rating", product.averageRating());
        putIfNotNull(metadata, "rating_count", product.ratingCount());
        return new ProductVector(product.productId(), embedding, metadata);
    }

    private ScoredResult toScoredResult(ScoredMatch match) {
        Map<String, Object> metadata = match.metadata();
        Product product = new Product(
                match.id(),
                stringOrNull(metadata.get("product_name")),
                stringOrNull(metadata.get("product_class")),
                stringOrNull(metadata.get("category_hierarchy")),
                doubleOrNull(metadata.get("average_rating")),
                intOrNull(metadata.get("rating_count"))
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
