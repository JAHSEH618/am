package com.am.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * /capability 按人下钻行：单用户窗口内的能力使用明细（含二级明细行，sub_item 空串=汇总粒度）。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityUserItemDto {
    private String kind;
    private String item;
    private String subItem;
    private long invokeCount;
    private long sessionCount;
}
