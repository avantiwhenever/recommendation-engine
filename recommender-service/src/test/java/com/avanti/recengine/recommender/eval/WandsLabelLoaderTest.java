package com.avanti.recengine.recommender.eval;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WandsLabelLoaderTest {

    @Test
    void loadsGradesKeyedByQueryThenProduct() throws URISyntaxException {
        Map<String, Map<String, Integer>> byQuery = WandsLabelLoader.load(fixture());

        assertThat(byQuery).containsOnlyKeys("0", "1");
        assertThat(byQuery.get("0")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "100", 2,
                "101", 1,
                "102", 0
        ));
        assertThat(byQuery.get("1")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "100", 0
        ));
    }

    @Test
    void queryWithNoJudgmentsIsAbsentFromTheMap() throws URISyntaxException {
        Map<String, Map<String, Integer>> byQuery = WandsLabelLoader.load(fixture());

        // Caller-side convention (EvalSessionLoader) is getOrDefault(queryId, Map.of()),
        // so a query never annotated here should simply not be a key.
        assertThat(byQuery).doesNotContainKey("999");
    }

    @Test
    void unknownLabelValueThrows() {
        // A malformed label.csv is a data-integrity bug worth failing loudly on,
        // same convention as the sibling `search` project's RelevanceGrade.fromLabel.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> WandsLabelLoader.load(malformedFixture()));
    }

    private Path fixture() throws URISyntaxException {
        return Paths.get(getClass().getClassLoader().getResource("eval-fixture/label.csv").toURI());
    }

    private Path malformedFixture() throws URISyntaxException {
        return Paths.get(getClass().getClassLoader().getResource("eval-fixture/label-malformed.csv").toURI());
    }
}
