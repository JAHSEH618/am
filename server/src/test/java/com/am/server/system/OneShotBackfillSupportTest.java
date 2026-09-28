package com.am.server.system;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OneShotBackfillSupportTest {

    @Test
    void rangesCoverMinToMaxInclusiveWithHalfOpenSteps() {
        List<long[]> r = OneShotBackfillSupport.ranges(1, 10, 4);
        assertThat(r).hasSize(3);
        assertThat(r.get(0)).containsExactly(1, 5);
        assertThat(r.get(1)).containsExactly(5, 9);
        assertThat(r.get(2)).containsExactly(9, 13);
    }

    @Test
    void singleRowTableYieldsOneRange() {
        assertThat(OneShotBackfillSupport.ranges(42, 42, 2000)).hasSize(1);
        assertThat(OneShotBackfillSupport.ranges(42, 42, 2000).get(0)).containsExactly(42, 2042);
    }

    @Test
    void rejectsNonPositiveStep() {
        assertThatThrownBy(() -> OneShotBackfillSupport.ranges(1, 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
