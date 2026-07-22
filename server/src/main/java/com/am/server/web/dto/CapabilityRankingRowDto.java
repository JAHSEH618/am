package com.am.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * /capability 排行行（一级维度：skill 名 / MCP server / 插件 namespace）。
 * <p>kind=skill 时 explicitCount / nlCount 分列（显式 / NL 隐式），children 为 null；
 * kind=mcp / plugin_ns 时 children 为二级明细（tool / 技能），explicitCount / nlCount 为 null。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityRankingRowDto {
    private String item;
    private long invokeCount;
    private Long explicitCount;
    private Long nlCount;
    private long userCount;
    private long sessionCount;
    private List<Child> children;

    /** 二级明细行：mcp=tool、plugin_ns=技能名。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Child {
        private String subItem;
        private long invokeCount;
        private long sessionCount;
    }
}
