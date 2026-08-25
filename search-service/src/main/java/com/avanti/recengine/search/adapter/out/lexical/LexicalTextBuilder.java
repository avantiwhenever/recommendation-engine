package com.avanti.recengine.search.adapter.out.lexical;

import com.avanti.recengine.support.wands.EmbeddingTextBuilder;
import com.avanti.recengine.support.wands.WandsProductRow;

/**
 * Builds the text BM25 indexes for a product. Starts from the same field
 * composition as {@link EmbeddingTextBuilder} (name, class, category,
 * description — see TODO.md #6) for consistency between the two retrieval
 * paths, but repeats {@code product_name} so its terms get roughly 3x the
 * term-frequency weight of the rest of the document. Unlike a dense
 * embedding, BM25 has no learned sense that the title is the most
 * query-relevant field — without this, a rare token buried in a long
 * description would out-score the same token appearing once in the name.
 * Kept local to search-service's lexical adapter rather than added to
 * rec-support: this weighting is specific to BM25 scoring, not a shared
 * catalog-text concern the way {@link EmbeddingTextBuilder} is.
 */
final class LexicalTextBuilder {

    private static final int NAME_REPETITIONS = 3;

    private LexicalTextBuilder() {
    }

    static String build(WandsProductRow product) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < NAME_REPETITIONS; i++) {
            text.append(product.productName()).append(". ");
        }
        text.append(EmbeddingTextBuilder.build(product));
        return text.toString();
    }
}
