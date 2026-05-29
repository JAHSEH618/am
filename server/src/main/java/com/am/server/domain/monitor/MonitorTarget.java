package com.am.server.domain.monitor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
 * 监控目标字典实体
 * 一行 = 一种被监控软件，例如 cursor / claude / codex
 * 提供给 Agent 端 Provider 启停、给后端做路由分发
 * gz
 */
@Entity
@Table(name = "monitor_target")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class MonitorTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "type_code", nullable = false, unique = true, length = 32)
    private String typeCode;

    @Column(name = "type_name", nullable = false, length = 64)
    private String typeName;

    @Column(name = "enabled", nullable = false, columnDefinition = "TINYINT")
    private Integer enabled;

    /**
     * AntD Tag 颜色名（geekblue / magenta / green / purple / orange / cyan / ...），
     * 用于前端 Segmented + Tag 统一上色，避免硬编码。
     * 老库无此列时由 schema 升级脚本 ALTER ADD 出来，默认值 default。
     */
    @Column(name = "display_color", nullable = false, length = 16)
    private String displayColor;

    /**
     * 前端 Segmented 排序字段，越小越靠前。
     */
    @Column(name = "sort_no", nullable = false)
    private Integer sortNo;

    @Column(name = "description", length = 256)
    private String description;

    @CreatedDate
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;

    @LastModifiedDate
    @Column(name = "updated_time", nullable = false)
    private LocalDateTime updatedTime;
}
