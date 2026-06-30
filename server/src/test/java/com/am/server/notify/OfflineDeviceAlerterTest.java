package com.am.server.notify;

import com.am.server.domain.agent.AgentDevice;
import com.am.server.domain.agent.AgentDeviceRepository;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.system.SystemConfigService;
import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfflineDeviceAlerterTest {

    private AgentDeviceRepository deviceRepository;
    private MailService mailService;
    private SystemConfigService config;
    private EmployeeDisplayService employeeDisplayService;
    private OfflineDeviceAlerter alerter;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(AgentDeviceRepository.class);
        mailService = mock(MailService.class);
        config = mock(SystemConfigService.class);
        employeeDisplayService = mock(EmployeeDisplayService.class);
        DynamicScheduledTaskManager sched = mock(DynamicScheduledTaskManager.class);
        alerter = new OfflineDeviceAlerter(
                deviceRepository, mailService, config, employeeDisplayService, sched);

        when(mailService.isConfigured()).thenReturn(true);
        when(config.getInt(contains("threshold"), anyInt())).thenReturn(2);
        when(config.getInt(contains("dedup"), anyInt())).thenReturn(12);
        // 默认把工作时段开成全天，让发信类测试与运行时刻无关；工作时段本身另有专测。
        when(config.getInt(contains("work_hour_start"), anyInt())).thenReturn(0);
        when(config.getInt(contains("work_hour_end"), anyInt())).thenReturn(24);
        when(employeeDisplayService.displayOf(anyString())).thenReturn("张三|U001");
    }

    private AgentDevice device(String cursorEmail, String gitEmail, LocalDateTime lastEmail) {
        AgentDevice d = new AgentDevice();
        d.setAgentId("a-1");
        d.setUserCode("U001");
        d.setHostname("HOST-1");
        d.setOsType("windows");
        d.setAgentVersion("1.0.17");
        d.setCursorEmail(cursorEmail);
        d.setGitUserEmail(gitEmail);
        d.setLastSeenTime(LocalDateTime.now().minusHours(5));
        d.setLastOfflineEmailTime(lastEmail);
        return d;
    }

    @Test
    void sendsEmailToCursorEmailAndStampsTime() {
        AgentDevice d = device("dev@corp.com", "git@corp.com", null);
        when(deviceRepository.findByStatusAndOfflineBefore(eq(AgentDevice.STATUS_ACTIVE), any()))
                .thenReturn(List.of(d));

        alerter.scanAndAlert();

        verify(mailService).send(eq("dev@corp.com"), anyString(), anyString());
        assertThat(d.getLastOfflineEmailTime()).isNotNull();
        verify(deviceRepository).save(d);
    }

    @Test
    void fallsBackToGitEmailWhenCursorEmailBlank() {
        AgentDevice d = device("   ", "git@corp.com", null);
        when(deviceRepository.findByStatusAndOfflineBefore(any(), any())).thenReturn(List.of(d));

        alerter.scanAndAlert();

        verify(mailService).send(eq("git@corp.com"), anyString(), anyString());
    }

    @Test
    void skipsDeviceWithNoUsableEmail() {
        AgentDevice d = device(null, null, null);
        when(deviceRepository.findByStatusAndOfflineBefore(any(), any())).thenReturn(List.of(d));

        alerter.scanAndAlert();

        verify(mailService, never()).send(anyString(), anyString(), anyString());
        verify(deviceRepository, never()).save(any());
    }

    @Test
    void skipsDeviceNotifiedWithinDedupWindow() {
        // 上次发信在 3 小时前，dedup 窗口 12 小时内 → 跳过
        AgentDevice d = device("dev@corp.com", null, LocalDateTime.now().minusHours(3));
        when(deviceRepository.findByStatusAndOfflineBefore(any(), any())).thenReturn(List.of(d));

        alerter.scanAndAlert();

        verify(mailService, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void reNotifiesWhenLastEmailOlderThanDedupWindow() {
        // 上次发信在 13 小时前，超过 12 小时 dedup 窗口 → 重新提醒
        AgentDevice d = device("dev@corp.com", null, LocalDateTime.now().minusHours(13));
        when(deviceRepository.findByStatusAndOfflineBefore(any(), any())).thenReturn(List.of(d));

        alerter.scanAndAlert();

        verify(mailService).send(eq("dev@corp.com"), anyString(), anyString());
    }

    @Test
    void doesNothingWhenMailNotConfigured() {
        when(mailService.isConfigured()).thenReturn(false);

        alerter.scanAndAlert();

        verify(deviceRepository, never()).findByStatusAndOfflineBefore(any(), any());
        verify(mailService, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void doesNothingOutsideWorkHours() {
        // 空窗口 [0,0) → 任何时刻都判为非工作时段，确定性地不发信
        when(config.getInt(contains("work_hour_start"), anyInt())).thenReturn(0);
        when(config.getInt(contains("work_hour_end"), anyInt())).thenReturn(0);

        alerter.scanAndAlert();

        verify(deviceRepository, never()).findByStatusAndOfflineBefore(any(), any());
        verify(mailService, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void withinWorkHoursBoundaries() {
        assertThat(OfflineDeviceAlerter.withinWorkHours(8, 9, 18)).isFalse();
        assertThat(OfflineDeviceAlerter.withinWorkHours(9, 9, 18)).isTrue();
        assertThat(OfflineDeviceAlerter.withinWorkHours(17, 9, 18)).isTrue();
        assertThat(OfflineDeviceAlerter.withinWorkHours(18, 9, 18)).isFalse();
    }
}
