package com.am.server.agent.ingest;

import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.web.sse.SseHub;
import org.springframework.stereotype.Service;

/**
 * HKUDS OpenHarness 的入库服务（type_code = "openharness"）。
 * <p>
 * OpenHarness 把整个会话写成单个 JSON 文件，文件 mtime 即 last_activity；
 * Agent 端按文件级解析（无增量）；活跃窗口与默认 2min 上报对齐（见 activity-window-seconds）。
 *
 * gz
 */
@Service
public class OpenHarnessIngestService extends AbstractAiSessionIngestService {

    public static final String TARGET_TYPE = "openharness";

    public OpenHarnessIngestService(AiSessionRepository sessionRepository,
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
