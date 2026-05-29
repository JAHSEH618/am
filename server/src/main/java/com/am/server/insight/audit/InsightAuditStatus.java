package com.am.server.insight.audit;

/**
 * {@code ai_session.insight_audit_status} 取值。
 * gz
 */
public enum InsightAuditStatus {

    /** 从未审计或尚未被后台扫描标记 */
    NONE,
    /** 预留：显式入队（当前扫描器不单独使用） */
    PENDING,
    /** 正在审计（带租约防止 RUNNING 卡死） */
    RUNNING,
    DONE,
    FAILED;

    public String code() {
        return name();
    }

    public static boolean isTerminal(String code) {
        return DONE.code().equals(code) || FAILED.code().equals(code);
    }
}
