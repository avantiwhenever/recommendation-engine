package com.avanti.recengine.support.pinecone;

import java.util.Map;

/** A vector to upsert: an id, its embedding, and arbitrary metadata (String/Number/Boolean values only). */
public record ProductVector(String id, float[] values, Map<String, Object> metadata) {
}
