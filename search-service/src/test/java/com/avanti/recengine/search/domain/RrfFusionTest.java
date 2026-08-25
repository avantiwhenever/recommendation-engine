package com.avanti.recengine.search.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionTest {

    private static final Product A = new Product("a", "product a", null, null, null, null);
    private static final Product B = new Product("b", "product b", null, null, null, null);
    private static final Product C = new Product("c", "product c", null, null, null, null);

    @Test
    void productRankedFirstInBothListsFusesToTheTop() {
        List<ScoredResult> listOne = List.of(new ScoredResult(A, 0.9), new ScoredResult(B, 0.5));
        List<ScoredResult> listTwo = List.of(new ScoredResult(A, 0.8), new ScoredResult(C, 0.4));

        List<ScoredResult> fused = RrfFusion.fuse(List.of(listOne, listTwo), 60);

        assertThat(fused.get(0).product().productId()).isEqualTo("a");
    }

    @Test
    void productAppearingInOnlyOneListStillContributes() {
        List<ScoredResult> listOne = List.of(new ScoredResult(A, 0.9));
        List<ScoredResult> listTwo = List.of(new ScoredResult(B, 0.9));

        List<ScoredResult> fused = RrfFusion.fuse(List.of(listOne, listTwo), 60);

        assertThat(fused).extracting(r -> r.product().productId()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void topRankInOneListCanOutweighMidRankInBoth() {
        // "a" is #1 in listOne only; "b" is #2 in both lists.
        // RRF: score(a) = 1/(60+1) = 0.01639
        //      score(b) = 1/(60+2) + 1/(60+2) = 0.03226
        List<ScoredResult> listOne = List.of(new ScoredResult(A, 0.9), new ScoredResult(B, 0.5));
        List<ScoredResult> listTwo = List.of(new ScoredResult(C, 0.9), new ScoredResult(B, 0.5));

        List<ScoredResult> fused = RrfFusion.fuse(List.of(listOne, listTwo), 60);

        assertThat(fused.get(0).product().productId()).isEqualTo("b");
    }

    @Test
    void emptyListsFuseToEmptyResult() {
        assertThat(RrfFusion.fuse(List.of(List.of(), List.of()), 60)).isEmpty();
    }
}
