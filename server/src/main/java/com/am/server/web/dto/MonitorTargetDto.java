package com.am.server.web.dto;

import com.am.server.domain.monitor.MonitorTarget;

/**
 * 监控目标字典 DTO（前端渲染用）。
 * <p>
 * 字段直接对齐 monitor_target 表，前端拿到后用作 Segmented 选项 + Tag 颜色映射。
 *
 * gz
 */
public record MonitorTargetDto(
        String typeCode,
        String typeName,
        Integer enabled,
        String displayColor,
        Integer sortNo,
        String description) {

    public static MonitorTargetDto of(MonitorTarget t) {
        return new MonitorTargetDto(
                t.getTypeCode(),
                t.getTypeName(),
                t.getEnabled(),
                t.getDisplayColor() == null ? "default" : t.getDisplayColor(),
                t.getSortNo() == null ? 100 : t.getSortNo(),
                t.getDescription());
    }
}
