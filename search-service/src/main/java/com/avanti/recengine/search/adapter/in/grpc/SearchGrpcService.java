package com.avanti.recengine.search.adapter.in.grpc;

import com.avanti.recengine.grpc.search.ScoredProduct;
import com.avanti.recengine.grpc.search.SearchRequest;
import com.avanti.recengine.grpc.search.SearchResponse;
import com.avanti.recengine.grpc.search.SearchServiceGrpc;
import com.avanti.recengine.search.domain.Product;
import com.avanti.recengine.search.domain.ScoredResult;
import com.avanti.recengine.search.port.in.SearchProductsUseCase;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class SearchGrpcService extends SearchServiceGrpc.SearchServiceImplBase {

    private final SearchProductsUseCase searchProductsUseCase;

    public SearchGrpcService(SearchProductsUseCase searchProductsUseCase) {
        this.searchProductsUseCase = searchProductsUseCase;
    }

    @Override
    public void search(SearchRequest request, StreamObserver<SearchResponse> responseObserver) {
        var results = searchProductsUseCase.search(request.getQuery(), request.getTopK());

        SearchResponse.Builder response = SearchResponse.newBuilder();
        for (ScoredResult result : results) {
            response.addResults(toProto(result));
        }

        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    private static ScoredProduct toProto(ScoredResult result) {
        Product product = result.product();
        ScoredProduct.Builder builder = ScoredProduct.newBuilder()
                .setProductId(nullToEmpty(product.productId()))
                .setScore(result.score())
                .setProductName(nullToEmpty(product.productName()))
                .setProductClass(nullToEmpty(product.productClass()))
                .setCategoryHierarchy(nullToEmpty(product.categoryHierarchy()));
        if (product.averageRating() != null) {
            builder.setAverageRating(product.averageRating());
        }
        if (product.ratingCount() != null) {
            builder.setRatingCount(product.ratingCount());
        }
        return builder.build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
