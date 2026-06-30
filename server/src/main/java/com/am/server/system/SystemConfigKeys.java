package com.am.server.system;

/**
 * sys_config 的 key 常量集中地，避免散落字符串。
 *
 * <p>命名规范：{@code <category>.<subkey>[.<field>]}，全部小写 + 下划线/点分隔。
 * gz
 */
public final class SystemConfigKeys {

    private SystemConfigKeys() {}

    // ========== category: scheduling ==========
    // 与 DynamicScheduledTaskManager 注册的 taskId 一一对应
    public static final String SCHED_DAILY_SUMMARY_DAILY     = "scheduling.daily_summary_daily";
    public static final String SCHED_DAILY_SUMMARY_HOURLY    = "scheduling.daily_summary_hourly";
    public static final String SCHED_AI_SESSION_STALE_CLOSER = "scheduling.ai_session_stale_closer";

    /** scheduling.* 通用后缀 */
    public static final String SUFFIX_ENABLED = ".enabled";
    public static final String SUFFIX_CRON    = ".cron";

    // ========== category: judge ==========
    public static final String JUDGE_A_PROVIDER   = "judge.a.provider";
    public static final String JUDGE_A_ENDPOINT   = "judge.a.endpoint";
    public static final String JUDGE_A_API_KEY    = "judge.a.api_key";
    public static final String JUDGE_A_MODEL      = "judge.a.model";
    public static final String JUDGE_A_TIMEOUT_MS = "judge.a.timeout_ms";

    public static final String JUDGE_B_PROVIDER   = "judge.b.provider";
    public static final String JUDGE_B_ENDPOINT   = "judge.b.endpoint";
    public static final String JUDGE_B_API_KEY    = "judge.b.api_key";
    public static final String JUDGE_B_MODEL      = "judge.b.model";
    public static final String JUDGE_B_TIMEOUT_MS = "judge.b.timeout_ms";

    // ========== category: insight ==========
    public static final String INSIGHT_MAX_LLM_CALLS_PER_REPORT  = "insight.max_llm_calls_per_report";
    public static final String INSIGHT_REAUDIT_MESSAGE_THRESHOLD = "insight.reaudit_message_threshold";
    public static final String INSIGHT_AUDIT_CONCURRENCY         = "insight.audit_concurrency";
    public static final String INSIGHT_RUBRIC_VERSION            = "insight.rubric_version";
    public static final String INSIGHT_AUDIT_VERSION             = "insight.audit_version";
    /** 后台洞察审计扫描器总开关；关=不自动审计，仅报告时审。UI 改后热生效不重启。 */
    public static final String INSIGHT_AUDIT_SCAN_ENABLED        = "insight.audit_scan_enabled";
    /** LLM 外发脱敏开关；开=拼 Judge prompt 前对密钥/令牌打码。默认开。 */
    public static final String INSIGHT_REDACT_ENABLED            = "insight.redact_enabled";
    /**
     * Rubric YAML 全文，作为 prompt 头部直接拼接给 LLM。
     * 大文本字段（几 KB ~ 几十 KB），存 sys_config.config_value（MEDIUMTEXT），
     * 由 {@link com.am.server.insight.audit.RubricLoader} 启动时 seed +
     * 监听 {@link com.am.server.system.SystemConfigChangedEvent} 热重载。
     * 变更后建议同步 bump {@link #INSIGHT_RUBRIC_VERSION} 做版本标记；<b>不会</b>自动触发历史会话重审。
     */
    public static final String INSIGHT_RUBRIC_YAML               = "insight.rubric_yaml";

    // ========== category: capture ==========
    public static final String CAPTURE_MAX_TEXT_BYTES_PER_PART  = "capture.max_text_bytes_per_part";
    public static final String CAPTURE_MAX_BLOB_BYTES_PER_PART  = "capture.max_blob_bytes_per_part";
    public static final String CAPTURE_MAX_BLOBS_PER_MESSAGE    = "capture.max_blobs_per_message";
    public static final String CAPTURE_MAX_PARTS_PER_MESSAGE    = "capture.max_parts_per_message";
    public static final String CAPTURE_INLINE_BLOB_MAX_BYTES    = "capture.inline_blob_max_bytes";
    public static final String CAPTURE_AUDIT_MESSAGE_MAX_CHARS  = "capture.audit_message_max_chars";

    // ========== category: auth ==========
    public static final String AUTH_USERNAME    = "auth.username";
    public static final String AUTH_PASSWORD    = "auth.password";
    public static final String AUTH_ADMIN_TOKEN = "auth.admin_token";

    // ========== category: install ==========
    /** 安装端点预共享令牌；非空则 /install/** 需带 ?t= 或 X-Install-Token，空=不启用。 */
    public static final String INSTALL_TOKEN    = "install.token";

    // ========== category: notifications ==========
    /** 离线提醒邮件发件人地址；留空 = 功能不发信（连同 spring.mail.* 一起视为"未配置"）。 */
    public static final String NOTIF_OFFLINE_EMAIL_FROM      = "notifications.offline_email.from";
    /** 设备离线超过几小时才发提醒邮件（避免给下班正常关机的机器发信）。 */
    public static final String NOTIF_OFFLINE_THRESHOLD_HOURS = "notifications.offline_email.threshold_hours";
    /** 同一设备两封提醒邮件的最小间隔小时数（默认 2，即"每 2 小时提醒一次"）。 */
    public static final String NOTIF_OFFLINE_DEDUP_HOURS     = "notifications.offline_email.dedup_hours";
    /** 工作时段起始小时（含）；只在 [start, end) 内发信，避免下班 / 夜间打扰。 */
    public static final String NOTIF_OFFLINE_WORK_HOUR_START = "notifications.offline_email.work_hour_start";
    /** 工作时段结束小时（不含）。 */
    public static final String NOTIF_OFFLINE_WORK_HOUR_END   = "notifications.offline_email.work_hour_end";

    // ========== category: console ==========
    /**
     * 管理控制台 IP 白名单（逗号分隔，支持精确 IP 或前缀如 10.0.，留空=放行所有）。
     * 非空时仅这些来源可访问 /console/**、/api/v1/admin/**、/api/v1/dashboard/**。
     */
    public static final String CONSOLE_IP_ALLOWLIST = "console.ip_allowlist";

    // ========== 分组常量 ==========
    public static final String CAT_AGENTS     = "agents";
    public static final String CAT_SCHEDULING = "scheduling";
    public static final String CAT_JUDGE      = "judge";
    public static final String CAT_INSIGHT    = "insight";
    public static final String CAT_CAPTURE    = "capture";
    public static final String CAT_AUTH       = "auth";
    public static final String CAT_INSTALL    = "install";
    public static final String CAT_CONSOLE    = "console";
    public static final String CAT_NOTIFICATIONS = "notifications";
}
