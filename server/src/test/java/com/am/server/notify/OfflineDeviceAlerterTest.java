package com.am.server.notify;

import com.am.server.domain.agent.AgentDevice;
import com.am.server.domain.agent.AgentDeviceRepository;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.system.SystemConfigService;
import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private MutableClock clock;

    /** 可手动推进的时钟：控制“应用就绪后过了多久”。 */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        deviceRepository = mock(AgentDeviceRepository.class);
        mailService = mock(MailService.class);
        config = mock(SystemConfigService.class);
        employeeDisplayService = mock(EmployeeDisplayService.class);
        DynamicScheduledTaskManager sched = mock(DynamicScheduledTaskManager.class);
        alerter = new OfflineDeviceAlerter(
                deviceRepository, mailService, config, employeeDisplayService, sched);
        // 默认：应用已就绪且早已过了 10 分钟启动宽限，让下面的发信类用例与宽限逻辑无关；宽限本身另有专测。
        clock = new MutableClock(Instant.parse("2026-09-29T02:00:00Z"));
        alerter.setClock(clock);
        alerter.onApplicationReady();
        clock.advance(Duration.ofMinutes(11));
        when(config.getInt(contains("startup_grace"), anyInt())).thenReturn(10);

        when(mailService.isConfigured()).thenReturn(true);
        when(config.getInt(contains("threshold"), anyInt())).thenReturn(2);
        when(config.getInt(contains("dedup"), anyInt())).thenReturn(12);
        // 默认把工作时段开成全天，让发信类测试与运行时刻无关；工作时段本身另有专测。
        when(config.getInt(contains("work_hour_start"), anyInt())).thenReturn(0);
        when(config.getInt(contains("work_hour_end"), anyInt())).thenReturn(24);
        // getString 默认返回空串（install_base_url 未配 → 重装步骤走通用指引分支，不 NPE）。
        when(config.getString(anyString(), anyString())).thenReturn("");
        when(employeeDisplayService.displayOf(anyString())).thenReturn("张三|U001");
    }

    /** 第一个参数是工号（user_code）——现在的主收件来源；cursor 设为哨兵值以验证它不再被使用。 */
    private AgentDevice device(String userCode, String gitEmail, LocalDateTime lastEmail) {
        AgentDevice d = new AgentDevice();
        d.setAgentId("a-1");
        d.setUserCode(userCode);
        d.setHostname("HOST-1");
        d.setOsType("windows");
        d.setAgentVersion("1.0.17");
        d.setCursorEmail("cursor@corp.com"); // 现在应被完全忽略，不作为收件来源
        d.setGitUserEmail(gitEmail);
        d.setLastSeenTime(LocalDateTime.now().minusHours(5));
        d.setLastOfflineEmailTime(lastEmail);
        return d;
    }

    @Test
    void sendsEmailToUserCodeWhenItIsAnEmail() {
        // 工号本身就是邮箱 → 直接用工号作收件地址（优先于 git，且忽略 cursor 哨兵）
        AgentDevice d = device("user@corp.com", "git@corp.com", null);
        when(deviceRepository.findByStatusAndOfflineBefore(eq(AgentDevice.STATUS_ACTIVE), any()))
                .thenReturn(List.of(d));

        alerter.scanAndAlert();

        verify(mailService).send(eq("user@corp.com"), anyString(), anyString());
        assertThat(d.getLastOfflineEmailTime()).isNotNull();
        // 只回写告警列：绝不整行 save(device)——那会把筛选时刻读到的 last_seen 写回，盖掉发信期间刚上报的心跳
        verify(deviceRepository).markOfflineEmailSent(eq("a-1"), eq(d.getLastOfflineEmailTime()));
        verify(deviceRepository, never()).save(any());
    }

    @Test
    void failedSendDoesNotWriteBackTheDedupAnchor() {
        AgentDevice d = device("user@corp.com", null, null);
        when(deviceRepository.findByStatusAndOfflineBefore(any(), any())).thenReturn(List.of(d));
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                .when(mailService).send(anyString(), anyString(), anyString());

        alerter.scanAndAlert();

        verify(deviceRepository, never()).markOfflineEmailSent(anyString(), any());
        assertThat(d.getLastOfflineEmailTime()).isNull();
    }

    @Test
    void fallsBackToGitEmailWhenUserCodeNotAnEmail() {
        // 工号 HS10086 不是邮箱 → 跳过工号，退到 git 邮箱（cursor 哨兵仍被忽略）
        AgentDevice d = device("HS10086", "git@corp.com", null);
        when(deviceRepository.findByStatusAndOfflineBefore(any(), any())).thenReturn(List.of(d));

        alerter.scanAndAlert();

        verify(mailService).send(eq("git@corp.com"), anyString(), anyString());
    }

    @Test
    void skipsDeviceWithNoUsableEmail() {
        // 工号不是邮箱、git 也没有 → 跳过；即便 cursor 哨兵存在也不发信（证明 cursor 不再兜底）
        AgentDevice d = device("HS10086", null, null);
        when(deviceRepository.findByStatusAndOfflineBefore(any(), any())).thenReturn(List.of(d));

        alerter.scanAndAlert();

        verify(mailService, never()).send(anyString(), anyString(), anyString());
        verify(deviceRepository, never()).markOfflineEmailSent(anyString(), any());
        verify(deviceRepository, never()).save(any());
    }

    @Test
    void skipsDeviceNotifiedWithinDedupWindow() {
        // 上次发信在 3 小时前，dedup 窗口 12 小时内 → 跳过
        AgentDevice d = device("user@corp.com", null, LocalDateTime.now().minusHours(3));
        when(deviceRepository.findByStatusAndOfflineBefore(any(), any())).thenReturn(List.of(d));

        alerter.scanAndAlert();

        verify(mailService, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void reNotifiesWhenLastEmailOlderThanDedupWindow() {
        // 上次发信在 13 小时前，超过 12 小时 dedup 窗口 → 重新提醒
        AgentDevice d = device("user@corp.com", null, LocalDateTime.now().minusHours(13));
        when(deviceRepository.findByStatusAndOfflineBefore(any(), any())).thenReturn(List.of(d));

        alerter.scanAndAlert();

        verify(mailService).send(eq("user@corp.com"), anyString(), anyString());
    }

    // ---------------------------------------------------------------- 启动宽限

    /** 新建一个“尚未就绪”的告警器（不调用 onApplicationReady），共用同一批 mock 与时钟。 */
    private OfflineDeviceAlerter notYetReadyAlerter() {
        OfflineDeviceAlerter a = new OfflineDeviceAlerter(
                deviceRepository, mailService, config, employeeDisplayService, mock(DynamicScheduledTaskManager.class));
        a.setClock(clock);
        return a;
    }

    @Test
    void doesNothingBeforeTheApplicationIsReady() {
        // 启动补丁 / 回填还没跑完（ApplicationReadyEvent 未发布）：上报入口在回 503，last_seen 不可信
        AgentDevice d = device("user@corp.com", null, null);
        when(deviceRepository.findByStatusAndOfflineBefore(any(), any())).thenReturn(List.of(d));

        notYetReadyAlerter().scanAndAlert();

        verify(deviceRepository, never()).findByStatusAndOfflineBefore(any(), any());
        verify(mailService, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void doesNothingInsideTheStartupGraceWindowThenResumes() {
        AgentDevice d = device("user@corp.com", null, null);
        when(deviceRepository.findByStatusAndOfflineBefore(any(), any())).thenReturn(List.of(d));
        OfflineDeviceAlerter a = notYetReadyAlerter();
        a.onApplicationReady();                                   // 就绪时刻 = 当前时钟

        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        a.scanAndAlert();
        verify(deviceRepository, never()).findByStatusAndOfflineBefore(any(), any());
        verify(mailService, never()).send(anyString(), anyString(), anyString());

        clock.advance(Duration.ofSeconds(1));                     // 恰好 10 分钟：宽限结束
        a.scanAndAlert();
        verify(deviceRepository).findByStatusAndOfflineBefore(any(), any());
        verify(mailService).send(eq("user@corp.com"), anyString(), anyString());
    }

    @Test
    void graceWindowIsReadFromConfigOnEveryScan() {
        when(config.getInt(contains("startup_grace"), anyInt())).thenReturn(30);
        OfflineDeviceAlerter a = notYetReadyAlerter();
        a.onApplicationReady();
        clock.advance(Duration.ofMinutes(29));

        a.scanAndAlert();
        verify(deviceRepository, never()).findByStatusAndOfflineBefore(any(), any());

        clock.advance(Duration.ofMinutes(1));
        a.scanAndAlert();
        verify(deviceRepository).findByStatusAndOfflineBefore(any(), any());
    }

    @Test
    void zeroOrNegativeGraceMeansNoWaitButStillRequiresReadiness() {
        when(config.getInt(contains("startup_grace"), anyInt())).thenReturn(0);
        OfflineDeviceAlerter a = notYetReadyAlerter();
        a.scanAndAlert();                                          // 未就绪：仍不判定
        verify(deviceRepository, never()).findByStatusAndOfflineBefore(any(), any());

        a.onApplicationReady();
        a.scanAndAlert();                                          // 就绪即判定
        verify(deviceRepository).findByStatusAndOfflineBefore(any(), any());

        when(config.getInt(contains("startup_grace"), anyInt())).thenReturn(-5);
        a.scanAndAlert();                                          // 负数按 0 处理
        verify(deviceRepository, org.mockito.Mockito.times(2)).findByStatusAndOfflineBefore(any(), any());
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
    void includesWindowsReinstallCommandWhenBaseUrlSet() {
        when(config.getString(contains("install_base_url"), anyString()))
                .thenReturn("https://aiwatch.example.com");
        // 工号 U001 不是邮箱 → 收件退到 git；重装命令仍用工号 U001 预填 -UserCode
        AgentDevice d = device("U001", "git@corp.com", null);
        d.setOsType("windows");
        when(deviceRepository.findByStatusAndOfflineBefore(any(), any())).thenReturn(List.of(d));

        alerter.scanAndAlert();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(eq("git@corp.com"), anyString(), body.capture());
        assertThat(body.getValue())
                .contains("aiwatchd.ps1")
                .contains("https://aiwatch.example.com")
                .contains("-UserCode 'U001'");
    }

    @Test
    void includesUnixReinstallCommandForMacDevice() {
        when(config.getString(contains("install_base_url"), anyString()))
                .thenReturn("https://aiwatch.example.com");
        AgentDevice d = device("U001", "git@corp.com", null);
        d.setOsType("macos");
        when(deviceRepository.findByStatusAndOfflineBefore(any(), any())).thenReturn(List.of(d));

        alerter.scanAndAlert();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(eq("git@corp.com"), anyString(), body.capture());
        assertThat(body.getValue())
                .contains("aiwatchd.sh")
                .contains("--user-code 'U001'");
    }

    @Test
    void reinstallCommandHasNoDoubledSchemeWhenBaseUrlMisconfigured() {
        // 管理员把公网地址误填成 http://http://...（粘贴/手打重复了一截 scheme）
        when(config.getString(contains("install_base_url"), anyString()))
                .thenReturn("http://http://183.214.120.190:9527");
        AgentDevice d = device("U001", "git@corp.com", null);
        d.setOsType("macos");
        when(deviceRepository.findByStatusAndOfflineBefore(any(), any())).thenReturn(List.of(d));

        alerter.scanAndAlert();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(eq("git@corp.com"), anyString(), body.capture());
        assertThat(body.getValue())
                .contains("http://183.214.120.190:9527/install/aiwatchd.sh")
                .doesNotContain("http://http://");
    }

    @Test
    void normalizeBaseUrlCollapsesDuplicateSchemeAndTrailingSlash() {
        assertThat(OfflineDeviceAlerter.normalizeBaseUrl("http://http://183.214.120.190:9527"))
                .isEqualTo("http://183.214.120.190:9527");
        assertThat(OfflineDeviceAlerter.normalizeBaseUrl("https://https://aiwatch.x.com/"))
                .isEqualTo("https://aiwatch.x.com");
        assertThat(OfflineDeviceAlerter.normalizeBaseUrl("http://a.com/")).isEqualTo("http://a.com");
        assertThat(OfflineDeviceAlerter.normalizeBaseUrl("  http://a.com  ")).isEqualTo("http://a.com");
        assertThat(OfflineDeviceAlerter.normalizeBaseUrl("")).isEqualTo("");
    }

    @Test
    void withinWorkHoursBoundaries() {
        assertThat(OfflineDeviceAlerter.withinWorkHours(8, 9, 18)).isFalse();
        assertThat(OfflineDeviceAlerter.withinWorkHours(9, 9, 18)).isTrue();
        assertThat(OfflineDeviceAlerter.withinWorkHours(17, 9, 18)).isTrue();
        assertThat(OfflineDeviceAlerter.withinWorkHours(18, 9, 18)).isFalse();
    }
}
