package com.am.server.domain.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 消息 content_parts 中下标与 blob 的关联。
 * gz
 */
@Entity
@Table(name = "ai_session_message_blob_link")
@Getter
@Setter
@NoArgsConstructor
public class AiSessionMessageBlobLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "part_index", nullable = false)
    private Integer partIndex;

    @Column(name = "blob_id", nullable = false)
    private Long blobId;
}
