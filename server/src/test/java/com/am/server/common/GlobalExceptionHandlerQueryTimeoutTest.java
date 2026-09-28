package com.am.server.common;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerQueryTimeoutTest {

    @Test
    void recognisesMySqlMaxExecutionTimeAbortInCauseChain() {
        SQLException mysql = new SQLException("Query execution was interrupted, maximum statement execution time exceeded",
                "HY000", 3024);
        RuntimeException wrapped = new RuntimeException("could not execute query", new RuntimeException(mysql));
        assertThat(GlobalExceptionHandler.isQueryTimeout(wrapped)).isTrue();
        assertThat(GlobalExceptionHandler.isQueryTimeout(new RuntimeException(new SQLTimeoutException("t")))).isTrue();
    }

    @Test
    void otherSqlErrorsAreNotTimeouts() {
        SQLException lockWait = new SQLException("Lock wait timeout exceeded", "40001", 1205);
        assertThat(GlobalExceptionHandler.isQueryTimeout(new RuntimeException(lockWait))).isFalse();
        assertThat(GlobalExceptionHandler.isQueryTimeout(new IllegalStateException("x"))).isFalse();
    }
}
