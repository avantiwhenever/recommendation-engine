package com.avanti.recengine.gateway.port.out;

import com.avanti.recengine.gateway.domain.Product;

import java.util.List;

/** Outbound port to search-service — the gateway's only source of baseline candidates. */
public interface SearchPort {
    List<Product> search(String query, int topK);

    /** {@code categoryFilter} blank/null and {@code minRating <= 0} both mean "no filter", matching search-service's own convention. */
    List<Product> search(String query, int topK, String categoryFilter, double minRating);
}
