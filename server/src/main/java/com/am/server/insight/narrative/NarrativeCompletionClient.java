package com.am.server.insight.narrative;

import com.am.server.insight.config.InsightProperties;

/** 叙事补全的窄接口：一段 prompt → 原始文本；便于单测替身。gz */
@FunctionalInterface
public interface NarrativeCompletionClient {
    String complete(InsightProperties.JudgeConfig config, String prompt);
}
