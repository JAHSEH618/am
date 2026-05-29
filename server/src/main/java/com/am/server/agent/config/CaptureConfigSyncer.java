package com.am.server.agent.config;

import com.am.server.system.SystemConfigChangedEvent;
import com.am.server.system.SystemConfigKeys;
import com.am.server.system.SystemConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * {@link CaptureProperties} 与 sys_config capture.* 双向同步。
 * gz
 */
@Component
@RequiredArgsConstructor
public class CaptureConfigSyncer {

    private static final Logger log = LoggerFactory.getLogger(CaptureConfigSyncer.class);

    private final CaptureProperties properties;
    private final SystemConfigService configService;

    @PostConstruct
    public void init() {
        seedDefaults();
        loadFromConfig();
        log.info("CaptureConfig synced: textBytes={} blobBytes={} blobsPerMsg={} parts={} auditChars={}",
                properties.getMaxTextBytesPerPart(),
                properties.getMaxBlobBytesPerPart(),
                properties.getMaxBlobsPerMessage(),
                properties.getMaxPartsPerMessage(),
                properties.getAuditMessageMaxChars());
    }

    private void seedDefaults() {
        configService.seedIfAbsent(SystemConfigKeys.CAPTURE_MAX_TEXT_BYTES_PER_PART,
                String.valueOf(properties.getMaxTextBytesPerPart()), "integer",
                SystemConfigKeys.CAT_CAPTURE, false, "单 part 文本上限（字节）");
        configService.seedIfAbsent(SystemConfigKeys.CAPTURE_MAX_BLOB_BYTES_PER_PART,
                String.valueOf(properties.getMaxBlobBytesPerPart()), "integer",
                SystemConfigKeys.CAT_CAPTURE, false, "单 blob 上限（字节，解压后）");
        configService.seedIfAbsent(SystemConfigKeys.CAPTURE_MAX_BLOBS_PER_MESSAGE,
                String.valueOf(properties.getMaxBlobsPerMessage()), "integer",
                SystemConfigKeys.CAT_CAPTURE, false, "单条消息最多图片/blob 数");
        configService.seedIfAbsent(SystemConfigKeys.CAPTURE_MAX_PARTS_PER_MESSAGE,
                String.valueOf(properties.getMaxPartsPerMessage()), "integer",
                SystemConfigKeys.CAT_CAPTURE, false, "单条消息最多 content part 数");
        configService.seedIfAbsent(SystemConfigKeys.CAPTURE_INLINE_BLOB_MAX_BYTES,
                String.valueOf(properties.getInlineBlobMaxBytes()), "integer",
                SystemConfigKeys.CAT_CAPTURE, false, "内联 blob 上限（字节）");
        configService.seedIfAbsent(SystemConfigKeys.CAPTURE_AUDIT_MESSAGE_MAX_CHARS,
                String.valueOf(properties.getAuditMessageMaxChars()), "integer",
                SystemConfigKeys.CAT_CAPTURE, false, "洞察审计 prompt 单条消息字符上限");
    }

    private void loadFromConfig() {
        properties.setMaxTextBytesPerPart(configService.getInt(
                SystemConfigKeys.CAPTURE_MAX_TEXT_BYTES_PER_PART, properties.getMaxTextBytesPerPart()));
        properties.setMaxBlobBytesPerPart(configService.getInt(
                SystemConfigKeys.CAPTURE_MAX_BLOB_BYTES_PER_PART, properties.getMaxBlobBytesPerPart()));
        properties.setMaxBlobsPerMessage(configService.getInt(
                SystemConfigKeys.CAPTURE_MAX_BLOBS_PER_MESSAGE, properties.getMaxBlobsPerMessage()));
        properties.setMaxPartsPerMessage(configService.getInt(
                SystemConfigKeys.CAPTURE_MAX_PARTS_PER_MESSAGE, properties.getMaxPartsPerMessage()));
        properties.setInlineBlobMaxBytes(configService.getInt(
                SystemConfigKeys.CAPTURE_INLINE_BLOB_MAX_BYTES, properties.getInlineBlobMaxBytes()));
        properties.setAuditMessageMaxChars(configService.getInt(
                SystemConfigKeys.CAPTURE_AUDIT_MESSAGE_MAX_CHARS, properties.getAuditMessageMaxChars()));
    }

    @EventListener
    public void onConfigChanged(SystemConfigChangedEvent ev) {
        Set<String> keys = ev.getChangedKeys();
        if (keys == null || keys.isEmpty()) {
            return;
        }
        boolean touched = keys.stream().anyMatch(k -> k.startsWith(SystemConfigKeys.CAT_CAPTURE + "."));
        if (touched) {
            loadFromConfig();
            log.info("CaptureConfig reloaded after sys_config change (keys={})", keys);
        }
    }
}
