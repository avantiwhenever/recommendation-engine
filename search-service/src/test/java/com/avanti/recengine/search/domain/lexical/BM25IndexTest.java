package com.avanti.recengine.search.domain.lexical;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BM25IndexTest {

    @Test
    void ranksExactRareTokenMatchAboveTopicallySimilarDocuments() {
        BM25Index index = new BM25Index(Map.of(
                "1", "sku-zx9000 desk lamp. bright reading light for any office",
                "2", "modern desk lamp with adjustable arm and warm light",
                "3", "led desk lamp, dimmable, energy efficient office lighting",
                "4", "outdoor patio umbrella, water resistant, large canopy"
        ));

        List<BM25Index.ScoredDoc> results = index.search("sku-zx9000", 4);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).productId()).isEqualTo("1");
    }

    @Test
    void documentsWithNoMatchingTermScoreLowestOrAreExcluded() {
        BM25Index index = new BM25Index(Map.of(
                "1", "platform bed frame queen size",
                "2", "outdoor patio umbrella"
        ));

        List<BM25Index.ScoredDoc> results = index.search("platform bed", 10);

        assertThat(results).extracting(BM25Index.ScoredDoc::productId).containsExactly("1");
    }

    @Test
    void higherTermFrequencyScoresHigherAllElseEqual() {
        BM25Index index = new BM25Index(Map.of(
                "frequent", "chair chair chair chair office",
                "rare", "chair office desk lamp shelf"
        ));

        List<BM25Index.ScoredDoc> results = index.search("chair", 2);

        assertThat(results.get(0).productId()).isEqualTo("frequent");
    }

    @Test
    void emptyQueryReturnsNoResults() {
        BM25Index index = new BM25Index(Map.of("1", "platform bed"));

        assertThat(index.search("", 10)).isEmpty();
        assertThat(index.search("   ", 10)).isEmpty();
    }

    @Test
    void emptyCorpusReturnsNoResults() {
        BM25Index index = new BM25Index(Map.of());

        assertThat(index.search("anything", 10)).isEmpty();
    }

    @Test
    void queryWithNoMatchingTermReturnsNoResults() {
        BM25Index index = new BM25Index(Map.of("1", "platform bed frame"));

        assertThat(index.search("outdoor umbrella", 10)).isEmpty();
    }

    @Test
    void respectsTopKLimit() {
        BM25Index index = new BM25Index(Map.of(
                "1", "chair", "2", "chair", "3", "chair", "4", "chair"));

        assertThat(index.search("chair", 2)).hasSize(2);
    }
}
