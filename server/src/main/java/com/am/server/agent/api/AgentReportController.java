package com.am.server.agent.api;

import com.am.server.agent.api.dto.AgentReportRequest;
import com.am.server.agent.api.dto.GitCommitReportRequest;
import com.am.server.agent.security.SignatureContext;
import com.am.server.agent.service.AgentReportService;
import com.am.server.agent.service.GitCommitIngestService;
import com.am.server.common.BizException;
import com.am.server.common.ErrorCode;
import com.am.server.common.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 通用上报接口
 * 必须经过 AgentSignatureFilter（HMAC 校验）后才能进入
 * gz
 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentReportController {

    private final AgentReportService reportService;
    private final GitCommitIngestService gitCommitIngestService;

    @PostMapping("/report")
    public R<AgentReportService.ReportSummary> report(@RequestBody @Valid AgentReportRequest request,
                                                      HttpServletRequest httpRequest) {
        Object attr = httpRequest.getAttribute(SignatureContext.ATTR);
        if (!(attr instanceof SignatureContext ctx)) {
            throw new BizException(ErrorCode.INVALID_SIGNATURE, "missing signature context");
        }
        return R.ok(reportService.handle(request, ctx));
    }

    /**
     * v2.2 Phase 3：gitlog Provider 周期性上报本机 git 提交流水。
     * 走与 /report 相同的 HMAC 校验，但独立 endpoint 避免与会话快照耦合。
     */
    @PostMapping("/report-commits")
    public R<GitCommitIngestService.IngestSummary> reportCommits(
            @RequestBody @Valid GitCommitReportRequest request,
            HttpServletRequest httpRequest) {
        Object attr = httpRequest.getAttribute(SignatureContext.ATTR);
        if (!(attr instanceof SignatureContext ctx)) {
            throw new BizException(ErrorCode.INVALID_SIGNATURE, "missing signature context");
        }
        return R.ok(gitCommitIngestService.handle(request, ctx));
    }
}
