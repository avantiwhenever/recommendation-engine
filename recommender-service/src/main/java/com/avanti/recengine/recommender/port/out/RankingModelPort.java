package com.avanti.recengine.recommender.port.out;

public interface RankingModelPort {
    /** Scores a fixed-length feature vector; higher is more relevant. */
    double score(float[] features);
}
