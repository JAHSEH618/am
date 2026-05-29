package com.am.server.domain.employee;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 员工 Repository
 * gz
 */
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByUserCode(String userCode);

    /**
     * 按状态批量查询，按 user_code 升序——给员工数据列表"全员视图"用：
     * 不仅展示有 daily_summary 的活跃员工，也展示 ACTIVE 在岗但还没产生 AI 数据的员工
     * （没装客户端 / 装了没用过 cursor / 完全沉默），方便管理者识别"未渗透"对象。
     */
    List<Employee> findByStatusOrderByUserCodeAsc(String status);
}
