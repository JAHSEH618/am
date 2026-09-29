package com.am.server.common;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class OverloadFailuresTest {

    private static Throwable wrap(Throwable root) {
        return new RuntimeException("outer", new IllegalStateException("middle", root));
    }

    @Test
    void connectionPoolTimeoutIsConnectionUnavailableEvenWhenWrappedDeep() {
        Throwable hikari = new SQLTransientConnectionException(
                "aiwatch-cp - Connection is not available, request timed out after 30000ms");
        assertThat(OverloadFailures.isConnectionUnavailable(hikari)).isTrue();
        assertThat(OverloadFailures.isConnectionUnavailable(wrap(hikari))).isTrue();
        assertThat(OverloadFailures.isOverload(wrap(hikari))).isTrue();
        assertThat(OverloadFailures.isConnectionUnavailable(
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction", hikari))).isTrue();
    }

    @Test
    void springAndJdbcConnectionLevelFailures() {
        assertThat(OverloadFailures.isConnectionUnavailable(new CannotCreateTransactionException("x"))).isTrue();
        assertThat(OverloadFailures.isConnectionUnavailable(new CannotGetJdbcConnectionException("x"))).isTrue();
        assertThat(OverloadFailures.isConnectionUnavailable(new DataAccessResourceFailureException("x"))).isTrue();
        assertThat(OverloadFailures.isConnectionUnavailable(new QueryTimeoutException("x"))).isTrue();
        assertThat(OverloadFailures.isConnectionUnavailable(new TransactionTimedOutException("x"))).isTrue();
        assertThat(OverloadFailures.isConnectionUnavailable(new SQLTimeoutException("x"))).isTrue();
        assertThat(OverloadFailures.isConnectionUnavailable(new SQLRecoverableException("Communications link failure"))).isTrue();
        assertThat(OverloadFailures.isConnectionUnavailable(new SQLNonTransientConnectionException("closed"))).isTrue();
        assertThat(OverloadFailures.isConnectionUnavailable(new org.hibernate.exception.JDBCConnectionException("x",
                new SQLException("boom")))).isTrue();
    }

    @Test
    void mysqlErrorCodesAndSqlStates() {
        assertThat(OverloadFailures.isConnectionUnavailable(new SQLException("Too many connections", "08004", 1040))).isTrue();
        assertThat(OverloadFailures.isConnectionUnavailable(new SQLException("gone away", "HY000", 2006))).isTrue();
        assertThat(OverloadFailures.isConnectionUnavailable(new SQLException("max exec time", "HY000", 3024))).isTrue();
        assertThat(OverloadFailures.isConnectionUnavailable(new SQLException("conn", "08S01", 0))).isTrue();
    }

    @Test
    void lockWaitTimeoutDeadlockAndOptimisticLockAreOverloadButNotConnectionUnavailable() {
        Throwable[] conflicts = {
                new CannotAcquireLockException("Lock wait timeout exceeded"),
                new DeadlockLoserDataAccessException("Deadlock found", null),
                new OptimisticLockingFailureException("stale"),
                new ObjectOptimisticLockingFailureException(Object.class, 1L),
                new SQLException("Lock wait timeout exceeded; try restarting transaction", "40001", 1205),
                new SQLException("Deadlock found when trying to get lock", "40001", 1213),
                new SQLTransactionRollbackException("rollback", "40001"),
                new jakarta.persistence.LockTimeoutException("lock"),
                new jakarta.persistence.PessimisticLockException("lock"),
                new jakarta.persistence.OptimisticLockException("stale"),
        };
        for (Throwable t : conflicts) {
            assertThat(OverloadFailures.isOverload(t)).as(t.toString()).isTrue();
            assertThat(OverloadFailures.isOverload(wrap(t))).as("wrapped " + t).isTrue();
            assertThat(OverloadFailures.isConnectionUnavailable(t))
                    .as("a single-row conflict must not abort a whole batch: " + t).isFalse();
        }
    }

    @Test
    void genuineBugsAndDataErrorsAreNotOverload() {
        Throwable[] notOverload = {
                new IllegalStateException("bug"),
                new NullPointerException(),
                new DuplicateKeyException("dup"),
                new DataIntegrityViolationException("constraint"),
                new InvalidDataAccessResourceUsageException("bad sql"),
                new SQLException("Data too long for column", "22001", 1406),
                new RuntimeException("outer", new IllegalArgumentException("inner")),
        };
        for (Throwable t : notOverload) {
            assertThat(OverloadFailures.isOverload(t)).as(t.toString()).isFalse();
            assertThat(OverloadFailures.isConnectionUnavailable(t)).as(t.toString()).isFalse();
        }
        assertThat(OverloadFailures.isOverload(null)).isFalse();
    }

    @Test
    void selfReferencingCauseChainDoesNotLoop() {
        RuntimeException a = new RuntimeException("a") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(OverloadFailures.isOverload(a)).isFalse();
    }

    @Test
    void veryDeepChainsAreCutOff() {
        Throwable t = new SQLTransientConnectionException("deep");
        for (int i = 0; i < 40; i++) {
            t = new RuntimeException("w" + i, t);
        }
        // 超过 16 层的病态链不追了：宁可漏判（回 500 让人看见），也不遍历无限长的链
        assertThat(OverloadFailures.isOverload(t)).isFalse();
    }
}
