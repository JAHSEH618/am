package com.am.server.insight.config;

import com.am.server.insight.config.InsightProperties.JudgeConfig;
import com.am.server.system.SystemConfigChangedEvent;
import com.am.server.system.SystemConfigKeys;
import com.am.server.system.SystemConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 在 {@link InsightProperties}（yml 默认值）和 sys_config 之间做双向同步。
 *
 * <p>方向：
 * <ul>
 *   <li><b>启动 seed</b>：把 yml 默认值（包括 apiKey 等敏感字段）灌进 sys_config（仅当 key 缺失）。
 *       首次部署时这一步让 sys_config 即时可用。</li>
 *   <li><b>启动 load</b>：seed 完后立刻反向把 sys_config 当前值刷回 InsightProperties 内存字段。
 *       生产环境如果管理员之前在 UI 改过 endpoint / model / key，重启后仍然走 sys_config 的真值，
 *       不会被 yml 默认覆盖。</li>
 *   <li><b>运行时事件</b>：监听 {@link SystemConfigChangedEvent}，对应 key 变更时即时刷回字段。
 *       所有持有 {@link InsightProperties} 引用的调用方（DualJudgeService / AnalysisJobRunner 等）
 *       下一次方法调用就能拿到新值，无需任何代码改动。</li>
 * </ul>
 *
 * <p>设计取舍：用独立 Bean 而不是把同步逻辑塞进 InsightProperties，是为了
 * 避免 InsightProperties → SystemConfigService 的循环依赖（SystemConfigService
 * 不依赖 InsightProperties，但 Spring 注入顺序可能出问题）。
 * gz
 */
@Component
@RequiredArgsConstructor
public class InsightConfigSyncer {

    private static final Logger log = LoggerFactory.getLogger(InsightConfigSyncer.class);

    private final InsightProperties properties;
    private final SystemConfigService configService;

    @PostConstruct
    public void init() {
        seedDefaults();
        loadFromConfig();
        log.info("InsightConfig synced from sys_config: judgeA[provider={} model={} timeoutMs={}] "
                        + "judgeB[provider={} model={} timeoutMs={}] maxLlmCalls={} reauditThreshold={} concurrency={}",
                properties.getJudgeA().getProvider(), properties.getJudgeA().getModel(),
                properties.getJudgeA().getTimeoutMs(),
                properties.getJudgeB().getProvider(), properties.getJudgeB().getModel(),
                properties.getJudgeB().getTimeoutMs(),
                properties.getMaxLlmCallsPerReport(), properties.getReauditMessageThreshold(),
                properties.getAuditConcurrency());
    }

    /** 把 application.yml 解析出来的 InsightProperties 字段值 seed 到 sys_config（仅缺失时）。 */
    private void seedDefaults() {
        // Judge A
        JudgeConfig a = properties.getJudgeA();
        configService.seedIfAbsent(SystemConfigKeys.JUDGE_A_PROVIDER, a.getProvider(), "string",
                SystemConfigKeys.CAT_JUDGE, false, "Judge A · provider 标识（mock / openai-compatible）");
        configService.seedIfAbsent(SystemConfigKeys.JUDGE_A_ENDPOINT, nullSafe(a.getEndpoint()), "string",
                SystemConfigKeys.CAT_JUDGE, false, "Judge A · 模型 API 基础 URL，OpenAI 兼容协议");
        configService.seedIfAbsent(SystemConfigKeys.JUDGE_A_API_KEY, nullSafe(a.getApiKey()), "string",
                SystemConfigKeys.CAT_JUDGE, true,  "Judge A · API Key（敏感字段，UI 仅显示掩码）");
        configService.seedIfAbsent(SystemConfigKeys.JUDGE_A_MODEL, nullSafe(a.getModel()), "string",
                SystemConfigKeys.CAT_JUDGE, false, "Judge A · 模型名（如 deepseek/deepseek-v4-pro）");
        configService.seedIfAbsent(SystemConfigKeys.JUDGE_A_TIMEOUT_MS, String.valueOf(a.getTimeoutMs()), "integer",
                SystemConfigKeys.CAT_JUDGE, false, "Judge A · 单次请求超时（毫秒）");

        // Judge B
        JudgeConfig b = properties.getJudgeB();
        configService.seedIfAbsent(SystemConfigKeys.JUDGE_B_PROVIDER, b.getProvider(), "string",
                SystemConfigKeys.CAT_JUDGE, false, "Judge B · provider 标识");
        configService.seedIfAbsent(SystemConfigKeys.JUDGE_B_ENDPOINT, nullSafe(b.getEndpoint()), "string",
                SystemConfigKeys.CAT_JUDGE, false, "Judge B · 模型 API 基础 URL");
        configService.seedIfAbsent(SystemConfigKeys.JUDGE_B_API_KEY, nullSafe(b.getApiKey()), "string",
                SystemConfigKeys.CAT_JUDGE, true,  "Judge B · API Key（敏感字段，UI 仅显示掩码）");
        configService.seedIfAbsent(SystemConfigKeys.JUDGE_B_MODEL, nullSafe(b.getModel()), "string",
                SystemConfigKeys.CAT_JUDGE, false, "Judge B · 模型名");
        configService.seedIfAbsent(SystemConfigKeys.JUDGE_B_TIMEOUT_MS, String.valueOf(b.getTimeoutMs()), "integer",
                SystemConfigKeys.CAT_JUDGE, false, "Judge B · 单次请求超时（毫秒）");

        // insight 参数
        configService.seedIfAbsent(SystemConfigKeys.INSIGHT_MAX_LLM_CALLS_PER_REPORT,
                String.valueOf(properties.getMaxLlmCallsPerReport()), "integer",
                SystemConfigKeys.CAT_INSIGHT, false,
                "单次报告 LLM 调用上限（双 judge 计 2 次）；超出后停止审计，报告 partial 仍出");
        configService.seedIfAbsent(SystemConfigKeys.INSIGHT_REAUDIT_MESSAGE_THRESHOLD,
                String.valueOf(properties.getReauditMessageThreshold()), "integer",
                SystemConfigKeys.CAT_INSIGHT, false,
                "已审计会话再增长 N 条消息触发重审");
        configService.seedIfAbsent(SystemConfigKeys.INSIGHT_AUDIT_CONCURRENCY,
                String.valueOf(properties.getAuditConcurrency()), "integer",
                SystemConfigKeys.CAT_INSIGHT, false,
                "审计 worker 并发数；外网网关建议 ≤ 4，自建本地模型可大");
        configService.seedIfAbsent(SystemConfigKeys.INSIGHT_RUBRIC_VERSION,
                nullSafe(properties.getRubricVersion()), "string",
                SystemConfigKeys.CAT_INSIGHT, false,
                "Rubric YAML 语义版本（运维标记；不自动重审历史会话）");
        configService.seedIfAbsent(SystemConfigKeys.INSIGHT_AUDIT_VERSION,
                nullSafe(properties.getAuditVersion()), "string",
                SystemConfigKeys.CAT_INSIGHT, false,
                "报告流水线版本号（写入 ai_session_audit；不自动重审历史会话）");
        configService.seedIfAbsent(SystemConfigKeys.INSIGHT_AUDIT_SCAN_ENABLED,
                String.valueOf(properties.isAuditScanEnabled()), "boolean",
                SystemConfigKeys.CAT_INSIGHT, false,
                "后台洞察审计扫描器总开关；关=不自动审计，仅报告时审。改后热生效不重启");
        configService.seedIfAbsent(SystemConfigKeys.INSIGHT_REDACT_ENABLED,
                String.valueOf(properties.isRedactEnabled()), "boolean",
                SystemConfigKeys.CAT_INSIGHT, false,
                "LLM 外发脱敏开关；开=拼 Judge prompt 前对密钥/令牌打码");
    }

