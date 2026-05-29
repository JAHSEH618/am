package com.am.server.agent.api;

import com.am.server.agent.service.AgentRegisterService;
import com.am.server.common.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 注册接口控制器
 * 不参与 HMAC 签名校验（首次注册时还没有密钥）
 * gz
 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentRegisterController {

    private final AgentRegisterService registerService;

    @PostMapping("/register")
    public R<AgentRegisterResponse> register(@RequestBody @Valid AgentRegisterRequest request) {
        return R.ok(registerService.register(request));
    }
}
