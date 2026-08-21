package com.avanti.recengine.search.port.out;

import java.util.List;

public interface EmbeddingPort {

    float[] embedQuery(String text);

    List<float[]> embedDocuments(List<String> texts);
}
