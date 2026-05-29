package com.am.server.domain.monitor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 监控目标字典 Repository
 * gz
 */
public interface MonitorTargetRepository extends JpaRepository<MonitorTarget, Long> {

    Optional<MonitorTarget> findByTypeCode(String typeCode);

    List<MonitorTarget> findAllByEnabled(Integer enabled);

    /**
     * 按 sort_no、id 升序返回所有 enabled=1 的字典行，用于前端 Segmented 渲染。
     */
    List<MonitorTarget> findAllByEnabledOrderBySortNoAscIdAsc(Integer enabled);
}
