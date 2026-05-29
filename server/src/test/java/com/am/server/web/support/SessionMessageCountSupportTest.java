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
}
