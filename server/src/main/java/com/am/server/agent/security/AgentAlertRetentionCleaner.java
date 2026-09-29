package com.am.server.agent.security;

import com.am.server.config.AgentProperties;
import com.am.server.domain.agent.AgentAlertRepository;
import com.am.server.system.SystemConfigService;
import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import com.am.server.system.scheduling.ScheduledTaskDefinition;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * {@code agent_alert} 的保留期清理。
 *
 * <p>{@link AgentSignatureFilter} 在验签阶段就会写告警，而这张表此前没有任何清理入口——与
 * {@code agent_nonce}（{@link AgentNonceCleaner}）是同一类「只增不减」问题。{@link AlertService} 的限速把增速压下来了，
 * 但不清理它依然无限增长，所以再加保留期：默认 30 天（{@code aiwatch.agent.alert-retention-days} 是种子默认值，
 * 运行期以 sys_config {@value #CONFIG_KEY_RETENTION_DAYS} 为准，UI 可热改，{@code <=0} = 不清理）。
 *
 * <p>分批删除（每批一个独立事务，见 {@link AgentAlertRepository#deleteExpiredBatch}），单次运行有批次上限，
 * 存量积压删不完留给下一拍，避免长事务顶住 undo / binlog。调度方式与 {@link AgentNonceCleaner} 相同：
 * 注册到 {@link DynamicScheduledTaskManager}（cron / 启停可在 UI 调整，可手动触发）。
 * 目前是单实例部署，没有启用 ShedLock，与 nonce 清理一致。
 * gz
 */
@Component
@RequiredArgsConstructor
public class AgentAlertRetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(AgentAlertRetentionCleaner.class);

    public static final String TASK_CODE = "agent_alert_cleanup";
    public static final String CONFIG_KEY_RETENTION_DAYS = "agent.alert_retention_days";

    /** 单批删除行数。 */
    static final int DELETE_BATCH_SIZE = 2_000;

    /** 单次运行的批次上限（20 万行）：稳态每天几千行，上限只为存量首清兜底。 */
    static final int MAX_BATCHES = 100;

    private final AgentAlertRepository alertRepository;
    private final SystemConfigService systemConfigService;
    private final DynamicScheduledTaskManager scheduledTaskManager;
    private final AgentProperties agentProperties;

    @PostConstruct
    public void registerDynamicTask() {
        systemConfigService.seedIfAbsent(CONFIG_KEY_RETENTION_DAYS,
                String.valueOf(agentProperties.getAlertRetentionDays()), "int", "agent", false,
                "agent_alert 告警保留天数；超期行由每日清理任务分批删除。0 = 不清理");
        scheduledTaskManager.register(
                new ScheduledTaskDefinition(
                        TASK_CODE,
                        "Agent 告警过期清理",
                        ScheduledTaskDefinition.CATEGORY_BUSINESS,
                        "0 30 4 * * *",
                        true,
                        true,
                        true,
                        "每天 04:30 分批删除 agent_alert 中超过保留期的行（默认 30 天，见 sys_config "
                                + CONFIG_KEY_RETENTION_DAYS + "；0 = 不清理）。验签失败等告警会持续写入，"
                                + "不清理会随时间无限增长。",
                        "Asia/Shanghai"),
                this::cleanup);
    }

    /** @return 本次删除的行数。 */
    public int cleanup() {
        int days = systemConfigService.getInt(CONFIG_KEY_RETENTION_DAYS, agentProperties.getAlertRetentionDays());
        if (days <= 0) {
            log.info("agent_alert cleanup: disabled (retention_days={})", days);
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        int deleted = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int n = alertRepository.deleteExpiredBatch(cutoff, DELETE_BATCH_SIZE);
            deleted += n;
            if (n < DELETE_BATCH_SIZE) {
                break;
            }
        }
        log.info("agent_alert cleanup done: deleted={} cutoff={} retention_days={}", deleted, cutoff, days);
        return deleted;
    }
}
