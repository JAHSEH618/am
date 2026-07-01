package com.am.server.domain.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * AI 会话消息原文实体
 * 团队内部公开使用，content_text 不做截断
 * 通过 (ai_session_id, external_message_id) 唯一索引去重，保证幂等上报不重写
 * gz
 */
@Entity
@Table(name = "ai_session_message")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class AiSessionMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "aiSessionMessageIdGen")
    @TableGenerator(name = "aiSessionMessageIdGen", table = "id_sequences",
            pkColumnName = "seq_name", valueColumnName = "next_val",
            pkColumnValue = "ai_session_message", allocationSize = 50)
    private Long id;

    @Column(name = "ai_session_id", nullable = false)
    private Long aiSessionId;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "user_code", nullable = false, length = 64)
    private String userCode;

    @Column(name = "external_message_id", length = 128)
    private String externalMessageId;

    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "sequence_no", nullable = false)
    private Integer sequenceNo;

    /** Cursor header 对话顺序（1 起）；与 sequence_no（入库顺序）不同，用于展示排序。 */
    @Column(name = "conversation_order")
    private Integer conversationOrder;

    @Column(name = "content_text", columnDefinition = "MEDIUMTEXT")
    private String contentText;

    @Column(name = "tool_name", length = 512)
    private String toolName;

    @Column(name = "input_tokens")
    private Integer inputTokens = 0;

    @Column(name = "output_tokens")
    private Integer outputTokens = 0;

    @Column(name = "message_time", nullable = false)
    private LocalDateTime messageTime;

    /** 本消息内 user 主动斜杠中判为「命令」的次数（全文多行扫描，入库时写入） */
    @Column(name = "slash_command_count", nullable = false)
    private Integer slashCommandCount = 0;

    /** 本消息内 user 主动斜杠中判为「技能」的次数 */
    @Column(name = "slash_skill_count", nullable = false)
    private Integer slashSkillCount = 0;

    /** 命中明细 JSON：[{token,kind}]，kind∈command|skill|noise；无命中为 null */
    @Column(name = "slash_hits_json", columnDefinition = "JSON")
    private String slashHitsJson;

    @Column(name = "content_parts_json", columnDefinition = "JSON")
    private String contentPartsJson;

    @Column(name = "content_kind", nullable = false, length = 16)
    private String contentKind = "text_only";

    @Column(name = "has_binary", nullable = false, columnDefinition = "TINYINT")
    private Integer hasBinary = 0;

    @Column(name = "parts_count", nullable = false)
    private Integer partsCount = 0;

    @Column(name = "ingest_version", nullable = false, columnDefinition = "TINYINT")
    private Integer ingestVersion = 1;

    @CreatedDate
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;
}
