package com.am.server.insight.narrative;

import com.am.server.insight.audit.OpenAiCompatibleJudgeClient;
import com.am.server.insight.config.InsightProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OpenAiNarrativeCompletionClient implements NarrativeCompletionClient {

    private final OpenAiCompatibleJudgeClient delegate;

    @Override
    public String complete(InsightProperties.JudgeConfig config, String prompt) {
        return delegate.completeRaw(config, prompt);
    }
}
