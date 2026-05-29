package com.am.server.insight.aggregate;

import com.am.server.domain.ai.AiSessionMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时对含 SKILL.md 读操作的会话做一次 NL skill 归因回填（幂等）。
 * gz
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NlSkillAttributionBackfillRunner {

    private final AiSessionMessageRepository messageRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void backfillOnStartup() {
        List<Long> sessionIds = messageRepository.findSessionIdsWithSkillMdToolReads();
        if (sessionIds.isEmpty()) {
            return;
        }
        int sessions = 0;
        int userMsgs = 0;
        for (Long sid : sessionIds) {
            int n = NlSkillAttributionSupport.reconcileSession(messageRepository, sid);
            if (n > 0) {
                sessions++;
                userMsgs += n;
            }
        }
        if (userMsgs > 0) {
            log.info("NL skill attribution backfill: {} user messages updated across {} sessions",
                    userMsgs, sessions);
        }
    }
}
