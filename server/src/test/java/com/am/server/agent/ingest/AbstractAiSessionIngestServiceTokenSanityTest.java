package com.am.server.agent.ingest;

import com.am.server.agent.api.dto.ActivityDeltaDto;
import com.am.server.agent.api.dto.ConversationMessageDto;
import com.am.server.agent.api.dto.MonitorSessionDto;
import com.am.server.agent.api.dto.MonitorSnapshotDto;
import com.am.server.agent.security.AlertService;
import com.am.server.agent.security.SignatureContext;
import com.am.server.domain.agent.AlertType;
import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionEvent;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionEventType;
import com.am.server.domain.ai.AiSessionMessage;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.web.sse.SseHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ingest 落库前的 token 合理性：会话累计不采信越界值、单条 delta 丢弃越界 / 负值、合法大值照常入库、
 * 累计从膨胀值归位不再写负向事件。默认上限：累计 20 亿 / 缓存 100 亿 / 单条 delta 5 亿。
 */
class AbstractAiSessionIngestServiceTokenSanityTest {

    private static final long SESSION_ID = 7L;
    private static final LocalDateTime T = LocalDateTime.of(2026, 9, 21, 10, 0);

    private final AiSessionRepository sessionRepository = mock(AiSessionRepository.class);
    private final AiSessionEventRepository eventRepository = mock(AiSessionEventRepository.class);
    private final AiSessionMessageRepository messageRepository = mock(AiSessionMessageRepository.class);
    private final SseHub sseHub = mock(SseHub.class);
    private final AlertService alertService = mock(AlertService.class);

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
        ingest.setTokenSanityGuard(TokenSanityGuard.of(2_000_000_000L, 10_000_000_000L, 500_000_000L, alertService));
        stored = new AiSession();
        stored.setId(SESSION_ID);
        stored.setTargetType("test");
        stored.setExternalSessionId("s1");
        stored.setUserCode("U1");
        stored.setAgentId("agent-1");
        stored.setHostHash("host-1");
        stored.setStatus("idle");
        stored.setLastActivity(T);
        stored.setInputTokens(100L);
        stored.setOutputTokens(50L);
        stored.setCacheReadTokens(30L);
        stored.setCacheCreateTokens(20L);
        when(sessionRepository.findByTargetTypeAndExternalSessionId("test", "s1")).thenReturn(Optional.of(stored));
        when(eventRepository.save(any(AiSessionEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(AiSessionMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.countByAiSessionId(SESSION_ID)).thenReturn(0);
    }

    @Test
    void oversizedSessionTotals_areNotAdopted_previousKeptAndAlertRaised() {
        // 事故形态：2.07 万亿 input / 102 亿 output
        ingest.ingest(snapshot(session(2_070_054_000_000L, 10_246_000_000L, null, null)), ctx());

        assertThat(stored.getInputTokens()).isEqualTo(100L);
        assertThat(stored.getOutputTokens()).isEqualTo(50L);
        assertThat(tokenDeltaEvents()).as("累计没变 → 快照差为 0 → 不写 TOKEN_DELTA").isEmpty();
        verify(alertService).warn(eq("agent-1"), eq("U1"), eq("host-1"), eq(AlertType.TOKEN_TAMPER), anyString());
    }

    @Test
    void exactlyAtSessionLimit_isAdopted_oneOverIsNot() {
        ingest.ingest(snapshot(session(2_000_000_000L, 2_000_000_001L, null, null)), ctx());

        assertThat(stored.getInputTokens()).as("刚好等于上限采信").isEqualTo(2_000_000_000L);
        assertThat(stored.getOutputTokens()).as("超一不采信").isEqualTo(50L);
    }

    @Test
    void negativeSessionTotals_areNotAdopted() {
        ingest.ingest(snapshot(session(-1L, -1_000L, null, null)), ctx());

        assertThat(stored.getInputTokens()).isEqualTo(100L);
        assertThat(stored.getOutputTokens()).isEqualTo(50L);
    }

    @Test
    void cacheTotals_useTheirOwnRelaxedLimit() {
        MonitorSessionDto dto = session(100L, 50L, null, null);
        dto.setCacheReadTokens(9_000_000_000L);      // > 20 亿但 < 100 亿：采信
        dto.setCacheCreateTokens(10_000_000_001L);   // 超 100 亿：不采信
        ingest.ingest(snapshot(dto), ctx());

        assertThat(stored.getCacheReadTokens()).isEqualTo(9_000_000_000L);
        assertThat(stored.getCacheCreateTokens()).isEqualTo(20L);
    }

    @Test
    void legitimateLargeTotal_isAdopted_andFallbackDeltaWritten() {
        ingest.ingest(snapshot(session(100_000_000L, 50L, null, null)), ctx());

        assertThat(stored.getInputTokens()).isEqualTo(100_000_000L);
        List<AiSessionEvent> deltas = tokenDeltaEvents();
        assertThat(deltas).hasSize(1);
        assertThat(deltas.get(0).getInputTokensDelta()).isEqualTo(99_999_900L);
        verify(alertService, never()).warn(anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void totalFallingBackFromInflatedValue_isAdopted_butWritesNoNegativeTokenDelta() {
        stored.setInputTokens(3_000_000_000L); // 历史坏值（旧客户端累加膨胀后落库）
        ingest.ingest(snapshot(session(5_000_000L, 50L, null, null)), ctx());

        assertThat(stored.getInputTokens()).as("合法的新值覆盖坏值，自愈").isEqualTo(5_000_000L);
        assertThat(tokenDeltaEvents()).as("不写负向 TOKEN_DELTA").isEmpty();
    }

    @Test
    void clientDeltas_oversizedAndNegativeDropped_normalKept() {
        ActivityDeltaDto huge = delta("d1", 600_000_000L, 10L);      // 超 5 亿
        ActivityDeltaDto atLimit = delta("d2", 500_000_000L, 1L);    // 刚好等于上限
        ActivityDeltaDto negative = delta("d3", -5L, -6L);
        ActivityDeltaDto normal = delta("d4", 1_000L, 10L);
        ingest.ingest(snapshot(session(100L, 50L, List.of(huge, atLimit, negative, normal), null)), ctx());

        List<AiSessionEvent> deltas = tokenDeltaEvents();
        assertThat(deltas).extracting(AiSessionEvent::getSourceRef).containsExactly("d1", "d2", "d4");
        // d1：input 越界被丢（记 0），output 10 合法 → 仍写一条
        assertThat(deltas.get(0).getInputTokensDelta()).isZero();
        assertThat(deltas.get(0).getOutputTokensDelta()).isEqualTo(10L);
        assertThat(deltas.get(1).getInputTokensDelta()).isEqualTo(500_000_000L);
        assertThat(deltas.get(2).getInputTokensDelta()).isEqualTo(1_000L);
        assertThat(deltas).allSatisfy(e -> {
            assertThat(e.getInputTokensDelta()).isBetween(0L, 500_000_000L);
            assertThat(e.getOutputTokensDelta()).isBetween(0L, 500_000_000L);
            assertThat(e.getTokensDelta()).isEqualTo(e.getInputTokensDelta() + e.getOutputTokensDelta());
        });
    }

    @Test
    void messageLevelTokens_areSanitizedToo() {
        ConversationMessageDto m = new ConversationMessageDto();
        m.setExternalMessageId("m1");
        m.setRole("assistant");
        m.setText("hello");
        m.setTimestamp(T);
        m.setInputTokens(2_100_000_000); // int 上限附近：远超单条 delta 5 亿
        m.setOutputTokens(-7);
        when(messageRepository.maxSequenceNoByAiSessionId(SESSION_ID)).thenReturn(0);
        ingest.ingest(snapshot(session(100L, 50L, null, List.of(m))), ctx());

        ArgumentCaptor<AiSessionMessage> saved = ArgumentCaptor.forClass(AiSessionMessage.class);
        verify(messageRepository, atLeast(1)).save(saved.capture());
        assertThat(saved.getValue().getInputTokens()).isZero();
        assertThat(saved.getValue().getOutputTokens()).isZero();
        assertThat(tokenDeltaEvents()).as("消息级异常 token 也不进事件流").isEmpty();
    }

    @Test
    void newSessionWithAbsurdTotals_startsFromZero_andWritesNoBaseline() {
        when(sessionRepository.findByTargetTypeAndExternalSessionId("test", "brand-new")).thenReturn(Optional.empty());
        MonitorSessionDto dto = session(Long.MAX_VALUE, Long.MAX_VALUE, null, null);
        dto.setSessionId("brand-new");
        ingest.ingest(snapshot(dto), ctx());

        ArgumentCaptor<AiSession> saved = ArgumentCaptor.forClass(AiSession.class);
        verify(sessionRepository, atLeast(1)).save(saved.capture());
        AiSession created = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(created.getInputTokens()).isZero();
        assertThat(created.getOutputTokens()).isZero();
        assertThat(tokenDeltaEvents()).as("不写把坏值当 baseline 的 TOKEN_DELTA").isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private List<AiSessionEvent> tokenDeltaEvents() {
        ArgumentCaptor<AiSessionEvent> events = ArgumentCaptor.forClass(AiSessionEvent.class);
        verify(eventRepository, atLeast(0)).save(events.capture());
        return events.getAllValues().stream()
                .filter(e -> AiSessionEventType.TOKEN_DELTA.name().equals(e.getEventType()))
                .toList();
    }

    private static ActivityDeltaDto delta(String ref, long in, long out) {
        ActivityDeltaDto d = new ActivityDeltaDto();
        d.setEventTime(T);
        d.setInputTokensDelta(in);
        d.setOutputTokensDelta(out);
        d.setSourceRef(ref);
        d.setSource("assistant_turn");
        return d;
    }

    private static MonitorSnapshotDto snapshot(MonitorSessionDto s) {
        MonitorSnapshotDto snap = new MonitorSnapshotDto();
        snap.setType("test");
        snap.setCapturedAt(T);
        snap.setSessions(List.of(s));
        return snap;
    }

    private static MonitorSessionDto session(Long in, Long out, List<ActivityDeltaDto> deltas,
                                             List<ConversationMessageDto> msgs) {
        MonitorSessionDto d = new MonitorSessionDto();
        d.setSessionId("s1");
        d.setStatus("idle");
        d.setLastActivity(T);
        d.setInputTokens(in);
        d.setOutputTokens(out);
        d.setActivityDeltas(deltas);
        d.setRecentMessages(msgs);
        return d;
    }

    private static SignatureContext ctx() {
        return new SignatureContext("agent-1", "U1", "host-1");
    }
}
