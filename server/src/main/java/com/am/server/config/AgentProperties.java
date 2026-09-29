package com.am.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code aiwatch.agent.*} — Agent 运行时与服务端判定相关的配置。
 *
 * <p>与 {@link com.am.server.agent.service.AgentRegisterService} 下发的
 * {@code report_interval_ms} 配合：默认 2min 上报时，在线窗口应为其 2～3 倍，
 * 避免 tick 偏慢或 bootstrap 首包耗时长时被大盘误判为离线。
 * gz
 */
@Data
@Component
@ConfigurationProperties(prefix = "aiwatch.agent")
public class AgentProperties {

    /**
     * 多久未收到成功上报则视为设备离线（秒）。
     * 默认 300（5min）≈ 2.5× 默认 2min 上报间隔。
     */
    private long onlineWindowSeconds = 300L;

    /**
     * ingest / work_session 判定「会话仍活跃」的 last_activity 窗口（秒）。
     * 默认 300（5min）≈ 2.5× 默认 2min 上报间隔，与客户端 ConfigureActivityTimeouts 对齐。
     */
    private long activityWindowSeconds = 300L;

    /**
     * 单次 /report 推进 work_session 在线/活跃秒数的上限（秒）。
     * 默认 120，与空闲上报间隔同量级（旧 10s tick 时代为 30）。
     */
    private long perReportCapSeconds = 120L;

    /**
     * 下发给客户端的「空闲基线」上报间隔（毫秒）。register 与每次 /report 响应都会带上，
     * 客户端据此动态调整 tick 节奏（见 {@code reporter.go} 的 mergeReportCadence）。
     *
     * <p>默认 45s（旧 120s）：把"刚开始干活 → 第一条活动出现"的冷启动最坏延迟从 ~2min 砍到 ~45s。
     * 空闲 payload 是游标增量、很小，团队规模下额外负载可忽略。ops 可经 {@code aiwatch.agent.report-interval-ms} 调。
     */
    private long reportIntervalMs = 45_000L;

    /**
     * 下发给客户端的「活跃时段」快速上报间隔（毫秒）。客户端收到响应 {@code active=true} 时切到此节奏。
     *
     * <p>默认 8s（旧 15s）：稳态下每条事件最坏延迟 15s→8s，更贴近"实时"。客户端硬下限 5s。
     */
    private long activeReportIntervalMs = 8_000L;

    /**
     * 同时处理中的「重」agent 上报（{@code /report}、{@code /report-commits} 且 body 大于轻量阈值）上限，
     * 见 {@link com.am.server.agent.security.AgentIngestBulkheadFilter}。
     *
     * <p>必须明显小于 Hikari 池（prod 40）：上报是唯一由客户端数量决定并发的入口，不设上限时
     * 全员同时上报（尤其服务重启后各 agent 的 outbox 一起补发）会占满全部连接，控制台、定时任务、
     * 连 agent 自己的验签查询都拿不到连接 → 30s 超时 → 客户端再重试，自我强化。
     */
    private int ingestMaxConcurrency = 16;

    /**
     * 等待「重」上报并发名额的最长时间（毫秒）。<b>默认 0 = 拿不到名额立即 503，不排队</b>。
     *
     * <p>为什么是 0：被拒请求每多等一毫秒都占着一个 Tomcat 线程（prod 只有 50 个）。满载时大量请求排队等名额，
     * 会把剩下的 34 个线程也吃光，连控制台 / 健康检查都进不来——舱壁反而成了放大器。快速失败对 1.3.3+ agent
     * 是免费的（游标未推进，下个 tick 自然重报），也低于 ApiTimingFilter 的 1000ms 慢请求阈值（否则每个被拒请求
     * 都会刷一条 slow api WARN）。
     */
    private long ingestAcquireTimeoutMs = 0L;

    /**
     * 「轻」请求（Content-Length 已知且 ≤2KB：设备心跳 / 空闲 tick）与 {@code /register} 的并发上限。
     * 轻请求不占重名额（不能让大盘因为重上报打满而误判离线），但也不能无上限地打进 DB
     * （每个心跳都会 {@code findByAgentId} + 写 {@code agent_device.last_seen} + 推进 work_session）。
     */
    private int ingestLightMaxConcurrency = 8;

    /** 轻请求等名额的最长时间（毫秒），需 ≤200：它们本身很快，短等一下比直接 503 更划算，但不能久等。 */
    private long ingestLightAcquireTimeoutMs = 200L;

