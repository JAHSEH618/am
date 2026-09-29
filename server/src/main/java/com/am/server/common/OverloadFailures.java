package com.am.server.common;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientConnectionException;
import java.util.Set;

/**
 * 判定一个异常是不是「服务过载 / 数据库暂时不可用」——即换个时间重试就可能成功、与请求内容无关的失败。
 *
 * <p>为什么单独成类：agent 客户端只有收到 <b>HTTP 503 / 429 / 业务码 50301</b> 才会「不把整包落 outbox、
 * 退回空闲节奏」（{@code agent/internal/apiclient} 的 {@code IsServerBusy}），其余失败一律整包落 outbox
 * 并保持快节奏重报——服务在连接池打满时恰恰会因此越重试越挂（2026-09 事故复盘）。
 * 所以凡是「过载类」失败，agent 通道上都必须映射成 503 + {@link ErrorCode#SERVER_BUSY}：
 * <ul>
 *   <li>{@code GlobalExceptionHandler}：controller / service 里抛出的（含被多层包装的）异常；</li>
 *   <li>{@code AgentSignatureFilter}：filter 层直接访问 DB（{@code findByAgentId}、nonce 占用）抛出的异常，
 *       它们绕过 {@code @RestControllerAdvice}；</li>
 *   <li>{@code GitCommitIngestService}：逐条 commit 落库时，连接不可用要冒泡而不是记进 {@code failed}。</li>
 * </ul>
 *
 * <p>两档判定（都沿 cause 链向下找，Hibernate / Spring 经常把根因包好几层）：
 * <ul>
 *   <li>{@link #isConnectionUnavailable}：<b>系统性</b>不可用——取不到连接（Hikari
 *       {@code SQLTransientConnectionException: Connection is not available}）、开不了事务、连接断开、
 *       语句 / 事务超时。这类失败下批里后面的条目会同样失败，继续循环只会让每条都白等一个 30s 连接超时。</li>
 *   <li>{@link #isOverload}：上一档 ∪ <b>并发冲突</b>——MySQL 1205 锁等待超时、1213 死锁、乐观锁冲突。
 *       单条可重试，但对整个 HTTP 请求而言同样应当 503 让客户端下个 tick 重报。</li>
 * </ul>
 *
 * <p>注意：不能把「所有 {@code DataAccessException}」都当过载——约束冲突、SQL 语法错误重试一万次也不会好，
 * 那些必须仍然是 500（让人看到 {@code log.error}）。
 * gz
 */
public final class OverloadFailures {

    /** 503 的 {@code Retry-After}（秒）。1.3.3+ agent 以自己的节奏重报，此头主要给反向代理 / 运维看。 */
    public static final String RETRY_AFTER_SECONDS = "30";

    /** 沿 cause 链最多向下走的层数：防环 + 防病态深链。 */
    private static final int MAX_CAUSE_DEPTH = 16;

    /**
     * MySQL 服务端错误码（连接 / 语句层面不可用）：1040 Too many connections、1053 Server shutdown in progress、
     * 1317 Query execution was interrupted、2006 server has gone away、2013 Lost connection during query、
     * 3024 max_execution_time 超时。
     */
    private static final Set<Integer> CONNECTION_ERROR_CODES = Set.of(1040, 1053, 1317, 2006, 2013, 3024);

    /** 1205 Lock wait timeout exceeded、1213 Deadlock found。 */
    private static final Set<Integer> CONFLICT_ERROR_CODES = Set.of(1205, 1213);

    private OverloadFailures() {
    }

    /** 系统性不可用：连接池 / 事务 / 连接断开 / 超时。 */
    public static boolean isConnectionUnavailable(Throwable e) {
        return scan(e, false);
    }

    /** {@link #isConnectionUnavailable} ∪ 并发冲突（锁等待超时 / 死锁 / 乐观锁）。 */
    public static boolean isOverload(Throwable e) {
        return scan(e, true);
    }

    private static boolean scan(Throwable e, boolean includeConflicts) {
        int depth = 0;
        for (Throwable t = e; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (isConnectionLevel(t) || (includeConflicts && isConflict(t))) {
                return true;
            }
            Throwable next = t.getCause();
            t = next == t ? null : next;
        }
        return false;
    }

    private static boolean isConnectionLevel(Throwable t) {
        if (t instanceof SQLTransientConnectionException            // Hikari: Connection is not available
                || t instanceof SQLNonTransientConnectionException  // 连接已关闭 / 拒绝连接
                || t instanceof SQLRecoverableException             // Communications link failure
                || t instanceof SQLTimeoutException                 // 含 MySQL 3024 / statement timeout
                || t instanceof CannotCreateTransactionException    // 开事务时拿不到连接
                || t instanceof CannotGetJdbcConnectionException
                || t instanceof DataAccessResourceFailureException  // Hibernate JDBCConnectionException 的翻译结果
                || t instanceof RecoverableDataAccessException
                || t instanceof QueryTimeoutException
                || t instanceof TransactionTimedOutException        // 每会话事务的 timeout 到期
                || t instanceof org.hibernate.exception.JDBCConnectionException
                || t instanceof jakarta.persistence.QueryTimeoutException) {
            return true;
        }
        if (t instanceof SQLException sql) {
            String state = sql.getSQLState();
            // SQLState 08xxx = connection exception
            return (state != null && state.startsWith("08")) || CONNECTION_ERROR_CODES.contains(sql.getErrorCode());
        }
        return false;
    }

    private static boolean isConflict(Throwable t) {
        if (t instanceof ConcurrencyFailureException                // Optimistic / Pessimistic / CannotAcquireLock / Deadlock
                || t instanceof TransientDataAccessException        // 其余「重试可能成功」的数据访问异常
                || t instanceof SQLTransactionRollbackException     // SQLState 40xxx：死锁 / 序列化失败
                || t instanceof org.hibernate.exception.LockAcquisitionException
                || t instanceof org.hibernate.exception.LockTimeoutException
                || t instanceof org.hibernate.StaleObjectStateException
                || t instanceof jakarta.persistence.LockTimeoutException
                || t instanceof jakarta.persistence.PessimisticLockException
                || t instanceof jakarta.persistence.OptimisticLockException) {
            return true;
        }
        return t instanceof SQLException sql && CONFLICT_ERROR_CODES.contains(sql.getErrorCode());
    }
}
