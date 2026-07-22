package com.am.server.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * /attribution 趋势点（按日）：B/A/NONE 三档 commit 数与净行数分列，前端做双档堆叠 + 占比线。
 * backfilled=true 表示该日数据（部分）来自上线前历史回溯，趋势图需注明「回溯推算」。
 * <p>bCommits 这类单字母前缀字段经 Lombok getter（getBCommits）+ Jackson 名称改写会变成
 * "bcommits" 而非 "b_commits"，故显式 @JsonProperty 钉死（契约测试 AttributionDtoNamingTest）。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttributionTrendPointDto {
    private LocalDate date;
    @JsonProperty("b_commits")
    private long bCommits;
    @JsonProperty("a_commits")
    private long aCommits;
    private long noneCommits;
    @JsonProperty("b_lines")
    private long bLines;
    @JsonProperty("a_lines")
    private long aLines;
    private long noneLines;
    private boolean backfilled;
}
