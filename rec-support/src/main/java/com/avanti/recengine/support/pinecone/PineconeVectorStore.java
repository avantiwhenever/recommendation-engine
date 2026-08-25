package com.avanti.recengine.support.pinecone;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import io.pinecone.exceptions.PineconeException;
import io.pinecone.unsigned_indices_model.QueryResponseWithUnsignedIndices;
import io.pinecone.unsigned_indices_model.ScoredVectorWithUnsignedIndices;
import io.pinecone.unsigned_indices_model.VectorWithUnsignedIndices;
import org.openapitools.db_control.client.model.DeletionProtection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.pinecone.commons.IndexInterface.buildUpsertVectorWithUnsignedIndices;

/**
 * Thin wrapper over the Pinecone Java client (verified against the real
 * {@code io.pinecone:pinecone-client:6.0.0} class files, not guessed from
 * memory — its API has changed across major versions).
 *
 * <p>Points at Pinecone Local by default ({@code tlsEnabled=false}, no real
 * API key needed — Pinecone Local ignores auth entirely) but works
 * identically against a real hosted Pinecone index by changing
 * host/key/TLS, since it's the same client and wire protocol either way.
 *
 * <p>{@code controlPlaneHost} must include the URL scheme (e.g.
 * {@code http://pinecone-local:5080}, not just {@code pinecone-local:5080})
 * — the control-plane client is REST/okhttp-based and requires it. Once
 * built, {@link Pinecone#getIndexConnection(String)} resolves the specific
 * index's data-plane host automatically via {@code describeIndex}; callers
 * never need to know or construct a separate data-plane host/port.
 */
public final class PineconeVectorStore implements AutoCloseable {

    private static final String DEFAULT_NAMESPACE = "";

    private final Pinecone pinecone;
    private final Index index;

    public PineconeVectorStore(String controlPlaneHost, String apiKey, boolean tlsEnabled, String indexName) {
        this.pinecone = new Pinecone.Builder(apiKey)
                .withHost(controlPlaneHost)
                .withTlsEnabled(tlsEnabled)
                .build();
        this.index = pinecone.getIndexConnection(indexName);
    }

    /**
     * Creates the serverless index if it doesn't already exist. Idempotent —
     * safe to call on every ingestion run. {@code cloud}/{@code region} are
     * accepted but not meaningful against Pinecone Local's in-memory emulator;
     * they matter only when pointed at a real hosted Pinecone project.
     */
    public static void ensureServerlessIndex(String controlPlaneHost, String apiKey, boolean tlsEnabled,
                                              String indexName, int dimensions) {
        Pinecone controlPlane = new Pinecone.Builder(apiKey)
                .withHost(controlPlaneHost)
                .withTlsEnabled(tlsEnabled)
                .build();
        try {
            controlPlane.describeIndex(indexName);
        } catch (PineconeException notFound) {
            controlPlane.createServerlessIndex(
                    indexName, "cosine", dimensions, "aws", "us-east-1", DeletionProtection.DISABLED);
        }
    }

    public void upsertBatch(List<ProductVector> vectors) {
        List<VectorWithUnsignedIndices> upserts = new ArrayList<>(vectors.size());
        for (ProductVector vector : vectors) {
            upserts.add(buildUpsertVectorWithUnsignedIndices(
                    vector.id(), toFloatList(vector.values()), null, null, toStruct(vector.metadata())));
        }
        index.upsert(upserts, DEFAULT_NAMESPACE);
    }

    public List<ScoredMatch> query(float[] queryVector, int topK) {
        QueryResponseWithUnsignedIndices response =
                index.queryByVector(topK, toFloatList(queryVector), DEFAULT_NAMESPACE, false, true);
        List<ScoredMatch> matches = new ArrayList<>();
        for (ScoredVectorWithUnsignedIndices match : response.getMatchesList()) {
            matches.add(new ScoredMatch(match.getId(), match.getScore(), fromStruct(match.getMetadata())));
        }
        return matches;
    }

    /**
     * Nearest neighbors of a vector already stored under {@code id} — no
     * client-side embedding step, Pinecone looks up the stored vector
     * server-side and queries with it directly. {@code topK} matches
     * typically include {@code id} itself (score 1.0, since a vector is its
     * own nearest neighbor); callers that want "other similar items" should
     * filter it out and should over-request {@code topK} by one to
     * compensate.
     */
    public List<ScoredMatch> queryById(String id, int topK) {
        QueryResponseWithUnsignedIndices response =
                index.queryByVectorId(topK, id, DEFAULT_NAMESPACE, false, true);
        List<ScoredMatch> matches = new ArrayList<>();
        for (ScoredVectorWithUnsignedIndices match : response.getMatchesList()) {
            matches.add(new ScoredMatch(match.getId(), match.getScore(), fromStruct(match.getMetadata())));
        }
        return matches;
    }

    private static List<Float> toFloatList(float[] values) {
        List<Float> list = new ArrayList<>(values.length);
        for (float v : values) {
            list.add(v);
        }
        return list;
    }

    private static Struct toStruct(Map<String, Object> metadata) {
        Struct.Builder builder = Struct.newBuilder();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            Value protoValue = switch (value) {
                case Number n -> Value.newBuilder().setNumberValue(n.doubleValue()).build();
                case Boolean b -> Value.newBuilder().setBoolValue(b).build();
                default -> Value.newBuilder().setStringValue(value.toString()).build();
            };
            builder.putFields(entry.getKey(), protoValue);
        }
        return builder.build();
    }

    private static Map<String, Object> fromStruct(Struct struct) {
        if (struct == null) {
            return Map.of();
        }
        Map<String, Object> metadata = new HashMap<>();
        struct.getFieldsMap().forEach((key, value) -> {
            switch (value.getKindCase()) {
                case STRING_VALUE -> metadata.put(key, value.getStringValue());
                case NUMBER_VALUE -> metadata.put(key, value.getNumberValue());
                case BOOL_VALUE -> metadata.put(key, value.getBoolValue());
                default -> {
                    // NULL_VALUE / STRUCT_VALUE / LIST_VALUE not used for product metadata; skip.
                }
            }
        });
        return metadata;
    }

    @Override
    public void close() {
        index.close();
    }
}
