package com.avanti.recengine.search.application;

import com.avanti.recengine.search.domain.Product;
import com.avanti.recengine.search.domain.ScoredResult;
import com.avanti.recengine.search.port.out.EmbeddingPort;
import com.avanti.recengine.search.port.out.LexicalIndexPort;
import com.avanti.recengine.search.port.out.VectorIndexPort;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchProductsServiceTest {

    private static final int CANDIDATE_POOL_SIZE = 50;
    private static final int RRF_K = 60;

    @Test
    void embedsQueryThenQueriesVectorIndex() {
        FakeEmbeddingPort embeddingPort = new FakeEmbeddingPort();
        FakeVectorIndexPort vectorIndexPort = new FakeVectorIndexPort();
        FakeLexicalIndexPort lexicalIndexPort = new FakeLexicalIndexPort();
        Product bed = new Product("1", "platform bed", "Beds", "Furniture > Beds", 4.5, 100);
        vectorIndexPort.upsertProduct(bed, new float[]{1f, 0f});

        SearchProductsService service = new SearchProductsService(
                vectorIndexPort, embeddingPort, lexicalIndexPort, CANDIDATE_POOL_SIZE, RRF_K);
        List<ScoredResult> results = service.search("platform bed", 5);

        assertThat(embeddingPort.lastQuery).isEqualTo("platform bed");
        assertThat(vectorIndexPort.lastTopK).isEqualTo(CANDIDATE_POOL_SIZE);
        assertThat(lexicalIndexPort.lastQuery).isEqualTo("platform bed");
        assertThat(lexicalIndexPort.lastTopK).isEqualTo(CANDIDATE_POOL_SIZE);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).product().productId()).isEqualTo("1");
    }

    @Test
    void fusesDenseAndLexicalResultsAndTruncatesToTopK() {
        Product denseOnly = new Product("dense-1", "sofa", null, null, null, null);
        Product lexicalOnly = new Product("lexical-1", "sku-zx9000 lamp", null, null, null, null);
        Product inBoth = new Product("both-1", "coffee table", null, null, null, null);

        FakeEmbeddingPort embeddingPort = new FakeEmbeddingPort();
        FakeVectorIndexPort vectorIndexPort = new FakeVectorIndexPort();
        vectorIndexPort.results = List.of(
                new ScoredResult(inBoth, 0.9),
                new ScoredResult(denseOnly, 0.5));
        FakeLexicalIndexPort lexicalIndexPort = new FakeLexicalIndexPort();
        lexicalIndexPort.results = List.of(
                new ScoredResult(lexicalOnly, 12.0),
                new ScoredResult(inBoth, 8.0));

        SearchProductsService service = new SearchProductsService(
                vectorIndexPort, embeddingPort, lexicalIndexPort, CANDIDATE_POOL_SIZE, RRF_K);
        List<ScoredResult> results = service.search("sku-zx9000", 10);

        // "both-1" ranked #1 in both lists so it fuses to the top; "lexical-1"
        // is rank #1 in its own list (RRF contribution 1/61) versus "dense-1"
        // at rank #2 in its list (1/62), so "lexical-1" edges it out.
        assertThat(results).extracting(r -> r.product().productId())
                .containsExactly("both-1", "lexical-1", "dense-1");
    }

    @Test
    void truncatesFusedResultsToRequestedTopK() {
        FakeEmbeddingPort embeddingPort = new FakeEmbeddingPort();
        FakeVectorIndexPort vectorIndexPort = new FakeVectorIndexPort();
        vectorIndexPort.results = List.of(
                new ScoredResult(new Product("1", "a", null, null, null, null), 0.9),
                new ScoredResult(new Product("2", "b", null, null, null, null), 0.8),
                new ScoredResult(new Product("3", "c", null, null, null, null), 0.7));
        FakeLexicalIndexPort lexicalIndexPort = new FakeLexicalIndexPort();

        SearchProductsService service = new SearchProductsService(
                vectorIndexPort, embeddingPort, lexicalIndexPort, CANDIDATE_POOL_SIZE, RRF_K);
        List<ScoredResult> results = service.search("query", 2);

        assertThat(results).hasSize(2);
    }

    private static final class FakeEmbeddingPort implements EmbeddingPort {
        String lastQuery;

        @Override
        public float[] embedQuery(String text) {
            this.lastQuery = text;
            return new float[]{1f, 0f};
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            throw new UnsupportedOperationException("not needed for this test");
        }
    }

    private static final class FakeVectorIndexPort implements VectorIndexPort {
        private final Map<String, Product> products = new HashMap<>();
        Integer lastTopK;
        List<ScoredResult> results;

        @Override
        public List<ScoredResult> query(float[] queryVector, int topK) {
            this.lastTopK = topK;
            if (results != null) {
                return results;
            }
            return products.values().stream().map(p -> new ScoredResult(p, 0.99)).toList();
        }

        @Override
        public void upsertProduct(Product product, float[] embedding) {
            products.put(product.productId(), product);
        }

        @Override
        public void upsertProducts(List<Product> products, List<float[]> embeddings) {
            for (Product product : products) {
                this.products.put(product.productId(), product);
            }
        }
    }

    private static final class FakeLexicalIndexPort implements LexicalIndexPort {
        String lastQuery;
        Integer lastTopK;
        List<ScoredResult> results = List.of();

        @Override
        public List<ScoredResult> search(String query, int topK) {
            this.lastQuery = query;
            this.lastTopK = topK;
            return results;
        }
    }
}
