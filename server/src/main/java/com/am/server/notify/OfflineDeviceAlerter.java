package com.am.server.notify;

import com.am.server.domain.agent.AgentDevice;
import com.am.server.domain.agent.AgentDeviceRepository;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.system.SystemConfigKeys;
import com.am.server.system.SystemConfigService;
import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import com.am.server.system.scheduling.ScheduledTaskDefinition;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 离线设备提醒邮件任务。
 *
 * <p>每 12 小时（默认 cron {@code 0 0 10,22 * * *}，UI 可调）扫描 ACTIVE 且离线超过阈值小时数的设备，
 * 取设备登录邮箱（cursor_email 优先，git_user_email 兜底）发一封"客户端已离线、请重启"的提醒，
 * 引导员工自助恢复上报——也是对"掉线后自身已无法 auto-update"的僵尸机的召回通道。
 *
 * <p>去重：每台设备两封提醒至少间隔 {@code dedup_hours}（默认 12h），由 agent_device.last_offline_email_time
 * 落点；设备恢复在线后不主动清空，靠 dedup 窗口自然过期，下次掉线满 12h 再提醒。
 *
 * <p>安全默认：任务 {@code defaultEnabled=false}——必须先配好 SMTP（spring.mail.* + from）并在 UI 启用才发信。
 * gz
 */
@Component
@RequiredArgsConstructor
public class OfflineDeviceAlerter {

    private static final Logger log = LoggerFactory.getLogger(OfflineDeviceAlerter.class);

    public static final String TASK_CODE = "offline_device_alerter";

    /** 工作时段判定与 cron 触发统一用此时区，避免随 JVM 默认时区漂移。 */
    private static final ZoneId WORK_ZONE = ZoneId.of("Asia/Shanghai");

    private final AgentDeviceRepository deviceRepository;
    private final MailService mailService;
    private final SystemConfigService config;
    private final EmployeeDisplayService employeeDisplayService;
    private final DynamicScheduledTaskManager scheduledTaskManager;

    @PostConstruct
    public void registerDynamicTask() {
        config.seedIfAbsent(SystemConfigKeys.NOTIF_OFFLINE_EMAIL_FROM, "", "string",
                SystemConfigKeys.CAT_NOTIFICATIONS, false, "离线提醒邮件发件人地址（留空=不发信）");
        config.seedIfAbsent(SystemConfigKeys.NOTIF_OFFLINE_THRESHOLD_HOURS, "1", "int",
                SystemConfigKeys.CAT_NOTIFICATIONS, false, "设备离线超过几小时才发提醒邮件");
        config.seedIfAbsent(SystemConfigKeys.NOTIF_OFFLINE_DEDUP_HOURS, "2", "int",
                SystemConfigKeys.CAT_NOTIFICATIONS, false, "同一设备两封提醒邮件最小间隔小时数");
        config.seedIfAbsent(SystemConfigKeys.NOTIF_OFFLINE_WORK_HOUR_START, "9", "int",
                SystemConfigKeys.CAT_NOTIFICATIONS, false, "工作时段起始小时（含），只在此时段内发信");
        config.seedIfAbsent(SystemConfigKeys.NOTIF_OFFLINE_WORK_HOUR_END, "18", "int",
                SystemConfigKeys.CAT_NOTIFICATIONS, false, "工作时段结束小时（不含），只在此时段内发信");

        scheduledTaskManager.register(
                new ScheduledTaskDefinition(
                        TASK_CODE,
                        "离线设备提醒邮件",
                        ScheduledTaskDefinition.CATEGORY_BUSINESS,
                        "0 0 9-17 * * *",
                        false,
                        true,
                        true,
                        "工作时段（默认 9:00–18:00）内每小时扫描离线超过阈值（默认 1h）的 ACTIVE 设备，"
                                + "向其登录邮箱发提醒邮件；同一设备最小间隔 dedup_hours（默认 2h）。"
                                + "需先配置 SMTP（spring.mail.*）+ 发件人，并在此启用。",
                        WORK_ZONE.getId()),
                this::scanAndAlert);
    }

