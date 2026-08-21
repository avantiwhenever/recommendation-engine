package com.avanti.recengine.search.adapter.in.grpc;

import com.avanti.recengine.grpc.search.ScoredProduct;
import com.avanti.recengine.grpc.search.SearchRequest;
import com.avanti.recengine.grpc.search.SearchResponse;
import com.avanti.recengine.grpc.search.SearchServiceGrpc;
import com.avanti.recengine.search.domain.Product;
import com.avanti.recengine.search.domain.ScoredResult;
import com.avanti.recengine.search.port.in.SearchProductsUseCase;
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

class SearchGrpcServiceTest {

    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void startInProcessServer() throws IOException {
        String name = "search-grpc-test-" + System.nanoTime();
        SearchProductsUseCase fakeUseCase = (query, topK) -> List.of(
                new ScoredResult(new Product("42", "queen platform bed", "Beds", "Furniture > Beds", 4.5, 200), 0.87));

        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(new SearchGrpcService(fakeUseCase))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void searchReturnsMappedProtoResults() {
        SearchServiceGrpc.SearchServiceBlockingStub stub = SearchServiceGrpc.newBlockingStub(channel);

        SearchResponse response = stub.search(SearchRequest.newBuilder()
                .setQuery("queen platform bed")
                .setTopK(5)
                .build());

        assertThat(response.getResultsList()).hasSize(1);
        ScoredProduct result = response.getResults(0);
        assertThat(result.getProductId()).isEqualTo("42");
        assertThat(result.getProductName()).isEqualTo("queen platform bed");
        assertThat(result.getScore()).isEqualTo(0.87);
        assertThat(result.getAverageRating()).isEqualTo(4.5);
        assertThat(result.getRatingCount()).isEqualTo(200);
    }

    @AfterEach
    void awaitTermination() throws InterruptedException {
        server.awaitTermination(1, TimeUnit.SECONDS);
    }
}
