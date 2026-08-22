package com.avanti.recengine.recommender.adapter.out.clickstream;

import com.avanti.recengine.recommender.domain.CatalogEntry;
import com.avanti.recengine.recommender.domain.UserProfile;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class CsvClickstreamRepositoryAdapterTest {

    @Test
    void aggregatesPopularityUserProfilesAndCoOccurrence() throws URISyntaxException {
        CsvClickstreamRepositoryAdapter adapter = new CsvClickstreamRepositoryAdapter(
                resource("clickstream-fixture.csv"), resource("product-fixture.csv"));

        // p2 has more weighted engagement (two users) than p1 (one user).
        assertThat(adapter.popularityScore("p2")).isGreaterThan(adapter.popularityScore("p1"));
        assertThat(adapter.mostPopularProducts(3)).startsWith("p2");

        UserProfile u1 = adapter.userProfile("u1");
        assertThat(u1.interactedProductIds()).containsExactlyInAnyOrder("p1", "p2");
        assertThat(u1.categoryCounts()).containsEntry("Furniture", 1L).containsEntry("Home", 1L);

        assertThat(adapter.coOccurrenceCount("p2", Set.of("p1"))).isEqualTo(1L);
        assertThat(adapter.relatedProducts("p1", 5)).containsExactly("p2");

        assertThat(adapter.catalogEntry("p3")).isPresent();
        CatalogEntry table = adapter.catalogEntry("p3").orElseThrow();
        assertThat(table.productName()).isEqualTo("table one");
        assertThat(table.averageRating()).isCloseTo(3.5, offset(0.001));
    }

    @Test
    void unknownUserGetsEmptyProfile() throws URISyntaxException {
        CsvClickstreamRepositoryAdapter adapter = new CsvClickstreamRepositoryAdapter(
                resource("clickstream-fixture.csv"), resource("product-fixture.csv"));

        UserProfile unknown = adapter.userProfile("does-not-exist");
        assertThat(unknown.interactedProductIds()).isEmpty();
        assertThat(unknown.topCategory()).isEmpty();
    }

    private Path resource(String name) throws URISyntaxException {
        return Paths.get(getClass().getClassLoader().getResource(name).toURI());
    }
}
