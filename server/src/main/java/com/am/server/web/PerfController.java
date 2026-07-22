package com.am.server.web;

import com.am.server.common.R;
import com.am.server.web.support.SlowApiRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 性能治理测量端点：慢接口清单（阈值 1s，环形缓冲最近 200 条，重启即清）。
 * 用途：30 天窗口全页面实测时拉取真实 Top 慢接口，出验收对照表；不凭体感修。
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/perf")
public class PerfController {

    private final SlowApiRecorder slowApiRecorder;

    @GetMapping("/slow-requests")
    public R<List<SlowApiRecorder.SlowApiEntry>> slowRequests() {
        return R.ok(slowApiRecorder.snapshot());
    }

    /** 实测前清零，跑完一轮页面点检后再拉清单。 */
    @DeleteMapping("/slow-requests")
    public R<Void> clear() {
        slowApiRecorder.clear();
        return R.ok(null);
    }
}
