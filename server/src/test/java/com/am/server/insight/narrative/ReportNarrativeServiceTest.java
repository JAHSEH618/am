package com.am.server.insight.narrative;

import com.am.server.insight.config.InsightProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportNarrativeServiceTest {

    private final NarrativePromptLoader loader = mock(NarrativePromptLoader.class);
    private final InsightProperties properties = new InsightProperties();

    private ReportNarrativeService service(NarrativeCompletionClient client) {
        when(loader.userPrompt()).thenReturn("用户评语模板");
        when(loader.teamPrompt()).thenReturn("团队总评模板");
        properties.getJudgeA().setProvider("openai-compatible");
        return new ReportNarrativeService(properties, loader, client);
    }

    @Test
    void userNarrativeHappyPath() {
        String llm = "{\"level_summary\":\"a\",\"evidence\":\"b\",\"strengths\":\"c\",\"weaknesses\":\"d\",\"suggestions\":\"e\"}";
        ReportNarrativeService s = service((cfg, prompt) -> llm);
        String out = s.generateUserNarrative("{\"grade\":\"A\"}");
        assertNotNull(out);
        assertTrue(out.contains("level_summary"));
    }

    @Test
    void stripsCodeFenceAndValidatesKeys() {
        String llm = "```json\n{\"overview\":\"a\",\"highlights\":\"b\",\"risks\":\"c\",\"recommendations\":\"d\"}\n```";
        ReportNarrativeService s = service((cfg, prompt) -> llm);
        assertNotNull(s.generateTeamNarrative("{}"));
    }

    @Test
    void missingKeyReturnsNull() {
        ReportNarrativeService s = service((cfg, prompt) -> "{\"level_summary\":\"only\"}");
        assertNull(s.generateUserNarrative("{}"));
    }

    @Test
    void clientFailureReturnsNullAfterRetries() {
        NarrativeCompletionClient failing = mock(NarrativeCompletionClient.class);
        when(failing.complete(any(), any())).thenThrow(new RuntimeException("boom"));
        ReportNarrativeService s = service(failing);
        assertNull(s.generateUserNarrative("{}"));
        verify(failing, times(3)).complete(any(), any());
    }

    @Test
    void mockProviderSkips() {
        properties.getJudgeA().setProvider("mock");
        NarrativeCompletionClient client = mock(NarrativeCompletionClient.class);
        when(loader.userPrompt()).thenReturn("t");
        when(loader.teamPrompt()).thenReturn("t");
        ReportNarrativeService s = new ReportNarrativeService(properties, loader, client);
        assertNull(s.generateUserNarrative("{}"));
        verifyNoInteractions(client);
    }
}
