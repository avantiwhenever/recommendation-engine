package com.avanti.recengine.search.application;

import com.avanti.recengine.search.domain.ScoredResult;
import com.avanti.recengine.search.port.in.SearchProductsUseCase;
import com.avanti.recengine.search.port.out.EmbeddingPort;
import com.avanti.recengine.search.port.out.VectorIndexPort;

import java.util.List;

/** Zero Spring/gRPC/Pinecone/ONNX imports — depends only on the two out-ports. */
public final class SearchProductsService implements SearchProductsUseCase {

    private final VectorIndexPort vectorIndexPort;
    private final EmbeddingPort embeddingPort;

    public SearchProductsService(VectorIndexPort vectorIndexPort, EmbeddingPort embeddingPort) {
        this.vectorIndexPort = vectorIndexPort;
        this.embeddingPort = embeddingPort;
    }

    @Override
    public List<ScoredResult> search(String query, int topK) {
        float[] queryVector = embeddingPort.embedQuery(query);
        return vectorIndexPort.query(queryVector, topK);
    }
}
