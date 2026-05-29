package com.am.server.insight.domain;

/**
 * 分析报告生命周期状态。
 *
 * <pre>
 *   pending  ───► running ───► completed
 *                     │
 *                     └──► failed
 * </pre>
 * gz
 */
public final class ReportStatus {

    public static final String PENDING = "pending";
    public static final String RUNNING = "running";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";

    private ReportStatus() {
    }
}
