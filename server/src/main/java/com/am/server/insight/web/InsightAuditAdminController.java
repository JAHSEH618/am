package com.am.server.insight.web;

import com.am.server.common.R;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.system.ActiveTargetTypesProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;

/**
 * 洞察审计运维 API：标记重审等（走 /api/v1/admin/** 管理鉴权）。
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/insight/audit")
public class InsightAuditAdminController {

    private final AiSessionRepository sessionRepository;
    private final ActiveTargetTypesProvider activeTargetTypesProvider;

    /**
     * 将时间窗内（含 overlap 口径，与报告一致：[from, to] 日历日 → [t0, t1)）有效会话标记为需重审。
     * rubric / audit_version bump 不会自动置位，须管理员调用本接口或自行 UPDATE。
     *
     * @param userCode 可选，非空时只标记该员工
     * @return 受影响行数
     */
    @PostMapping("/request-reaudit")
    public R<Integer> requestReaudit(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String userCode) {
        Collection<String> typesCol = activeTargetTypesProvider.getActiveTypes();
        if (typesCol == null || typesCol.isEmpty()) {
            return R.ok(0);
        }
        LocalDateTime t0 = from.atStartOfDay();
        LocalDateTime t1 = to.plusDays(1).atStartOfDay();
        int updated = sessionRepository.markInsightReauditRequiredInWindow(
                new ArrayList<>(typesCol), t0, t1,
                userCode == null || userCode.isBlank() ? null : userCode.trim());
        return R.ok(updated);
    }
}
