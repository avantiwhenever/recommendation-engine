package com.avanti.recengine.recommender.eval;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses the same shared eval-fixture/ CSVs as {@link TemporalClickstreamIndexTest}: sessions s1 (query q1, P1+P2) and s2 (query q1, P1). */
class EvalSessionLoaderTest {

    @Test
    void attachesIndependentLabelGradesPerSessionsQuery() throws URISyntaxException {
        List<EvalSession> sessions = EvalSessionLoader.load(clickstreamCsv(), productCsv(), labelCsv());

        EvalSession s1 = sessions.stream().filter(s -> s.sessionId().equals("s1")).findFirst().orElseThrow();
        assertThat(s1.queryId()).isEqualTo("q1");
        assertThat(s1.independentRelevanceGrades()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of("P1", 2, "P2", 1));

        // Clickstream-derived grades are untouched by this change (P1=click=1, P2=click=1).
        assertThat(s1.relevanceGrades()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of("P1", 1, "P2", 1));
    }

    @Test
    void nullLabelCsvProducesEmptyIndependentGradesEverywhere() throws URISyntaxException {
        List<EvalSession> sessions = EvalSessionLoader.load(clickstreamCsv(), productCsv(), null);

        assertThat(sessions).isNotEmpty();
        assertThat(sessions).allSatisfy(s -> assertThat(s.independentRelevanceGrades()).isEmpty());
    }

    @Test
    void queryWithNoLabelJudgmentsGetsEmptyIndependentGrades() throws URISyntaxException {
        // s2's query_id is also "q1" in the shared fixture, so to exercise the
        // "no judgments for this query" path we point at a label file that
        // doesn't mention q1 at all.
        List<EvalSession> sessions = EvalSessionLoader.load(clickstreamCsv(), productCsv(), otherLabelCsv());

        assertThat(sessions).allSatisfy(s -> assertThat(s.independentRelevanceGrades()).isEmpty());
    }

    private Path clickstreamCsv() throws URISyntaxException {
        return Paths.get(getClass().getClassLoader().getResource("eval-fixture/clickstream.csv").toURI());
    }

    private Path productCsv() throws URISyntaxException {
        return Paths.get(getClass().getClassLoader().getResource("eval-fixture/product.csv").toURI());
    }

    private Path labelCsv() throws URISyntaxException {
        return Paths.get(getClass().getClassLoader().getResource("eval-fixture/label-for-sessions.csv").toURI());
    }

    private Path otherLabelCsv() throws URISyntaxException {
        // "label.csv" uses query_id 0/1, disjoint from clickstream.csv's "q1" — a stand-in for "no judgments exist for this query".
        return Paths.get(getClass().getClassLoader().getResource("eval-fixture/label.csv").toURI());
    }
}
