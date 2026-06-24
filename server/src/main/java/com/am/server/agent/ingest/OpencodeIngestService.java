package com.am.server.agent.ingest;

import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.web.sse.SseHub;
import org.springframework.stereotype.Service;

/**
 * sst/opencode（anomalyco/opencode）监控目标的入库服务（type_code = "opencode"）。
 * <p>
 * opencode 为终端 Agent，单 turn 内会等待多轮工具/子智能体执行，跨度可达数分钟，
 * 因此活跃窗口给到 360s，与 Codex / Hermes 对齐。
 *
 * gz
 */
@Service
public class OpencodeIngestService extends AbstractAiSessionIngestService {

    public static final String TARGET_TYPE = "opencode";

    public OpencodeIngestService(AiSessionRepository sessionRepository,
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
