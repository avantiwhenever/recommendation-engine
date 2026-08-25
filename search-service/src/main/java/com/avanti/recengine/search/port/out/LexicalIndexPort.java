package com.avanti.recengine.search.port.out;

import com.avanti.recengine.search.domain.ScoredResult;

import java.util.List;

/**
 * Keyword/BM25 retrieval — the lexical half of the hybrid search, fused
 * with {@link VectorIndexPort}'s dense results via
 * {@link com.avanti.recengine.search.domain.RrfFusion}. Defined purely in
 * domain terms, same as {@link VectorIndexPort}, so the application layer
 * never depends on whether the implementation is an in-memory BM25 index or
 * something heavier later.
 */
public interface LexicalIndexPort {

    List<ScoredResult> search(String query, int topK);
}
