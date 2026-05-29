package com.am.server.domain.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiSessionMessageBlobRepository extends JpaRepository<AiSessionMessageBlob, Long> {

    Optional<AiSessionMessageBlob> findByContentSha256(String contentSha256);
}
