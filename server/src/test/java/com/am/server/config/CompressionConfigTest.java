package com.am.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.core.io.ClassPathResource;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code server.compression.mime-types} 是覆盖式配置：没列出的 Content-Type 一律明文下发。
 * 这里按容器实际给控制台构建产物打的 Content-Type（{@link MimeMappings#DEFAULT}）核对，
 * 防止再出现「.js 实际是 text/javascript、列表里只有 application/javascript」这种静默失效。
 */
class CompressionConfigTest {

    @Test
    void compressesEveryTextAssetTypeTheConsoleBundleIsServedAs() {
        Properties p = load();
        assertThat(p.getProperty("server.compression.enabled")).isEqualTo("true");
        List<String> types = Arrays.stream(p.getProperty("server.compression.mime-types").split(","))
                .map(String::trim)
                .toList();

        for (String ext : List.of("js", "css", "html", "svg")) {
            assertThat(types)
                    .as("static .%s is served as %s", ext, MimeMappings.DEFAULT.get(ext))
                    .contains(MimeMappings.DEFAULT.get(ext));
        }
        assertThat(types).contains("application/json");
    }

    @Test
    void neverCompressesServerSentEvents() {
        // 压缩会让 SSE 帧在 gzip 缓冲里攒着不下发，实时推送失效。
        assertThat(load().getProperty("server.compression.mime-types")).doesNotContain("text/event-stream");
    }

    private static Properties load() {
        YamlPropertiesFactoryBean y = new YamlPropertiesFactoryBean();
        y.setResources(new ClassPathResource("application.yml"));
        Properties p = y.getObject();
        assertThat(p).isNotNull();
        return p;
    }
}
