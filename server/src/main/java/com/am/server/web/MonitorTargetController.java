package com.am.server.web;

import com.am.server.common.R;
import com.am.server.domain.monitor.MonitorTargetRepository;
import com.am.server.web.dto.MonitorTargetDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 监控目标字典查询接口（公共）。
 * <p>
 * 前端 Segmented 选项卡（"全部 / Cursor / Claude / Codex / Hermes / OpenClaw / OpenHarness ..."）
 * 由本接口动态返回，避免硬编码；新增 Provider 时只需在 monitor_target 表加一行 + Agent 端注册一个 Provider，
 * 前端不需要发版。
 * <p>
 * 活跃 Agent 开关管理端接口见 {@link com.am.server.system.web.MonitorTargetAdminController}。
 *
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/monitor-targets")
public class MonitorTargetController {

    private final MonitorTargetRepository monitorTargetRepository;

    /**
     * 返回所有 enabled=1 的监控目标，按 sort_no、id 升序。
     */
    @GetMapping
    public R<List<MonitorTargetDto>> list() {
        List<MonitorTargetDto> targets = monitorTargetRepository
                .findAllByEnabledOrderBySortNoAscIdAsc(1)
                .stream()
                .map(MonitorTargetDto::of)
                .toList();
        return R.ok(targets);
    }
}
