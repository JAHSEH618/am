package com.am.server.config;

import com.am.server.common.QueryBudget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryBudgetConfigTest {

    @AfterEach
    void clear() {
        QueryBudget.clear();
    }

    @Test
    void injectsHintAfterLeadingSelect() {
        assertThat(QueryBudgetConfig.withMaxExecutionTime("select a from t", 20000))
                .isEqualTo("select /*+ MAX_EXECUTION_TIME(20000) */ a from t");
        assertThat(QueryBudgetConfig.withMaxExecutionTime("\n        SELECT m.id FROM x", 500))
                .isEqualTo("\n        SELECT /*+ MAX_EXECUTION_TIME(500) */ m.id FROM x");
    }

    @Test
    void leavesNonSelectAndLookalikesUntouched() {
        assertThat(QueryBudgetConfig.withMaxExecutionTime("update t set a=1", 1000)).isEqualTo("update t set a=1");
        assertThat(QueryBudgetConfig.withMaxExecutionTime("insert into t select 1", 1000))
                .isEqualTo("insert into t select 1");
        assertThat(QueryBudgetConfig.withMaxExecutionTime("selector()", 1000)).isEqualTo("selector()");
    }

    @Test
    void doesNotDoubleInject() {
        String once = QueryBudgetConfig.withMaxExecutionTime("select 1", 1000);
        assertThat(QueryBudgetConfig.withMaxExecutionTime(once, 1000)).isEqualTo(once);
    }

    @Test
    void inspectorOnlyActsWhenBudgetSetOnThread() {
        var inspector = new QueryBudgetConfig.MaxExecutionTimeInspector();
        assertThat(inspector.inspect("select 1")).isEqualTo("select 1");
        QueryBudget.set(20000);
        assertThat(inspector.inspect("select 1")).isEqualTo("select /*+ MAX_EXECUTION_TIME(20000) */ 1");
    }
}
