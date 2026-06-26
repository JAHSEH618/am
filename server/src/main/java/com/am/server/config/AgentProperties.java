package com.am.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code aiwatch.agent.*} — Agent 运行时与服务端判定相关的配置。
 *
 * <p>与 {@link com.am.server.agent.service.AgentRegisterService} 下发的
 * {@code report_interval_ms} 配合：默认 2min 上报时，在线窗口应为其 2～3 倍，
 * 避免 tick 偏慢或 bootstrap 首包耗时长时被大盘误判为离线。
 * gz
 */
@Data
@Component
@ConfigurationProperties(prefix = "aiwatch.agent")
public class AgentProperties {

    /**
     * 多久未收到成功上报则视为设备离线（秒）。
     * 默认 300（5min）≈ 2.5× 默认 2min 上报间隔。
     */
    private long onlineWindowSeconds = 300L;

    /**
     * ingest / work_session 判定「会话仍活跃」的 last_activity 窗口（秒）。
     * 默认 300（5min）≈ 2.5× 默认 2min 上报间隔，与客户端 ConfigureActivityTimeouts 对齐。
     */
    private long activityWindowSeconds = 300L;

    /**
     * 单次 /report 推进 work_session 在线/活跃秒数的上限（秒）。
     * 默认 120，与空闲上报间隔同量级（旧 10s tick 时代为 30）。
     */
    private long perReportCapSeconds = 120L;

    /**
     * 下发给客户端的「空闲基线」上报间隔（毫秒）。register 与每次 /report 响应都会带上，
     * 客户端据此动态调整 tick 节奏（见 {@code reporter.go} 的 mergeReportCadence）。
     *
     * <p>默认 45s（旧 120s）：把"刚开始干活 → 第一条活动出现"的冷启动最坏延迟从 ~2min 砍到 ~45s。
     * 空闲 payload 是游标增量、很小，团队规模下额外负载可忽略。ops 可经 {@code aiwatch.agent.report-interval-ms} 调。
     */
    private long reportIntervalMs = 45_000L;

    /**
     * 下发给客户端的「活跃时段」快速上报间隔（毫秒）。客户端收到响应 {@code active=true} 时切到此节奏。
     *
     * <p>默认 8s（旧 15s）：稳态下每条事件最坏延迟 15s→8s，更贴近"实时"。客户端硬下限 5s。
     */
    private long activeReportIntervalMs = 8_000L;
}
