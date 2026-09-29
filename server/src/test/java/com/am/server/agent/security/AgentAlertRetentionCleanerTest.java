package com.am.server.agent.security;

import com.am.server.config.AgentProperties;
import com.am.server.domain.agent.AgentAlertRepository;
import com.am.server.system.SystemConfigService;
import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import com.am.server.system.scheduling.ScheduledTaskDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAlertRetentionCleanerTest {

    private final AgentAlertRepository repo = mock(AgentAlertRepository.class);
    private final SystemConfigService config = mock(SystemConfigService.class);
    private final DynamicScheduledTaskManager manager = mock(DynamicScheduledTaskManager.class);
    private final AgentProperties props = new AgentProperties();
    private final AgentAlertRetentionCleaner cleaner = new AgentAlertRetentionCleaner(repo, config, manager, props);

    @Test
    void defaultRetentionIsThirtyDays() {
        assertThat(props.getAlertRetentionDays()).isEqualTo(30);
        when(config.getInt(AgentAlertRetentionCleaner.CONFIG_KEY_RETENTION_DAYS, 30)).thenReturn(30);
        when(repo.deleteExpiredBatch(any(), anyInt())).thenReturn(0);

        LocalDateTime before = LocalDateTime.now().minusDays(30);
        cleaner.cleanup();
        LocalDateTime after = LocalDateTime.now().minusDays(30);

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repo).deleteExpiredBatch(cutoff.capture(), eq(AgentAlertRetentionCleaner.DELETE_BATCH_SIZE));
        assertThat(cutoff.getValue()).isBetween(before, after);
    }

    @Test
    void deletesInBatchesUntilAShortBatch() {
        when(config.getInt(any(), anyInt())).thenReturn(30);
        when(repo.deleteExpiredBatch(any(), anyInt()))
                .thenReturn(AgentAlertRetentionCleaner.DELETE_BATCH_SIZE)
                .thenReturn(AgentAlertRetentionCleaner.DELETE_BATCH_SIZE)
                .thenReturn(17);

        assertThat(cleaner.cleanup()).isEqualTo(2 * AgentAlertRetentionCleaner.DELETE_BATCH_SIZE + 17);
        verify(repo, times(3)).deleteExpiredBatch(any(), anyInt());
    }

    @Test
    void perRunBatchCapBoundsTheWorkOfASingleRun() {
        when(config.getInt(any(), anyInt())).thenReturn(30);
        when(repo.deleteExpiredBatch(any(), anyInt())).thenReturn(AgentAlertRetentionCleaner.DELETE_BATCH_SIZE);

        cleaner.cleanup();

        verify(repo, times(AgentAlertRetentionCleaner.MAX_BATCHES)).deleteExpiredBatch(any(), anyInt());
    }

    @Test
    void zeroOrNegativeRetentionDisablesCleanup() {
        when(config.getInt(any(), anyInt())).thenReturn(0);
        assertThat(cleaner.cleanup()).isZero();
        when(config.getInt(any(), anyInt())).thenReturn(-1);
        assertThat(cleaner.cleanup()).isZero();
        verify(repo, never()).deleteExpiredBatch(any(), anyInt());
    }

    @Test
    void registersADailyTaskLikeTheNonceCleanerAndSeedsTheRetentionKey() {
        cleaner.registerDynamicTask();

        ArgumentCaptor<ScheduledTaskDefinition> def = ArgumentCaptor.forClass(ScheduledTaskDefinition.class);
        verify(manager).register(def.capture(), any(Runnable.class));
        assertThat(def.getValue().taskCode()).isEqualTo(AgentAlertRetentionCleaner.TASK_CODE);
        assertThat(def.getValue().defaultCron()).isEqualTo("0 30 4 * * *");
        assertThat(def.getValue().category()).isEqualTo(ScheduledTaskDefinition.CATEGORY_BUSINESS);
        assertThat(def.getValue().manualTriggerable()).isTrue();
        verify(config).seedIfAbsent(eq(AgentAlertRetentionCleaner.CONFIG_KEY_RETENTION_DAYS), eq("30"), eq("int"),
                any(), eq(false), any());
    }
}
