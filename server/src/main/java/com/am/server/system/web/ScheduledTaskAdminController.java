package com.am.server.system.web;

import com.am.server.common.BizException;
import com.am.server.common.ErrorCode;
import com.am.server.common.R;
import com.am.server.system.SystemConfigKeys;
import com.am.server.system.SystemConfigService;
import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import com.am.server.system.scheduling.DynamicScheduledTaskManager.ScheduledTaskStatus;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统设置 - 定时任务 Tab 的后端管理接口。
 *
 * <p>路径 {@code /api/v1/admin/scheduled-tasks}，全部经 {@code AdminTokenInterceptor}。
 * 写操作的"操作者"取登录态 username，缺省 "anonymous-admin"（X-Admin-Token 直连场景）。
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/scheduled-tasks")
public class ScheduledTaskAdminController {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskAdminController.class);

    private final DynamicScheduledTaskManager manager;
    private final SystemConfigService configService;

    /** 列出全部已注册任务 + 运行时状态（业务任务在前）。 */
    @GetMapping
    public R<List<ScheduledTaskStatus>> listAll() {
        return R.ok(manager.listAll());
    }

    /**
     * 修改某任务的 cron / enabled。可只传需要变的字段。
     * <p>cron 字段会先做 {@link CronExpression#parse} 校验，非法时 400，DB 不会被污染。
     * <p>cronEditable=false 的任务拒绝改 cron（基础设施类）。
     */
    @PatchMapping("/{taskCode}")
    public R<ScheduledTaskStatus> update(
            @PathVariable String taskCode,
            @RequestBody UpdateRequest req,
            HttpServletRequest http,
            Principal principal) {
        ScheduledTaskStatus status = manager.listAll().stream()
                .filter(s -> taskCode.equals(s.taskCode()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND, "unknown task: " + taskCode));

        Map<String, String> updates = new LinkedHashMap<>();
        if (req.cron != null && !req.cron.equals(status.cron())) {
            if (!status.cronEditable()) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "task " + taskCode + " 不允许修改 cron（基础设施任务）");
            }
            String cron = req.cron.trim();
            if (cron.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "cron 不能为空");
            }
            try {
                CronExpression.parse(cron);
            } catch (IllegalArgumentException ex) {
                throw new BizException(ErrorCode.PARAM_INVALID, "cron 表达式非法：" + ex.getMessage());
            }
            updates.put(SystemConfigKeys.CAT_SCHEDULING + "." + taskCode + SystemConfigKeys.SUFFIX_CRON, cron);
        }
        if (req.enabled != null && req.enabled != status.enabled()) {
            updates.put(SystemConfigKeys.CAT_SCHEDULING + "." + taskCode + SystemConfigKeys.SUFFIX_ENABLED,
                    Boolean.toString(req.enabled));
        }
        if (updates.isEmpty()) {
            return R.ok(status);
        }

        String operator = resolveOperator(principal, http);
        configService.setBatch(updates, operator);
        log.info("scheduled task {} updated by {}: {}", taskCode, operator, updates);

        ScheduledTaskStatus refreshed = manager.listAll().stream()
                .filter(s -> taskCode.equals(s.taskCode()))
                .findFirst()
                .orElse(status);
        return R.ok(refreshed);
    }

    /** 立即执行某任务一次（异步，调用方不阻塞等待执行结果）。 */
    @PostMapping("/{taskCode}/trigger")
    public R<ScheduledTaskStatus> trigger(
            @PathVariable String taskCode,
            HttpServletRequest http,
            Principal principal) {
        try {
            manager.triggerNow(taskCode);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new BizException(ErrorCode.PARAM_INVALID, ex.getMessage());
        }
        log.info("scheduled task {} triggered by {}", taskCode, resolveOperator(principal, http));

        ScheduledTaskStatus status = manager.listAll().stream()
                .filter(s -> taskCode.equals(s.taskCode()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND, taskCode));
        return R.ok(status);
    }

    private String resolveOperator(Principal principal, HttpServletRequest http) {
        if (principal != null && principal.getName() != null) return principal.getName();
        if (http != null) {
            Object u = http.getSession(false) != null ? http.getSession(false).getAttribute("username") : null;
            if (u != null) return u.toString();
            if (http.getHeader("X-Admin-Token") != null) return "admin-token";
        }
        return "anonymous-admin";
    }

    /** PATCH body：cron / enabled 可单独传。 */
    public static class UpdateRequest {
        public String cron;
        public Boolean enabled;
    }
}
