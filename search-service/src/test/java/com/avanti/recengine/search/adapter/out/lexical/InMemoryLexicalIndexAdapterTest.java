package com.avanti.recengine.search.adapter.out.lexical;

import com.avanti.recengine.search.domain.ScoredResult;
import com.avanti.recengine.support.wands.WandsProductRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryLexicalIndexAdapterTest {

    @Test
    void searchReturnsFullyPopulatedProductsRankedByBm25Score() {
        WandsProductRow lamp = new WandsProductRow(
                "1", "sku-zx9000 desk lamp", "Lighting", "Home / Lighting / Lamps",
                "A bright reading lamp for any office desk.", "color:black", 12, 4.5, 12);
        WandsProductRow umbrella = new WandsProductRow(
                "2", "patio umbrella", "Outdoor", "Outdoor / Patio",
                "Large water-resistant canopy.", "color:tan", 5, 4.0, 5);

        InMemoryLexicalIndexAdapter adapter = new InMemoryLexicalIndexAdapter(List.of(lamp, umbrella));

        List<ScoredResult> results = adapter.search("sku-zx9000", 5);

        assertThat(results).hasSize(1);
        ScoredResult top = results.get(0);
        assertThat(top.product().productId()).isEqualTo("1");
        assertThat(top.product().productName()).isEqualTo("sku-zx9000 desk lamp");
        assertThat(top.product().productClass()).isEqualTo("Lighting");
        assertThat(top.product().categoryHierarchy()).isEqualTo("Home / Lighting / Lamps");
        assertThat(top.product().averageRating()).isEqualTo(4.5);
        assertThat(top.product().ratingCount()).isEqualTo(12);
        assertThat(top.score()).isGreaterThan(0.0);
    }

    @Test
    void nameTermsOutweighTheSameTermOnlyInDescription() {
        // "lamp" appears once, only in the description, for "umbrella-with-lamp-shaped-finial";
        // it appears 3x (via LexicalTextBuilder's name-repetition boost) for "desk lamp".
        WandsProductRow lampNamed = new WandsProductRow(
                "named", "desk lamp", null, null, "simple lamp for desks", null, null, null, null);
        WandsProductRow lampMentionedOnlyInDescription = new WandsProductRow(
                "mentioned", "patio umbrella", null, null, "not related to a lamp at all, just mentions lamp once", null, null, null, null);

        InMemoryLexicalIndexAdapter adapter = new InMemoryLexicalIndexAdapter(
                List.of(lampNamed, lampMentionedOnlyInDescription));

        List<ScoredResult> results = adapter.search("lamp", 5);

        assertThat(results.get(0).product().productId()).isEqualTo("named");
    }
}
