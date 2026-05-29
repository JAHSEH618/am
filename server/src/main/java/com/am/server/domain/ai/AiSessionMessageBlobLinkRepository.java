package com.am.server.domain.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiSessionMessageBlobLinkRepository extends JpaRepository<AiSessionMessageBlobLink, Long> {

    List<AiSessionMessageBlobLink> findByMessageIdOrderByPartIndexAsc(Long messageId);

    boolean existsByMessageIdAndBlobId(Long messageId, Long blobId);
}
