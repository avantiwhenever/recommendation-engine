package com.avanti.recengine.recommender.adapter.out.onnx;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.avanti.recengine.recommender.port.out.RankingModelPort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Runs the neural ranker (trained by {@code training/train_neural_ranker.py},
 * exported to ONNX) via ONNX Runtime directly — a small single-input,
 * single-output feature-vector model, unlike rec-support's
 * {@code EmbeddingService} (which is specific to the transformer embedding
 * model's tokenizer + multi-input shape), so this is its own minimal wrapper
 * rather than a reuse of that class.
 *
 * <p>Input tensor name {@code "features"}, shape [1, 6]; output tensor name
 * {@code "score"}, shape [1, 1] — must match the export in
 * {@code train_neural_ranker.py} exactly.
 */
public final class OnnxRankingModelAdapter implements RankingModelPort, AutoCloseable {

    private static final String INPUT_NAME = "features";
    private static final String OUTPUT_NAME = "score";

    private final OrtEnvironment environment;
    private final OrtSession session;

    public OnnxRankingModelAdapter(Path modelPath) {
        this.environment = OrtEnvironment.getEnvironment();
        try {
            this.session = environment.createSession(modelPath.toString(), new OrtSession.SessionOptions());
        } catch (OrtException e) {
            throw new UncheckedIOException(new IOException("Failed to load ONNX model from " + modelPath, e));
        }
    }

    @Override
    public double score(float[] features) {
        try (OnnxTensor input = OnnxTensor.createTensor(environment, new float[][]{features});
             OrtSession.Result result = session.run(Map.of(INPUT_NAME, input))) {
            float[][] output = (float[][]) result.get(OUTPUT_NAME)
                    .orElseThrow(() -> new IllegalStateException("Model produced no '" + OUTPUT_NAME + "' output"))
                    .getValue();
            return output[0][0];
        } catch (OrtException e) {
            throw new UncheckedIOException(new IOException("ONNX inference failed", e));
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            throw new UncheckedIOException(new IOException("Failed to close ONNX session", e));
        }
    }
}
