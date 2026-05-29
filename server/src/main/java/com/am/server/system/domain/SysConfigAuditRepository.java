package com.am.server.system.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * sys_config_audit 数据访问。
 * gz
 */
public interface SysConfigAuditRepository extends JpaRepository<SysConfigAudit, Long> {

    /** 默认列表：按时间倒序。 */
    Page<SysConfigAudit> findAllByOrderByChangedTimeDescIdDesc(Pageable pageable);

    /** 按 category 过滤。 */
    Page<SysConfigAudit> findByCategoryOrderByChangedTimeDescIdDesc(String category, Pageable pageable);

    /** 按 configKey 模糊。 */
    @Query("""
            SELECT a FROM SysConfigAudit a
            WHERE a.configKey LIKE :kw
            ORDER BY a.changedTime DESC, a.id DESC
            """)
    Page<SysConfigAudit> searchByKey(@Param("kw") String kwLike, Pageable pageable);

    /** category + key 模糊组合。 */
    @Query("""
            SELECT a FROM SysConfigAudit a
            WHERE a.category = :cat
              AND a.configKey LIKE :kw
            ORDER BY a.changedTime DESC, a.id DESC
            """)
    Page<SysConfigAudit> searchByCategoryAndKey(@Param("cat") String category,
                                                @Param("kw") String kwLike,
                                                Pageable pageable);
}
