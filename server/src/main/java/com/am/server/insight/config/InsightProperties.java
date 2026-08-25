package com.am.server.insight.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 分析报告 v3.0 的统一配置入口，对应 application*.yml 里 {@code aiwatch.insight.*}。
 *
 * <p>双 judge 模型、调用上限、并发度、重审阈值都通过这里读出。
 * 见 {@code docs/design/employee-insight-from-ai-sessions-v1.0.md} §10.5 / §10.6。
 * gz
 */
@Component
@ConfigurationProperties(prefix = "aiwatch.insight")
@Data
public class InsightProperties {

    /**
     * Rubric YAML 语义版本（-sys_config 同步）；写入 rubric 遥测/运维，与 LLM 审计缓存
     * <b>脱钩</b>——bump 后不会自动重审历史会话，除非管理员显式置位 {@code insight_reaudit_required}。
     */
    private String rubricVersion = "v3.0";

    /**
     * 报告流水线 / 审计落库版本号，写入 analysis_report.report_version 与 ai_session_audit.audit_version；
     * 变更流水线逻辑时可 bump，同样不触发历史会话自动重审。
     */
    private String auditVersion = "v3.0";

    /** Judge A —— 主审。建议自建本地大模型（Qwen / DeepSeek 等）。 */
    private JudgeConfig judgeA = new JudgeConfig();

    /** Judge B —— 互审。建议外部 API（GPT-4o-mini / Claude 等），擅长领域与 A 不同更好。 */
    private JudgeConfig judgeB = new JudgeConfig();

    /** 单次报告生成时的 LLM 调用次数硬上限；超出后停止审计、报告标记 partial 仍出。 */
    private int maxLlmCallsPerReport = 50_000;

    /** 同一 session 审计后再增长 N 条消息触发重审。 */
    private int reauditMessageThreshold = 20;

    /**
     * 审计并发 worker 数;每个 worker 串行调双 judge。
     * 注意有效下游 LLM 网关并发:{@code parallelJudges=true}(默认)时 = 2 × 本值(判官 A/B 并发),
     * 另叠加后台扫描 2 × {@code auditBackgroundConcurrency}。调高前评估网关承载。
     */
    private int auditConcurrency = 8;

    /**
     * 是否让每会话双判官 A/B 并发调用(默认开)。关闭则回退串行(A 完再 B)——
     * 作为下游 LLM 网关限流的保护开关。并发时有效网关并发 = 2 × {@link #auditConcurrency}
     * (另叠加后台 2 × {@link #auditBackgroundConcurrency})。
     */
    private boolean parallelJudges = true;

    /** LLM 外发脱敏：拼 Judge prompt 前对密钥/令牌打码。默认开。 */
    private boolean redactEnabled = true;

    // ----- 后台表扫描审计（无第三方队列；游标见 auditScanCursorPath） -----

    /** 是否启用定时扫描 {@code ai_session} 并异步补全洞察审计。 */
    private boolean auditScanEnabled = true;

    /**
     * 扫描节拍（毫秒，上一次 tick 结束后再延迟这么多）。可用 yml：{@code aiwatch.insight.audit-scan-fixed-delay-ms}。
     */
    private long auditScanFixedDelayMs = 30_000L;

    /** 每轮从表里最多拉多少条候选 session id（有界内存）。 */
    private int auditScanBatchSize = 100;

    /**
     * 本地 JSON 游标文件；空则默认为 {@code ~/.am/audit-scan.cursor.json}。
     */
    private String auditScanCursorPath = "";

    /** RUNNING 租约（分钟），过期后可被下一轮扫描 reclaim。 */
    private int auditScanLeaseMinutes = 15;

    /**
     * FAILED 冷却（分钟）：失败的 session 在这段时间内不再被扫描器选中。
     *
     * <p>没有冷却时 FAILED 与 NONE/PENDING 同等待遇，30s 一拍原样重打——网关 429 的那一刻起，
     * 每拍 100 个 session × 双 Judge × 3 次重试 = 每 30 秒最多 600 次调用，永不收敛
     * （现网一小时打出过 3564 条 429 告警）。
     */
    private int auditFailureCooldownMinutes = 30;

    /** 后台审计线程池大小（与报告任务的 auditConcurrency 独立，避免抢爆网关）。 */
    private int auditBackgroundConcurrency = 2;

    /**
     * 单轮 tick 内 LLM 调用上限（一次 session 计 2 次）。≤0 表示不限制（仅受 batchSize 约束）。
     */
    private int auditBackgroundMaxLlmCallsPerTick = 500_000;

    /**
     * 解析 {@link #auditScanCursorPath}；blank → {@code ~/.am/audit-scan.cursor.json}。
     */
    public Path resolveAuditScanCursorPath() {
        if (auditScanCursorPath == null || auditScanCursorPath.isBlank()) {
            String home = System.getProperty("user.home", ".");
            return Paths.get(home, ".am", "audit-scan.cursor.json");
        }
        return Paths.get(auditScanCursorPath.trim());
    }

    @Data
    public static class JudgeConfig {
        /** mock / openai-compatible */
        private String provider = "mock";
        private String endpoint = "";
        private String apiKey = "";
        private String model = "mock-judge";
        private int timeoutMs = 60_000;
    }
}
