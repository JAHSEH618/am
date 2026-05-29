package com.am.server.aggregator;

import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.domain.ai.AiSessionStatus;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import com.am.server.system.scheduling.ScheduledTaskDefinition;
import com.am.server.web.dto.AiSessionDto;
import com.am.server.web.sse.SseHub;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 卡僵 AI 会话兜底关闭器（v2.8）
 *
 * <p>背景：dashboard / 会话列表 / online 表 v2.8 起统一以"DB 里 status 字段"为
 * 单一真相源，不再做 last_activity 时间窗的二次降级。这意味着如果客户端崩了 / 断网了 /
 * 用户切走了不再上报，DB 里 status 会一直停在 running/writing 永远不归 idle。
 *
 * <p>本任务每 60s 跑一次，扫描 {@code status != idle AND last_activity < now - 5min}
 * 的会话，把 status UPDATE 成 idle、current_tool 清空，并通过 SSE 推送 session_changed
 * 让前端 dashboard / 列表立即同步。这样：
 * <ul>
 *   <li>DB 始终是真相</li>
 *   <li>cursor 思考间隙（&lt; 5 分钟）保留 status，不会瞬间归零</li>
 *   <li>真卡僵会话最迟 5–6 分钟内被识别</li>
 * </ul>
 *
 * gz
 */
@Component
@RequiredArgsConstructor
public class AiSessionStaleCloser {

    private static final Logger log = LoggerFactory.getLogger(AiSessionStaleCloser.class);

    /**
     * 多久没有活动视为"卡僵"——必须比 reporter 默认 tick (2min) 大很多，
     * 但又要短到让用户感受不到延迟。5 分钟覆盖 cursor 模型生成 + 用户思考间隙的常见上限。
     */
    private static final long STALE_AFTER_SECONDS = 300;

    /** 单次扫描的处理上限：保护 DB（极端积压时分批处理）。 */
    private static final int MAX_BATCH = 500;

    private final AiSessionRepository sessionRepository;
    private final SseHub sseHub;
    private final EmployeeDisplayService employeeDisplayService;
    private final DynamicScheduledTaskManager scheduledTaskManager;

    public static final String TASK_CODE = "ai_session_stale_closer";

    @PostConstruct
    public void registerDynamicTask() {
        // v2.10：原 fixedDelay 60s 改为 cron 每分钟一次（0 */1 * * * * 等价"每分钟 0 秒触发"）。
        // 节奏一致；语义差异：cron 是按时钟，fixedDelay 是按上次结束 → cron 略稳定但若任务耗时 > 1min
        // 会跳过下一拍，本任务批量上限 500，极端场景仍是秒级，不存在跳拍风险。
        scheduledTaskManager.register(
                new ScheduledTaskDefinition(
                        TASK_CODE,
                        "卡僵会话兜底关闭",
                        ScheduledTaskDefinition.CATEGORY_BUSINESS,
                        "0 * * * * *",
                        true,
                        true,
                        true,
                        "每分钟扫一次 status != idle 且 last_activity 已超过 5 分钟的 ai_session，强制 IDLE 并 SSE 推送。",
                        null),
                this::closeStale);
    }

    @Transactional
    public void closeStale() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(STALE_AFTER_SECONDS);
        List<AiSession> stale = sessionRepository.findStaleNonIdle(cutoff);
        if (stale.isEmpty()) {
            return;
        }

        int processed = 0;
        for (AiSession s : stale) {
            if (processed++ >= MAX_BATCH) break;
            s.setStatus(AiSessionStatus.IDLE.code());
            s.setCurrentTool(null);
        }
        sessionRepository.saveAll(stale.subList(0, Math.min(stale.size(), processed)));

        // 推 SSE，让 dashboard 立即同步（不等 polling）
        for (int i = 0; i < Math.min(stale.size(), processed); i++) {
            AiSession s = stale.get(i);
            AiSessionDto dto = AiSessionDto.of(s);
            dto.setUserDisplay(employeeDisplayService.displayOf(dto.getUserCode()));
            sseHub.publish("session_changed", dto);
        }

        log.info("ai_session stale closer: closed {} sessions (cutoff={}, total found={})",
                Math.min(stale.size(), processed), cutoff, stale.size());
    }
}
