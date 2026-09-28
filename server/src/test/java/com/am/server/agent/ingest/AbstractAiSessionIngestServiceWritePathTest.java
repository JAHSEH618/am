package com.am.server.agent.ingest;

import com.am.server.agent.api.dto.ConversationMessageDto;
import com.am.server.agent.api.dto.MonitorSessionDto;
import com.am.server.agent.api.dto.MonitorSnapshotDto;
import com.am.server.agent.security.SignatureContext;
import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionEvent;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessage;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.insight.domain.AiSessionAuditRepository;
import com.am.server.web.sse.SseHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每 tick × 每会话的写路径开销：没有新消息的会话不得再数 message 表；有新消息时只按 role 分组数一次，
 * NL skill 归因只从新消息所在轮起取数。
 */
class AbstractAiSessionIngestServiceWritePathTest {

    private static final long SESSION_ID = 7L;
    private static final LocalDateTime T = LocalDateTime.of(2026, 9, 28, 10, 0);

    private final AiSessionRepository sessionRepository = mock(AiSessionRepository.class);
    private final AiSessionEventRepository eventRepository = mock(AiSessionEventRepository.class);
    private final AiSessionMessageRepository messageRepository = mock(AiSessionMessageRepository.class);
    private final SseHub sseHub = mock(SseHub.class);
    private final AiSessionAuditRepository auditRepository = mock(AiSessionAuditRepository.class);

    private static class TestIngest extends AbstractAiSessionIngestService {
        TestIngest(AiSessionRepository s, AiSessionEventRepository e, AiSessionMessageRepository m, SseHub h) {
            super(s, e, m, h);
        }

        @Override
        protected String targetType() {
            return "test";
        }
    }

    private final TestIngest ingest = new TestIngest(sessionRepository, eventRepository, messageRepository, sseHub);

    private AiSession stored;

    @BeforeEach
    void setUp() {
        ingest.setSessionAuditRepository(auditRepository);
        // 上一拍按 message 表对齐后的计数：user 18 / assistant 58 / total 76；表里共 776 行（含 tool）
        stored = new AiSession();
        stored.setId(SESSION_ID);
        stored.setTargetType("test");
        stored.setExternalSessionId("s1");
        stored.setUserCode("U1");
        stored.setAgentId("agent-1");
        stored.setStatus("idle");
        stored.setLastActivity(T);
        stored.setUserMessages(18);
        stored.setAssistantMessages(58);
        stored.setTotalMessages(76);
        stored.setInputTokens(100L);
        stored.setOutputTokens(50L);
        when(sessionRepository.findByTargetTypeAndExternalSessionId("test", "s1")).thenReturn(Optional.of(stored));
        when(eventRepository.save(any(AiSessionEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(AiSessionMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.countByAiSessionId(SESSION_ID)).thenReturn(776);
    }

    @Test
    void tickWithoutNewMessages_keepsReconciledCountersAndSkipsMessageAggregates() {
        // Agent 快照（Cursor fast path 缓存）报 user 1 / assistant 38，本拍没带新消息
        ingest.ingest(snapshot(sessionDto(1, 38, null)), ctx());

        assertThat(stored.getUserMessages()).isEqualTo(18);
        assertThat(stored.getAssistantMessages()).isEqualTo(58);
        assertThat(stored.getTotalMessages()).isEqualTo(76);
        verify(messageRepository, never()).countGroupedByRoleForSession(anyLong());
        // 只剩 reported_snapshot 对齐用的那一次（idx_session_seq 覆盖的 COUNT）
        verify(messageRepository, times(1)).countByAiSessionId(SESSION_ID);
    }

    @Test
    void tickWithNewMessages_recountsOnceAndReconcilesNlSkillFromLastTurn() {
        when(messageRepository.maxSequenceNoByAiSessionId(SESSION_ID)).thenReturn(40);
        when(messageRepository.findUserSequenceNosBefore(eq(SESSION_ID), eq(41), any(Pageable.class)))
                .thenReturn(List.of(39));
        AiSessionMessage turnStart = new AiSessionMessage();
        turnStart.setRole("user");
        turnStart.setSequenceNo(39);
        when(messageRepository.findByAiSessionIdAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(SESSION_ID, 39))
                .thenReturn(List.of(turnStart));
        when(messageRepository.countGroupedByRoleForSession(SESSION_ID)).thenReturn(List.of(
                new Object[]{"user", 19L},
                new Object[]{"assistant", 59L},
                new Object[]{"tool", 700L}));

        List<ConversationMessageDto> msgs = new ArrayList<>();
        msgs.add(message("m41", "user", "继续"));
        msgs.add(message("m42", "assistant", "好的"));
        ingest.ingest(snapshot(sessionDto(19, 59, msgs)), ctx());

        assertThat(stored.getUserMessages()).isEqualTo(19);
        assertThat(stored.getAssistantMessages()).isEqualTo(59);
        assertThat(stored.getTotalMessages()).isEqualTo(78);
        verify(messageRepository, times(1)).countGroupedByRoleForSession(SESSION_ID);
        verify(messageRepository, times(1)).countByAiSessionId(SESSION_ID);
        verify(messageRepository).findUserSequenceNosBefore(eq(SESSION_ID), eq(41), any(Pageable.class));
        verify(messageRepository, never()).findByAiSessionIdOrderBySequenceNoAsc(anyLong());
    }

    @Test
    void noSseSubscribers_skipsAuditLookupAndDtoWork() {
        when(sseHub.size()).thenReturn(0);

        ingest.ingest(snapshot(sessionDto(1, 38, null)), ctx());

        verify(auditRepository, never()).findByAiSessionIdIn(any());
        verify(sseHub, never()).publish(anyString(), any());
    }

    @Test
    void withSseSubscriber_publishesSessionChangedWithAudit() {
        when(sseHub.size()).thenReturn(1);

        ingest.ingest(snapshot(sessionDto(1, 38, null)), ctx());

        verify(auditRepository).findByAiSessionIdIn(List.of(SESSION_ID));
        verify(sseHub).publish(eq("session_changed"), any());
    }

    private static MonitorSnapshotDto snapshot(MonitorSessionDto s) {
        MonitorSnapshotDto snap = new MonitorSnapshotDto();
        snap.setType("test");
        snap.setCapturedAt(T);
        snap.setSessions(List.of(s));
        return snap;
    }

    private static MonitorSessionDto sessionDto(int user, int assistant, List<ConversationMessageDto> msgs) {
        MonitorSessionDto d = new MonitorSessionDto();
        d.setSessionId("s1");
        d.setStatus("idle");
        d.setLastActivity(T);
        d.setUserMessages(user);
        d.setAssistantMessages(assistant);
        d.setInputTokens(100L);
        d.setOutputTokens(50L);
        d.setRecentMessages(msgs);
        return d;
    }

    private static ConversationMessageDto message(String extId, String role, String text) {
        ConversationMessageDto m = new ConversationMessageDto();
        m.setExternalMessageId(extId);
        m.setRole(role);
        m.setText(text);
        m.setTimestamp(T);
        return m;
    }

    private static SignatureContext ctx() {
        return new SignatureContext("agent-1", "U1", "host-1");
    }
}
