package com.avanti.recengine.search.domain.lexical;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A hand-rolled, in-memory BM25 inverted index — no Elasticsearch/Solr/Lucene
 * dependency. This project deliberately has no self-hosted search server;
 * the WANDS catalog is small enough (~43K products) to hold a
 * full inverted index in heap memory, so a real server-grade engine buys
 * nothing here beyond what this class already provides for this project's
 * scale. Standard Okapi BM25 (Robertson &amp; Zaragoza's formulation, k1=1.2,
 * b=0.75 — the conventional defaults used by both Lucene and Elasticsearch).
 *
 * <p>Framework-free by design (hexagonal {@code domain} package): takes
 * plain {@code Map<String, String>} documents in its constructor and returns
 * plain {@link ScoredDoc} records, so it's testable with zero Spring/gRPC/
 * Pinecone dependencies and zero I/O.
 */
public final class BM25Index {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final Map<String, List<Posting>> postingsByTerm = new HashMap<>();
    private final Map<String, Integer> docLengthByProductId = new HashMap<>();
    private final double averageDocLength;
    private final int documentCount;

    /** @param documentsByProductId raw (untokenized) catalog text, keyed by product ID. */
    public BM25Index(Map<String, String> documentsByProductId) {
        this.documentCount = documentsByProductId.size();
        long totalLength = 0;
        for (Map.Entry<String, String> entry : documentsByProductId.entrySet()) {
            String productId = entry.getKey();
            List<String> tokens = Tokenizer.tokenize(entry.getValue());
            docLengthByProductId.put(productId, tokens.size());
            totalLength += tokens.size();

            Map<String, Integer> termFrequencies = new HashMap<>();
            for (String token : tokens) {
                termFrequencies.merge(token, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> tf : termFrequencies.entrySet()) {
                postingsByTerm.computeIfAbsent(tf.getKey(), t -> new ArrayList<>())
                        .add(new Posting(productId, tf.getValue()));
            }
        }
        this.averageDocLength = documentCount == 0 ? 0.0 : (double) totalLength / documentCount;
    }

    /** Highest-scoring {@code topK} documents for {@code query}; empty if no query term matches anything. */
    public List<ScoredDoc> search(String query, int topK) {
        List<String> queryTerms = Tokenizer.tokenize(query);
        if (queryTerms.isEmpty() || documentCount == 0) {
            return List.of();
        }

        Map<String, Double> scoreByProductId = new HashMap<>();
        for (String term : new java.util.LinkedHashSet<>(queryTerms)) {
            List<Posting> postings = postingsByTerm.get(term);
            if (postings == null || postings.isEmpty()) {
                continue;
            }
            double idf = inverseDocumentFrequency(postings.size());
            for (Posting posting : postings) {
                int docLength = docLengthByProductId.get(posting.productId());
                double normalizedTf = (posting.termFrequency() * (K1 + 1))
                        / (posting.termFrequency() + K1 * (1 - B + B * docLength / averageDocLength));
                scoreByProductId.merge(posting.productId(), idf * normalizedTf, Double::sum);
            }
        }

        return scoreByProductId.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> new ScoredDoc(e.getKey(), e.getValue()))
                .toList();
    }

    /** BM25's "+1 inside the log" variant — stays non-negative even for terms in more than half the corpus. */
    private double inverseDocumentFrequency(int documentsContainingTerm) {
        return Math.log(1.0 + (documentCount - documentsContainingTerm + 0.5) / (documentsContainingTerm + 0.5));
    }

    private record Posting(String productId, int termFrequency) {
    }

    public record ScoredDoc(String productId, double score) {
    }
}
