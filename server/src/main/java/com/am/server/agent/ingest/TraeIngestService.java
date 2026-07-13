package com.am.server.agent.ingest;

import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.web.sse.SseHub;
import org.springframework.stereotype.Service;

/** TRAE / TRAE SOLO 监控目标的入库服务（type_code = "trae"）。 */
@Service
public class TraeIngestService extends AbstractAiSessionIngestService {
    public static final String TARGET_TYPE = "trae";

    public TraeIngestService(AiSessionRepository sessionRepository,
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