    /** 反向：把 sys_config 当前值刷回 InsightProperties 内存字段。 */
    private void loadFromConfig() {
        JudgeConfig a = properties.getJudgeA();
        a.setProvider(configService.getString(SystemConfigKeys.JUDGE_A_PROVIDER, a.getProvider()));
        a.setEndpoint(configService.getString(SystemConfigKeys.JUDGE_A_ENDPOINT, a.getEndpoint()));
        a.setApiKey(configService.getString(SystemConfigKeys.JUDGE_A_API_KEY, a.getApiKey()));
        a.setModel(configService.getString(SystemConfigKeys.JUDGE_A_MODEL, a.getModel()));
        a.setTimeoutMs(configService.getInt(SystemConfigKeys.JUDGE_A_TIMEOUT_MS, a.getTimeoutMs()));

        JudgeConfig b = properties.getJudgeB();
        b.setProvider(configService.getString(SystemConfigKeys.JUDGE_B_PROVIDER, b.getProvider()));
        b.setEndpoint(configService.getString(SystemConfigKeys.JUDGE_B_ENDPOINT, b.getEndpoint()));
        b.setApiKey(configService.getString(SystemConfigKeys.JUDGE_B_API_KEY, b.getApiKey()));
        b.setModel(configService.getString(SystemConfigKeys.JUDGE_B_MODEL, b.getModel()));
        b.setTimeoutMs(configService.getInt(SystemConfigKeys.JUDGE_B_TIMEOUT_MS, b.getTimeoutMs()));

        properties.setMaxLlmCallsPerReport(configService.getInt(
                SystemConfigKeys.INSIGHT_MAX_LLM_CALLS_PER_REPORT, properties.getMaxLlmCallsPerReport()));
        properties.setReauditMessageThreshold(configService.getInt(
                SystemConfigKeys.INSIGHT_REAUDIT_MESSAGE_THRESHOLD, properties.getReauditMessageThreshold()));
        properties.setAuditConcurrency(configService.getInt(
                SystemConfigKeys.INSIGHT_AUDIT_CONCURRENCY, properties.getAuditConcurrency()));
        properties.setRubricVersion(configService.getString(
                SystemConfigKeys.INSIGHT_RUBRIC_VERSION, properties.getRubricVersion()));
        properties.setAuditVersion(configService.getString(
                SystemConfigKeys.INSIGHT_AUDIT_VERSION, properties.getAuditVersion()));
        properties.setAuditScanEnabled(configService.getBool(
                SystemConfigKeys.INSIGHT_AUDIT_SCAN_ENABLED, properties.isAuditScanEnabled()));
        properties.setRedactEnabled(configService.getBool(
                SystemConfigKeys.INSIGHT_REDACT_ENABLED, properties.isRedactEnabled()));
    }

    /** sys_config 任何 judge.* / insight.* key 变化都会 trigger 重载（粒度刷整个 group，足够小）。 */
    @EventListener
    public void onConfigChanged(SystemConfigChangedEvent ev) {
        Set<String> keys = ev.getChangedKeys();
        if (keys == null || keys.isEmpty()) return;
        boolean touched = keys.stream().anyMatch(k ->
                k.startsWith(SystemConfigKeys.CAT_JUDGE + ".")
                        || k.startsWith(SystemConfigKeys.CAT_INSIGHT + "."));
        if (touched) {
            loadFromConfig();
            log.info("InsightConfig reloaded after sys_config change (keys={})", keys);
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
