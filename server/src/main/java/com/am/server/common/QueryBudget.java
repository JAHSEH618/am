package com.am.server.common;

/**
 * 当前线程的 SELECT 执行时长上限（毫秒）。由 web 层对控制台读请求设置，
 * {@code com.am.server.config.QueryBudgetConfig} 的 StatementInspector 据此给 SELECT 加
 * MySQL {@code MAX_EXECUTION_TIME} 优化器提示。后台任务 / ingest 线程不设置，不受影响。
 * gz
 */
public final class QueryBudget {

    private static final ThreadLocal<Long> MAX_EXECUTION_MS = new ThreadLocal<>();

    private QueryBudget() {
    }

    public static void set(long maxExecutionMs) {
        MAX_EXECUTION_MS.set(maxExecutionMs);
    }

    public static void clear() {
        MAX_EXECUTION_MS.remove();
    }

    /** @return 上限毫秒；未设置返回 null */
    public static Long current() {
        return MAX_EXECUTION_MS.get();
    }
}
