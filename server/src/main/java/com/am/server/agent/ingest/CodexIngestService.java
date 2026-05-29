package com.am.server.agent.ingest;

import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.web.sse.SseHub;
import org.springframework.stereotype.Service;

/**
 * OpenAI Codex CLI 监控目标的入库服务（type_code = "codex"）。
 * <p>
 * Codex 单次 turn 跨度可达数分钟（function_call → function_call_output 之间会等待 sandbox 执行），
 * 因此活跃窗口给到 90s。
 *
 * gz
 */
@Service
public class CodexIngestService extends AbstractAiSessionIngestService {

    public static final String TARGET_TYPE = "codex";

    public CodexIngestService(AiSessionRepository sessionRepository,
                              AiSessionEventRepository eventRepository,
                              AiSessionMessageRepository messageRepository,
                              SseHub sseHub) {
        super(sessionRepository, eventRepository, messageRepository, sseHub);
    }

    @Override
    protected String targetType() {
        return TARGET_TYPE;
    }

    /** Codex 单 turn 跨度可达数分钟，窗口略高于默认 300s。 */
    @Override
    protected long activeWindowSeconds() {
        return Math.max(360L, super.activeWindowSeconds());
    }
}
