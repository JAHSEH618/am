package com.am.server.agent.ingest;

import com.am.server.agent.api.dto.ConversationMessageDto;
import com.am.server.agent.api.dto.MonitorSessionDto;
import com.am.server.agent.api.dto.MonitorSnapshotDto;
import com.am.server.agent.security.SignatureContext;
import com.am.server.agent.service.MessageContentIngestService;
import com.am.server.config.AgentProperties;
import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionEvent;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessage;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.web.sse.SseHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每会话事务（REQUIRES_NEW）带超时，且超时 / 基础设施异常在 ingest 内部<b>不被吞</b>：
 * 整包失败 → 客户端游标不推进 → 下个 tick 重报（上层把 TransactionTimedOutException 映射成 503 + 50301）。
 */
class AbstractAiSessionIngestServiceTimeoutTest {

    private static final long SESSION_ID = 7L;
    private static final LocalDateTime T = LocalDateTime.of(2026, 9, 28, 10, 0);

    private final AiSessionRepository sessionRepository = mock(AiSessionRepository.class);
    private final AiSessionEventRepository eventRepository = mock(AiSessionEventRepository.class);
    private final AiSessionMessageRepository messageRepository = mock(AiSessionMessageRepository.class);
    private final PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
    private final MessageContentIngestService content = mock(MessageContentIngestService.class);

    private static class TestIngest extends AbstractAiSessionIngestService {
        TestIngest(AiSessionRepository s, AiSessionEventRepository e, AiSessionMessageRepository m) {
            super(s, e, m, mock(SseHub.class));
        }

        @Override
        protected String targetType() {
            return "test";
        }
    }

    private final TestIngest ingest = new TestIngest(sessionRepository, eventRepository, messageRepository);

    @BeforeEach
    void setUp() {
        AiSession stored = new AiSession();
        stored.setId(SESSION_ID);
        stored.setTargetType("test");
        stored.setExternalSessionId("s1");
        stored.setUserCode("U1");
        stored.setAgentId("agent-1");
        stored.setStatus("idle");
        stored.setLastActivity(T);
        stored.setUserMessages(1);
        stored.setAssistantMessages(1);
        stored.setTotalMessages(2);
        when(sessionRepository.findByTargetTypeAndExternalSessionId("test", "s1")).thenReturn(Optional.of(stored));
        when(eventRepository.save(any(AiSessionEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(AiSessionMessage.class))).thenAnswer(inv -> {
            AiSessionMessage m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId(99L);   // 真库里 TABLE 生成器在 persist 时就分配 id
            }
            return m;
        });
        when(messageRepository.countByAiSessionId(SESSION_ID)).thenReturn(2);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ingest.setTransactionManager(tm);
        ingest.setMessageContentIngestService(content);
        ingest.initSessionTxTemplate();
    }

