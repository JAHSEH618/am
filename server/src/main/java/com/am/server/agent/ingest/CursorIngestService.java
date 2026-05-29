package com.am.server.agent.ingest;

import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.web.sse.SseHub;
import org.springframework.stereotype.Service;

/**
 * Cursor 监控目标的入库服务。
 * <p>
 * 全部业务逻辑在 {@link AbstractAiSessionIngestService}；这里只声明 type_code = "cursor"。
 * 这样新增 claude / codex 的接入零拷贝。
 *
 * 对应设计文档 v1.3 §4.2 流水定义、§5.2 ~ §5.4 表设计
 * gz
 */
@Service
public class CursorIngestService extends AbstractAiSessionIngestService {

    public static final String TARGET_TYPE = "cursor";

    public CursorIngestService(AiSessionRepository sessionRepository,
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
