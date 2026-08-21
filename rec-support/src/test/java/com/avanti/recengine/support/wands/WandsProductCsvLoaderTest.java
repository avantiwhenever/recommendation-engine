package com.avanti.recengine.support.wands;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WandsProductCsvLoaderTest {

    @Test
    void loadsAllRows() throws URISyntaxException {
        List<WandsProductRow> products = WandsProductCsvLoader.load(sampleFile());
        assertThat(products).hasSize(3);
    }

    @Test
    void parsesFieldsIncludingBlankOnes() throws URISyntaxException {
        List<WandsProductRow> products = WandsProductCsvLoader.load(sampleFile());

        WandsProductRow bed = products.stream().filter(p -> p.productId().equals("0")).findFirst().orElseThrow();
        assertThat(bed.productName()).isEqualTo("solid wood platform bed");
        assertThat(bed.categoryHierarchy()).isEqualTo("Furniture / Bedroom Furniture / Beds & Headboards / Beds / Twin Beds");
        assertThat(bed.ratingCount()).isEqualTo(15);
        assertThat(bed.averageRating()).isEqualTo(4.5);

        WandsProductRow table = products.stream().filter(p -> p.productId().equals("1")).findFirst().orElseThrow();
        assertThat(table.productDescription()).isNull();
        assertThat(table.ratingCount()).isNull();
        assertThat(table.averageRating()).isNull();
    }

    @Test
    void buildsEmbeddingTextFromNameClassCategoryAndDescription() throws URISyntaxException {
        WandsProductRow bed = WandsProductCsvLoader.load(sampleFile()).stream()
                .filter(p -> p.productId().equals("0"))
                .findFirst()
                .orElseThrow();

        String text = EmbeddingTextBuilder.build(bed);

        assertThat(text).startsWith("solid wood platform bed. Beds. Furniture > Bedroom Furniture > Beds & Headboards > Beds > Twin Beds. A comfortable bed, built to last.");
        assertThat(text).doesNotContain("material:wood");
    }

    @Test
    void embeddingTextOmitsBlankFieldsGracefully() throws URISyntaxException {
        WandsProductRow table = WandsProductCsvLoader.load(sampleFile()).stream()
                .filter(p -> p.productId().equals("1"))
                .findFirst()
                .orElseThrow();

        String text = EmbeddingTextBuilder.build(table);

        assertThat(text).isEqualTo("modern glass coffee table. Coffee Tables. Furniture > Living Room Furniture > Coffee Tables");
    }

    private Path sampleFile() throws URISyntaxException {
        return Paths.get(getClass().getClassLoader().getResource("wands-sample/product.csv").toURI());
    }
}
