package com.avanti.recengine.search.adapter.out.lexical;

import com.avanti.recengine.search.domain.Product;
import com.avanti.recengine.search.domain.ScoredResult;
import com.avanti.recengine.search.domain.lexical.BM25Index;
import com.avanti.recengine.search.port.out.LexicalIndexPort;
import com.avanti.recengine.support.wands.WandsProductRow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a full in-memory BM25 index over the WANDS catalog once at startup
 * — no second ingestion step, no external search server (see
 * {@link BM25Index}'s Javadoc for why). Constructed directly from the same
 * {@code product.csv} rows the ingestion CLI embeds into Pinecone, so the
 * lexical and dense retrieval paths always index the same catalog snapshot.
 */
public final class InMemoryLexicalIndexAdapter implements LexicalIndexPort {

    private final BM25Index index;
    private final Map<String, Product> productById;

    public InMemoryLexicalIndexAdapter(List<WandsProductRow> rows) {
        Map<String, String> textByProductId = new HashMap<>(rows.size());
        this.productById = new HashMap<>(rows.size());
        for (WandsProductRow row : rows) {
            textByProductId.put(row.productId(), LexicalTextBuilder.build(row));
            productById.put(row.productId(), toProduct(row));
        }
        this.index = new BM25Index(textByProductId);
    }

    @Override
    public List<ScoredResult> search(String query, int topK) {
        return index.search(query, topK).stream()
                .map(doc -> new ScoredResult(productById.get(doc.productId()), doc.score()))
                .toList();
    }

    private static Product toProduct(WandsProductRow row) {
        return new Product(
                row.productId(),
                row.productName(),
                row.productClass(),
                row.categoryHierarchy(),
                row.averageRating(),
                row.ratingCount()
        );
    }
}
