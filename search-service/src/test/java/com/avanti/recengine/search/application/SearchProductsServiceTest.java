package com.avanti.recengine.search.application;

import com.avanti.recengine.search.domain.Product;
import com.avanti.recengine.search.domain.ScoredResult;
import com.avanti.recengine.search.port.out.EmbeddingPort;
import com.avanti.recengine.search.port.out.VectorIndexPort;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchProductsServiceTest {

    @Test
    void embedsQueryThenQueriesVectorIndex() {
        FakeEmbeddingPort embeddingPort = new FakeEmbeddingPort();
        FakeVectorIndexPort vectorIndexPort = new FakeVectorIndexPort();
        Product bed = new Product("1", "platform bed", "Beds", "Furniture > Beds", 4.5, 100);
        vectorIndexPort.upsertProduct(bed, new float[]{1f, 0f});

        SearchProductsService service = new SearchProductsService(vectorIndexPort, embeddingPort);
        List<ScoredResult> results = service.search("platform bed", 5);

        assertThat(embeddingPort.lastQuery).isEqualTo("platform bed");
        assertThat(vectorIndexPort.lastTopK).isEqualTo(5);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).product().productId()).isEqualTo("1");
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

        @Override
        public List<ScoredResult> query(float[] queryVector, int topK) {
            this.lastTopK = topK;
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
}