    @Test
    void sessionTransactionIsRequiresNewWithTheDefaultTenSecondTimeout() {
        ingest.ingest(snapshot(sessionDto(null)), ctx());

        ArgumentCaptor<TransactionDefinition> def = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(tm).getTransaction(def.capture());
        assertThat(def.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(def.getValue().getTimeout()).isEqualTo(10);
    }

    @Test
    void timeoutIsConfigurableAndZeroMeansNoTimeout() {
        AgentProperties props = new AgentProperties();
        props.setIngestSessionTimeoutSeconds(25);
        ingest.setAgentProperties(props);
        ingest.initSessionTxTemplate();
        ingest.ingest(snapshot(sessionDto(null)), ctx());

        props.setIngestSessionTimeoutSeconds(0);
        ingest.initSessionTxTemplate();
        ingest.ingest(snapshot(sessionDto(null)), ctx());

        ArgumentCaptor<TransactionDefinition> def = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(tm, times(2)).getTransaction(def.capture());
        assertThat(def.getAllValues().get(0).getTimeout()).isEqualTo(25);
        assertThat(def.getAllValues().get(1).getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
    }

    @Test
    void statementTimeoutInsideTheSessionTransactionFailsTheWholeReportAsTransactionTimedOut() {
        when(messageRepository.maxSequenceNoByAiSessionId(SESSION_ID))
                .thenThrow(new JpaSystemException(new org.hibernate.TransactionException("transaction timeout expired")));

        assertThatThrownBy(() -> ingest.ingest(snapshot(sessionDto(List.of(message("m1")))), ctx()))
                .isInstanceOf(TransactionTimedOutException.class)
                .hasCauseInstanceOf(JpaSystemException.class);

        verify(tm).rollback(any());
        verify(tm, never()).commit(any());
        verify(messageRepository, never()).save(any(AiSessionMessage.class));
    }

    @Test
    void timeoutIsNotMaskedWhenTheRollbackAfterAnAbortedStatementAlsoFails() {
        // 真实现象：慢语句被 setQueryTimeout 中止 → HikariCP 判连接损坏并关闭 → 回滚失败，
        // TransactionTemplate 用回滚异常盖掉应用异常。超时必须被找回来，回滚失败作为 suppressed 保留。
        QueryTimeoutException timeout = new QueryTimeoutException("statement cancelled",
                new java.sql.SQLTimeoutException("Statement cancelled due to timeout or client request"));
        when(messageRepository.maxSequenceNoByAiSessionId(SESSION_ID)).thenThrow(timeout);
        JpaSystemException rollbackFailure = new JpaSystemException(
                new org.hibernate.TransactionException("Unable to rollback against JDBC Connection"));
        org.mockito.Mockito.doThrow(rollbackFailure).when(tm).rollback(any());

        assertThatThrownBy(() -> ingest.ingest(snapshot(sessionDto(List.of(message("m1")))), ctx()))
                .isInstanceOf(TransactionTimedOutException.class)
                .hasCause(timeout)
                .satisfies(e -> assertThat(timeout.getSuppressed()).contains(rollbackFailure));
    }

    @Test
    void ordinaryFailuresAreNotTimeouts_soTheyPropagateUntouched() {
        IllegalStateException boom = new IllegalStateException("boom");
        when(messageRepository.maxSequenceNoByAiSessionId(SESSION_ID)).thenThrow(boom);

        assertThatThrownBy(() -> ingest.ingest(snapshot(sessionDto(List.of(message("m1")))), ctx()))
                .isSameAs(boom);
    }

    @Test
    void contentPreparationBugStillDegradesToTextOnlyAndTheMessageIsKept() throws Exception {
        when(content.prepare(any(), any())).thenThrow(new IllegalStateException("bad gzip payload"));

        ingest.ingest(snapshot(sessionDto(List.of(message("m1")))), ctx());

        ArgumentCaptor<AiSessionMessage> saved = ArgumentCaptor.forClass(AiSessionMessage.class);
        verify(messageRepository).save(saved.capture());
        assertThat(saved.getValue().getContentKind()).isEqualTo("text_only");
        assertThat(saved.getValue().getIngestVersion()).isZero();
        assertThat(saved.getValue().getContentText()).isEqualTo("hello m1");
        verify(tm).commit(any());
    }

    private static MonitorSnapshotDto snapshot(MonitorSessionDto s) {
        MonitorSnapshotDto snap = new MonitorSnapshotDto();
        snap.setType("test");
        snap.setCapturedAt(T);
        snap.setSessions(List.of(s));
        return snap;
    }

    private static MonitorSessionDto sessionDto(List<ConversationMessageDto> msgs) {
        MonitorSessionDto d = new MonitorSessionDto();
        d.setSessionId("s1");
        d.setStatus("idle");
        d.setLastActivity(T);
        d.setUserMessages(1);
        d.setAssistantMessages(1);
        d.setInputTokens(100L);
        d.setOutputTokens(50L);
        d.setRecentMessages(msgs == null ? null : new ArrayList<>(msgs));
        return d;
    }

    private static ConversationMessageDto message(String extId) {
        ConversationMessageDto m = new ConversationMessageDto();
        m.setExternalMessageId(extId);
        m.setRole("assistant");
        m.setText("hello " + extId);
        m.setTimestamp(T);
        return m;
    }

    private static SignatureContext ctx() {
        return new SignatureContext("agent-1", "U1", "host-1");
    }
}
