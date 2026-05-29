package com.am.server.web;

import com.am.server.common.R;
import com.am.server.domain.agent.AgentAlert;
import com.am.server.domain.agent.AgentAlertRepository;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.web.dto.AlertDto;
import com.am.server.web.dto.PageDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 异常告警查询
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AgentAlertRepository alertRepository;
    private final EmployeeDisplayService employeeDisplayService;

    @GetMapping
    public R<PageDto<AlertDto>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        LocalDateTime fromTs = (from == null ? LocalDate.now().minusDays(7) : from).atStartOfDay();
        LocalDateTime toTs = (to == null ? LocalDate.now() : to).atTime(23, 59, 59);
        PageRequest pageable = PageRequest.of(page, Math.min(Math.max(size, 1), 100));

        Page<AgentAlert> p = (type == null || type.isBlank())
                ? alertRepository.findByEventTimeBetweenOrderByEventTimeDesc(fromTs, toTs, pageable)
                : alertRepository.findByAlertTypeAndEventTimeBetweenOrderByEventTimeDesc(
                        type, fromTs, toTs, pageable);
        return R.ok(PageDto.of(p, a -> {
            AlertDto d = AlertDto.of(a);
            d.setUserDisplay(employeeDisplayService.displayOf(d.getUserCode()));
            return d;
        }));
    }
}
