package com.am.server.agent.ingest;

import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionEvent;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionEventType;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.web.sse.SseHub;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractAiSessionIngestServiceSourceRefTest {

    private final AiSessionEventRepository eventRepository = mock(AiSessionEventRepository.class);

    private static class TestIngest extends AbstractAiSessionIngestService {
        TestIngest(AiSessionEventRepository eventRepository) {
            super(mock(AiSessionRepository.class), eventRepository,
                    mock(AiSessionMessageRepository.class), mock(SseHub.class));
        }
        @Override
        protected String targetType() {
            return "test";
        }
    }

    private final TestIngest ingest = new TestIngest(eventRepository);

    private AiSession session() {
        AiSession s = new AiSession();
        s.setId(7L);
        s.setTargetType("test");
        s.setUserCode("U1");
        s.setStatus("running");
        return s;
    }

    @Test
    void writeEventStoresSourceRefColumnNotExtraJson() {
        when(eventRepository.save(any(AiSessionEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        Set<String> refs = new HashSet<>();

        AiSessionEvent e = ingest.writeEvent(session(), AiSessionEventType.TOKEN_DELTA, "running", null,
                5L, 3L, 0, LocalDateTime.now(), "ref-1", refs);

        assertThat(e.getSourceRef()).isEqualTo("ref-1");
        assertThat(e.getExtraJson()).as("不再写 extra_json").isNull();
        assertThat(refs).contains("ref-1");   // 标记进内存去重集
    }

    @Test
    void prefersPerItemDeltaPathUsesMaterializedCount() {
        com.am.server.agent.api.dto.MonitorSessionDto dto =
                new com.am.server.agent.api.dto.MonitorSessionDto();   // 无 activityDeltas / recentMessages
        when(eventRepository.countByAiSessionIdWithAnySourceRef(7L)).thenReturn(1L);

        assertThat(ingest.prefersPerItemDeltaPath(dto, 7L)).isTrue();
    }
}
