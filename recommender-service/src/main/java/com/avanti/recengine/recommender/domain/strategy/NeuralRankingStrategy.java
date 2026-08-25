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
 * ML-based strategy using ONNX: scores each candidate with a gradient-
 * boosted pairwise ranking model (XGBoost, {@code rank:ndcg} objective)
 * trained offline on implicit clickstream feedback (see
 * {@code training/train_neural_ranker.py} and {@code training/TRAINING.md}),
 * mirroring the sibling {@code search} project's own
 * {@code NeuralRerankStrategy}/{@code RerankFeatureBuilder} pattern — cheap,
 * mostly-cached features and one small forward pass, not a transformer.
 * Grounded in Airbnb's KDD 2019 "Applying Deep Learning to Airbnb Search"
 * (arXiv:1810.09591) for the pairwise training objective.
 *
 * <p><b>Reported honestly</b>: a plain logistic-regression linear
 * combination of the same 7 features (0.8751 ± 0.0025 mean held-out
 * pairwise accuracy across 5 seeds) scores higher on the held-out split
 * than this XGBoost model (0.8687 ± 0.0019). This model is kept in service
 * anyway because its training objective is the one that actually matches
 * what's measured — see {@code training/TRAINING.md} for the full account.
 *
 * <p><b>Feature vector (order matters — must exactly match
 * {@code train_neural_ranker.py}'s feature builder; verified in sync by
 * {@code FeatureParityTest} against a shared golden-vector fixture, not
 * just a hand-maintained comment table):</b>
 * <ol>
 *   <li>category match: 1.0 if the candidate's top-level category segment
 *       matches the user's most-frequent interacted category, else 0.0</li>
 *   <li>base score, sigmoid-squashed to (0,1) — search-service's score at
 *       serving time; a {@code 1/position} proxy at training time, since
 *       training pairs aren't tied to a real search call. This is a real,
 *       documented train/serve skew — see TRAINING.md.</li>
 *   <li>log1p(popularity score) — same clickstream-derived definition both
 *       sides, no skew</li>
 *   <li>log1p(raw co-occurrence count with the user's interaction history)
 *       — same definition both sides, no skew. Note this is the raw count,
 *       not {@link CollaborativeFilteringStrategy}'s normalized
 *       {@code itemSimilarity} — the two strategies' signals are related
 *       but not identical; unifying them is a follow-up, not done here.</li>
 *   <li>average rating / 5.0 — same product.csv source both sides</li>
 *   <li>log1p(rating count) — same product.csv source both sides</li>
 *   <li>same-session category overlap: the fraction of {@link
 *       RecommendationContext#recentProductIds()} (the current-session
 *       signal, distinct from feature 4's all-time co-occurrence) that
 *       share the candidate's top-level category segment. 0.0 when
 *       there's no session signal. This is "recency-weighted" in the
 *       sense of "derived from the session-scoped list," not an actual
 *       time-decay curve — the proto carries an unordered ID list, not
 *       per-event timestamps.</li>
 * </ol>
 *
 * <p><b>Cold-start items</b>: for a product with zero clickstream
 * footprint (features 3 and 4 both collapse to 0 — {@code TRAINING.md}
 * notes this is true for ~97% of the WANDS catalog), this class does not
 * substitute a Pinecone content-similarity score in their place, even
 * though a {@code VectorSimilarityPort} exists in this service. Feature 1
 * (category match against the user's all-time top category) and feature 7
 * (session category overlap) both still produce a real, non-zero signal
 * for a zero-footprint item, since they're derived from the candidate's
 * own {@code categoryHierarchy} metadata, not clickstream history —
 * a partial fallback, not a full fix. Swapping in real embedding
 * similarity via the port, once wired here, is a documented follow-on.
 */
public final class NeuralRankingStrategy implements RecommendationStrategy {

    static final int FEATURE_COUNT = 7;

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
        List<String> recentCategorySegments = sessionCategorySegments(context.recentProductIds());

        List<ScoredProduct> reranked = new ArrayList<>(baseResults.size());
        for (ScoredProduct product : baseResults) {
            float[] features = buildFeatures(product, topCategory, interacted, recentCategorySegments);
            double modelScore = model.score(features);
            reranked.add(product.withScore(modelScore));
        }
        reranked.sort((a, b) -> Double.compare(b.score(), a.score()));
        return reranked;
    }

    /** Top-level category segment of each catalog-resolvable recent product, one entry per input ID (unresolvable IDs are skipped, not padded). */
    private List<String> sessionCategorySegments(List<String> recentProductIds) {
        List<String> segments = new ArrayList<>(recentProductIds.size());
        for (String recentId : recentProductIds) {
            clickstream.catalogEntry(recentId)
                    .map(entry -> entry.categoryHierarchy())
                    .filter(hierarchy -> hierarchy != null && !hierarchy.isBlank())
                    .ifPresent(hierarchy -> segments.add(topLevelSegment(hierarchy)));
        }
        return segments;
    }

    float[] buildFeatures(ScoredProduct product, Optional<String> userTopCategory, Set<String> userInteracted) {
        return buildFeatures(product, userTopCategory, userInteracted, List.of());
    }

    float[] buildFeatures(ScoredProduct product, Optional<String> userTopCategory, Set<String> userInteracted,
                           List<String> recentCategorySegments) {
        float categoryMatch = userTopCategory.isPresent() && categoryMatches(product.categoryHierarchy(), userTopCategory.get())
                ? 1.0f : 0.0f;
        float baseScore = (float) sigmoid(product.score());
        float popularity = (float) Math.log1p(clickstream.popularityScore(product.productId()));
        float coOccurrence = (float) Math.log1p(clickstream.coOccurrenceCount(product.productId(), userInteracted));
        float avgRating = (float) (product.averageRating() / 5.0);
        float reviewCount = (float) Math.log1p(product.ratingCount());
        float sessionCategoryOverlap = sessionCategoryOverlap(product.categoryHierarchy(), recentCategorySegments);
        return new float[]{categoryMatch, baseScore, popularity, coOccurrence, avgRating, reviewCount, sessionCategoryOverlap};
    }

    private static float sessionCategoryOverlap(String categoryHierarchy, List<String> recentCategorySegments) {
        if (categoryHierarchy == null || categoryHierarchy.isBlank() || recentCategorySegments.isEmpty()) {
            return 0.0f;
        }
        String candidateSegment = topLevelSegment(categoryHierarchy);
        long matches = recentCategorySegments.stream().filter(segment -> segment.equalsIgnoreCase(candidateSegment)).count();
        return (float) matches / recentCategorySegments.size();
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
