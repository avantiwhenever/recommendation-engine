package com.avanti.recengine.gateway.adapter.out.grpc;

import com.avanti.recengine.gateway.domain.Product;
import com.avanti.recengine.gateway.port.out.SearchPort;
import com.avanti.recengine.grpc.search.ScoredProduct;
import com.avanti.recengine.grpc.search.SearchRequest;
import com.avanti.recengine.grpc.search.SearchResponse;
import com.avanti.recengine.grpc.search.SearchServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/** Outbound adapter: the only place this service talks gRPC to search-service. */
@Component
public class GrpcSearchServiceClientAdapter implements SearchPort {

    private static final String SOURCE = "search";

    private final SearchServiceGrpc.SearchServiceBlockingStub searchStub;

    public GrpcSearchServiceClientAdapter(@GrpcClient("search-service") SearchServiceGrpc.SearchServiceBlockingStub searchStub) {
        this.searchStub = searchStub;
    }

    @Override
    public List<Product> search(String query, int topK) {
        return search(query, topK, null, 0.0);
    }

    @Override
    public List<Product> search(String query, int topK, String categoryFilter, double minRating) {
        SearchRequest.Builder builder = SearchRequest.newBuilder()
                .setQuery(query)
                .setTopK(topK)
                .setMinRating(minRating);
        if (categoryFilter != null) {
            builder.setCategoryFilter(categoryFilter);
        }
        SearchResponse response = searchStub.search(builder.build());
        return response.getResultsList().stream().map(GrpcSearchServiceClientAdapter::toDomain).toList();
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
                SOURCE
        );
    }

    private static String blankToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }
}
