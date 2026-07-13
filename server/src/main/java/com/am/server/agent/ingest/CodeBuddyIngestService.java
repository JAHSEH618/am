package com.am.server.agent.ingest;

import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.web.sse.SseHub;
import org.springframework.stereotype.Service;

/** 腾讯 CodeBuddy IDE 监控目标的入库服务（type_code = "codebuddy"）。 */
@Service
public class CodeBuddyIngestService extends AbstractAiSessionIngestService {
    public static final String TARGET_TYPE = "codebuddy";

    public CodeBuddyIngestService(AiSessionRepository sessionRepository,
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
