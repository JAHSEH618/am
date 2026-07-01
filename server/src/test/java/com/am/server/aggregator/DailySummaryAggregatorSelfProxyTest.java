package com.am.server.aggregator;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Lazy;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class DailySummaryAggregatorSelfProxyTest {

    @Test
    void hasLazySelfProxyField() throws Exception {
        Field f = DailySummaryAggregator.class.getDeclaredField("self");
        assertThat(f.getType()).isEqualTo(DailySummaryAggregator.class);
        assertThat(f.isAnnotationPresent(Lazy.class)).isTrue();
    }
}
