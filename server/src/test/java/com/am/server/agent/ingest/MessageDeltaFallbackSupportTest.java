package com.am.server.agent.ingest;

import com.am.server.agent.api.dto.ActivityDeltaDto;
import com.am.server.agent.api.dto.ConversationMessageDto;
import com.am.server.agent.api.dto.MonitorSessionDto;
import com.am.server.domain.ai.AiSessionEventRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageDeltaFallbackSupportTest {

    private CursorIngestService ingest(AiSessionEventRepository events) {
        return new CursorIngestService(null, events, null, null);
    }

    @Test
    void clampFallbackMessageDelta_negativeBecomesZero() {
        assertEquals(0, AbstractAiSessionIngestService.clampFallbackMessageDelta(-8));
        assertEquals(0, AbstractAiSessionIngestService.clampFallbackMessageDelta(-1));
    }

    @Test
    void clampFallbackMessageDelta_positiveUnchanged() {
        assertEquals(3, AbstractAiSessionIngestService.clampFallbackMessageDelta(3));
        assertEquals(0, AbstractAiSessionIngestService.clampFallbackMessageDelta(0));
    }

    @Test
    void prefersPerItemDeltaPath_activityDeltas() {
        AiSessionEventRepository events = mock(AiSessionEventRepository.class);
        MonitorSessionDto dto = new MonitorSessionDto();
        dto.setActivityDeltas(List.of(new ActivityDeltaDto()));
        assertTrue(ingest(events).prefersPerItemDeltaPath(dto, 188L));
    }

    @Test
    void prefersPerItemDeltaPath_recentMessages() {
        AiSessionEventRepository events = mock(AiSessionEventRepository.class);
        MonitorSessionDto dto = new MonitorSessionDto();
        dto.setRecentMessages(List.of(new ConversationMessageDto()));
        assertTrue(ingest(events).prefersPerItemDeltaPath(dto, 188L));
    }

    @Test
    void prefersPerItemDeltaPath_storedSourceRefEvents() {
        AiSessionEventRepository events = mock(AiSessionEventRepository.class);
        when(events.countByAiSessionIdWithSourceRef(188L)).thenReturn(5L);
        assertTrue(ingest(events).prefersPerItemDeltaPath(new MonitorSessionDto(), 188L));
    }

    @Test
    void prefersPerItemDeltaPath_aggregateOnlyMonitor() {
        AiSessionEventRepository events = mock(AiSessionEventRepository.class);
        when(events.countByAiSessionIdWithSourceRef(188L)).thenReturn(0L);
        MonitorSessionDto dto = new MonitorSessionDto();
        assertFalse(ingest(events).prefersPerItemDeltaPath(dto, 188L));
        assertFalse(ingest(events).prefersPerItemDeltaPath(dto, null));
    }
}
