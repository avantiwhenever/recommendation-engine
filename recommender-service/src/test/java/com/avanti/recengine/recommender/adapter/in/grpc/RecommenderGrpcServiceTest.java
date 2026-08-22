package com.avanti.recengine.recommender.adapter.in.grpc;

import com.avanti.recengine.grpc.recommender.RecommendRequest;
import com.avanti.recengine.grpc.recommender.RecommendResponse;
import com.avanti.recengine.grpc.recommender.RecommenderServiceGrpc;
import com.avanti.recengine.grpc.recommender.Strategy;
import com.avanti.recengine.grpc.search.ScoredProduct;
import com.avanti.recengine.recommender.port.in.RecommendUseCase;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RecommenderGrpcServiceTest {

    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void startInProcessServer() throws IOException {
        String name = "recommender-grpc-test-" + System.nanoTime();
        RecommendUseCase fakeUseCase = (context, baseResults) -> new RecommendUseCase.Recommendation(
                List.of(new com.avanti.recengine.recommender.domain.ScoredProduct(
                        "boosted-product", 9.9, "Boosted", "Chairs", "Furniture / Chairs", 4.0, 10)),
                "Fake Strategy"
        );

        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(new RecommenderGrpcService(fakeUseCase))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow();
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void mapsRequestAndResponseAcrossTheGrpcBoundary() {
        RecommenderServiceGrpc.RecommenderServiceBlockingStub stub = RecommenderServiceGrpc.newBlockingStub(channel);

        RecommendRequest request = RecommendRequest.newBuilder()
                .setQuery("chair")
                .setUserId("u1")
                .setStrategy(Strategy.COLLABORATIVE)
                .addCandidates(ScoredProduct.newBuilder()
                        .setProductId("original")
                        .setScore(1.0)
                        .setProductName("Original Chair")
                        .build())
                .build();

        RecommendResponse response = stub.recommend(request);

        assertThat(response.getSource()).isEqualTo("Fake Strategy");
        assertThat(response.getResultsList()).hasSize(1);
        assertThat(response.getResults(0).getProductId()).isEqualTo("boosted-product");
        assertThat(response.getResults(0).getScore()).isEqualTo(9.9);
    }
}
