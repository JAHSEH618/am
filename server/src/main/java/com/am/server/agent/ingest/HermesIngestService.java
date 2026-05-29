package com.am.server.agent.ingest;

import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.web.sse.SseHub;
import org.springframework.stereotype.Service;

/**
 * Nous Research Hermes Agent 的入库服务（type_code = "hermes"）。
 * <p>
 * Hermes session 形态接近 Codex：单 turn 内可能等待多轮工具/子智能体调用，
 * 因此活跃窗口给到 90s，与 Codex 对齐。
 *
 * gz
 */
@Service
public class HermesIngestService extends AbstractAiSessionIngestService {

    public static final String TARGET_TYPE = "hermes";

    public HermesIngestService(AiSessionRepository sessionRepository,
                               AiSessionEventRepository eventRepository,
                               AiSessionMessageRepository messageRepository,
                               SseHub sseHub) {
        super(sessionRepository, eventRepository, messageRepository, sseHub);
    }

    @Override
    protected String targetType() {
        return TARGET_TYPE;
    }

    @Override
    protected long activeWindowSeconds() {
        return Math.max(360L, super.activeWindowSeconds());
    }
}
