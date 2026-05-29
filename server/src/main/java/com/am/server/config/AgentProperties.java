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
     * 默认 120，与 2min 上报间隔一致（旧 10s tick 时代为 30）。
     */
    private long perReportCapSeconds = 120L;
}
