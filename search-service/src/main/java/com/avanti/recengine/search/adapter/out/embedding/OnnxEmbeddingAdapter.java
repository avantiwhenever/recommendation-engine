package com.avanti.recengine.search.adapter.out.embedding;

import com.avanti.recengine.search.port.out.EmbeddingPort;
import com.avanti.recengine.support.embedding.EmbeddingService;

import java.util.List;

public final class OnnxEmbeddingAdapter implements EmbeddingPort {

    private final EmbeddingService embeddingService;

    public OnnxEmbeddingAdapter(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Override
    public float[] embedQuery(String text) {
        return embeddingService.embedQuery(text);
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return embeddingService.embedDocuments(texts);
    }
}
