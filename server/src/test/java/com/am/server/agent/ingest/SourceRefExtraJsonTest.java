package com.am.server.agent.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceRefExtraJsonTest {

    @Test
    void buildSourceRefExtraJson_toolCallDedupeRefWithNullSeparator() {
        String ref = "Read\u00002026-05-11T17:33:33";
        String json = AbstractAiSessionIngestService.buildSourceRefExtraJson(ref);
        assertTrue(json.contains("\\u0000"));
    }

    @Test
    void buildSourceRefExtraJson_escapesControlChars() {
        String json = AbstractAiSessionIngestService.buildSourceRefExtraJson("line1\nline2\"quote\\");
        assertTrue(json.contains("\\n"));
        assertTrue(json.contains("\\\""));
        assertTrue(json.contains("\\\\"));
    }

    @Test
    void buildSourceRefExtraJson_plainUuidRef() {
        String ref = "6782ad31-b318-4656-b4c0-254a6b4cbf45:d6080b46-7d02-43e1-8388-7a4a7723a787";
        assertEquals("{\"source_ref\":\"" + ref + "\"}",
                AbstractAiSessionIngestService.buildSourceRefExtraJson(ref));
    }
}
