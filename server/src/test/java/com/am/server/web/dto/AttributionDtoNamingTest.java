package com.am.server.web.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /attribution DTO 与前端的 snake_case 字段契约测试。
 * <p>专防 bCommits 这类单字母前缀字段：Lombok 生成 getBCommits，Jackson 的 bean 名称
 * 改写 + SNAKE_CASE 转换结果并不显然，前端 types.ts 按这里断言的字段名书写。
 * gz
 */
class AttributionDtoNamingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .findAndRegisterModules();

    @Test
    void trendPointFieldNames() throws Exception {
        AttributionTrendPointDto p = new AttributionTrendPointDto(
                LocalDate.of(2026, 7, 21), 1, 2, 3, 10, 20, 30, true);
        String json = MAPPER.writeValueAsString(p);
        assertTrue(json.contains("\"b_commits\":1"), json);
        assertTrue(json.contains("\"a_commits\":2"), json);
        assertTrue(json.contains("\"none_commits\":3"), json);
        assertTrue(json.contains("\"b_lines\":10"), json);
        assertTrue(json.contains("\"a_lines\":20"), json);
        assertTrue(json.contains("\"none_lines\":30"), json);
        assertTrue(json.contains("\"backfilled\":true"), json);
    }

    @Test
    void pivotCellFieldNames() throws Exception {
        AttributionPivotDto.Cell cell = new AttributionPivotDto.Cell("u1", "*", "B", 5, 100, 20);
        String json = MAPPER.writeValueAsString(cell);
        assertTrue(json.contains("\"row_key\":\"u1\""), json);
        assertTrue(json.contains("\"col_key\":\"*\""), json);
        assertTrue(json.contains("\"commit_count\":5"), json);
        assertTrue(json.contains("\"lines_added\":100"), json);
    }
}
