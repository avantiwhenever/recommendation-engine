package com.avanti.recengine.recommender.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserProfileTest {

    @Test
    void topCategoryIsTheMostInteractedCategory() {
        UserProfile profile = profileWithCounts("Home", 1L, "Furniture", 3L, "Decor", 2L);

        assertThat(profile.topCategory()).contains("Furniture");
    }

    @Test
    void topCategoryTieGoesToTheFirstInteractedCategory() {
        // Matches train_neural_ranker.py: max() over an insertion-ordered dict keeps the first maximal key.
        assertThat(profileWithCounts("Home", 2L, "Furniture", 2L).topCategory()).contains("Home");
        assertThat(profileWithCounts("Furniture", 2L, "Home", 2L).topCategory()).contains("Furniture");
    }

    @Test
    void emptyProfileHasNoTopCategory() {
        assertThat(UserProfile.empty("u1").topCategory()).isEmpty();
    }

    /** Category counts in the given (first-interaction) order. */
    private static UserProfile profileWithCounts(Object... categoryThenCount) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < categoryThenCount.length; i += 2) {
            counts.put((String) categoryThenCount[i], (Long) categoryThenCount[i + 1]);
        }
        return new UserProfile("u1", Set.of(), counts);
    }
}
