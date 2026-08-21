package com.avanti.recengine.search.port.in;

import com.avanti.recengine.search.domain.ScoredResult;

import java.util.List;

public interface SearchProductsUseCase {
    List<ScoredResult> search(String query, int topK);
}
