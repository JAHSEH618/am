package com.am.server.web.support;

import com.am.server.domain.summary.CapabilityDaily;
import com.am.server.domain.summary.CapabilityDailyRepository;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.web.dto.CapabilityMatrixDto;
import com.am.server.web.dto.CapabilityRankingRowDto;
import com.am.server.web.dto.CapabilityTrendPointDto;
import com.am.server.web.dto.CapabilityUserItemDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * /capability 窗口聚合：全部只打 capability_daily（预聚合表，见
 * 《管理后台-产出归因与能力使用分析 v1.0》§3.3），不实时扫 slash_hits_json / tool_name。
 *
 * <p>kind 参数语义（与前端 tab 对齐）：
 * <ul>
 *   <li>{@code skill} → 表内 kind ∈ (skill, nl_skill)，排行/趋势显式与 NL 分列</li>
 *   <li>{@code mcp} → MCP server 一级 + tool 二级</li>
 *   <li>{@code plugin_ns} → 插件 namespace 一级 + 技能二级</li>
 * </ul>
 * gz
 */
@Component
@RequiredArgsConstructor
public class CapabilityStatSupport {

    private final CapabilityDailyRepository capabilityRepository;
    private final EmployeeDisplayService employeeDisplayService;

    /** tab kind → capability_daily.kind 集合。非法值回退 skill tab。 */
    public static List<String> kindsOf(String kind) {
        if (CapabilityDaily.KIND_MCP.equals(kind)) {
            return List.of(CapabilityDaily.KIND_MCP);
        }
        if (CapabilityDaily.KIND_PLUGIN_NS.equals(kind)) {
            return List.of(CapabilityDaily.KIND_PLUGIN_NS);
        }
        return List.of(CapabilityDaily.KIND_SKILL, CapabilityDaily.KIND_NL_SKILL);
    }

    private static boolean isSkillTab(String kind) {
        return !CapabilityDaily.KIND_MCP.equals(kind) && !CapabilityDaily.KIND_PLUGIN_NS.equals(kind);
    }

    public List<CapabilityRankingRowDto> ranking(String kind, LocalDate from, LocalDate to, int limit) {
        List<String> kinds = kindsOf(kind);
        Map<String, CapabilityRankingRowDto> byItem = new LinkedHashMap<>();
        for (Object[] row : capabilityRepository.rankRollupByItemKind(from, to, kinds)) {
            if (row == null || row.length < 5 || row[0] == null) {
                continue;
            }
            String item = row[0].toString();
            String rowKind = row[1] == null ? "" : row[1].toString();
            long invokes = toLong(row[2]);
            long sessions = toLong(row[4]);
            CapabilityRankingRowDto dto = byItem.computeIfAbsent(item, k -> {
                CapabilityRankingRowDto fresh = new CapabilityRankingRowDto();
                fresh.setItem(k);
                if (isSkillTab(kind)) {
                    fresh.setExplicitCount(0L);
                    fresh.setNlCount(0L);
                }
                return fresh;
            });
            dto.setInvokeCount(dto.getInvokeCount() + invokes);
            dto.setSessionCount(dto.getSessionCount() + sessions);
            if (isSkillTab(kind)) {
                if (CapabilityDaily.KIND_NL_SKILL.equals(rowKind)) {
                    dto.setNlCount(dto.getNlCount() + invokes);
                } else {
                    dto.setExplicitCount(dto.getExplicitCount() + invokes);
                }
            }
        }
        // 使用人数：跨 kind 按 item 去重（显式 + NL 同人只算一次）
        for (Object[] row : capabilityRepository.rankUserCountByItem(from, to, kinds)) {
            if (row == null || row.length < 2 || row[0] == null) {
                continue;
            }
            CapabilityRankingRowDto dto = byItem.get(row[0].toString());
            if (dto != null) {
                dto.setUserCount(toLong(row[1]));
            }
        }
        // 二级明细（mcp=tool、plugin_ns=技能）挂 children
        if (!isSkillTab(kind)) {
            Map<String, List<CapabilityRankingRowDto.Child>> childrenByItem = new HashMap<>();
            for (Object[] row : capabilityRepository.rankDetailByItemSubItem(from, to, kind)) {
                if (row == null || row.length < 4 || row[0] == null || row[1] == null) {
                    continue;
                }
                childrenByItem.computeIfAbsent(row[0].toString(), k -> new ArrayList<>())
                        .add(new CapabilityRankingRowDto.Child(
                                row[1].toString(), toLong(row[2]), toLong(row[3])));
            }
            for (Map.Entry<String, List<CapabilityRankingRowDto.Child>> en : childrenByItem.entrySet()) {
                CapabilityRankingRowDto dto = byItem.get(en.getKey());
                if (dto != null) {
                    en.getValue().sort(Comparator.comparingLong(
                            CapabilityRankingRowDto.Child::getInvokeCount).reversed());
                    dto.setChildren(en.getValue());
                }
            }
        }
        return byItem.values().stream()
                .sorted(Comparator.comparingLong(CapabilityRankingRowDto::getInvokeCount).reversed())
                .limit(Math.max(limit, 1))
                .toList();
    }

