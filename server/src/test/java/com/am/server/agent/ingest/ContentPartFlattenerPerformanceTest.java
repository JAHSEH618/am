package com.am.server.agent.ingest;

import com.am.server.agent.api.dto.ContentPartDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentPartFlattenerPerformanceTest {

    @Test
    void flatten_large_session_under_one_second() {
        List<ContentPartDto> parts = new ArrayList<>(500);
        for (int i = 0; i < 500; i++) {
            ContentPartDto p = new ContentPartDto();
            p.setType(i % 3 == 0 ? "tool_result" : "text");
            p.setText("line-" + i + "-" + "x".repeat(200));
            p.setSortOrder(i);
            parts.add(p);
        }
        long start = System.nanoTime();
        String flat = ContentPartFlattener.flatten(parts);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(flat.length() > 50_000);
        assertTrue(ms < 1000, "flatten took " + ms + "ms");
    }
}
