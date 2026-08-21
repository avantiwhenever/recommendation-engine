package com.avanti.recengine.search.ingestion;

import com.avanti.recengine.support.embedding.EmbeddingService;
import com.avanti.recengine.support.pinecone.PineconeVectorStore;
import com.avanti.recengine.support.pinecone.ProductVector;
import com.avanti.recengine.support.wands.EmbeddingTextBuilder;
import com.avanti.recengine.support.wands.WandsProductCsvLoader;
import com.avanti.recengine.support.wands.WandsProductRow;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Batch tool, outside the hexagon: loads WANDS product.csv, embeds each
 * product with the ONNX bge-small-en-v1.5 model, and upserts into Pinecone.
 * Not a served use case, so it talks to rec-support directly rather than
 * through search-service's ports/adapters.
 */
@Command(name = "ingest", mixinStandardHelpOptions = true,
        description = "Embeds WANDS products and upserts them into Pinecone.")
public class IngestionCli implements Callable<Integer> {

    @Option(names = "--data-dir", description = "Directory containing product.csv (default: ../data)")
    private Path dataDir = Path.of("../data");

    @Option(names = "--models-dir", description = "Directory containing bge-small-en-v1.5/ (default: ../models)")
    private Path modelsDir = Path.of("../models");

    @Option(names = "--batch-size", description = "Products embedded/upserted per batch (default: 500)")
    private int batchSize = 500;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new IngestionCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        String pineconeHost = envOr("PINECONE_HOST", "http://localhost:5080");
        String apiKey = envOr("PINECONE_API_KEY", "pclocal");
        boolean tlsEnabled = Boolean.parseBoolean(envOr("PINECONE_TLS_ENABLED", "false"));
        String indexName = envOr("PINECONE_INDEX", "wands-products");
        Path embeddingDir = modelsDir.resolve("bge-small-en-v1.5");
        Path productCsv = dataDir.resolve("product.csv");

        System.out.println("Loading products from " + productCsv);
        List<WandsProductRow> rows = WandsProductCsvLoader.load(productCsv);
        System.out.println("Loaded " + rows.size() + " products");

        System.out.println("Ensuring Pinecone index '" + indexName + "' exists at " + pineconeHost);
        PineconeVectorStore.ensureServerlessIndex(pineconeHost, apiKey, tlsEnabled, indexName, EmbeddingService.DIMENSIONS);

        try (EmbeddingService embeddingService = new EmbeddingService(embeddingDir);
             PineconeVectorStore store = new PineconeVectorStore(pineconeHost, apiKey, tlsEnabled, indexName)) {

            int total = rows.size();
            int batches = (total + batchSize - 1) / batchSize;
            for (int b = 0; b < batches; b++) {
                int start = b * batchSize;
                int end = Math.min(start + batchSize, total);
                List<WandsProductRow> batch = rows.subList(start, end);

                List<String> texts = batch.stream().map(EmbeddingTextBuilder::build).toList();
                List<float[]> embeddings = embeddingService.embedDocuments(texts);

                List<ProductVector> vectors = new ArrayList<>(batch.size());
                for (int i = 0; i < batch.size(); i++) {
                    vectors.add(toProductVector(batch.get(i), embeddings.get(i)));
                }
                store.upsertBatch(vectors);

                if (b % 10 == 0 || b == batches - 1) {
                    System.out.printf("Ingested batch %d/%d (%d/%d products)%n", b + 1, batches, end, total);
                }
            }
        }

        System.out.println("Done.");
        return 0;
    }

    private static ProductVector toProductVector(WandsProductRow row, float[] embedding) {
        Map<String, Object> metadata = new HashMap<>();
        putIfNotNull(metadata, "product_name", row.productName());
        putIfNotNull(metadata, "product_class", row.productClass());
        putIfNotNull(metadata, "category_hierarchy", row.categoryHierarchy());
        putIfNotNull(metadata, "average_rating", row.averageRating());
        putIfNotNull(metadata, "rating_count", row.ratingCount());
        putIfNotNull(metadata, "review_count", row.reviewCount());
        return new ProductVector(row.productId(), embedding, metadata);
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
