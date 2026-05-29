package com.am.server.agent.ingest;

import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.web.sse.SseHub;
import org.springframework.stereotype.Service;

/**
 * OpenClaw 任务运行的入库服务（type_code = "openclaw"）。
 * <p>
 * OpenClaw 的"会话"是 task_runs 表中的单行任务，单个任务可能持续数分钟到数小时
 * （pending → running → completed/failed），活跃窗口给到 120s 以兼容慢任务。
 * <p>
 * 注意：OpenClaw 没有 messages 表，因此 user/assistant 消息计数恒为 0。
 *
 * gz
 */
@Service
public class OpenClawIngestService extends AbstractAiSessionIngestService {

    public static final String TARGET_TYPE = "openclaw";

    public OpenClawIngestService(AiSessionRepository sessionRepository,
                                 AiSessionEventRepository eventRepository,
                                 AiSessionMessageRepository messageRepository,
                                 SseHub sseHub) {
        super(sessionRepository, eventRepository, messageRepository, sseHub);
    }

    @Override
    protected String targetType() {
        return TARGET_TYPE;
    }

    /** OpenClaw 任务可持续数小时，活跃窗口单独放宽。 */
    @Override
    protected long activeWindowSeconds() {
        return Math.max(600L, super.activeWindowSeconds());
    }
}
