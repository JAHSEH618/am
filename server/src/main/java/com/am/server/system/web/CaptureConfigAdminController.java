package com.am.server.system.web;

import com.am.server.common.BizException;
import com.am.server.common.ErrorCode;
import com.am.server.common.R;
import com.am.server.system.SystemConfigKeys;
import com.am.server.system.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统设置 — 会话内容采集限额（{@code /api/v1/admin/capture-config}）。
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/capture-config")
public class CaptureConfigAdminController {

    private final SystemConfigService configService;

    @GetMapping
    public R<Map<String, Object>> get() {
        return R.ok(new LinkedHashMap<>(configService.getPlain(SystemConfigKeys.CAT_CAPTURE)));
    }

    @PutMapping
    public R<Map<String, Object>> save(@RequestBody Map<String, String> body,
                                       Principal principal) {
        if (body == null || body.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "body 不能为空");
        }
        Map<String, String> updates = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : body.entrySet()) {
            String key = e.getKey();
            if (key != null && key.startsWith(SystemConfigKeys.CAT_CAPTURE + ".")) {
                updates.put(key, e.getValue() == null ? "" : e.getValue());
            }
        }
        if (updates.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "body 中没有可保存的 capture.* key");
        }
        validatePositiveInts(updates,
                SystemConfigKeys.CAPTURE_MAX_TEXT_BYTES_PER_PART,
                SystemConfigKeys.CAPTURE_MAX_BLOB_BYTES_PER_PART,
                SystemConfigKeys.CAPTURE_MAX_BLOBS_PER_MESSAGE,
                SystemConfigKeys.CAPTURE_MAX_PARTS_PER_MESSAGE,
                SystemConfigKeys.CAPTURE_INLINE_BLOB_MAX_BYTES,
                SystemConfigKeys.CAPTURE_AUDIT_MESSAGE_MAX_CHARS);

        String operator = principal != null ? principal.getName() : "admin";
        configService.setBatch(updates, operator);
        return get();
    }

    private static void validatePositiveInts(Map<String, String> updates, String... keys) {
        for (String key : keys) {
            if (!updates.containsKey(key)) {
                continue;
            }
            String raw = updates.get(key);
            int v;
            try {
                v = Integer.parseInt(raw == null ? "" : raw.trim());
            } catch (NumberFormatException e) {
                throw new BizException(ErrorCode.PARAM_INVALID, key + " 必须是整数");
            }
            if (v <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, key + " 必须大于 0");
            }
        }
    }
}
