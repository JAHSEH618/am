package com.am.server.web.dto;

import com.am.server.agent.api.dto.ContentPartDto;
import com.am.server.domain.ai.AiSessionMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 会话消息视图
 * gz
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AiSessionMessageDto {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Long id;
    private Long aiSessionId;
    private String externalMessageId;
    private String role;
    private int sequenceNo;
    private Integer conversationOrder;
    private String contentText;
    private String toolName;
    private int inputTokens;
    private int outputTokens;
    private LocalDateTime messageTime;

    private int slashCommandCount;
    private int slashSkillCount;
    private String slashHitsJson;

    private List<ContentPartDto> contentParts;
    private String contentKind;
    private boolean hasBinary;
    private int partsCount;
    private int ingestVersion;

    public static AiSessionMessageDto of(AiSessionMessage m) {
        AiSessionMessageDto d = new AiSessionMessageDto();
        d.id = m.getId();
        d.aiSessionId = m.getAiSessionId();
        d.externalMessageId = m.getExternalMessageId();
        d.role = m.getRole();
        d.sequenceNo = m.getSequenceNo() == null ? 0 : m.getSequenceNo();
        d.conversationOrder = m.getConversationOrder();
        d.contentText = m.getContentText();
        d.toolName = m.getToolName();
        d.inputTokens = m.getInputTokens() == null ? 0 : m.getInputTokens();
        d.outputTokens = m.getOutputTokens() == null ? 0 : m.getOutputTokens();
        d.messageTime = m.getMessageTime();
        d.slashCommandCount = m.getSlashCommandCount() == null ? 0 : m.getSlashCommandCount();
        d.slashSkillCount = m.getSlashSkillCount() == null ? 0 : m.getSlashSkillCount();
        d.slashHitsJson = m.getSlashHitsJson();
        d.contentKind = m.getContentKind() != null ? m.getContentKind() : "text_only";
        d.hasBinary = m.getHasBinary() != null && m.getHasBinary() == 1;
        d.partsCount = m.getPartsCount() == null ? 0 : m.getPartsCount();
        d.ingestVersion = m.getIngestVersion() == null ? 0 : m.getIngestVersion();
        if (m.getContentPartsJson() != null && !m.getContentPartsJson().isBlank()) {
            try {
                d.contentParts = MAPPER.readValue(m.getContentPartsJson(), new TypeReference<>() {});
            } catch (Exception ignored) {
                d.contentParts = null;
            }
        }
        return d;
    }
}
