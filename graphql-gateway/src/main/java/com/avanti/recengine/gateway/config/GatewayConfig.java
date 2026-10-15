package com.avanti.recengine.gateway.config;

import com.avanti.recengine.gateway.application.SearchOrchestrationUseCase;
import com.avanti.recengine.gateway.port.out.RecommenderPort;
import com.avanti.recengine.gateway.port.out.SearchPort;
import com.avanti.recengine.grpc.recommender.RecommenderServiceGrpc;
import com.avanti.recengine.grpc.search.SearchServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

/**
 * The only place ports get bound to adapters — {@code @Component}-scanned
 * gRPC client adapters are injected here, so {@link SearchOrchestrationUseCase}
 * itself never sees a Spring or gRPC type.
 *
 * <p>Also builds the gRPC stubs those adapters wrap, one per named channel
 * under {@code spring.grpc.client.channel} in application.yml.
 */
@Configuration
public class GatewayConfig {

    @Bean
    public SearchServiceGrpc.SearchServiceBlockingStub searchServiceStub(GrpcChannelFactory channels) {
        return SearchServiceGrpc.newBlockingStub(channels.createChannel("search-service"));
    }

    @Bean
    public RecommenderServiceGrpc.RecommenderServiceBlockingStub recommenderServiceStub(GrpcChannelFactory channels) {
        return RecommenderServiceGrpc.newBlockingStub(channels.createChannel("recommender-service"));
    }

    @Bean
    public SearchOrchestrationUseCase searchOrchestrationUseCase(SearchPort searchPort, RecommenderPort recommenderPort) {
        return new SearchOrchestrationUseCase(searchPort, recommenderPort);
    }
}
