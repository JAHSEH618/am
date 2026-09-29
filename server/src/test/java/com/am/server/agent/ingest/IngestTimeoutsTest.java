package com.am.server.agent.ingest;

import org.hibernate.TransactionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class IngestTimeoutsTest {

    private static final String HIBERNATE_MSG = "transaction timeout expired";

    @Test
    void recognisesEveryFormTheTimeoutActuallyTakes() {
        // 提交时 / 仓库调用：JpaSystemException(cause = Hibernate TransactionException)——实测形态
        assertThat(IngestTimeouts.isTimeout(new JpaSystemException(new TransactionException(HIBERNATE_MSG)))).isTrue();
        // 直接走 EntityManager：原始 Hibernate TransactionException——实测形态
        assertThat(IngestTimeouts.isTimeout(new TransactionException(HIBERNATE_MSG))).isTrue();
        // 执行中的慢语句被 MySQL setQueryTimeout 中止
        assertThat(IngestTimeouts.isTimeout(new QueryTimeoutException("x", new SQLTimeoutException("t")))).isTrue();
        assertThat(IngestTimeouts.isTimeout(new RuntimeException(new SQLTimeoutException("t")))).isTrue();
        assertThat(IngestTimeouts.isTimeout(new jakarta.persistence.QueryTimeoutException())).isTrue();
        assertThat(IngestTimeouts.isTimeout(new TransactionTimedOutException("deadline"))).isTrue();
    }

    @Test
    void doesNotMistakeOtherFailuresForTimeouts() {
        assertThat(IngestTimeouts.isTimeout(new TransactionException("could not commit"))).isFalse();
        assertThat(IngestTimeouts.isTimeout(new CannotAcquireLockException("lock wait"))).isFalse();
        assertThat(IngestTimeouts.isTimeout(new IllegalStateException("boom"))).isFalse();
        assertThat(IngestTimeouts.isTimeout(new RuntimeException((Throwable) null))).isFalse();
    }

    @Test
    void normalizeWrapsIntoTransactionTimedOutExceptionKeepingTheOriginalAsCause() {
        JpaSystemException original = new JpaSystemException(new TransactionException(HIBERNATE_MSG));

        RuntimeException out = IngestTimeouts.normalize(original, "target=cursor session=s1");

        assertThat(out).isInstanceOf(TransactionTimedOutException.class);
        assertThat(out.getCause()).isSameAs(original);
        assertThat(out.getMessage()).contains("target=cursor session=s1");
    }

    @Test
    void normalizeLeavesAlreadyNormalizedAndUnrelatedExceptionsUntouched() {
        TransactionTimedOutException already = new TransactionTimedOutException("x");
        IllegalStateException other = new IllegalStateException("boom");

        assertThat(IngestTimeouts.normalize(already, "ctx")).isSameAs(already);
        assertThat(IngestTimeouts.normalize(other, "ctx")).isSameAs(other);
    }
}
