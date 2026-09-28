package com.am.server.web;

import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.insight.domain.AiSessionAuditRepository;
import com.am.server.service.AiSessionMessageBlobService;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.system.ActiveTargetTypesProvider;
import com.am.server.web.dto.AiSessionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话详情：已入库总数与 role 计数每次请求各只查一遍（原先实体对齐、DTO 回填各查一遍），
 * 展示结果不变——user / assistant / total 以 message 表为准覆盖 Agent 快照。
 */
class AiSessionDetailCountQueriesTest {

    private final AiSessionRepository sessionRepository = mock(AiSessionRepository.class);
    private final AiSessionMessageRepository messageRepository = mock(AiSessionMessageRepository.class);
    private final ActiveTargetTypesProvider activeTargetTypesProvider = mock(ActiveTargetTypesProvider.class);
    private final AiSessionAuditRepository auditRepository = mock(AiSessionAuditRepository.class);

    private final AiSessionController controller = new AiSessionController(
            sessionRepository, messageRepository, mock(AiSessionEventRepository.class), auditRepository,
            mock(EmployeeDisplayService.class), activeTargetTypesProvider, mock(AiSessionMessageBlobService.class));

    @BeforeEach
    void seed() {
        AiSession s = new AiSession();
        s.setId(45L);
        s.setTargetType("cursor");
        s.setExternalSessionId("ext-45");
        s.setStatus("idle");
        s.setUserCode("U1");
        // Agent 快照（Cursor fast path 缓存）与 message 表不一致
        s.setUserMessages(1);
        s.setAssistantMessages(38);
        s.setTotalMessages(39);
        s.setStartedAt(LocalDateTime.of(2026, 9, 22, 9, 0));
        s.setLastActivity(LocalDateTime.of(2026, 9, 22, 18, 0));
        when(sessionRepository.findById(45L)).thenReturn(Optional.of(s));
        when(activeTargetTypesProvider.shouldInclude("cursor")).thenReturn(true);
        when(auditRepository.findByAiSessionId(anyLong())).thenReturn(Optional.empty());
        when(messageRepository.countGroupedByRoleForSession(45L)).thenReturn(List.of(
                new Object[]{"user", 18L},
                new Object[]{"assistant", 58L},
                new Object[]{"subagent", 2L},
                new Object[]{"tool", 900L}));
    }

    @Test
    void detailCountsStoredMessagesOnce() {
        when(messageRepository.countByAiSessionId(45L)).thenReturn(978);

        AiSessionDto d = controller.detail(45L, null, null).getData();

        verify(messageRepository, times(1)).countByAiSessionId(45L);
        verify(messageRepository, times(1)).countGroupedByRoleForSession(45L);
        assertThat(d.getStoredMessageCount()).isEqualTo(978);
        assertThat(d.getUserMessages()).isEqualTo(18);
        assertThat(d.getAssistantMessages()).isEqualTo(58);
        assertThat(d.getTotalMessages()).isEqualTo(76);
        assertThat(d.getStoredUserMessages()).isEqualTo(18);
        assertThat(d.getStoredAssistantMessages()).isEqualTo(58);
        assertThat(d.getStoredConversationCount()).isEqualTo(78);
    }

    @Test
    void windowedDetailCountsStoredMessagesOnce() {
        when(messageRepository.countByAiSessionId(45L)).thenReturn(978);
        when(messageRepository.aggregateSessionWindowConversation(any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{100L, 200L, 30L}));

        AiSessionDto d = controller.detail(45L, LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27)).getData();

        verify(messageRepository, times(1)).countByAiSessionId(45L);
        verify(messageRepository, times(1)).countGroupedByRoleForSession(45L);
        verify(messageRepository, times(1)).aggregateSessionWindowConversation(any(), any(), any());
        assertThat(d.getTotalMessages()).isEqualTo(76);
        assertThat(d.getWindowMessageCount()).isEqualTo(30);
        assertThat(d.getWindowTokens()).isEqualTo(300L);
    }

    @Test
    void noStoredMessagesKeepsAgentSnapshotAndSkipsRoleCounts() {
        when(messageRepository.countByAiSessionId(45L)).thenReturn(0);

        AiSessionDto d = controller.detail(45L, null, null).getData();

        verify(messageRepository, times(1)).countByAiSessionId(45L);
        verify(messageRepository, never()).countGroupedByRoleForSession(anyLong());
        assertThat(d.getStoredMessageCount()).isZero();
        assertThat(d.getUserMessages()).isEqualTo(1);
        assertThat(d.getAssistantMessages()).isEqualTo(38);
        assertThat(d.getTotalMessages()).isEqualTo(39);
    }
}
