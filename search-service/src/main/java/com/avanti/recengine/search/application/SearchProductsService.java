package com.avanti.recengine.search.application;

import com.avanti.recengine.search.domain.RrfFusion;
import com.avanti.recengine.search.domain.ScoredResult;
import com.avanti.recengine.search.port.in.SearchProductsUseCase;
import com.avanti.recengine.search.port.out.EmbeddingPort;
import com.avanti.recengine.search.port.out.LexicalIndexPort;
import com.avanti.recengine.search.port.out.VectorIndexPort;

import java.util.List;

/**
 * Hybrid dense + lexical retrieval, fused with Reciprocal Rank Fusion.
 * Dense-only embedding search is known-worse on exact-match
 * queries (SKUs, model numbers, distinctive brand tokens): a sentence
 * embedding has no mechanism to guarantee an exact token match outranks a
 * merely-topical near-miss. Both sub-retrievers are queried for
 * {@code candidatePoolSize} results (wider than the caller's requested
 * {@code topK}) so RRF always fuses over a full pool rather than two
 * already-truncated top-K lists — same pattern as the sibling {@code search}
 * project's {@code HybridRrfSearchStrategy}.
 *
 * <p>Zero Spring/gRPC/Pinecone/ONNX imports — depends only on the three
 * out-ports and the framework-free {@link RrfFusion}.
 */
public final class SearchProductsService implements SearchProductsUseCase {

    private final VectorIndexPort vectorIndexPort;
    private final EmbeddingPort embeddingPort;
    private final LexicalIndexPort lexicalIndexPort;
    private final int candidatePoolSize;
    private final int rrfK;

    public SearchProductsService(VectorIndexPort vectorIndexPort, EmbeddingPort embeddingPort,
                                  LexicalIndexPort lexicalIndexPort, int candidatePoolSize, int rrfK) {
        this.vectorIndexPort = vectorIndexPort;
        this.embeddingPort = embeddingPort;
        this.lexicalIndexPort = lexicalIndexPort;
        this.candidatePoolSize = candidatePoolSize;
        this.rrfK = rrfK;
    }

    @Override
    public List<ScoredResult> search(String query, int topK) {
        return search(query, topK, null, 0.0);
    }

    @Override
    public List<ScoredResult> search(String query, int topK, String categoryFilter, double minRating) {
        int poolSize = Math.max(topK, candidatePoolSize);

        float[] queryVector = embeddingPort.embedQuery(query);
        List<ScoredResult> denseResults = vectorIndexPort.query(queryVector, poolSize);
        List<ScoredResult> lexicalResults = lexicalIndexPort.search(query, poolSize);

        List<ScoredResult> fused = RrfFusion.fuse(List.of(denseResults, lexicalResults), rrfK);
        List<ScoredResult> filtered = fused.stream()
                .filter(r -> matchesCategory(r, categoryFilter))
                .filter(r -> matchesMinRating(r, minRating))
                .toList();
        return filtered.size() > topK ? filtered.subList(0, topK) : filtered;
    }

    private static boolean matchesCategory(ScoredResult result, String categoryFilter) {
        if (categoryFilter == null || categoryFilter.isBlank()) {
            return true;
        }
        String hierarchy = result.product().categoryHierarchy();
        return hierarchy != null && hierarchy.toLowerCase().contains(categoryFilter.toLowerCase());
    }

    private static boolean matchesMinRating(ScoredResult result, double minRating) {
        if (minRating <= 0.0) {
            return true;
        }
        Double rating = result.product().averageRating();
        return rating != null && rating >= minRating;
    }
}
