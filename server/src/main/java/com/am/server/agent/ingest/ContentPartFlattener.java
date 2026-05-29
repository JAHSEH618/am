package com.am.server.agent.ingest;

import com.am.server.agent.api.dto.ContentPartDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 将 content_parts 扁平化为 content_text（审计 / 搜索 / 旧前端回退）。
 * gz
 */
public final class ContentPartFlattener {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContentPartFlattener() {
    }

    public static String flattenJson(String contentPartsJson) {
        if (contentPartsJson == null || contentPartsJson.isBlank()) {
            return "";
        }
        try {
            List<ContentPartDto> parts = MAPPER.readValue(contentPartsJson, new TypeReference<>() {});
            return flatten(parts);
        } catch (Exception e) {
            return "";
        }
    }

    public static String flatten(List<ContentPartDto> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(4096);
        for (ContentPartDto p : parts) {
            if (p == null || p.getType() == null) {
                continue;
            }
            switch (p.getType()) {
                case "text", "thinking", "system_context", "tool_result" -> appendBlock(sb, p.getType(), p.getText());
                case "file_ref", "file_snippet" -> {
                    String header = p.getPath() != null ? p.getPath() : "";
                    if (p.getStartLine() != null && p.getEndLine() != null) {
                        header += ":" + p.getStartLine() + "-" + p.getEndLine();
                    }
                    appendBlock(sb, p.getType(), header);
                    if (p.getText() != null && !p.getText().isBlank()) {
                        sb.append(p.getText());
                        if (!p.getText().endsWith("\n")) {
                            sb.append('\n');
                        }
                    }
                }
                case "image" -> {
                    String dim = "";
                    if (p.getWidth() != null && p.getHeight() != null) {
                        dim = p.getWidth() + "x" + p.getHeight();
                    }
                    sb.append("[image");
                    if (!dim.isEmpty()) {
                        sb.append(' ').append(dim);
                    }
                    if (p.getBlobId() != null) {
                        sb.append(" blob_id=").append(p.getBlobId());
                    }
                    sb.append("]\n");
                }
                case "tool_call" -> {
                    sb.append("[tool_call ").append(nullToEmpty(p.getToolName())).append("]\n");
                    if (p.getArgumentsJson() != null && !p.getArgumentsJson().isBlank()) {
                        sb.append(p.getArgumentsJson()).append('\n');
                    }
                }
                default -> {
                    if (p.getText() != null && !p.getText().isBlank()) {
                        appendBlock(sb, p.getType(), p.getText());
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    public static String resolveContentKind(List<ContentPartDto> parts) {
        if (parts == null || parts.isEmpty()) {
            return "text_only";
        }
        boolean hasUserContent = false;
        boolean onlySystem = true;
        for (ContentPartDto p : parts) {
            if (p == null || p.getType() == null) {
                continue;
            }
            if ("system_context".equals(p.getType())) {
                continue;
            }
            onlySystem = false;
            if ("text".equals(p.getType()) || "thinking".equals(p.getType())
                    || "image".equals(p.getType()) || "file_snippet".equals(p.getType())
                    || "file_ref".equals(p.getType()) || "tool_call".equals(p.getType())
                    || "tool_result".equals(p.getType())) {
                hasUserContent = true;
            }
        }
        if (onlySystem) {
            return "system_only";
        }
        if (hasUserContent) {
            for (ContentPartDto p : parts) {
                if (p != null && ("image".equals(p.getType()) || "file_snippet".equals(p.getType())
                        || "tool_call".equals(p.getType()) || "tool_result".equals(p.getType()))) {
                    return "multipart";
                }
            }
            return "text_only";
        }
        return "multipart";
    }

    private static void appendBlock(StringBuilder sb, String label, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if ("system_context".equals(label)) {
            sb.append("--- system ---\n");
        }
        sb.append(text);
        if (!text.endsWith("\n")) {
            sb.append('\n');
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
