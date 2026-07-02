package com.am.server.insight.narrative;

import com.am.server.system.SystemConfigKeys;
import com.am.server.system.SystemConfigService;
import com.am.server.system.SystemConfigChangedEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 叙事 prompt：sys_config 为真值，classpath 仅首次 seed；改完热生效（与 RubricLoader 同模式）。gz */
@Slf4j
@Component
@RequiredArgsConstructor
public class NarrativePromptLoader {

    private final SystemConfigService configService;

    private volatile String userPrompt = "";
    private volatile String teamPrompt = "";

    @PostConstruct
    void load() {
        seedIfAbsent(SystemConfigKeys.INSIGHT_NARRATIVE_USER_PROMPT, "insight/narrative-user-prompt.txt",
                "个人评语 prompt 模板（叙事阶段）；改完立即生效");
        seedIfAbsent(SystemConfigKeys.INSIGHT_NARRATIVE_TEAM_PROMPT, "insight/narrative-team-prompt.txt",
                "团队总评 prompt 模板；改完立即生效");
        reload();
    }

    private void seedIfAbsent(String key, String classpath, String desc) {
        if (configService.find(key).isPresent()) {
            return;
        }
        String fallback = "";
        try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
            fallback = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("NarrativePromptLoader: read {} failed, seed empty", classpath, e);
        }
        configService.seedIfAbsent(key, fallback, "text", SystemConfigKeys.CAT_INSIGHT, false, desc);
    }

    private void reload() {
        userPrompt = configService.getString(SystemConfigKeys.INSIGHT_NARRATIVE_USER_PROMPT, "");
        teamPrompt = configService.getString(SystemConfigKeys.INSIGHT_NARRATIVE_TEAM_PROMPT, "");
    }

    @EventListener
    public void onConfigChanged(SystemConfigChangedEvent ev) {
        Set<String> keys = ev.getChangedKeys();
        if (keys != null && (keys.contains(SystemConfigKeys.INSIGHT_NARRATIVE_USER_PROMPT)
                || keys.contains(SystemConfigKeys.INSIGHT_NARRATIVE_TEAM_PROMPT))) {
            reload();
        }
    }

    public String userPrompt() { return userPrompt; }
    public String teamPrompt() { return teamPrompt; }
}
