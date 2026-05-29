package com.am.server.service;

import com.am.server.config.InstallProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

/**
 * 读取运维分发目录中的 manifest.json，得到当前发布的客户端版本号。
 *
 * <p>与 agent/dist/install/manifest.json 及 {@code aiwatch.install.dir} 部署约定一致。
 *
 * gz
 */
@Service
@RequiredArgsConstructor
public class InstallManifestService {

    private static final Logger log = LoggerFactory.getLogger(InstallManifestService.class);

    private final InstallProperties installProperties;
    private final ObjectMapper objectMapper;

    /**
     * @return {@code manifest.json} 的 {@code version} 字段；目录未配置、文件缺失或解析失败时为空
     */
    public Optional<String> readPublishedClientVersion() {
        String dir = installProperties.getDir();
        if (dir == null || dir.isBlank()) {
            return Optional.empty();
        }
        File f = new File(dir.trim(), "manifest.json").getAbsoluteFile();
        if (!f.isFile()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(f);
            JsonNode v = root.get("version");
            if (v == null || !v.isTextual()) {
                return Optional.empty();
            }
            String s = v.asText().trim();
            return s.isEmpty() ? Optional.empty() : Optional.of(s);
        } catch (IOException e) {
            log.warn("readPublishedClientVersion: failed to parse {}: {}", f.getAbsolutePath(), e.toString());
            return Optional.empty();
        }
    }
}
