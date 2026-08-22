package com.avanti.recengine.recommender.adapter.out.onnx;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the real ONNX model trained by training/train_neural_ranker.py.
 * Skipped (not failed) when the model isn't present — same convention as
 * rec-support's EmbeddingServiceTest for the gitignored models/ directory.
 */
class OnnxRankingModelAdapterTest {

    private static final Path MODEL_PATH = Path.of("../models/neural-ranker/model.onnx");

    @BeforeAll
    static void modelIsPresent() {
        assumeTrue(Files.exists(MODEL_PATH),
                "Skipping: run training/train_neural_ranker.py first to produce the model");
    }

    @Test
    void scoresAReal6FeatureVectorWithoutError() {
        try (OnnxRankingModelAdapter adapter = new OnnxRankingModelAdapter(MODEL_PATH)) {
            double score = adapter.score(new float[]{1.0f, 0.6f, 2.0f, 1.5f, 0.9f, 3.0f});
            assertThat(score).isFinite();
        }
    }

    @Test
    void higherSignalFeaturesScoreHigherOnAverage() {
        try (OnnxRankingModelAdapter adapter = new OnnxRankingModelAdapter(MODEL_PATH)) {
            // Strong signal: category match, popular, co-occurring, well-rated.
            double strong = adapter.score(new float[]{1.0f, 0.6f, 4.0f, 3.0f, 1.0f, 5.0f});
            // Weak signal: no category match, unpopular, no co-occurrence, poorly rated.
            double weak = adapter.score(new float[]{0.0f, 0.5f, 0.0f, 0.0f, 0.2f, 0.0f});

            assertThat(strong).isGreaterThan(weak);
        }
    }
}
