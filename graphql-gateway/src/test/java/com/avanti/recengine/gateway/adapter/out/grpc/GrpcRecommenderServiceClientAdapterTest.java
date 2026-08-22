package com.avanti.recengine.gateway.adapter.out.grpc;

import com.avanti.recengine.gateway.domain.Product;
import com.avanti.recengine.gateway.domain.RecommendResult;
import com.avanti.recengine.gateway.domain.RecommenderStrategy;
import com.avanti.recengine.grpc.recommender.RecommendRequest;
import com.avanti.recengine.grpc.recommender.RecommendResponse;
import com.avanti.recengine.grpc.recommender.RecommenderServiceGrpc;
import com.avanti.recengine.grpc.recommender.Strategy;
import com.avanti.recengine.grpc.search.ScoredProduct;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcRecommenderServiceClientAdapterTest {

    private Server server;
    private ManagedChannel channel;
    private GrpcRecommenderServiceClientAdapter adapter;
    private final AtomicReference<RecommendRequest> capturedRequest = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        String serverName = "recommender-service-" + System.nanoTime();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new FakeRecommenderService(capturedRequest))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        adapter = new GrpcRecommenderServiceClientAdapter(RecommenderServiceGrpc.newBlockingStub(channel));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void sendsCandidatesAndStrategyThenMapsResponseWithUniformSource() {
        Product candidate = new Product("1", "chair", "Chairs", "Furniture > Chairs", 4.5, 10, 0.9, "search");

        RecommendResult result = adapter.recommend("chair", "u1", RecommenderStrategy.COLLABORATIVE, List.of(candidate));

        RecommendRequest sent = capturedRequest.get();
        assertThat(sent.getQuery()).isEqualTo("chair");
        assertThat(sent.getUserId()).isEqualTo("u1");
        assertThat(sent.getStrategy()).isEqualTo(Strategy.COLLABORATIVE);
        assertThat(sent.getCandidatesList()).hasSize(1);
        assertThat(sent.getCandidates(0).getProductId()).isEqualTo("1");

        assertThat(result.source()).isEqualTo("collaborative-filtering");
        assertThat(result.products()).hasSize(1);
        assertThat(result.products().get(0).productId()).isEqualTo("99");
    }

    @Test
    void omitsUserIdWhenNull() {
        adapter.recommend("chair", null, RecommenderStrategy.POPULARITY, List.of());

        assertThat(capturedRequest.get().getUserId()).isEmpty();
        assertThat(capturedRequest.get().getStrategy()).isEqualTo(Strategy.POPULARITY);
    }

    private static final class FakeRecommenderService extends RecommenderServiceGrpc.RecommenderServiceImplBase {
        private final AtomicReference<RecommendRequest> capturedRequest;

        FakeRecommenderService(AtomicReference<RecommendRequest> capturedRequest) {
            this.capturedRequest = capturedRequest;
        }

        @Override
        public void recommend(RecommendRequest request, StreamObserver<RecommendResponse> responseObserver) {
            capturedRequest.set(request);
            responseObserver.onNext(RecommendResponse.newBuilder()
                    .addResults(ScoredProduct.newBuilder().setProductId("99").setProductName("armchair").setScore(0.95).build())
                    .setSource("collaborative-filtering")
                    .build());
            responseObserver.onCompleted();
        }
    }
}
