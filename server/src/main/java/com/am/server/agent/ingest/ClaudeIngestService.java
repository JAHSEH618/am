package com.am.server.agent.ingest;

import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.web.sse.SseHub;
import org.springframework.stereotype.Service;

/**
 * Claude Code CLI 监控目标的入库服务（type_code = "claude"）。
 * <p>
 * 活跃窗口与默认 2min 上报对齐（见 {@code aiwatch.agent.activity-window-seconds}）。
 * 其它字段处理走 {@link AbstractAiSessionIngestService} 通用流程。
 *
 * gz
 */
@Service
public class ClaudeIngestService extends AbstractAiSessionIngestService {

    public static final String TARGET_TYPE = "claude";

    public ClaudeIngestService(AiSessionRepository sessionRepository,
                               AiSessionEventRepository eventRepository,
                               AiSessionMessageRepository messageRepository,
                               SseHub sseHub) {
        super(sessionRepository, eventRepository, messageRepository, sseHub);
    }

    @Override
    protected String targetType() {
        return TARGET_TYPE;
    }
}
