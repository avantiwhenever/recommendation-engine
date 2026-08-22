package com.avanti.recengine.gateway.adapter.out.grpc;

import com.avanti.recengine.gateway.domain.Product;
import com.avanti.recengine.gateway.domain.RecommendResult;
import com.avanti.recengine.gateway.domain.RecommenderStrategy;
import com.avanti.recengine.gateway.port.out.RecommenderPort;
import com.avanti.recengine.grpc.recommender.RecommendRequest;
import com.avanti.recengine.grpc.recommender.RecommendResponse;
import com.avanti.recengine.grpc.recommender.RecommenderServiceGrpc;
import com.avanti.recengine.grpc.recommender.Strategy;
import com.avanti.recengine.grpc.search.ScoredProduct;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/** Outbound adapter: the only place this service talks gRPC to recommender-service. */
@Component
public class GrpcRecommenderServiceClientAdapter implements RecommenderPort {

    private final RecommenderServiceGrpc.RecommenderServiceBlockingStub recommenderStub;

    public GrpcRecommenderServiceClientAdapter(
            @GrpcClient("recommender-service") RecommenderServiceGrpc.RecommenderServiceBlockingStub recommenderStub) {
        this.recommenderStub = recommenderStub;
    }

    @Override
    public RecommendResult recommend(String query, String userId, RecommenderStrategy strategy, List<Product> candidates) {
        RecommendRequest.Builder builder = RecommendRequest.newBuilder()
                .setQuery(query)
                .setStrategy(toProtoStrategy(strategy));
        if (userId != null) {
            builder.setUserId(userId);
        }
        candidates.forEach(c -> builder.addCandidates(toProtoScoredProduct(c)));

        RecommendResponse response = recommenderStub.recommend(builder.build());
        List<Product> results = response.getResultsList().stream()
                .map(GrpcRecommenderServiceClientAdapter::toDomain)
                .toList();
        return new RecommendResult(results, response.getSource());
    }

    /**
     * Enum values are named identically between {@link RecommenderStrategy}
     * and the generated proto {@link Strategy} by design (see
     * RecommenderStrategy's javadoc) — {@code valueOf(name())} is the whole
     * mapping. Add a value to one without the other and this throws at
     * runtime rather than silently mismapping.
     */
    private static Strategy toProtoStrategy(RecommenderStrategy strategy) {
        return Strategy.valueOf(strategy.name());
    }

    private static ScoredProduct toProtoScoredProduct(Product p) {
        ScoredProduct.Builder builder = ScoredProduct.newBuilder()
                .setProductId(p.productId())
                .setScore(p.score())
                .setProductName(nullToBlank(p.name()))
                .setAverageRating(p.averageRating())
                .setRatingCount(p.ratingCount());
        if (p.productClass() != null) {
            builder.setProductClass(p.productClass());
        }
        if (p.categoryHierarchy() != null) {
            builder.setCategoryHierarchy(p.categoryHierarchy());
        }
        return builder.build();
    }

    private static Product toDomain(ScoredProduct p) {
        return new Product(
                p.getProductId(),
                p.getProductName(),
                blankToNull(p.getProductClass()),
                blankToNull(p.getCategoryHierarchy()),
                p.getAverageRating(),
                p.getRatingCount(),
                p.getScore(),
                null // source is set uniformly from RecommendResponse.source by the caller
        );
    }

    private static String blankToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
