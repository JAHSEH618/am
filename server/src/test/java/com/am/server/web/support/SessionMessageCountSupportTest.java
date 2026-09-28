package com.am.server.web.support;

import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionMessageCountSupportTest {

    @Mock
    private AiSessionMessageRepository messageRepository;

    @Test
    void loadRoleCounts_sumsConversationRoles() {
        when(messageRepository.countGroupedByRoleForSession(45L)).thenReturn(List.of(
                new Object[]{"user", 18L},
                new Object[]{"assistant", 58L},
                new Object[]{"subagent", 2L},
                new Object[]{"tool", 748L}
        ));

        SessionMessageCountSupport.RoleCounts c =
                SessionMessageCountSupport.loadRoleCounts(messageRepository, 45L);

        assertEquals(18, c.user());
        assertEquals(58, c.assistant());
        assertEquals(2, c.subagent());
        assertEquals(78, c.conversation());
    }

    @Test
    void reconcileSessionEntity_overwritesAgentSnapshotWhenStored() {
        AiSession session = new AiSession();
        session.setId(45L);
        session.setUserMessages(1);
        session.setAssistantMessages(38);
        session.setTotalMessages(39);

        when(messageRepository.countByAiSessionId(45L)).thenReturn(1000);
        when(messageRepository.countGroupedByRoleForSession(45L)).thenReturn(List.of(
                new Object[]{"user", 18L},
                new Object[]{"assistant", 58L},
                new Object[]{"subagent", 2L}
        ));

        SessionMessageCountSupport.reconcileSessionEntity(session, messageRepository);

        assertEquals(18, session.getUserMessages());
        assertEquals(58, session.getAssistantMessages());
        assertEquals(76, session.getTotalMessages());
    }

    @Test
    void reconcileAfterIngest_noNewMessagesRestoresPreviousCountersWithoutQuerying() {
        // 覆写前 = 上一拍按 message 表对齐的值；本拍 Agent 快照（1/38）覆写后又没写消息 → 恢复，零查询
        AiSession session = snapshotOverwritten(45L);

        SessionMessageCountSupport.reconcileAfterIngest(session, messageRepository, 1000, 0,
                new SessionMessageCountSupport.SessionCounters(18, 58, 76));

        assertEquals(18, session.getUserMessages());
        assertEquals(58, session.getAssistantMessages());
        assertEquals(76, session.getTotalMessages());
        verifyNoInteractions(messageRepository);
    }

    @Test
    void reconcileAfterIngest_nothingStoredKeepsAgentSnapshot() {
        // 库里没有消息的 provider（只报计数不报 recent_messages）：快照值每拍都要跟上，不能冻结在旧值
        AiSession session = snapshotOverwritten(45L);

        SessionMessageCountSupport.reconcileAfterIngest(session, messageRepository, 0, 0,
                new SessionMessageCountSupport.SessionCounters(0, 0, 0));

        assertEquals(1, session.getUserMessages());
        assertEquals(38, session.getAssistantMessages());
        assertEquals(39, session.getTotalMessages());
        verifyNoInteractions(messageRepository);
    }

    @Test
    void reconcileAfterIngest_newMessagesRecountOnceByRole() {
        AiSession session = snapshotOverwritten(45L);
        when(messageRepository.countGroupedByRoleForSession(45L)).thenReturn(List.of(
                new Object[]{"user", 19L},
                new Object[]{"assistant", 60L},
                new Object[]{"tool", 700L}
        ));

        SessionMessageCountSupport.reconcileAfterIngest(session, messageRepository, 776, 3,
                new SessionMessageCountSupport.SessionCounters(18, 58, 76));

        assertEquals(19, session.getUserMessages());
        assertEquals(60, session.getAssistantMessages());
        assertEquals(79, session.getTotalMessages());
        verify(messageRepository, times(1)).countGroupedByRoleForSession(45L);
        verify(messageRepository, never()).countByAiSessionId(anyLong());
    }

    @Test
    void reconcileAfterIngest_brandNewSessionWithMessagesRecounts() {
        AiSession session = snapshotOverwritten(46L);
        when(messageRepository.countGroupedByRoleForSession(46L)).thenReturn(List.<Object[]>of(
                new Object[]{"user", 2L}));

        SessionMessageCountSupport.reconcileAfterIngest(session, messageRepository, 0, 2, null);

        assertEquals(2, session.getUserMessages());
        assertEquals(0, session.getAssistantMessages());
        assertEquals(2, session.getTotalMessages());
    }

    @Test
    void reconcileAfterIngest_incompletePreviousCountersFallBackToQuery() {
        AiSession session = snapshotOverwritten(45L);
        when(messageRepository.countGroupedByRoleForSession(45L)).thenReturn(List.of(
                new Object[]{"user", 18L},
                new Object[]{"assistant", 58L}
        ));

        SessionMessageCountSupport.reconcileAfterIngest(session, messageRepository, 1000, 0,
                new SessionMessageCountSupport.SessionCounters(null, 58, 76));

        assertEquals(18, session.getUserMessages());
        assertEquals(76, session.getTotalMessages());
    }

    /** upsertSession 已用 Agent 快照（user=1 / assistant=38）覆写过计数的会话。 */
    private static AiSession snapshotOverwritten(Long id) {
        AiSession session = new AiSession();
        session.setId(id);
        session.setUserMessages(1);
        session.setAssistantMessages(38);
        session.setTotalMessages(39);
        return session;
    }
}
