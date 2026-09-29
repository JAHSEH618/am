package com.am.server.agent.ingest;

import com.am.server.agent.security.AlertService;
import com.am.server.agent.security.SignatureContext;
import com.am.server.common.TokenSanitySupport;
import com.am.server.common.TokenSanitySupport.Verdict;
import com.am.server.domain.agent.AlertType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ingest 入口的 token 合理性护栏：把 {@link TokenSanitySupport} 的纯函数判定接上"限流 WARN 日志 + agent_alert"。
 *
 * <p><b>处理规则</b>（与 {@link TokenSanitySupport} 一致）：
 * <ul>
 *   <li>会话累计值（input / output / cache_read / cache_create）负数或超上限 → <b>不采信</b>，保留库内上一次的值；</li>
 *   <li>单条 delta 负数或超上限 → 丢弃（记 0）；</li>
 *   <li>越界 / 负的<b>累计值</b>与越界的 delta：WARN 日志（限流）+ {@link AlertType#TOKEN_TAMPER} 告警（限流）；</li>
 *   <li>负 delta 只计数、打 DEBUG：老版本 agent 在 Codex / Kimi 上下文压缩后会合法地下发负增量
 *       （见 {@code clampTokenDelta}），不算异常，不告警，避免刷屏。</li>
 * </ul>
 *
 * <p><b>日志与告警绝不含消息内容</b>，只带 agent_id / user_code / provider / 会话 id / 字段名 / 被拒值 / 上限。
 *
 * <p><b>配置</b>（{@code @Value} 带默认值，不进 {@code AgentProperties}；环境变量形式如
 * {@code AIWATCH_INGEST_MAX_SESSION_TOKENS}）：
 * <ul>
 *   <li>{@code aiwatch.ingest.max-session-tokens}（默认 2000000000）</li>
 *   <li>{@code aiwatch.ingest.max-session-cache-tokens}（默认 10000000000）</li>
 *   <li>{@code aiwatch.ingest.max-delta-tokens}（默认 500000000）</li>
 *   <li>{@code aiwatch.ingest.token-sanity.enabled}（默认 true；仅作应急关闭）</li>
 *   <li>{@code aiwatch.ingest.token-sanity.log-interval-seconds}（默认 600，同一会话同一字段的 WARN 间隔）</li>
 *   <li>{@code aiwatch.ingest.token-sanity.alert-interval-seconds}（默认 3600，同一 agent 的告警间隔）</li>
 * </ul>
 * 合法的超大会话（真有 &gt; 20 亿的长会话）出现时，被拒的值会以告警形式暴露，把上限调大即可；
 * 拒绝的后果只是"该会话累计值停在上一次可信值"，不会丢消息、不会丢事件。
 *
 * <p>单测 / 不挂 Spring 的场景用 {@link #withDefaults()}（内置默认上限、不发告警）。
 */
@Component
public class TokenSanityGuard {

    private static final Logger log = LoggerFactory.getLogger(TokenSanityGuard.class);

    /** 限流表的容量上限：超过就整体清空（宁可多打几条日志，也不让 map 无界增长）。 */
    private static final int MAX_TRACKED_KEYS = 4096;

    @Value("${aiwatch.ingest.max-session-tokens:" + TokenSanitySupport.DEFAULT_MAX_SESSION_TOKENS + "}")
    private long maxSessionTokens = TokenSanitySupport.DEFAULT_MAX_SESSION_TOKENS;

    @Value("${aiwatch.ingest.max-session-cache-tokens:" + TokenSanitySupport.DEFAULT_MAX_SESSION_CACHE_TOKENS + "}")
    private long maxSessionCacheTokens = TokenSanitySupport.DEFAULT_MAX_SESSION_CACHE_TOKENS;

    @Value("${aiwatch.ingest.max-delta-tokens:" + TokenSanitySupport.DEFAULT_MAX_DELTA_TOKENS + "}")
    private long maxDeltaTokens = TokenSanitySupport.DEFAULT_MAX_DELTA_TOKENS;

    @Value("${aiwatch.ingest.token-sanity.enabled:true}")
    private boolean enabled = true;

    @Value("${aiwatch.ingest.token-sanity.log-interval-seconds:600}")
    private long logIntervalSeconds = 600L;

    @Value("${aiwatch.ingest.token-sanity.alert-interval-seconds:3600}")
    private long alertIntervalSeconds = 3600L;

    private AlertService alertService;

    private final ConcurrentHashMap<String, Long> lastLogAtMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastAlertAtMs = new ConcurrentHashMap<>();
    private final AtomicLong rejectedTotals = new AtomicLong();
    private final AtomicLong rejectedDeltas = new AtomicLong();
    private final AtomicLong negativeDeltas = new AtomicLong();

    /** 告警是可选依赖：单测 / 精简装配下缺省，只打日志。 */
    @Autowired(required = false)
    public void setAlertService(AlertService alertService) {
        this.alertService = alertService;
    }

    /** 内置默认上限、无告警的实例（供不挂 Spring 的单测与子类默认值使用）。 */
    public static TokenSanityGuard withDefaults() {
        return new TokenSanityGuard();
    }

    /** 测试 / 手工装配用。 */
    public static TokenSanityGuard of(long maxSessionTokens, long maxSessionCacheTokens, long maxDeltaTokens,
                                      AlertService alertService) {
        TokenSanityGuard g = new TokenSanityGuard();
        g.maxSessionTokens = maxSessionTokens;
        g.maxSessionCacheTokens = maxSessionCacheTokens;
        g.maxDeltaTokens = maxDeltaTokens;
        g.alertService = alertService;
        return g;
    }

    public long maxSessionTokens() {
        return maxSessionTokens;
    }

    public long maxSessionCacheTokens() {
        return maxSessionCacheTokens;
    }

    public long maxDeltaTokens() {
        return maxDeltaTokens;
    }

    /** 累计被拒的会话累计值个数（进程内计数，测试 / 排障用）。 */
    public long rejectedTotalCount() {
        return rejectedTotals.get();
    }

    /** 累计被丢弃的越界 delta 个数。 */
    public long rejectedDeltaCount() {
        return rejectedDeltas.get();
    }

    /** 累计被丢弃的负 delta 个数（老 agent 压缩场景，合法，仅计数）。 */
    public long negativeDeltaCount() {
        return negativeDeltas.get();
    }

    /**
     * 会话累计 input / output。{@code cache=false} 用单会话上限，{@code cache=true}（cache_read / cache_create）
     * 用放宽后的缓存上限。返回应写入 {@code ai_session} 的值。
     */
    public long acceptSessionTotal(SignatureContext ctx, String targetType, String externalSessionId,
                                   String field, Long incoming, long previous, boolean cache) {
        if (!enabled) {
            return incoming == null ? 0L : incoming;
        }
        long max = cache ? maxSessionCacheTokens : maxSessionTokens;
        Verdict v = TokenSanitySupport.judge(incoming, max);
        if (v.isOk()) {
            return incoming == null ? 0L : incoming;
        }
        rejectedTotals.incrementAndGet();
        report(ctx, targetType, externalSessionId, field, incoming, max, v,
                "kept previous=" + previous, true);
        return previous;
    }

    /** 单条 delta（客户端 activity_deltas / 消息级 token / 快照差）。返回可写入事件的值（异常 → 0）。 */
    public long acceptDelta(SignatureContext ctx, String targetType, String externalSessionId,
                            String field, Long incoming) {
        if (!enabled) {
            return incoming == null ? 0L : incoming;
        }
        Verdict v = TokenSanitySupport.judge(incoming, maxDeltaTokens);
        if (v.isOk()) {
            return incoming == null ? 0L : incoming;
        }
        if (v == Verdict.NEGATIVE) {
            negativeDeltas.incrementAndGet();
            if (log.isDebugEnabled()) {
                log.debug("token delta negative dropped: target={} session={} field={} value={}",
                        targetType, externalSessionId, field, incoming);
            }
            return 0L;
        }
        rejectedDeltas.incrementAndGet();
        report(ctx, targetType, externalSessionId, field, incoming, maxDeltaTokens, v, "dropped delta", false);
        return 0L;
    }

    // ------------------------------------------------------------------------------------------

    private void report(SignatureContext ctx, String targetType, String externalSessionId, String field,
                        Long value, long limit, Verdict verdict, String action, boolean total) {
        String agentId = ctx == null ? null : ctx.getAgentId();
        String userCode = ctx == null ? null : ctx.getUserCode();
        String hostHash = ctx == null ? null : ctx.getHostHash();
        long now = System.currentTimeMillis();

        String logKey = agentId + '|' + targetType + '|' + externalSessionId + '|' + field;
        if (shouldEmit(lastLogAtMs, logKey, now, logIntervalSeconds * 1000L)) {
            log.warn("token sanity: {} {} rejected ({}): agent_id={} user_code={} provider={} session={} field={} "
                            + "value={} limit={} — {}",
                    total ? "session total" : "delta", verdict, total ? "not adopted" : "not written",
                    agentId, userCode, targetType, externalSessionId, field, value, limit, action);
        }

        if (alertService == null || agentId == null) {
            return;
        }
        String alertKey = agentId + '|' + targetType;
        if (!shouldEmit(lastAlertAtMs, alertKey, now, alertIntervalSeconds * 1000L)) {
            return;
        }
        try {
            alertService.warn(agentId, userCode, hostHash, AlertType.TOKEN_TAMPER,
                    "token out of range (" + verdict + "): provider=" + targetType + " session=" + externalSessionId
                            + " field=" + field + " value=" + value + " limit=" + limit + " — " + action);
        } catch (RuntimeException e) {
            // 告警是尽力而为：绝不能因为写 agent_alert 失败而拖垮整份上报
            log.warn("token sanity: failed to record alert for agent {}: {}", agentId, e.toString());
        }
    }

    /** 同一 key 在 {@code intervalMs} 内只放行一次；表超过容量时整体清空。 */
    private static boolean shouldEmit(ConcurrentHashMap<String, Long> table, String key, long nowMs, long intervalMs) {
        if (table.size() > MAX_TRACKED_KEYS) {
            table.clear();
        }
        final boolean[] emit = {false};
        table.compute(key, (k, last) -> {
            if (last == null || nowMs - last >= intervalMs) {
                emit[0] = true;
                return nowMs;
            }
            return last;
        });
        return emit[0];
    }
}
