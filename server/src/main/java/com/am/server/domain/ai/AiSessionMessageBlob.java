package com.am.server.domain.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 会话消息二进制附件（按 content_sha256 去重）。
 * gz
 */
@Entity
@Table(name = "ai_session_message_blob")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class AiSessionMessageBlob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Column(name = "mime_type", nullable = false, length = 128)
    private String mimeType = "application/octet-stream";

    @Column(name = "byte_size", nullable = false)
    private Integer byteSize;

    @Lob
    @Column(name = "gzip_blob", nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] gzipBlob;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @CreatedDate
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;
}
