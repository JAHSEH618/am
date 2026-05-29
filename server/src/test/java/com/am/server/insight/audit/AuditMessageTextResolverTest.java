package com.am.server.insight.audit;

import com.am.server.domain.ai.AiSessionMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditMessageTextResolverTest {

    @Test
    void prefers_content_parts_over_legacy_text() {
        AiSessionMessage m = new AiSessionMessage();
        m.setIngestVersion(1);
        m.setContentText("legacy only");
        m.setContentPartsJson("""
                [{"type":"tool_call","tool_name":"Read","arguments_json":"{}","sort_order":0},
                 {"type":"tool_result","text":"file body","sort_order":1}]
                """);
        String flat = AuditMessageTextResolver.forAudit(m, 10_000);
        assertTrue(flat.contains("[tool_call Read]"));
        assertTrue(flat.contains("file body"));
    }

    @Test
    void falls_back_to_content_text_when_no_parts() {
        AiSessionMessage m = new AiSessionMessage();
        m.setIngestVersion(0);
        m.setContentText("hello audit");
        String flat = AuditMessageTextResolver.forAudit(m, 10_000);
        assertTrue(flat.contains("hello audit"));
    }

    @Test
    void truncates_long_audit_text() {
        AiSessionMessage m = new AiSessionMessage();
        m.setContentText("x".repeat(100));
        String flat = AuditMessageTextResolver.forAudit(m, 50);
        assertTrue(flat.endsWith("...[截断]"));
        assertTrue(flat.length() < 100);
    }
}