    // 不加 @Transactional：循环内含 SMTP 慢 I/O，避免把 DB 事务跨整批邮件长时间持有；
    // 每台设备的 save 走 Spring Data 自带事务、彼此独立，不需要跨设备原子性。
    public void scanAndAlert() {
        if (!mailService.isConfigured()) {
            log.info("offline_device_alerter: skip — 邮件未配置（spring.mail.* + notifications.offline_email.from）");
            return;
        }
        int workStart = config.getInt(SystemConfigKeys.NOTIF_OFFLINE_WORK_HOUR_START, 9);
        int workEnd = config.getInt(SystemConfigKeys.NOTIF_OFFLINE_WORK_HOUR_END, 18);
        int hour = LocalTime.now(WORK_ZONE).getHour();
        if (!withinWorkHours(hour, workStart, workEnd)) {
            log.info("offline_device_alerter: skip — 非工作时段 hour={} window=[{},{})", hour, workStart, workEnd);
            return;
        }
        int thresholdHours = Math.max(1, config.getInt(SystemConfigKeys.NOTIF_OFFLINE_THRESHOLD_HOURS, 1));
        int dedupHours = Math.max(1, config.getInt(SystemConfigKeys.NOTIF_OFFLINE_DEDUP_HOURS, 2));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime offlineCutoff = now.minusHours(thresholdHours);
        LocalDateTime dedupCutoff = now.minusHours(dedupHours);

        List<AgentDevice> offline =
                deviceRepository.findByStatusAndOfflineBefore(AgentDevice.STATUS_ACTIVE, offlineCutoff);

        int sent = 0, dedup = 0, noEmail = 0, failed = 0;
        for (AgentDevice d : offline) {
            LocalDateTime lastEmail = d.getLastOfflineEmailTime();
            if (lastEmail != null && lastEmail.isAfter(dedupCutoff)) {
                dedup++;
                continue;
            }
            String to = pickEmail(d);
            if (to == null) {
                noEmail++;
                continue;
            }
            try {
                mailService.send(to, buildSubject(), buildBody(d));
                d.setLastOfflineEmailTime(now);
                deviceRepository.save(d);
                sent++;
            } catch (Exception e) {
                failed++;
                log.warn("offline_device_alerter: 发送失败 agent={} to={}: {}", d.getAgentId(), to, e.toString());
            }
        }
        log.info("offline_device_alerter: offline={} sent={} dedup={} noEmail={} failed={} thresholdH={} dedupH={}",
                offline.size(), sent, dedup, noEmail, failed, thresholdHours, dedupHours);
    }

    /** 当前小时是否落在工作时段 [start, end)。 */
    static boolean withinWorkHours(int hour, int start, int end) {
        return hour >= start && hour < end;
    }

    /** cursor_email 优先，git_user_email 兜底；都不可用返回 null。 */
    private String pickEmail(AgentDevice d) {
        String cursor = trimToNull(d.getCursorEmail());
        if (looksLikeEmail(cursor)) {
            return cursor;
        }
        String git = trimToNull(d.getGitUserEmail());
        if (looksLikeEmail(git)) {
            return git;
        }
        return null;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean looksLikeEmail(String s) {
        return s != null && s.contains("@") && !s.contains(" ");
    }

    private String buildSubject() {
        return "[AIWatch] 你的 AI 监测客户端已离线，请重新启动";
    }

    private String buildBody(AgentDevice d) {
        String emp = employeeDisplayService.displayOf(d.getUserCode());
        String lastSeen = d.getLastSeenTime() == null ? "从未上报" : d.getLastSeenTime().toString();
        return emp + " 你好，\n\n"
                + "检测到你机器上的 AIWatch 客户端（aiwatchd）已离线，可能已被关闭或异常退出：\n\n"
                + "  主机名：" + nz(d.getHostname()) + "\n"
                + "  系统：" + nz(d.getOsType()) + "\n"
                + "  客户端版本：" + nz(d.getAgentVersion()) + "\n"
                + "  最后在线：" + lastSeen + "\n\n"
                + "请重新启动客户端，或重新运行安装脚本以恢复上报。若已离职 / 已换机请忽略本邮件。\n";
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "(未知)" : s;
    }
}
