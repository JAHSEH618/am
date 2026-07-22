package com.am.server.web;

import com.am.server.common.R;
import com.am.server.service.AiPenetrationService;
import com.am.server.service.PenetrationWindow;
import com.am.server.web.dto.AttributionCommitRowDto;
import com.am.server.web.dto.AttributionPivotDto;
import com.am.server.web.dto.AttributionTrendPointDto;
import com.am.server.web.dto.PageDto;
import com.am.server.web.dto.PenetrationCheckDto;
import com.am.server.web.support.AttributionStatSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * /attribution 产出归因（B 确定 / A 疑似双档，见《管理后台-产出归因与能力使用分析 v1.0》§2）：
 * 团队趋势、人×项目×工具×模型交叉透视、commit 明细抽屉、渗透率迁移对照。
 * 全部查询只打 git_commit_attribution 预计算表；写入由归因引擎（增量/夜间批/回溯）负责，
 * 读路径无 ensureFresh——commit 数据本身就是分钟级延迟采集，增量去抖 15s 已足够新鲜。
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/attribution")
public class AttributionController {

    private final AttributionStatSupport attributionStatSupport;
    private final AiPenetrationService aiPenetrationService;

    @GetMapping("/trend")
    public R<List<AttributionTrendPointDto>> trend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDateTime[] window = window(from, to);
        return R.ok(attributionStatSupport.trend(window[0], window[1]));
    }

    @GetMapping("/pivot")
    public R<AttributionPivotDto> pivot(
            @RequestParam(defaultValue = AttributionStatSupport.DIM_USER) String row,
            @RequestParam(defaultValue = AttributionStatSupport.DIM_NONE) String col,
            @RequestParam(value = "user_code", required = false) String userCode,
            @RequestParam(value = "project_name", required = false) String projectName,
            @RequestParam(value = "target_type", required = false) String targetType,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDateTime[] window = window(from, to);
        return R.ok(attributionStatSupport.pivot(
                AttributionStatSupport.normalizeRowDim(row),
                AttributionStatSupport.normalizeColDim(col),
                userCode, projectName, targetType, model,
                window[0], window[1]));
    }

    @GetMapping("/commits")
    public R<PageDto<AttributionCommitRowDto>> commits(
            @RequestParam(value = "user_code", required = false) String userCode,
            @RequestParam(value = "project_name", required = false) String projectName,
            @RequestParam(value = "target_type", required = false) String targetType,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String tier,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDateTime[] window = window(from, to);
        return R.ok(attributionStatSupport.commits(
                window[0], window[1], userCode, projectName, targetType, model, tier, page, size));
    }

    /** 渗透率迁移对照（验收 #2：30d 偏差 ≤ 2pp，偏差大则暂缓迁移并排查）。 */
    @GetMapping("/penetration-check")
    public R<PenetrationCheckDto> penetrationCheck(
            @RequestParam(defaultValue = "30d") String window) {
        PenetrationWindow w = PenetrationWindow.parse(window);
        AiPenetrationService.Comparison c = aiPenetrationService.compare(w);
        return R.ok(new PenetrationCheckDto(window, c.legacyPercent(), c.attributionPercent(), c.deviationPp()));
    }

    /** 缺省窗口 = 近 30 天（含今日）；返回 [fromInclusive, toExclusive)。 */
    private static LocalDateTime[] window(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        return new LocalDateTime[]{start.atStartOfDay(), end.plusDays(1).atStartOfDay()};
    }
}
