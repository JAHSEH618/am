package com.am.server.insight.audit;

import com.am.server.agent.ingest.ContentPartFlattener;
import com.am.server.domain.ai.AiSessionMessage;

/**
 * 为洞察审计拼接会话原文：优先 content_parts，回退 content_text。
 * gz
 */
public final class AuditMessageTextResolver {

    private AuditMessageTextResolver() {
    }

    /**
     * @param maxChars 单条消息在 prompt 中的字符上限（非字节）
     */
    public static String forAudit(AiSessionMessage m, int maxChars) {
        String text = resolveRaw(m);
        if (maxChars > 0 && text.length() > maxChars) {
            return text.substring(0, maxChars) + "...[截断]";
        }
        return text;
    }

    private static String resolveRaw(AiSessionMessage m) {
        if (m == null) {
            return "";
        }
        Integer ver = m.getIngestVersion();
        if (ver != null && ver >= 1
                && m.getContentPartsJson() != null && !m.getContentPartsJson().isBlank()) {
            String flat = ContentPartFlattener.flattenJson(m.getContentPartsJson());
            if (flat != null && !flat.isBlank()) {
                return flat;
            }
        }
        return m.getContentText() == null ? "" : m.getContentText();
    }
}
