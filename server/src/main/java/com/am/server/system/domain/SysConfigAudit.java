package com.am.server.system.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * sys_config 的变更流水（操作日志 Tab 的数据源）。
 *
 * <p>每次 {@link com.am.server.system.SystemConfigService#set} 或 {@code setBatch} 成功后
 * 写入一条；同步入库（与 sys_config 在同一事务），保证审计与变更一致。
 *
 * <p>密级与 sys_config 等同：is_secret=1 的 old/new_value 明文进库——
 * 这张表只在 admin 鉴权后通过 API 暴露，不再单独加密。
 *
 * gz
 */
@Entity
@Table(name = "sys_config_audit")
@Getter
@Setter
@NoArgsConstructor
public class SysConfigAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, length = 128)
    private String configKey;

    @Column(name = "old_value", columnDefinition = "MEDIUMTEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "MEDIUMTEXT")
    private String newValue;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "is_secret", nullable = false, columnDefinition = "TINYINT")
    private Integer isSecret = 0;

    @Column(name = "operator", length = 64)
    private String operator;

    @Column(name = "changed_time", nullable = false)
    private LocalDateTime changedTime;
}