    /**
     * 「未按 503 退让的老客户端」（agent_version 低于 {@link #legacyClientBelowVersion}）同时在途的重请求上限。
     * 这些客户端收到 503 仍会把整包落 outbox 并保持快节奏重报，任其占满重名额会把 1.3.3+ 客户端挤出去；
     * 给它们一个很小的独立名额（验签通过、进入业务前扣除；超额 503 + 50301）。{@code <=0} 关闭该限制。
     */
    private int ingestLegacyMaxConcurrency = 2;

    /**
     * 认识 503 / 50301 并据此「不落 outbox、退让重报」的最低 agent 版本（1.3.3 起）。低于它的按老客户端处理，
     * 版本取自注册记录 {@code agent_device.agent_version}（请求头里没有版本信号；body 里的版本要读完 body 才有）。
     * 无法解析（dev 构建 / 空）的一律按新客户端处理，不误伤。
     */
    private String legacyClientBelowVersion = "1.3.3";

    /**
     * 单个 agent 请求体（线上字节，即 gzip 后）的硬上限，默认 32MB。超过（Content-Length 已知时读 body 前直接判定，
     * chunked 时读到上限+1 即中止）返回 <b>HTTP 413</b> + {@code ErrorCode.PAYLOAD_TOO_LARGE}。
     * 1.3.3 之前的老客户端首次回填可能产出远大于此的 body，它们会持续收到 413（有意为之：服务端无法为它们把堆撑爆），
     * 升级到带 MaxMessagesPerSession 切片 + 字节预算的新客户端后不会触发。
     */
    private long maxBodyBytes = 32L * 1024 * 1024;

    /** gzip 解压后的硬上限，默认 64MB；解压中途超限即中止并 413（防 gzip 炸弹）。 */
    private long maxDecodedBodyBytes = 64L * 1024 * 1024;

    /**
     * gzip 解压后 / 压缩后的最大比例，默认 100:1。正常 JSON 上报约 5~30:1；比例失常几乎只可能是炸弹。
     * 极小的 body 有 1MB 的解压额度保底，不会被比例误伤。
     */
    private int maxGzipRatio = 100;

    /**
     * 所有在途「重」请求的<b>估算堆占用</b>总预算（字节），默认 512MB（{@code -Xmx2g} 的 1/4）。
     * 请求进入时按 Content-Length（chunked 时按 {@link #maxBodyBytes}）预占，gzip 解压时按实际解压量继续追加，
     * 结束归还；预占不到 → 503 + 50301。计价与最坏情况算术见 {@code AgentIngestBulkheadFilter} 的 Javadoc。
     * 必须 ≥ 3 × {@link #maxBodyBytes}，否则接近上限的合法请求永远拿不到预算。
     */
    private long ingestInflightBytesBudget = 512L * 1024 * 1024;

    /** {@code /agent/register} 的请求体上限（字节）。注册体是几百字节的小 JSON，且该端点未认证，必须封顶。 */
    private long registerMaxBodyBytes = 64L * 1024;

    /**
     * 同一 (agentId, 告警类型) 在此窗口（分钟）内只写一条 {@code agent_alert}，其余计数并附在窗口后的下一条 message 里。
     * 验签失败（缺头 / 时钟漂移）的 agent 每个 tick 都会触发一次，不限速一台机器一天就是上千行。
     */
    private int alertDedupWindowMinutes = 5;

    /** 全局每分钟最多写入的告警条数（超出丢弃并计数），防止伪造大量 agent id 的未认证请求刷爆表。 */
    private int alertMaxPerMinute = 120;

    /**
     * {@code agent_alert} 保留天数，默认 30；{@code AgentAlertRetentionCleaner} 每天分批删除更早的行。
     * 运行期可经 sys_config {@code agent.alert_retention_days} 热改（≤0 = 不清理），此处只是种子默认值。
     */
    private int alertRetentionDays = 30;

    /**
     * 单个会话 ingest 事务（{@code REQUIRES_NEW}）的超时秒数。DB 变慢时，没有它上报线程会无限期占着连接、
     * 把 {@link #ingestMaxConcurrency} 个名额和对应连接全部拖住；超时后事务回滚，异常以
     * {@code TransactionTimedOutException} 上抛（不吞、不推进游标），客户端下个 tick 用同一批数据重报。
     *
     * <p>注意有效预算 ≈ 本值 − 1 秒：Hibernate 把剩余<b>整秒数</b>设为语句超时，剩余不足 1 秒后发出的语句立即失败。
     * 已有会话的增量 tick 通常是毫秒～百毫秒级；bootstrap 大会话（Cursor 单包上千条消息）才可能逼近，
     * 若某会话持续超时，先看它的消息数 / DB 慢查询，再考虑调大本值，别直接关掉。
     */
    private int ingestSessionTimeoutSeconds = 10;
}
