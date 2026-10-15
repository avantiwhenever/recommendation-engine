package com.avanti.recengine.recommender.adapter.out.clickstream;

import com.avanti.recengine.recommender.domain.CatalogEntry;
import com.avanti.recengine.support.wands.WandsProductCsvLoader;
import com.avanti.recengine.support.wands.WandsProductRow;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads WANDS {@code product.csv} as this service's {@link CatalogEntry} domain type, keyed by product id. */
public final class WandsCatalog {

    private WandsCatalog() {
    }

    public static Map<String, CatalogEntry> load(Path productCsv) {
        Map<String, CatalogEntry> catalog = new LinkedHashMap<>();
        for (WandsProductRow row : WandsProductCsvLoader.load(productCsv)) {
            catalog.put(row.productId(), toCatalogEntry(row));
        }
        return catalog;
    }

    private static CatalogEntry toCatalogEntry(WandsProductRow row) {
        return new CatalogEntry(
                row.productId(),
                row.productName(),
                row.productClass(),
                row.categoryHierarchy(),
                row.averageRating() == null ? 0.0 : row.averageRating(),
                row.ratingCount() == null ? 0 : row.ratingCount()
        );
    }
}
