package com.am.server.insight.audit;

import com.am.server.system.SystemConfigChangedEvent;
import com.am.server.system.SystemConfigKeys;
import com.am.server.system.SystemConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 加载 rubric 模板。
 *
 * <p>模板内容作为<b>一整段 prompt 头部</b>与对话原文拼接送给 LLM。
 * RubricLoader 不解析 YAML 内部结构 —— 它是给 LLM 看的指令，可读性优先。
 *
 * <p><b>v2.10 来源切换</b>：rubric 真值在 sys_config（key={@code insight.rubric_yaml}），
 * 通过系统设置 → Judge 模型 Tab 实时编辑。RubricLoader 启动时：
 * <ol>
 *   <li>{@code seedIfAbsent}：DB 没值时把 classpath:insight/rubric-v3.0.yaml 灌进去；</li>
 *   <li>从 sys_config 读出 {@link #rubricText} 缓存到内存；</li>
 *   <li>监听 {@link SystemConfigChangedEvent}，rubric 变更后下一次 judge 调用立即用新版。</li>
 * </ol>
 *
 * <p>更新 rubric 时建议在 UI 同步 bump {@code insight.rubric_version} 做运维标记；
 * 历史会话<b>不会</b>因版本漂移自动重审，需管理员置位 {@code insight_reaudit_required} 或分析报告 force。
 * gz
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RubricLoader {

    public static final String CLASSPATH_FALLBACK = "insight/rubric-v3.0.yaml";

    private final SystemConfigService configService;

    private volatile String rubricText = "";

    @PostConstruct
    void load() {
        seedFromClasspathIfAbsent();
        reloadFromConfig();
        log.info("RubricLoader: loaded {} bytes from sys_config({})",
                rubricText.length(), SystemConfigKeys.INSIGHT_RUBRIC_YAML);
    }

    /** sys_config 里 rubric_yaml 缺失（首次部署 / 全新 DB）时，把 classpath 默认版 seed 进去。 */
    private void seedFromClasspathIfAbsent() {
        if (configService.find(SystemConfigKeys.INSIGHT_RUBRIC_YAML).isPresent()) {
            return;
        }
        String fallback = "";
        try (InputStream in = new ClassPathResource(CLASSPATH_FALLBACK).getInputStream()) {
            fallback = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("RubricLoader: failed to read classpath fallback {}, will seed empty",
                    CLASSPATH_FALLBACK, e);
        }
        configService.seedIfAbsent(
                SystemConfigKeys.INSIGHT_RUBRIC_YAML,
                fallback,
                "text",
                SystemConfigKeys.CAT_INSIGHT,
                false,
                "Rubric YAML 模板（拼到 LLM prompt 头部用于评判）；改完立即生效，建议同时 bump rubric_version");
    }

    private void reloadFromConfig() {
        String fromDb = configService.getString(SystemConfigKeys.INSIGHT_RUBRIC_YAML, "");
        this.rubricText = fromDb == null ? "" : fromDb;
    }

    /** sys_config 中 rubric_yaml 被修改后立即生效；下一次 judge 调用就用新 rubric。 */
    @EventListener
    public void onConfigChanged(SystemConfigChangedEvent ev) {
        Set<String> keys = ev.getChangedKeys();
        if (keys != null && keys.contains(SystemConfigKeys.INSIGHT_RUBRIC_YAML)) {
            reloadFromConfig();
            log.info("RubricLoader: rubric reloaded after sys_config change, {} bytes",
                    rubricText.length());
        }
    }

    public String rubricText() {
        return rubricText;
    }
}
