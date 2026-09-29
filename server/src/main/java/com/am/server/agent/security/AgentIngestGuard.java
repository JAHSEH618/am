package com.am.server.agent.security;

import com.am.server.config.AgentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * <b>验签通过之后</b>的入场限制（{@link AgentSignatureFilter} 在进入业务前调用）：
 * <ol>
 *   <li><b>同一个 agent 同时最多一个重请求</b>：客户端超时后重发、outbox 补发与新 tick 叠在一起时，
 *       同一 agent 的多个整包会并行打进 DB 抢同一批 session 行（乐观锁冲突 + 间隙锁），只会更慢；
 *       第二个并发的重请求直接 503 + 50301，让客户端等前一个跑完。</li>
 *   <li><b>老客户端小名额</b>：agent_version 低于 {@link AgentProperties#getLegacyClientBelowVersion()}
 *       （1.3.3 之前）的客户端收到 503 仍会把整包落 outbox 并保持快节奏，任其占用全局重名额会把新客户端挤出去，
 *       因此它们共享一个很小的独立名额（默认 2）。</li>
 * </ol>
 *
 * <p><b>为什么放在验签之后而不是舱壁里</b>：X-Agent-Id 头未经认证，若在验签前占 per-agent 名额，
 * 任何人伪造别人的 agent id 就能把该 agent 的正常上报一直挡在门外。验签通过才证明请求确实来自持有密钥的那台机器
 * （重放攻击由随后的 nonce 占用挡住，且占用发生在名额之后、持有时间只有一次 INSERT）。
 *
 * <p><b>版本信号从哪来</b>：请求头里没有版本（Go 客户端不带 User-Agent 版本、也没有 X-Agent-Version），
 * body 里的 {@code agent_version} 要读完 body 才有；唯一能廉价拿到的是注册记录
 * {@code agent_device.agent_version}（验签前的设备查询已经读出，不额外查库）。它由每次 /report 刷新，
 * 所以升级后的第一个 /report 之后就会被识别为新客户端。无法解析的版本（dev / 空）按新客户端处理，不误伤。
 *
 * <p>名额在 {@link Admission#close()} 里归还，调用方必须 try-with-resources / finally。
 * gz
 */
@Component
public class AgentIngestGuard {

    /** 归还即释放，幂等；{@link #denied()} 非 null 表示被拒（此时无需 close，但 close 也安全）。 */
    public static final class Admission implements AutoCloseable {

        static final Admission NONE = new Admission(null, null, null, null);

        private final String deniedReason;
        private final Set<String> inflight;
        private final String agentId;
        private final Semaphore legacyPermits;
        private boolean closed;

        private Admission(String deniedReason, Set<String> inflight, String agentId, Semaphore legacyPermits) {
            this.deniedReason = deniedReason;
            this.inflight = inflight;
            this.agentId = agentId;
            this.legacyPermits = legacyPermits;
        }

        private static Admission denied(String reason) {
            return new Admission(reason, null, null, null);
        }

        /** @return 被拒原因（{@link AgentIngestMetrics#REASON_PER_AGENT} / {@link AgentIngestMetrics#REASON_LEGACY}）；放行时 null。 */
        public String denied() {
            return deniedReason;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (inflight != null) {
                inflight.remove(agentId);
            }
            if (legacyPermits != null) {
                legacyPermits.release();
            }
        }
    }

    private final Set<String> inflightAgents = ConcurrentHashMap.newKeySet();
    private final Semaphore legacyPermits;
    private final int[] busyAwareMinVersion;

    @Autowired
    public AgentIngestGuard(AgentProperties props) {
        int legacyMax = props.getIngestLegacyMaxConcurrency();
        this.legacyPermits = legacyMax > 0 ? new Semaphore(legacyMax) : null;
        this.busyAwareMinVersion = parseVersion(props.getLegacyClientBelowVersion());
    }

    /**
     * 尝试入场。先占 per-agent（便宜且精确），再看是否老客户端、占老客户端名额；任何一步被拒都不留残余占用。
     *
     * @param agentVersion 注册记录里的版本，可为 null
     */
    public Admission tryAdmit(String agentId, String agentVersion) {
        if (!inflightAgents.add(agentId)) {
            return Admission.denied(AgentIngestMetrics.REASON_PER_AGENT);
        }
        Semaphore legacy = null;
        if (legacyPermits != null && isLegacy(agentVersion)) {
            if (!legacyPermits.tryAcquire()) {
                inflightAgents.remove(agentId);
                return Admission.denied(AgentIngestMetrics.REASON_LEGACY);
            }
            legacy = legacyPermits;
        }
        return new Admission(null, inflightAgents, agentId, legacy);
    }

    /** 当前有重请求在途的 agent 数（观测 / 测试用）。 */
    int inflightAgentCount() {
        return inflightAgents.size();
    }

    /** 老客户端剩余名额（观测 / 测试用）；功能关闭时返回 -1。 */
    int legacyAvailablePermits() {
        return legacyPermits == null ? -1 : legacyPermits.availablePermits();
    }

    boolean isLegacy(String agentVersion) {
        if (busyAwareMinVersion == null) {
            return false;
        }
        int[] v = parseVersion(agentVersion);
        return v != null && compare(v, busyAwareMinVersion) < 0;
    }

    /**
     * 宽松解析：去掉前导 v，取到第一个非「数字 / 点」为止的数字前缀（{@code 1.3.2-rc1}、{@code 1.3.2+build}
     * 都按 1.3.2），缺位补 0，至多三段。没有数字前缀（dev / 空）返回 null。
     */
    static int[] parseVersion(String version) {
        if (version == null) {
            return null;
        }
        String s = version.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        int end = 0;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) {
            end++;
        }
        if (end == 0 || !Character.isDigit(s.charAt(0))) {
            return null;
        }
        String[] parts = s.substring(0, end).split("\\.");
        int[] out = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                break;
            }
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }
}
