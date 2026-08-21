package com.avanti.recengine.support.pinecone;

import java.util.Map;

/** A single query result: id, cosine similarity score, and its stored metadata. */
public record ScoredMatch(String id, float score, Map<String, Object> metadata) {
}
