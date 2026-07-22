package com.am.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 渗透率迁移对照（验收标准 #2：30d 偏差 ≤ 2 个百分点）：
 * legacy = 查询时 JOIN 推断（旧口径）；attribution = git_commit_attribution 预计算 A∪B（新口径）。
 * 任一侧无数据（-1）时 deviation_pp = -1。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PenetrationCheckDto {
    private String window;
    private int legacyPercent;
    private int attributionPercent;
    private int deviationPp;
}
