package com.avanti.recengine.gateway.config;

import com.avanti.recengine.gateway.application.SearchOrchestrationUseCase;
import com.avanti.recengine.gateway.port.out.RecommenderPort;
import com.avanti.recengine.gateway.port.out.SearchPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The only place ports get bound to adapters — {@code @Component}-scanned
 * gRPC client adapters are injected here, so {@link SearchOrchestrationUseCase}
 * itself never sees a Spring or gRPC type.
 */
@Configuration
public class GatewayConfig {

    @Bean
    public SearchOrchestrationUseCase searchOrchestrationUseCase(SearchPort searchPort, RecommenderPort recommenderPort) {
        return new SearchOrchestrationUseCase(searchPort, recommenderPort);
    }
}
