package com.am.server.web;

import com.am.server.domain.ai.AiSession;
import com.am.server.insight.domain.AiSessionAuditRepository;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.service.AiSessionMessageBlobService;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.system.ActiveTargetTypesProvider;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class AiSessionDetailNoWriteTest {

    private final AiSessionRepository sessionRepository = mock(AiSessionRepository.class);
    private final AiSessionMessageRepository messageRepository = mock(AiSessionMessageRepository.class);
    private final AiSessionEventRepository eventRepository = mock(AiSessionEventRepository.class);
    private final AiSessionAuditRepository auditRepository = mock(AiSessionAuditRepository.class);
    private final EmployeeDisplayService employeeDisplayService = mock(EmployeeDisplayService.class);
    private final ActiveTargetTypesProvider activeTargetTypesProvider = mock(ActiveTargetTypesProvider.class);
    private final AiSessionMessageBlobService messageBlobService = mock(AiSessionMessageBlobService.class);

    private final AiSessionController controller = new AiSessionController(
            sessionRepository, messageRepository, eventRepository, auditRepository,
            employeeDisplayService, activeTargetTypesProvider, messageBlobService);

    @Test
    void detailGetDoesNotWriteSession() {
        AiSession s = new AiSession();
        s.setId(1L);
        s.setTargetType("cursor");
        s.setExternalSessionId("ext-1");
        s.setStatus("running");
        s.setUserCode("U1");
        s.setLastActivity(LocalDateTime.now());
        s.setStartedAt(LocalDateTime.now());
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(s));
        // loadActiveSession calls activeTargetTypesProvider.shouldInclude(targetType);
        // Mockito mock returns false by default, causing BizException before the save.
        // Stub it to true so the session passes the whitelist check.
        when(activeTargetTypesProvider.shouldInclude("cursor")).thenReturn(true);
        when(messageRepository.countByAiSessionId(1L)).thenReturn(5);
        when(messageRepository.countGroupedByRoleForSession(1L)).thenReturn(Collections.emptyList());
        when(employeeDisplayService.displayOf(any())).thenReturn("U1");
        when(auditRepository.findByAiSessionId(anyLong())).thenReturn(Optional.empty());

        controller.detail(1L, null, null);

        verify(sessionRepository, never()).save(any());
    }
}
