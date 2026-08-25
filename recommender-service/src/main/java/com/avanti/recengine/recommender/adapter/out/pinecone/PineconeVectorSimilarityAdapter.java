package com.avanti.recengine.recommender.adapter.out.pinecone;

import com.avanti.recengine.recommender.port.out.VectorSimilarityPort;
import com.avanti.recengine.support.pinecone.PineconeVectorStore;
import com.avanti.recengine.support.pinecone.ScoredMatch;
import io.pinecone.exceptions.PineconeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Outbound adapter for {@link VectorSimilarityPort}, backed by the same
 * {@code wands-products} Pinecone index {@code search-service} populates —
 * see {@link VectorSimilarityPort}'s Javadoc for why this is a direct
 * infrastructure dependency rather than a gRPC call to {@code search-service}.
 */
public final class PineconeVectorSimilarityAdapter implements VectorSimilarityPort {

    private static final Logger log = LoggerFactory.getLogger(PineconeVectorSimilarityAdapter.class);

    private final PineconeVectorStore store;

    public PineconeVectorSimilarityAdapter(PineconeVectorStore store) {
        this.store = store;
    }

    @Override
    public List<String> similarProductIds(String productId, int topK) {
        if (productId == null || productId.isBlank() || topK <= 0) {
            return List.of();
        }
        List<ScoredMatch> matches;
        try {
            // Over-fetch by one: queryByVectorId's own topK typically
            // includes productId itself (a vector is its own nearest
            // neighbor at score 1.0) — see PineconeVectorStore#queryById.
            matches = store.queryById(productId, topK + 1);
        } catch (PineconeException e) {
            // Pinecone Local (and real Pinecone) return an empty match list
            // for an id absent from the index rather than throwing, but a
            // strategy asking "what's similar to X" shouldn't itself fail
            // on any other Pinecone-side hiccup either — treat it the same
            // as "no similar items found," matching the port's contract.
            log.warn("Vector similarity lookup failed for productId={}: {}", productId, e.getMessage());
            return List.of();
        }
        return matches.stream()
                .map(ScoredMatch::id)
                .filter(id -> !id.equals(productId))
                .limit(topK)
                .toList();
    }
}