    public List<CapabilityTrendPointDto> trend(String kind, LocalDate from, LocalDate to) {
        List<String> kinds = kindsOf(kind);
        Map<LocalDate, CapabilityTrendPointDto> byDate = new TreeMap<>();
        for (Object[] row : capabilityRepository.trendByDateKind(from, to, kinds)) {
            if (row == null || row.length < 3 || row[0] == null) {
                continue;
            }
            LocalDate date = toDate(row[0]);
            if (date == null) {
                continue;
            }
            String rowKind = row[1] == null ? "" : row[1].toString();
            long invokes = toLong(row[2]);
            CapabilityTrendPointDto p = byDate.computeIfAbsent(date, d -> {
                CapabilityTrendPointDto fresh = new CapabilityTrendPointDto();
                fresh.setDate(d);
                if (isSkillTab(kind)) {
                    fresh.setExplicitCount(0L);
                    fresh.setNlCount(0L);
                }
                return fresh;
            });
            p.setInvokeCount(p.getInvokeCount() + invokes);
            if (isSkillTab(kind)) {
                if (CapabilityDaily.KIND_NL_SKILL.equals(rowKind)) {
                    p.setNlCount(p.getNlCount() + invokes);
                } else {
                    p.setExplicitCount(p.getExplicitCount() + invokes);
                }
            }
        }
        return new ArrayList<>(byDate.values());
    }

    public CapabilityMatrixDto matrix(String kind, LocalDate from, LocalDate to) {
        List<String> kinds = kindsOf(kind);
        Map<String, Map<String, Long>> byUser = new LinkedHashMap<>();
        Map<String, Long> itemTotals = new HashMap<>();
        for (Object[] row : capabilityRepository.matrixByUserItem(from, to, kinds)) {
            if (row == null || row.length < 3 || row[0] == null || row[1] == null) {
                continue;
            }
            String user = row[0].toString();
            String item = row[1].toString();
            long invokes = toLong(row[2]);
            byUser.computeIfAbsent(user, k -> new LinkedHashMap<>()).merge(item, invokes, Long::sum);
            itemTotals.merge(item, invokes, Long::sum);
        }
        List<String> items = itemTotals.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
        List<CapabilityMatrixDto.UserRow> users = byUser.entrySet().stream()
                .map(en -> new CapabilityMatrixDto.UserRow(
                        en.getKey(),
                        employeeDisplayService.displayOf(en.getKey()),
                        en.getValue().values().stream().mapToLong(Long::longValue).sum()))
                .sorted(Comparator.comparingLong(CapabilityMatrixDto.UserRow::getTotalCount).reversed())
                .toList();
        Map<String, Integer> itemIndex = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            itemIndex.put(items.get(i), i);
        }
        List<int[]> cells = new ArrayList<>();
        for (int u = 0; u < users.size(); u++) {
            Map<String, Long> rowValues = byUser.get(users.get(u).getUserCode());
            for (Map.Entry<String, Long> en : rowValues.entrySet()) {
                Integer i = itemIndex.get(en.getKey());
                if (i != null && en.getValue() > 0) {
                    cells.add(new int[]{u, i, (int) Math.min(en.getValue(), Integer.MAX_VALUE)});
                }
            }
        }
        return new CapabilityMatrixDto(users, items, cells);
    }

    public List<CapabilityUserItemDto> userDrilldown(String userCode, LocalDate from, LocalDate to) {
        List<CapabilityUserItemDto> out = new ArrayList<>();
        for (Object[] row : capabilityRepository.userDrilldown(userCode, from, to)) {
            if (row == null || row.length < 5 || row[1] == null) {
                continue;
            }
            out.add(new CapabilityUserItemDto(
                    row[0] == null ? "" : row[0].toString(),
                    row[1].toString(),
                    row[2] == null ? "" : row[2].toString(),
                    toLong(row[3]),
                    toLong(row[4])));
        }
        return out;
    }

    private static long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static LocalDate toDate(Object v) {
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        return null;
    }
}
