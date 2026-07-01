package com.am.server.system;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import javax.sql.DataSource;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class IdSequenceSeedOrderTest {

    @Test
    void seedRunnerHasHighestPrecedenceSoItSeedsBeforeBootBackfills() throws Exception {
        Method m = IdSequenceSeedSchemaPatches.class.getDeclaredMethod("ensureIdSequences", DataSource.class);
        Order order = m.getAnnotation(Order.class);
        assertThat(order).as("ensureIdSequences must be @Order-ed to run first").isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
