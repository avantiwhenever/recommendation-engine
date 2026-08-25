package com.avanti.recengine.recommender.adapter.out.pinecone;

import com.avanti.recengine.support.pinecone.PineconeVectorStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real integration test against the live {@code wands-products} Pinecone
 * index a running {@code docker compose} stack has already ingested — not
 * mocked, not a scratch index. Mirrors
 * {@code PineconeVectorStoreSmokeTest}'s "skip gracefully if unreachable"
 * pattern, plus an extra skip if the specific known product this test
 * depends on hasn't been ingested (e.g. a fresh, not-yet-ingested stack).
 */
class PineconeVectorSimilarityAdapterSmokeTest {

    private static final String CONTROL_PLANE_HOST = "http://localhost:5080";
    private static final String INDEX_NAME = "wands-products";
    // "witter coffee table" — confirmed present via a live `search` GraphQL
    // query against this repo's running stack at the time this test was
    // written; any product actually in the index would do equally well.
    private static final String KNOWN_PRODUCT_ID = "29365";

    @Test
    void findsSimilarProductsForARealIngestedItem() throws Exception {
        Assumptions.assumeTrue(pineconeLocalReachable(), "Pinecone Local not reachable on localhost:5080 — skipping");

        try (PineconeVectorStore store = new PineconeVectorStore(CONTROL_PLANE_HOST, "pclocal", false, INDEX_NAME)) {
            Assumptions.assumeTrue(!store.queryById(KNOWN_PRODUCT_ID, 1).isEmpty(),
                    "Product " + KNOWN_PRODUCT_ID + " not found in " + INDEX_NAME + " — stack not ingested yet, skipping");

            PineconeVectorSimilarityAdapter adapter = new PineconeVectorSimilarityAdapter(store);
            List<String> similar = adapter.similarProductIds(KNOWN_PRODUCT_ID, 5);

            assertThat(similar).isNotEmpty();
            assertThat(similar).doesNotContain(KNOWN_PRODUCT_ID);
            assertThat(similar).doesNotHaveDuplicates();
            assertThat(similar.size()).isLessThanOrEqualTo(5);
        }
    }

    @Test
    void returnsEmptyForAnUnknownProductRatherThanThrowing() throws Exception {
        Assumptions.assumeTrue(pineconeLocalReachable(), "Pinecone Local not reachable on localhost:5080 — skipping");

        try (PineconeVectorStore store = new PineconeVectorStore(CONTROL_PLANE_HOST, "pclocal", false, INDEX_NAME)) {
            PineconeVectorSimilarityAdapter adapter = new PineconeVectorSimilarityAdapter(store);
            assertThat(adapter.similarProductIds("not-a-real-product-id-xyz", 5)).isEmpty();
        }
    }

    private static boolean pineconeLocalReachable() {
        try {
            HttpResponse<Void> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(CONTROL_PLANE_HOST + "/indexes")).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (ConnectException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
