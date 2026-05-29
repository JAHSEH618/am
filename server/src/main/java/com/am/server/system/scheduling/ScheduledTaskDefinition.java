package com.am.server.system.scheduling;

/**
 * 动态调度任务的元信息。
 *
 * <p>每个注册到 {@link DynamicScheduledTaskManager} 的任务必须提供一份定义；
 * 定义里描述了任务的展示信息、默认调度和管理员对它的可操作能力。
 *
 * @param taskCode           任务编码（业务键），用作 sys_config 子 key + URL path 变量；命名 snake_case
 * @param displayName        UI 展示名
 * @param category           "business"（业务任务，受管，UI 可见 cron / 启停 / 手动触发）
 *                           或 "infra"（基础设施，仅手动触发；cron 由代码硬编码）
 * @param defaultCron        默认 cron 表达式（6 字段 spring 风格："秒 分 时 日 月 周"）
 * @param defaultEnabled     默认是否启用
 * @param cronEditable       管理员是否允许在线编辑 cron
 * @param manualTriggerable  是否允许 UI "立即执行"
 * @param description        给管理员看的功能说明（在 UI 卡片中展示）
 * @param zoneId             cron 触发时区，{@code null} = JVM 默认。dailySummary 等业务相关任务推荐 "Asia/Shanghai"
 * gz
 */
public record ScheduledTaskDefinition(
        String taskCode,
        String displayName,
        String category,
        String defaultCron,
        boolean defaultEnabled,
        boolean cronEditable,
        boolean manualTriggerable,
        String description,
        String zoneId) {

    public static final String CATEGORY_BUSINESS = "business";
    public static final String CATEGORY_INFRA = "infra";
}
