package com.am.server.agent.ingest;

import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLTimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 每会话事务超时（{@code aiwatch.agent.ingest-session-timeout-seconds}）触发后，异常在到达 controller 之前
 * 会是几种<b>形态各异</b>的类型，这里统一识别并归一成 {@link TransactionTimedOutException}，
 * 让上层（{@code GlobalExceptionHandler} → HTTP 503 + 50301）只需认一个类型。
 *
 * <p>实测（Spring 6.1 + Hibernate 6.4 + HikariCP 5.0 + MySQL Connector/J 8.3，见
 * {@code SessionTxTimeoutSemanticsTest}）{@code TransactionTemplate.setTimeout(n)} 的表现：
 * <ul>
 *   <li>Hibernate 把「剩余整秒数」设成每条语句的 query timeout；<b>剩余不足 1 秒（截断为 0）之后发出的语句立刻抛</b>
 *       {@code org.hibernate.TransactionException("transaction timeout expired")}——事务的有效预算约为
 *       {@code timeout-1} 秒；经 Spring Data 仓库代理被翻译成 {@code JpaSystemException(cause = TransactionException)}，
 *       直接走 EntityManager 时保持原样；</li>
 *   <li>提交时（flush / commit）已过截止时间同样抛 {@code JpaSystemException("transaction timeout expired")}；</li>
 *   <li>正在执行中的慢语句被 {@code setQueryTimeout(剩余秒)} 中止：{@code MySQLTimeoutException} →
 *       {@code jakarta.persistence.QueryTimeoutException}（仓库代理下为
 *       {@code org.springframework.dao.QueryTimeoutException}）。<b>但随后 HikariCP 把
 *       {@code SQLTimeoutException} 视为连接损坏并关闭它，事务回滚就会失败</b>——{@code TransactionTemplate}
 *       用回滚异常 {@code JpaSystemException("Unable to rollback against JDBC Connection")} 盖掉应用异常
 *       （只在 ERROR 日志里留一句 "Application exception overridden by rollback exception"），
 *       上层于是只看到一个与超时毫无关系的异常。{@link #inTransaction} 负责把被盖掉的超时找回来。</li>
 * </ul>
 * 以上都<b>不是</b> Spring 的 {@code TransactionTimedOutException}（那是 JDBC/DataSource 事务管理器的形态）。
 */
public final class IngestTimeouts {

    private static final String HIBERNATE_TX_TIMEOUT_MESSAGE = "transaction timeout expired";

    private IngestTimeouts() {
    }

    /** cause 链上是否存在「事务 / 语句超时」的任一形态。 */
    public static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof TransactionTimedOutException
                    || t instanceof QueryTimeoutException
                    || t instanceof jakarta.persistence.QueryTimeoutException
                    || t instanceof SQLTimeoutException) {
                return true;
            }
            if (t instanceof org.hibernate.TransactionException
                    && t.getMessage() != null && t.getMessage().contains(HIBERNATE_TX_TIMEOUT_MESSAGE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 超时 → {@link TransactionTimedOutException}（原异常挂在 cause 上）；已经是该类型或不是超时则原样返回。
     * 绝不吞掉：调用方必须 {@code throw} 返回值。
     */
    public static RuntimeException normalize(RuntimeException e, String context) {
        if (e instanceof TransactionTimedOutException || !isTimeout(e)) {
            return e;
        }
        return new TransactionTimedOutException("session ingest timed out: " + context, e);
    }

    /**
     * 在 {@code template} 的事务里执行 {@code body}，超时异常不被回滚失败盖掉、且归一成
     * {@link TransactionTimedOutException}；其它异常原样抛出（包括乐观锁，供外层重试环识别）。
     */
    public static <T> T inTransaction(TransactionTemplate template, Supplier<T> body, String context) {
        AtomicReference<RuntimeException> applicationFailure = new AtomicReference<>();
        try {
            return template.execute(status -> {
                try {
                    return body.get();
                } catch (RuntimeException e) {
                    applicationFailure.set(e);
                    throw e;
                }
            });
        } catch (RuntimeException thrown) {
            RuntimeException original = applicationFailure.get();
            if (original != null && original != thrown && isTimeout(original)) {
                // 回滚失败（连接已被 Hikari 关闭）把超时盖掉了：以超时为准，回滚失败作为 suppressed 保留
                original.addSuppressed(thrown);
                throw normalize(original, context);
            }
            throw normalize(thrown, context);
        }
    }
}
