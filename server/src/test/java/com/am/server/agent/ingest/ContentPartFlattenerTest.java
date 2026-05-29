package com.am.server.agent.ingest;

import com.am.server.agent.api.dto.ContentPartDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentPartFlattenerTest {

    @Test
    void flatten_toolCallAndImage() {
        ContentPartDto tool = new ContentPartDto();
        tool.setType("tool_call");
        tool.setToolName("Read");
        tool.setArgumentsJson("{\"path\":\"/a\"}");

        ContentPartDto img = new ContentPartDto();
        img.setType("image");
        img.setBlobId(42L);
        img.setWidth(100);
        img.setHeight(200);

        String flat = ContentPartFlattener.flatten(List.of(tool, img));
        assertTrue(flat.contains("[tool_call Read]"));
        assertTrue(flat.contains("{\"path\":\"/a\"}"));
        assertTrue(flat.contains("[image 100x200 blob_id=42]"));
    }

    @Test
    void resolveContentKind_multipartWhenTool() {
        ContentPartDto p = new ContentPartDto();
        p.setType("tool_call");
        p.setToolName("Bash");
        assertEquals("multipart", ContentPartFlattener.resolveContentKind(List.of(p)));
    }

    @Test
    void resolveContentKind_systemOnly() {
        ContentPartDto p = new ContentPartDto();
        p.setType("system_context");
        p.setText("cwd=/tmp");
        assertEquals("system_only", ContentPartFlattener.resolveContentKind(List.of(p)));
    }
}
