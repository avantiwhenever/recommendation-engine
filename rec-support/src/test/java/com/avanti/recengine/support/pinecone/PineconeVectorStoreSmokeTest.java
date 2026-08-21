package com.avanti.recengine.support.pinecone;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real integration test against a locally-running Pinecone Local container
 * (ghcr.io/pinecone-io/pinecone-local) — not mocked, verifies the actual
 * wire protocol works end-to-end. Skips gracefully if Pinecone Local isn't
 * reachable (e.g. in CI, which runs unit tests only per search's own
 * "needs no Elasticsearch" pattern for the plain `test` job).
 */
class PineconeVectorStoreSmokeTest {

    private static final String CONTROL_PLANE_HOST = "http://localhost:5080";
    private static final String INDEX_NAME = "smoketest-index";

    @Test
    void upsertsAndQueriesRealVectors() throws Exception {
        Assumptions.assumeTrue(pineconeLocalReachable(), "Pinecone Local not reachable on localhost:5080 — skipping");

        PineconeVectorStore.ensureServerlessIndex(CONTROL_PLANE_HOST, "pclocal", false, INDEX_NAME, 4);
        // Pinecone Local's control plane needs a moment to bring the new
        // index's data-plane port up after creation.
        Thread.sleep(2000);

        try (PineconeVectorStore store = new PineconeVectorStore(CONTROL_PLANE_HOST, "pclocal", false, INDEX_NAME)) {
            store.upsertBatch(List.of(
                    new ProductVector("p1", new float[]{1f, 0f, 0f, 0f}, Map.of("name", "chair", "rating", 4.5)),
                    new ProductVector("p2", new float[]{0f, 1f, 0f, 0f}, Map.of("name", "table", "rating", 3.2)),
                    new ProductVector("p3", new float[]{0.9f, 0.1f, 0f, 0f}, Map.of("name", "armchair", "rating", 4.8))
            ));
            // Pinecone Local's upsert is not immediately read-your-writes consistent.
            Thread.sleep(2000);

            List<ScoredMatch> matches = store.query(new float[]{1f, 0f, 0f, 0f}, 2);

            assertThat(matches).hasSize(2);
            assertThat(matches.get(0).id()).isEqualTo("p1");
            assertThat(matches.get(0).metadata()).containsEntry("name", "chair");
            assertThat(matches.get(0).metadata().get("rating")).isEqualTo(4.5);
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
