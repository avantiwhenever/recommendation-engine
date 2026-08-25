package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.ScoredProduct;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Golden-vector feature-parity test (Java side) — reads the SAME
 * {@code training/feature_parity_fixtures.csv} that
 * {@code training/test_feature_parity.py} reads, and asserts
 * {@link NeuralRankingStrategy#buildFeatures} produces the expected values
 * for the 4 features that must be train/serve-identical (category match,
 * popularity, co-occurrence, avg rating, rating count — see
 * {@code training/TRAINING.md}). Feature 1 (base_score_proxy) is
 * deliberately not cross-checked against the Python side's train-time
 * formula here — they're different formulas by design (documented
 * train/serve skew) — only that it's a valid sigmoid of the serve-time
 * score.
 *
 * <p>This exists because {@code NeuralRankingStrategy.buildFeatures} (Java)
 * and {@code train_neural_ranker.py}'s feature builder (Python) are two
 * independently-maintained implementations of the same formulas, previously
 * kept in sync only by a hand-written comment table — a gap that already
 * caused one real train/serve skew bug. A future edit that silently breaks
 * parity on either side now fails both this test and
 * {@code training/test_feature_parity.py}, from one shared fixture file.
 */
class FeatureParityTest {

    // buildFeatures returns float[] (32-bit) while the fixture's expected
    // values are computed in Python double precision — 1e-9 is too tight
    // for the resulting float32 truncation (~7 significant digits), not a
    // real logic mismatch.
    private static final double TOLERANCE = 1e-6;
    private static final String OTHER_PRODUCT = "fixture-co-occurring-product";

    @Test
    void javaFeaturesMatchSharedFixture() throws IOException {
        List<CSVRecord> rows = loadFixtures();
        assertThat(rows).isNotEmpty();

        for (CSVRecord row : rows) {
            String caseName = row.get("name");

            String categoryHierarchy = blankToNull(row.get("category_hierarchy"));
            double avgRating = Double.parseDouble(row.get("avg_rating"));
            int ratingCount = Integer.parseInt(row.get("rating_count"));
            String userTopCategoryRaw = blankToNull(row.get("user_top_category"));
            double popularityRaw = Double.parseDouble(row.get("popularity_raw"));
            int coOccurrenceRaw = Integer.parseInt(row.get("co_occurrence_raw"));
            double serveTimeScore = Double.parseDouble(row.get("serve_time_score"));

            FakeClickstreamRepository repo = new FakeClickstreamRepository(List.of());
            repo.withPopularity("fixture-product", popularityRaw);
            if (coOccurrenceRaw > 0) {
                repo.withCoOccurrence("fixture-product", OTHER_PRODUCT, coOccurrenceRaw);
            }
            NeuralRankingStrategy strategy = new NeuralRankingStrategy(repo, features -> 0.0);

            ScoredProduct product = new ScoredProduct(
                    "fixture-product", serveTimeScore, "fixture-name", null, categoryHierarchy, avgRating, ratingCount);
            Optional<String> userTopCategory = Optional.ofNullable(userTopCategoryRaw);
            Set<String> userInteracted = coOccurrenceRaw > 0 ? Set.of(OTHER_PRODUCT) : Set.of();

            float[] features = strategy.buildFeatures(product, userTopCategory, userInteracted);

            assertThat(features).as(caseName).hasSize(6);
            assertThat((double) features[0]).as(caseName + ": category_match")
                    .isCloseTo(Double.parseDouble(row.get("expected_category_match")), offset(TOLERANCE));
            assertThat((double) features[2]).as(caseName + ": popularity_log")
                    .isCloseTo(Double.parseDouble(row.get("expected_popularity_log")), offset(TOLERANCE));
            assertThat((double) features[3]).as(caseName + ": co_occurrence_log")
                    .isCloseTo(Double.parseDouble(row.get("expected_co_occurrence_log")), offset(TOLERANCE));
            assertThat((double) features[4]).as(caseName + ": avg_rating_over_5")
                    .isCloseTo(Double.parseDouble(row.get("expected_avg_rating_over_5")), offset(TOLERANCE));
            assertThat((double) features[5]).as(caseName + ": rating_count_log")
                    .isCloseTo(Double.parseDouble(row.get("expected_rating_count_log")), offset(TOLERANCE));

            // Feature 1: serve-time formula is sigmoid(product.score()) —
            // verify independently, not against Python's train-time value.
            double expectedServeFeature1 = 1.0 / (1.0 + Math.exp(-serveTimeScore));
            assertThat((double) features[1]).as(caseName + ": base_score_proxy (serve-time formula)")
                    .isCloseTo(expectedServeFeature1, offset(TOLERANCE));
        }
    }

    private static List<CSVRecord> loadFixtures() throws IOException {
        Path path = Path.of("../training/feature_parity_fixtures.csv");
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            return parser.getRecords();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + path, e);
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
