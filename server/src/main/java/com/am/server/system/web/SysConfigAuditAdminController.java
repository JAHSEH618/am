package com.am.server.system.web;

import com.am.server.common.R;
import com.am.server.system.domain.SysConfigAudit;
import com.am.server.system.domain.SysConfigAuditRepository;
import com.am.server.web.dto.PageDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 系统设置 - 操作日志（{@code /api/v1/admin/sys-config-audit}）。
 *
 * <p>展示 {@code sys_config_audit} 表的变更流水：修改人 / 时间 / 旧值 / 新值。
 * 出问题时可一眼回溯——"昨天谁把 Judge model 改了"。
 *
 * <p>分页 + 可选 category / key 过滤；不暴露 PUT/DELETE，审计行只读。
 * is_secret=1 的值前端自己决定是否掩码（默认掩码，点眼睛展开）。
 *
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/sys-config-audit")
public class SysConfigAuditAdminController {

    private final SysConfigAuditRepository repository;

    @GetMapping
    public R<PageDto<AuditRow>> list(
            @RequestParam(required = false) String category,
            @RequestParam(name = "key", required = false) String keyKw,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(Math.max(size, 1), 200));
        Page<SysConfigAudit> result;

        boolean hasCat = category != null && !category.isBlank();
        boolean hasKw = keyKw != null && !keyKw.isBlank();
        if (hasCat && hasKw) {
            result = repository.searchByCategoryAndKey(category, "%" + keyKw + "%", pageable);
        } else if (hasCat) {
            result = repository.findByCategoryOrderByChangedTimeDescIdDesc(category, pageable);
        } else if (hasKw) {
            result = repository.searchByKey("%" + keyKw + "%", pageable);
        } else {
            result = repository.findAllByOrderByChangedTimeDescIdDesc(pageable);
        }
        return R.ok(PageDto.of(result, AuditRow::of));
    }

    /** 列表行 DTO（{@code old_value/new_value} 不在这里掩码，前端自行控制展示）。 */
    public record AuditRow(
            Long id,
            String config_key,
            String category,
            int is_secret,
            String old_value,
            String new_value,
            String operator,
            LocalDateTime changed_time
    ) {
        public static AuditRow of(SysConfigAudit a) {
            return new AuditRow(
                    a.getId(),
                    a.getConfigKey(),
                    a.getCategory(),
                    a.getIsSecret() == null ? 0 : a.getIsSecret(),
                    a.getOldValue(),
                    a.getNewValue(),
                    a.getOperator(),
                    a.getChangedTime());
        }
    }
}
