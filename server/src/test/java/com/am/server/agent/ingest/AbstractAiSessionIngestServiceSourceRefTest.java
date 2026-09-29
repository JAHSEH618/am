package com.am.server.agent.ingest;

import com.am.server.agent.api.dto.MonitorSessionDto;
import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionEvent;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionEventType;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.system.AiSessionEventSourceRefSchemaPatches;
import com.am.server.system.SystemConfigService;
import com.am.server.system.domain.SysConfig;
import com.am.server.web.sse.SseHub;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    void writeEventTruncatesOverlongSourceRefToColumnLimit() {
        when(eventRepository.save(any(AiSessionEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        String longRef = "t".repeat(300);

        AiSessionEvent e = ingest.writeEvent(session(), AiSessionEventType.TOOL_CALL, "running", "toolX",
                0L, 0L, 0, LocalDateTime.now(), longRef, new HashSet<>());

        assertThat(e.getSourceRef()).hasSize(191);
    }

    @Test
    void prefersPerItemDeltaPathUsesMaterializedCount() {
        com.am.server.agent.api.dto.MonitorSessionDto dto =
                new com.am.server.agent.api.dto.MonitorSessionDto();   // 无 activityDeltas / recentMessages
        when(eventRepository.existsAnySourceRef(7L)).thenReturn(true);

        assertThat(ingest.prefersPerItemDeltaPath(dto, 7L)).isTrue();
    }

    private SystemConfigService configWithMarker(boolean present) {
        SystemConfigService config = mock(SystemConfigService.class);
        when(config.find(AiSessionEventSourceRefSchemaPatches.MARKER_KEY))
                .thenReturn(present ? Optional.of(new SysConfig()) : Optional.empty());
        return config;
    }

    @Test
    void withBackfillMarker_probeIsIndexOnlyAndNeverRunsTheLegacyJsonBranch() {
        ingest.setSystemConfigService(configWithMarker(true));
        when(eventRepository.existsMaterializedSourceRef(7L)).thenReturn(true);

        assertThat(ingest.prefersPerItemDeltaPath(new MonitorSessionDto(), 7L)).isTrue();

        verify(eventRepository).existsMaterializedSourceRef(7L);
        verify(eventRepository, never()).existsAnySourceRef(anyLong());
    }

    @Test
    void withoutBackfillMarker_probeStillIncludesTheLegacyJsonBranch() {
        ingest.setSystemConfigService(configWithMarker(false));
        when(eventRepository.existsAnySourceRef(7L)).thenReturn(true);

        assertThat(ingest.prefersPerItemDeltaPath(new MonitorSessionDto(), 7L)).isTrue();

        verify(eventRepository).existsAnySourceRef(7L);
        verify(eventRepository, never()).existsMaterializedSourceRef(anyLong());
    }

    @Test
    void markerIsCachedOnceSeen_soLaterTicksNeverConsultItAgain() {
        SystemConfigService config = configWithMarker(true);
        ingest.setSystemConfigService(config);

        for (int i = 0; i < 5; i++) {
            ingest.prefersPerItemDeltaPath(new MonitorSessionDto(), 7L);
        }
        ingest.loadExistingSourceRefs(7L, new MonitorSessionDto());

        verify(config, times(1)).find(AiSessionEventSourceRefSchemaPatches.MARKER_KEY);
        assertThat(ingest.sourceRefBackfillDone()).isTrue();
    }

    @Test
    void absentMarkerIsNotCached_soItSwitchesOverAsSoonAsItAppears() {
        SystemConfigService config = mock(SystemConfigService.class);
        when(config.find(AiSessionEventSourceRefSchemaPatches.MARKER_KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new SysConfig()));
        ingest.setSystemConfigService(config);

        assertThat(ingest.sourceRefBackfillDone()).isFalse();
        assertThat(ingest.sourceRefBackfillDone()).isTrue();
        assertThat(ingest.sourceRefBackfillDone()).isTrue();
        verify(config, times(2)).find(AiSessionEventSourceRefSchemaPatches.MARKER_KEY);
    }

    @Test
    void withoutSystemConfigService_isTreatedAsNotBackfilled() {
        assertThat(ingest.sourceRefBackfillDone()).isFalse();
    }
}
