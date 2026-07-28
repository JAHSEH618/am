package com.am.server.agent.security;

import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import com.am.server.system.scheduling.ScheduledTaskDefinition;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 上报防重放 nonce 的过期清理。
 *
 * <p>{@link AgentSignatureFilter} 每处理一个 agent 请求就往 {@code agent_nonce} 写一行，
 * 而这张表此前没有任何清理入口——{@link NonceStoreService#deleteExpired} 写好了却零调用方，
 * 于是它从部署第一天起只增不减（生产上跑了两个月攒到 100 万行 / 276MB）。
 *
 * <p>nonce 的唯一用途是挡住 {@code ±300s} 时间戳窗口内的重放；超出窗口的请求在验签阶段
 * 就已经被时间戳检查拒掉，对应的 nonce 行再无价值。这里按 24 小时保留（窗口的 288 倍，
 * 足以覆盖任何时钟偏移），稳态下表体积就是一天的请求量。
 *
 * gz
 */
@Component
@RequiredArgsConstructor
public class AgentNonceCleaner {

    private static final Logger log = LoggerFactory.getLogger(AgentNonceCleaner.class);

    /** 保留窗口，远大于 AgentSignatureFilter 的 ±300s 时间戳窗口。 */
    private static final long RETENTION_HOURS = 24;

    public static final String TASK_CODE = "agent_nonce_cleanup";

    private final NonceStoreService nonceStoreService;
    private final DynamicScheduledTaskManager scheduledTaskManager;

    @PostConstruct
    public void registerDynamicTask() {
        scheduledTaskManager.register(
                new ScheduledTaskDefinition(
                        TASK_CODE,
                        "上报 nonce 过期清理",
                        ScheduledTaskDefinition.CATEGORY_BUSINESS,
                        "0 15 4 * * *",
                        true,
                        true,
                        true,
                        "每天 04:15 删除 agent_nonce 中 24 小时前的行。nonce 只用于 ±300s 时间戳窗口内的"
                                + "重放防护，超窗行没有保留价值；不清理会随上报量无限增长。",
                        "Asia/Shanghai"),
                this::cleanup);
    }

    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(RETENTION_HOURS);
        int deleted = nonceStoreService.deleteExpired(cutoff);
        log.info("agent_nonce cleanup done: deleted={} cutoff={}", deleted, cutoff);
    }
}
