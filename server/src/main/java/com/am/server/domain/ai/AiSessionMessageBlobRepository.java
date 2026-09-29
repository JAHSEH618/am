package com.am.server.domain.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface AiSessionMessageBlobRepository extends JpaRepository<AiSessionMessageBlob, Long> {

    Optional<AiSessionMessageBlob> findByContentSha256(String contentSha256);

    /**
     * 内容保留期清理：把 gzip_blob 置为空字节（列 NOT NULL，行 / sha / mime / byte_size / 宽高保留），
     * 仅限「已没有任何仍带 content_parts_json 的消息引用它」的 blob——一条同 sha 的新消息复用旧 blob 时，
     * 它的 parts 非空，这条 blob 就不会被清。已为空的行不重复写。
     * 之后再有消息带来同一份内容时由 {@code MessageContentIngestService#storeBlob} 补回字节。
     */
    @Modifying
    @Query(value = """
            UPDATE ai_session_message_blob b SET b.gzip_blob = ''
            WHERE b.id IN (:blobIds) AND LENGTH(b.gzip_blob) > 0
              AND NOT EXISTS (
                  SELECT 1 FROM ai_session_message_blob_link l
                  JOIN ai_session_message m ON m.id = l.message_id
                  WHERE l.blob_id = b.id AND m.content_parts_json IS NOT NULL)
            """, nativeQuery = true)
    int clearBlobsNotReferencedByLiveMessages(@Param("blobIds") Collection<Long> blobIds);
}
