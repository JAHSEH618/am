package com.am.server.system.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 运行时配置实体（一行 = 一个 key）。
 *
 * <p>定位见 {@code schema.sql §9}：所有原本要改 {@code application.yml} 重启才能生效的配置项，
 * 搬到这张表 + {@link com.am.server.system.SystemConfigService} 单点读写 + Spring 事件广播 →
 * 改完即时生效，不重启。
 *
 * <p>分组 (category)：{@code agents / scheduling / judge / insight / auth}，前端按分组渲染。
 * is_secret=1 的 key（api-key / password / token）前端默认 **** 不回显，需要点 [👁] 显式拉明文。
 * gz
 */
@Entity
@Table(name = "sys_config")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class SysConfig {

    @Id
    @Column(name = "config_key", nullable = false, length = 128)
    private String configKey;

    @Column(name = "config_value", columnDefinition = "MEDIUMTEXT")
    private String configValue;

    @Column(name = "value_type", nullable = false, length = 16)
    private String valueType = "string";

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "is_secret", nullable = false, columnDefinition = "TINYINT")
    private Integer isSecret = 0;

    @Column(name = "description", length = 256)
    private String description;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    @CreatedDate
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;

    @LastModifiedDate
    @Column(name = "updated_time", nullable = false)
    private LocalDateTime updatedTime;
}
