package com.avanti.recengine.gateway.domain;

/**
 * Mirrors the GraphQL schema's {@code RecommenderStrategy} enum and the
 * generated proto {@code Strategy} enum by name, deliberately kept as its
 * own type rather than reusing either directly — domain/port/application
 * stay free of both Spring GraphQL and gRPC/protobuf imports. The gRPC
 * client adapter maps between this and the proto enum by name; if you add a
 * value here, add the matching value to {@code proto/recommender_service.proto}
 * or that mapping breaks at runtime, not compile time.
 */
public enum RecommenderStrategy {
    NONE,
    POPULARITY,
    COLLABORATIVE,
    BANDIT,
    NEURAL
}
