package com.avanti.recengine.recommender.eval;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Fixture: two sessions, s2 listed FIRST in the CSV but timestamped LATER
 * than s1 — proves {@link TemporalClickstreamIndex#sessionIdsInTimeOrder()}
 * sorts by time, not file order. s1 (u1): P1 click, P2 click. s2 (u2): P1
 * purchase. The core assertion is the point-in-time property: querying the
 * index's state *before* advancing s2 must not reflect s2's own events yet.
 */
class TemporalClickstreamIndexTest {

    @Test
    void sessionsAreOrderedByTimestampNotFileOrder() throws URISyntaxException {
        TemporalClickstreamIndex index = TemporalClickstreamIndex.load(clickstreamCsv(), productCsv());

        assertThat(index.sessionIdsInTimeOrder()).containsExactly("s1", "s2");
    }

    @Test
    void aSessionsEventsAreInvisibleUntilItIsAdvanced() throws URISyntaxException {
        TemporalClickstreamIndex index = TemporalClickstreamIndex.load(clickstreamCsv(), productCsv());

        assertThat(index.popularityScore("P1")).isEqualTo(0.0);

        index.advance("s1");
        // s1 contributes a 0.5-weighted click on P1 — s2's later purchase (weight 1.0) must NOT be visible yet.
        assertThat(index.popularityScore("P1")).isCloseTo(0.5, offset(1e-9));

        index.advance("s2");
        assertThat(index.popularityScore("P1")).isCloseTo(1.5, offset(1e-9));
    }

    @Test
    void userProfileOnlyReflectsSessionsAlreadyAdvanced() throws URISyntaxException {
        TemporalClickstreamIndex index = TemporalClickstreamIndex.load(clickstreamCsv(), productCsv());

        assertThat(index.userProfile("u1").interactedProductIds()).isEmpty();

        index.advance("s1");
        assertThat(index.userProfile("u1").interactedProductIds()).containsExactlyInAnyOrder("P1", "P2");
    }

    @Test
    void itemSimilarityReflectsOnlyAdvancedCoOccurrence() throws URISyntaxException {
        TemporalClickstreamIndex index = TemporalClickstreamIndex.load(clickstreamCsv(), productCsv());

        assertThat(index.itemSimilarity("P1", "P2")).isEqualTo(0.0);

        index.advance("s1");
        // P1 and P2 co-occurred once in s1, each with marginal session-interaction-count 1:
        // similarity = 1 / sqrt(1*1) = 1.0.
        assertThat(index.itemSimilarity("P1", "P2")).isCloseTo(1.0, offset(1e-9));
    }

    @Test
    void catalogEntryIsAvailableImmediately() throws URISyntaxException {
        // Catalog (product.csv) is loaded eagerly, unlike the clickstream-derived
        // aggregates — a product's static metadata isn't a temporal-leak concern.
        TemporalClickstreamIndex index = TemporalClickstreamIndex.load(clickstreamCsv(), productCsv());

        assertThat(index.catalogEntry("P1")).isPresent();
        assertThat(index.catalogEntry("P1").get().productName()).isEqualTo("widget one");
    }

    private Path clickstreamCsv() throws URISyntaxException {
        return Paths.get(getClass().getClassLoader().getResource("eval-fixture/clickstream.csv").toURI());
    }

    private Path productCsv() throws URISyntaxException {
        return Paths.get(getClass().getClassLoader().getResource("eval-fixture/product.csv").toURI());
    }
}
