package com.am.server.system;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Set;

/**
 * 配置变更广播事件。
 *
 * <p>{@link SystemConfigService} 每次成功写入 sys_config 后发布；订阅方：
 * <ul>
 *   <li>{@code DynamicScheduledTaskManager} —— scheduling.* 变更时重排 cron</li>
 *   <li>{@code InsightConfigService} —— judge.* / insight.* 变更时刷内存视图</li>
 *   <li>{@code ActiveTargetTypesProvider} —— agents.* 变更时失效白名单缓存</li>
 * </ul>
 *
 * <p>使用 {@code Set<String>} 表示一次批量更新涉及的 keys，订阅方按前缀决定要不要 react。
 * gz
 */
@Getter
@RequiredArgsConstructor
public class SystemConfigChangedEvent {

    /** 本次变更的全部 config_key */
    private final Set<String> changedKeys;

    /** 操作者（用户名 / "system"） */
    private final String operator;
}
