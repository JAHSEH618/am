package com.am.server.agent.ingest;

import com.am.server.Application;
import com.am.server.domain.ai.AiSessionMessageRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 打真库（JPA + Hibernate 6.4 + MySQL）验证：给每会话 ingest 事务设的 {@code TransactionTemplate.setTimeout}
 * 到底以什么形态失败——{@link IngestTimeouts} 的识别 / 归一依据的就是这里观测到的类型，
 * 而不是凭印象写的 {@code TransactionTimedOutException}（JPA 下从来不是它）。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class SessionTxTimeoutSemanticsTest {

    @Autowired
    PlatformTransactionManager tm;
    @Autowired
    AiSessionMessageRepository repo;
    @PersistenceContext
    EntityManager em;

    private TransactionTemplate template(int timeoutSeconds) {
        TransactionTemplate t = new TransactionTemplate(tm);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        t.setTimeout(timeoutSeconds);
        return t;
    }

    @Test
    void fastTransactionWithinTheBudgetCommitsNormally() {
        Object n = template(10).execute(s -> repo.count());
        assertThat(n).isNotNull();
    }

    @Test
    void repositoryCallAfterTheDeadlineFailsAsJpaSystemExceptionAroundHibernateTimeout() {
        assertThatThrownBy(() -> template(1).execute(s -> {
            repo.count();
            sleep(1500);
            return repo.count();
        })).isInstanceOf(org.springframework.orm.jpa.JpaSystemException.class)
                .hasRootCauseInstanceOf(org.hibernate.TransactionException.class)
                .hasMessageContaining("transaction timeout expired")
                .matches(IngestTimeouts::isTimeout);
    }

    @Test
    void entityManagerCallInsideTheLastSecondFailsAsRawHibernateTransactionException() {
        // Hibernate 把「剩余整秒数」当语句超时：剩余 <1s（截断为 0）后发出的语句立刻抛
        assertThatThrownBy(() -> template(2).execute(s -> {
            sleep(1200);
            return em.createNativeQuery("select 1").getSingleResult();
        })).isInstanceOf(org.hibernate.TransactionException.class)
                .hasMessageContaining("transaction timeout expired")
                .matches(IngestTimeouts::isTimeout);
    }

    @Test
    void commitAfterTheDeadline_eitherSucceedsOrFailsAsARecognizedTimeout() {
        // Hibernate 只在「创建语句」时检查事务截止时间（JdbcCoordinatorImpl#determineRemainingTransactionTimeOutPeriod），
        // 过期后若 commit 时没有待执行语句（这里的只读事务）就直接提交成功，不会报错——对 ingest 无害：
        // 真正写库的事务在 flush / 下一条语句时都会走到上面几个用例里的已识别形态。
        // 所以契约是「不抛，或抛就必须被 IngestTimeouts 识别」，而不是「一定抛」（后者随 flush 时机 / 驱动而变）。
        try {
            template(1).execute(s -> {
                repo.count();
                sleep(1500);
                return null;
            });
        } catch (RuntimeException e) {
            assertThat(IngestTimeouts.isTimeout(e)).as("提交期失败必须是可识别的超时形态: %s", e).isTrue();
        }
    }

    @Test
    void slowStatementAbortedByMysql_isMaskedByAFailedRollbackWithAPlainTemplate() {
        // 记录现状（HikariCP 把 SQLTimeoutException 当连接损坏并关闭）：普通 TransactionTemplate 只会抛回滚失败，
        // 超时信号丢在 ERROR 日志里。若哪天升级驱动 / 连接池后此断言失败，说明 IngestTimeouts.inTransaction 的兜底可以重新评估。
        long t0 = System.currentTimeMillis();
        assertThatThrownBy(() -> template(3).execute(s -> em.createNativeQuery("select sleep(30)").getSingleResult()))
                .isInstanceOf(org.springframework.orm.jpa.JpaSystemException.class)
                .hasMessageContaining("Unable to rollback")
                .matches(e -> !IngestTimeouts.isTimeout(e));
        assertThat(System.currentTimeMillis() - t0).as("被 setQueryTimeout 中止，而不是睡满 30s").isLessThan(8_000);
    }

    @Test
    void inTransaction_recoversTheTimeoutMaskedByTheFailedRollback() {
        long t0 = System.currentTimeMillis();
        assertThatThrownBy(() -> IngestTimeouts.inTransaction(template(3),
                () -> em.createNativeQuery("select sleep(30)").getSingleResult(), "ctx"))
                .isInstanceOf(org.springframework.transaction.TransactionTimedOutException.class)
                .hasCauseInstanceOf(jakarta.persistence.QueryTimeoutException.class);
        assertThat(System.currentTimeMillis() - t0).isLessThan(8_000);
    }

    @Test
    void inTransaction_normalizesTheDeadlineFormsToo() {
        assertThatThrownBy(() -> IngestTimeouts.inTransaction(template(1), () -> {
            repo.count();
            sleep(1500);
            return repo.count();
        }, "ctx")).isInstanceOf(org.springframework.transaction.TransactionTimedOutException.class);

        long n = IngestTimeouts.inTransaction(template(10), () -> repo.count(), "ctx");
        assertThat(n).isGreaterThanOrEqualTo(0L);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
