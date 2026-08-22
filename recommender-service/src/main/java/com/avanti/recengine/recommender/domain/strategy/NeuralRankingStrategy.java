package com.avanti.recengine.recommender.domain.strategy;

import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.RecommendationStrategy;
import com.avanti.recengine.recommender.domain.ScoredProduct;
import com.avanti.recengine.recommender.domain.UserProfile;
import com.avanti.recengine.recommender.port.out.ClickstreamRepositoryPort;
import com.avanti.recengine.recommender.port.out.RankingModelPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * ML-based strategy using ONNX: scores each candidate with a small MLP
 * trained offline on implicit clickstream feedback (see
 * {@code training/train_neural_ranker.py} and {@code training/TRAINING.md}),
 * mirroring the sibling {@code search} project's own
 * {@code NeuralRerankStrategy}/{@code RerankFeatureBuilder} pattern — cheap,
 * mostly-cached features and one small forward pass, not a transformer.
 *
 * <p><b>Feature vector (order matters — must exactly match
 * {@code train_neural_ranker.py}'s feature builder):</b>
 * <ol>
 *   <li>category match: 1.0 if the candidate's top-level category segment
 *       matches the user's most-frequent interacted category, else 0.0</li>
 *   <li>base score, sigmoid-squashed to (0,1) — search-service's score at
 *       serving time; a {@code 1/position} proxy at training time, since
 *       training pairs aren't tied to a real search call. This is a real,
 *       documented train/serve skew — see TRAINING.md.</li>
 *   <li>log1p(popularity score) — same clickstream-derived definition both
 *       sides, no skew</li>
 *   <li>log1p(co-occurrence count with the user's interaction history) —
 *       same definition both sides, no skew</li>
 *   <li>average rating / 5.0 — same product.csv source both sides</li>
 *   <li>log1p(rating count) — same product.csv source both sides</li>
 * </ol>
 */
public final class NeuralRankingStrategy implements RecommendationStrategy {

    static final int FEATURE_COUNT = 6;

    private final ClickstreamRepositoryPort clickstream;
    private final RankingModelPort model;

    public NeuralRankingStrategy(ClickstreamRepositoryPort clickstream, RankingModelPort model) {
        this.clickstream = clickstream;
        this.model = model;
    }

    @Override
    public List<ScoredProduct> apply(RecommendationContext context, List<ScoredProduct> baseResults) {
        UserProfile profile = clickstream.userProfile(context.userId());
        Optional<String> topCategory = profile.topCategory();
        Set<String> interacted = profile.interactedProductIds();

        List<ScoredProduct> reranked = new ArrayList<>(baseResults.size());
        for (ScoredProduct product : baseResults) {
            float[] features = buildFeatures(product, topCategory, interacted);
            double modelScore = model.score(features);
            reranked.add(product.withScore(modelScore));
        }
        reranked.sort((a, b) -> Double.compare(b.score(), a.score()));
        return reranked;
    }

    float[] buildFeatures(ScoredProduct product, Optional<String> userTopCategory, Set<String> userInteracted) {
        float categoryMatch = userTopCategory.isPresent() && categoryMatches(product.categoryHierarchy(), userTopCategory.get())
                ? 1.0f : 0.0f;
        float baseScore = (float) sigmoid(product.score());
        float popularity = (float) Math.log1p(clickstream.popularityScore(product.productId()));
        float coOccurrence = (float) Math.log1p(clickstream.coOccurrenceCount(product.productId(), userInteracted));
        float avgRating = (float) (product.averageRating() / 5.0);
        float reviewCount = (float) Math.log1p(product.ratingCount());
        return new float[]{categoryMatch, baseScore, popularity, coOccurrence, avgRating, reviewCount};
    }

    private static boolean categoryMatches(String categoryHierarchy, String userTopCategory) {
        if (categoryHierarchy == null || userTopCategory == null) {
            return false;
        }
        return topLevelSegment(categoryHierarchy).equalsIgnoreCase(topLevelSegment(userTopCategory));
    }

    private static String topLevelSegment(String categoryHierarchy) {
        String[] parts = categoryHierarchy.split("/");
        return parts[0].trim();
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    @Override
    public String name() {
        return "Neural Ranking";
    }
}
