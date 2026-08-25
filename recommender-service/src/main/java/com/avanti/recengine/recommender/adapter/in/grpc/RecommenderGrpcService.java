package com.avanti.recengine.recommender.adapter.in.grpc;

import com.avanti.recengine.grpc.recommender.RecommendRequest;
import com.avanti.recengine.grpc.recommender.RecommendResponse;
import com.avanti.recengine.grpc.recommender.RecommenderServiceGrpc;
import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.ScoredProduct;
import com.avanti.recengine.recommender.domain.Strategy;
import com.avanti.recengine.recommender.port.in.RecommendUseCase;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.List;
import java.util.stream.Collectors;

@GrpcService
public class RecommenderGrpcService extends RecommenderServiceGrpc.RecommenderServiceImplBase {

    private final RecommendUseCase useCase;

    public RecommenderGrpcService(RecommendUseCase useCase) {
        this.useCase = useCase;
    }

    @Override
    public void recommend(RecommendRequest request, StreamObserver<RecommendResponse> responseObserver) {
        RecommendationContext context = new RecommendationContext(
                request.getQuery(),
                request.getUserId(),
                Strategy.valueOf(request.getStrategy().name()),
                List.copyOf(request.getRecentProductIdsList())
        );
        List<ScoredProduct> candidates = request.getCandidatesList().stream()
                .map(this::toDomain)
                .collect(Collectors.toList());

        RecommendUseCase.Recommendation recommendation = useCase.recommend(context, candidates);

        RecommendResponse.Builder response = RecommendResponse.newBuilder().setSource(recommendation.source());
        for (ScoredProduct product : recommendation.results()) {
            response.addResults(toProto(product));
        }
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    private ScoredProduct toDomain(com.avanti.recengine.grpc.search.ScoredProduct proto) {
        return new ScoredProduct(
                proto.getProductId(),
                proto.getScore(),
                proto.getProductName(),
                proto.getProductClass(),
                proto.getCategoryHierarchy(),
                proto.getAverageRating(),
                proto.getRatingCount()
        );
    }

    private com.avanti.recengine.grpc.search.ScoredProduct toProto(ScoredProduct product) {
        return com.avanti.recengine.grpc.search.ScoredProduct.newBuilder()
                .setProductId(product.productId())
                .setScore(product.score())
                .setProductName(nullToEmpty(product.productName()))
                .setProductClass(nullToEmpty(product.productClass()))
                .setCategoryHierarchy(nullToEmpty(product.categoryHierarchy()))
                .setAverageRating(product.averageRating())
                .setRatingCount(product.ratingCount())
                .build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
