package com.am.server.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 会话全量输入采集的服务端限额（与 sys_config capture.* 同步）。
 * gz
 */
@Component
@ConfigurationProperties(prefix = "aiwatch.capture")
@Data
public class CaptureProperties {

    /** 单 part 文本最大字节（UTF-8）。 */
    private int maxTextBytesPerPart = 512 * 1024;

    /** 单 blob 最大字节（解压后）。 */
    private int maxBlobBytesPerPart = 1024 * 1024;

    /** 单条消息最多 blob 数。 */
    private int maxBlobsPerMessage = 8;

    /** 单条消息最多 part 数。 */
    private int maxPartsPerMessage = 64;

    /** Agent 内联 gzip+base64 阈值；更大 blob 应由 Agent 侧截断。 */
    private int inlineBlobMaxBytes = 32 * 1024;

    /** 洞察审计 prompt 中单条消息展示上限（字符）。 */
    private int auditMessageMaxChars = 4000;
}
