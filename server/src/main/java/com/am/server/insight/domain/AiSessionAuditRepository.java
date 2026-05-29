package com.am.server.insight.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 会话级审计缓存 Repository
 * gz
 */
public interface AiSessionAuditRepository extends JpaRepository<AiSessionAudit, Long> {

    Optional<AiSessionAudit> findByAiSessionId(Long aiSessionId);

    /** 批量取一组 session 的已审计结果（窗口聚合时一次性 IN 查询）。 */
    List<AiSessionAudit> findByAiSessionIdIn(Collection<Long> aiSessionIds);
}
