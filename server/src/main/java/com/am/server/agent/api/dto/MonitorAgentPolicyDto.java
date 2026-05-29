package com.am.server.agent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 下发给 aiwatchd 的采集白名单策略：与后台「活跃 Agent」（monitor_target.enabled）对齐。
 *
 * <p>老服务端不返回 monitor_policy → 客户端按「未下发」处理视为全开；
 * enabled_monitor_types 显式为空数组时表示服务端全部禁用会话类 Provider。
 * gz
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitorAgentPolicyDto {

    /**
     * 当前允许采集上报的监控目标类型（小写），与 monitor_target.type_code 一致；
     * 通常为 cursor / claude / codex / hermes / openclaw / openharness。
     */
    private List<String> enabledMonitorTypes;

    /**
     * 服务端策略代数；任一 enabled 集合变化时递增。客户端可按版本判断是否需落盘。
     */
    private long version;

    /**
     * 建议客户端视作「策略新鲜度窗口」毫秒数（当前固定 300_000）。
     */
    private long ttlMs;
}
