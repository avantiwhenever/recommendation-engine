package com.avanti.recengine.search.port.out;

import com.avanti.recengine.search.domain.Product;
import com.avanti.recengine.search.domain.ScoredResult;

import java.util.List;

/** Defined purely in domain terms — implementations (real Pinecone, fakes for tests) handle any infra-specific translation. */
public interface VectorIndexPort {

    List<ScoredResult> query(float[] queryVector, int topK);

    void upsertProduct(Product product, float[] embedding);

    /** Batched form for bulk ingestion — avoids one network round trip per product. */
    void upsertProducts(List<Product> products, List<float[]> embeddings);
}
