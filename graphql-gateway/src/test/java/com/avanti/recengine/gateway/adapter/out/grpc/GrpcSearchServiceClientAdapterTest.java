package com.avanti.recengine.gateway.adapter.out.grpc;

import com.avanti.recengine.gateway.domain.Product;
import com.avanti.recengine.grpc.search.ScoredProduct;
import com.avanti.recengine.grpc.search.SearchRequest;
import com.avanti.recengine.grpc.search.SearchResponse;
import com.avanti.recengine.grpc.search.SearchServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real gRPC round trip over an in-process channel — no network sockets, but
 * a genuine client stub talking to a genuine (fake) server implementation,
 * proving the proto <-> domain mapping and the wire call itself both work.
 */
class GrpcSearchServiceClientAdapterTest {

    private Server server;
    private ManagedChannel channel;
    private GrpcSearchServiceClientAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = "search-service-" + System.nanoTime();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new FakeSearchService())
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        adapter = new GrpcSearchServiceClientAdapter(SearchServiceGrpc.newBlockingStub(channel));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void mapsProtoResultsToDomainProducts() {
        List<Product> products = adapter.search("chair", 3);

        assertThat(products).hasSize(2);
        Product first = products.get(0);
        assertThat(first.productId()).isEqualTo("1");
        assertThat(first.name()).isEqualTo("office chair");
        assertThat(first.productClass()).isEqualTo("Chairs");
        assertThat(first.averageRating()).isEqualTo(4.5);
        assertThat(first.ratingCount()).isEqualTo(10);
        assertThat(first.score()).isEqualTo(0.9);
        assertThat(first.source()).isEqualTo("search");

        // Second result has blank optional proto fields — verifies blank -> null mapping.
        Product second = products.get(1);
        assertThat(second.productClass()).isNull();
        assertThat(second.categoryHierarchy()).isNull();
    }

    private static final class FakeSearchService extends SearchServiceGrpc.SearchServiceImplBase {
        @Override
        public void search(SearchRequest request, StreamObserver<SearchResponse> responseObserver) {
            responseObserver.onNext(SearchResponse.newBuilder()
                    .addResults(ScoredProduct.newBuilder()
                            .setProductId("1").setProductName("office chair")
                            .setProductClass("Chairs").setCategoryHierarchy("Furniture > Chairs")
                            .setAverageRating(4.5).setRatingCount(10).setScore(0.9)
                            .build())
                    .addResults(ScoredProduct.newBuilder()
                            .setProductId("2").setProductName("stool")
                            .setAverageRating(3.0).setRatingCount(2).setScore(0.5)
                            .build())
                    .build());
            responseObserver.onCompleted();
        }
    }
}
