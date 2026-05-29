package com.am.server.insight.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 后台洞察审计表扫描游标（本地 JSON 文件，单实例约定；多实例需换 DB 游标）。
 * gz
 */
@Component
public class AuditScanCursorStore {

    private static final Logger log = LoggerFactory.getLogger(AuditScanCursorStore.class);

    private final ObjectMapper objectMapper;

    public AuditScanCursorStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public long readLastId(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return 0L;
        }
        try {
            CursorDto dto = objectMapper.readValue(file.toFile(), CursorDto.class);
            return dto.getLastScannedId() <= 0 ? 0L : dto.getLastScannedId();
        } catch (IOException e) {
            log.warn("audit scan cursor read failed (reset to 0): {}", e.getMessage());
            return 0L;
        }
    }

    public void writeLastId(Path file, long lastScannedId) {
        if (file == null) {
            return;
        }
        CursorDto dto = new CursorDto();
        dto.setLastScannedId(lastScannedId);
        dto.setSchemaVersion(1);
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Path tmp = Files.createTempFile(dir, "audit-scan-", ".tmp");
            byte[] bytes = objectMapper.writeValueAsBytes(dto);
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("audit scan cursor write failed: {}", e.getMessage());
        }
    }

    /** JSON 结构与字段名保持稳定，便于人工排查。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CursorDto {
        private long lastScannedId;
        private int schemaVersion = 1;
    }
}
