package com.am.server.notify;

import com.am.server.system.SystemConfigKeys;
import com.am.server.system.SystemConfigService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 纯文本邮件发送的薄封装。
 *
 * <p>{@link JavaMailSender} 由 spring-boot-starter-mail 在 {@code spring.mail.host} 存在时自动装配；
 * 未配置 SMTP 时该 Bean 可能缺失，故用 {@link ObjectProvider} 软依赖，避免拖垮应用启动。
 * 发件人地址走 sys_config（{@link SystemConfigKeys#NOTIF_OFFLINE_EMAIL_FROM}），UI 可热改。
 *
 * <p>{@link #isConfigured()} 同时要求 sender 在位且 from 非空——任一缺失则上层任务直接跳过，不报错。
 * gz
 */
@Service
public class MailService {

    private final JavaMailSender sender;
    private final SystemConfigService config;

    public MailService(ObjectProvider<JavaMailSender> senderProvider, SystemConfigService config) {
        this.sender = senderProvider.getIfAvailable();
        this.config = config;
    }

    /** SMTP 已装配且发件人地址已填，才算可用。 */
    public boolean isConfigured() {
        return sender != null && !from().isBlank();
    }

    /** 发一封纯文本邮件；失败抛 {@link org.springframework.mail.MailException}，由调用方兜底。 */
    public void send(String to, String subject, String body) {
        if (sender == null) {
            throw new IllegalStateException("JavaMailSender 未装配（请配置 spring.mail.*）");
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from());
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(body);
        sender.send(msg);
    }

    private String from() {
        return config.getString(SystemConfigKeys.NOTIF_OFFLINE_EMAIL_FROM, "");
    }
}
