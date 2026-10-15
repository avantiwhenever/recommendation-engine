package com.avanti.recengine.gateway.adapter.out.grpc;

import com.avanti.recengine.gateway.domain.Product;
import com.avanti.recengine.grpc.search.ScoredProduct;

/** Maps between proto {@link ScoredProduct} and domain {@link Product}, shared by both gRPC client adapters. */
final class ScoredProductMapper {

    private ScoredProductMapper() {
    }

    /** Proto3 has no null strings, so empty optional fields map back to {@code null}. */
    static Product toDomain(ScoredProduct p, String source) {
        return new Product(
                p.getProductId(),
                p.getProductName(),
                emptyToNull(p.getProductClass()),
                emptyToNull(p.getCategoryHierarchy()),
                p.getAverageRating(),
                p.getRatingCount(),
                p.getScore(),
                source
        );
    }

    static ScoredProduct toProto(Product p) {
        ScoredProduct.Builder builder = ScoredProduct.newBuilder()
                .setProductId(p.productId())
                .setScore(p.score())
                .setProductName(nullToEmpty(p.name()))
                .setAverageRating(p.averageRating())
                .setRatingCount(p.ratingCount());
        if (p.productClass() != null) {
            builder.setProductClass(p.productClass());
        }
        if (p.categoryHierarchy() != null) {
            builder.setCategoryHierarchy(p.categoryHierarchy());
        }
        return builder.build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }
}
