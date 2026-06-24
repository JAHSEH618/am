package com.am.server.agent.ingest;

import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.web.sse.SseHub;
import org.springframework.stereotype.Service;

/**
 * Moonshot Kimi Code CLI 监控目标的入库服务（type_code = "kimicode"）。
 * <p>
 * Kimi Code 与 Codex 形态接近：单 turn 内可能等待多轮工具/子智能体调用，
 * 因此活跃窗口给到 360s，与 Codex / Hermes 对齐。
 *
 * gz
 */
@Service
public class KimicodeIngestService extends AbstractAiSessionIngestService {

    public static final String TARGET_TYPE = "kimicode";

    public KimicodeIngestService(AiSessionRepository sessionRepository,
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
