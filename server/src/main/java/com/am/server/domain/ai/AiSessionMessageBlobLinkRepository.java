package com.am.server.domain.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AiSessionMessageBlobLinkRepository extends JpaRepository<AiSessionMessageBlobLink, Long> {

    List<AiSessionMessageBlobLink> findByMessageIdOrderByPartIndexAsc(Long messageId);

    boolean existsByMessageIdAndBlobId(Long messageId, Long blobId);

    /** 这批消息引用过哪些 blob（idx_message 逐个定位）。内容保留期清理用。 */
    @Query(value = "SELECT DISTINCT blob_id FROM ai_session_message_blob_link WHERE message_id IN (:messageIds)",
            nativeQuery = true)
    List<Long> findBlobIdsByMessageIdIn(@Param("messageIds") Collection<Long> messageIds);
}
