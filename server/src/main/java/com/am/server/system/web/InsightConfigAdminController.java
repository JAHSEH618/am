package com.am.server.system.web;

import com.am.server.common.BizException;
import com.am.server.common.ErrorCode;
import com.am.server.common.R;
import com.am.server.insight.audit.JudgeClient;
import com.am.server.insight.audit.JudgeClientRegistry;
import com.am.server.insight.audit.JudgeResult;
import com.am.server.insight.config.InsightProperties;
import com.am.server.insight.config.InsightProperties.JudgeConfig;
import com.am.server.system.SystemConfigKeys;
import com.am.server.system.SystemConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 系统设置 - Judge 模型 + 评判参数管理接口（{@code /api/v1/admin/insight-config}）。
 *
 * <p>读：返回当前 sys_config 中 judge.* + insight.* 的全部值；
 * 敏感字段（{@code is_secret=1}）以 {@link SystemConfigService#SECRET_MASK} 输出占位，
 * 前端要修改时传明文，未修改原样回传占位串即可（service 层自动跳过占位）。
 *
 * <p>写：批量保存到 sys_config，触发 SystemConfigChangedEvent，
 * {@link com.am.server.insight.config.InsightConfigSyncer} 立刻把内存 InsightProperties 刷新成新值，
 * 下一次报告流水线、下一次 ensureFresh / 触发 Judge 调用都生效——不重启服务。
 *
 * <p>测试连通性：用当前生效的 JudgeConfig 跑一个固定的小 prompt，
 * 验证 endpoint / api-key / model 三件套都对，UI 立刻知道"保存的能不能用"。
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/insight-config")
public class InsightConfigAdminController {

    private static final Logger log = LoggerFactory.getLogger(InsightConfigAdminController.class);

    /** 测试连通性的小 prompt：要求 LLM 输出严格 JSON，省得真烧 token 也能验证返回路径。 */
    private static final String TEST_PROMPT = "请严格用以下 JSON 回复（不要任何额外文字）：\n"
            + "{\"difficulty\":3,\"outcome\":\"partial\",\"mode\":\"exploratory\","
            + "\"capabilities\":{\"problem_decomposition\":3,\"context_management\":3,"
            + "\"debugging_skill\":3,\"tool_orchestration\":3,\"self_correction\":3},"
            + "\"reason\":\"connectivity test\"}";

    private final SystemConfigService configService;
    private final InsightProperties properties;
    private final JudgeClientRegistry registry;

    /**
     * 读当前配置：返回扁平 key-value Map。
     *
     * <p>结构：
     * <pre>
     * {
     *   "judge.a.provider": "openai-compatible",
     *   "judge.a.endpoint": "https://...",
     *   "judge.a.api_key":  "********",
     *   ...
     *   "insight.max_llm_calls_per_report": "400",
     *   ...
     * }
     * </pre>
     */
    @GetMapping
    public R<Map<String, Object>> get() {
        // admin 自己的设置页：直接返回明文，包含 API Key。
        // 整个接口走 AdminTokenInterceptor / 登录态校验，非 admin 拿不到。
        // 这样 admin 在 UI 上点眼睛能切换显示 API Key 真值，方便核对 / 复制 / 轮换。
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(configService.getPlain(SystemConfigKeys.CAT_JUDGE));
        out.putAll(configService.getPlain(SystemConfigKeys.CAT_INSIGHT));
        return R.ok(out);
    }

    /**
     * 批量保存。body 是与 {@link #get} 同结构的扁平 Map；
     * 包含掩码占位的字段会被 {@link SystemConfigService#set} 自动跳过（未修改）。
     */
    @PutMapping
    public R<Map<String, Object>> save(@RequestBody Map<String, String> body,
                                       HttpServletRequest http,
                                       Principal principal) {
        if (body == null || body.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "body 不能为空");
        }
        // 只接受 judge.* / insight.* 命名空间，避免 PUT 端口扩散到鉴权 / 调度等敏感配置
        Map<String, String> updates = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : body.entrySet()) {
            String key = e.getKey();
            if (key == null) continue;
            if (key.startsWith(SystemConfigKeys.CAT_JUDGE + ".")
                    || key.startsWith(SystemConfigKeys.CAT_INSIGHT + ".")) {
                updates.put(key, e.getValue() == null ? "" : e.getValue());
            }
        }
        if (updates.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "body 中没有可保存的 judge.* / insight.* key");
        }
        // 简单合法性校验：超时 / 上限 / 阈值 / 并发必须是非负整数；非法时 400 不入库
        validateInts(updates, SystemConfigKeys.JUDGE_A_TIMEOUT_MS, SystemConfigKeys.JUDGE_B_TIMEOUT_MS,
                SystemConfigKeys.INSIGHT_MAX_LLM_CALLS_PER_REPORT,
                SystemConfigKeys.INSIGHT_REAUDIT_MESSAGE_THRESHOLD,
                SystemConfigKeys.INSIGHT_AUDIT_CONCURRENCY);
        validateProvider(updates, SystemConfigKeys.JUDGE_A_PROVIDER);
        validateProvider(updates, SystemConfigKeys.JUDGE_B_PROVIDER);

        String operator = resolveOperator(principal, http);
        configService.setBatch(updates, operator);
        log.info("insight-config updated by {} keys={}", operator, updates.keySet());

        return get();
    }

    /**
     * 测试某个 slot 的 Judge 连通性。
     * @param slot "a" / "b"
     * @return     {@code success / latency_ms / message / sample_difficulty}
     */
    @PostMapping("/test")
    public R<TestResult> test(@RequestParam String slot) {
        JudgeConfig cfg = switch (slot == null ? "" : slot.toLowerCase()) {
            case "a" -> properties.getJudgeA();
            case "b" -> properties.getJudgeB();
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "slot 必须是 a 或 b");
        };
        // mock provider 直接走 mock client；不强制要求填 endpoint。
        JudgeClient client;
        try {
            client = registry.require(cfg.getProvider());
        } catch (IllegalStateException ex) {
            return R.ok(new TestResult(false, 0L, "unknown provider: " + cfg.getProvider(), null));
        }

        long t0 = System.currentTimeMillis();
        try {
            JudgeResult result = client.judge(cfg, TEST_PROMPT);
            long latency = System.currentTimeMillis() - t0;
            log.info("judge {} test ok: provider={} model={} latencyMs={} difficulty={}",
                    slot, cfg.getProvider(), cfg.getModel(), latency, result.getDifficulty());
            return R.ok(new TestResult(true, latency,
                    "ok · model=" + cfg.getModel(), result.getDifficulty()));
        } catch (RuntimeException ex) {
            long latency = System.currentTimeMillis() - t0;
            log.warn("judge {} test failed: provider={} model={} latencyMs={} err={}",
                    slot, cfg.getProvider(), cfg.getModel(), latency, ex.getMessage());
            return R.ok(new TestResult(false, latency,
                    truncate(ex.getMessage(), 500), null));
        }
    }

    // -------- helpers --------

    private static void validateInts(Map<String, String> updates, String... keys) {
        Set<String> intKeys = Set.of(keys);
        for (Map.Entry<String, String> e : updates.entrySet()) {
            if (!intKeys.contains(e.getKey())) continue;
            String v = e.getValue();
            if (v == null || v.isBlank()) continue;
            try {
                int n = Integer.parseInt(v.trim());
                if (n < 0) {
                    throw new BizException(ErrorCode.PARAM_INVALID,
                            e.getKey() + " 必须 ≥ 0，当前 " + v);
                }
                e.setValue(String.valueOf(n));
            } catch (NumberFormatException nfe) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        e.getKey() + " 不是合法整数：" + v);
            }
        }
    }

    private static void validateProvider(Map<String, String> updates, String key) {
        String v = updates.get(key);
        if (v == null) return;
        if (!"mock".equalsIgnoreCase(v) && !"openai-compatible".equalsIgnoreCase(v)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    key + " 必须是 mock 或 openai-compatible，当前 " + v);
        }
        updates.put(key, v.toLowerCase());
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

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 测试连通性返回体。 */
    public record TestResult(boolean success, long latencyMs, String message, Integer sampleDifficulty) {}
}
