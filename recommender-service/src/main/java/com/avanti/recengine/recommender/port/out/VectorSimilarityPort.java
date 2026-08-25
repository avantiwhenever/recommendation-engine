package com.avanti.recengine.recommender.port.out;

import java.util.List;

/**
 * Embedding-based "find nearest neighbors of product X" — a real second
 * retrieval source, distinct from everything else in this service. Every
 * strategy up to this point only reranks/injects within the fixed
 * candidate list {@code search-service} already returned over gRPC; this
 * port instead queries the same Pinecone {@code wands-products} index
 * {@code search-service} populated, directly, to generate genuinely new
 * candidates that {@code search-service}'s lexical/dense query for the
 * original search string may never have surfaced.
 *
 * <p>Talking to Pinecone here is an infrastructure dependency, not
 * inter-service traffic — the architecture's "Java services talk to each
 * other over gRPC only" rule governs calls to {@code search-service}
 * itself, not a strategy's own outbound call to shared infrastructure. See
 * the original architecture plan's "recommender-service" section (mirrored
 * into {@code docs/PROJECT_STATE.md}): "Any strategy needing supplementary
 * vector similarity... calls its own outbound VectorIndexPort → Pinecone
 * directly — not a gRPC call to search-service."
 *
 * <p>Grounded in Pinterest's ItemSage (KDD 2022,
 * <a href="https://arxiv.org/abs/2205.11728">arXiv:2205.11728</a>): one
 * shared product embedding space reused across multiple retrieval
 * surfaces, rather than each surface learning its own. WANDS has no
 * product images, so ItemSage's multi-modal (text+image) aggregation
 * doesn't apply here — only the "reuse the same embedding space for a
 * second retrieval surface instead of building a new one" pattern does:
 * this port reuses the exact {@code bge-small-en-v1.5} embeddings
 * {@code search-service} already computed and upserted, rather than
 * training or serving a second embedding model.
 */
public interface VectorSimilarityPort {

    /**
     * Product IDs most similar to {@code productId} by embedding cosine
     * distance, nearest first, excluding {@code productId} itself. Returns
     * an empty list if {@code productId} isn't in the index (e.g. it was
     * never ingested) rather than throwing — callers should treat "no
     * similar items found" as a normal, cheap-to-check outcome, the same
     * way {@link ClickstreamRepositoryPort#catalogEntry} returns
     * {@link java.util.Optional#empty()} for an unknown product instead of
     * failing.
     */
    List<String> similarProductIds(String productId, int topK);
}
