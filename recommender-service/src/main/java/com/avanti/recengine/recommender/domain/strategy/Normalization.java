package com.avanti.recengine.recommender.domain.strategy;

import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Min-max normalizes a list of values to [0, 1] within themselves. Strategies
 * blend heterogeneous signals (search-service's raw score, which can be a
 * BM25-scale or cosine-scale number depending on strategy, alongside a
 * log-scaled popularity count) — comparing them meaningfully requires
 * normalizing within the current candidate set rather than assuming a fixed
 * scale for either.
 */
final class Normalization {

    private Normalization() {
    }

    static <T> java.util.Map<T, Double> minMax(List<T> items, ToDoubleFunction<T> valueOf) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (T item : items) {
            double v = valueOf.applyAsDouble(item);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double range = max - min;
        java.util.Map<T, Double> normalized = new java.util.HashMap<>();
        for (T item : items) {
            double v = valueOf.applyAsDouble(item);
            normalized.put(item, range == 0.0 ? 0.5 : (v - min) / range);
        }
        return normalized;
    }
}
