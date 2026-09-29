package com.am.server.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启动期“大回填”的可续跑 + 容错骨架（capability_daily / git_commit_attribution / NL skill 归因 /
 * git_commit path_stats 补 file 行共用）。
 *
 * <p><b>此前的问题</b>：这几个回填“任何一天 / 一个 commit 失败就不写完成标记”，于是每次重启都从头重算全部历史——
 * 恰好压在服务刚起、agent 集中补报的时候；而一天的失败往往是确定性的（唯一键冲突、坏数据），重跑一百遍也不会好。
 *
 * <p><b>现在</b>（键都挂在完成标记 {@code <marker>} 下，标记升版本号即整体重来）：
 * <ul>
 *   <li>{@code <marker>.progress}：进度游标（日期回填存“下一个待处理日期”，主键区间回填存“已扫完的主键上界”），
 *       每处理完一个单元落一次；重启从游标续跑，不再从头；</li>
 *   <li>单元（一天 / 一个 commit / 一个会话）失败：先原地重试 {@value #UNIT_ATTEMPTS} 次，仍失败则 WARN
 *       （带日期 / ID）、记入 {@code <marker>.failed}（限长 {@value #MAX_FAILED_ITEMS}）、<b>跳过继续</b>；
 *       清单写满说明是系统性故障（库不可用等），此时中止本轮、不写标记，下次启动从游标续跑；</li>
 *   <li>整轮跑完写完成标记，失败项另留在 {@code .failed} 里：之后每次启动<b>只重试失败项</b>，
 *       最多 {@value #MAX_RETRY_ATTEMPTS} 次启动（次数落在 {@code .failed} 的 attempts 里，崩溃也算一次），
 *       用尽后 ERROR 日志点名放弃，不再重试；删掉 {@code .failed} 键或升 marker 版本可重新起跑。</li>
 * </ul>
 *
 * <p>本类<b>非线程安全</b>：一个回填实例只由它自己的那条后台线程使用。状态读写经 {@link Store}，单测用内存实现。
 * 状态落库失败一律降级为 WARN（只损失续跑能力，不拖垮回填本身）。
 */
final class ResumableBackfill {

    private static final Logger log = LoggerFactory.getLogger(ResumableBackfill.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 失败清单上限；写满 = 系统性故障，中止本轮。 */
    static final int MAX_FAILED_ITEMS = 200;
    /** 失败项最多被“重试轮”重试的启动次数。 */
    static final int MAX_RETRY_ATTEMPTS = 3;
    /** 单元内原地重试次数（含首次），吸收锁等待 / 死锁 / 与整点任务撞车这类瞬时失败。 */
    static final int UNIT_ATTEMPTS = 2;
    static final long DEFAULT_UNIT_BACKOFF_MS = 1_000L;

    /** sys_config 读写抽象：生产走 JDBC 直连，单测走内存 Map。 */
    interface Store {
        boolean markerExists(String key) throws SQLException;

        void writeMarker(String key, String description) throws SQLException;

        /** 键不存在返回 null。 */
        String read(String key) throws SQLException;

        void write(String key, String value, String description) throws SQLException;

        void delete(String key) throws SQLException;

        static Store jdbc(DataSource dataSource) {
            return new Store() {
                @Override
                public boolean markerExists(String key) throws SQLException {
                    return OneShotBackfillSupport.markerExists(dataSource, key);
                }

                @Override
                public void writeMarker(String key, String description) throws SQLException {
                    OneShotBackfillSupport.writeMarker(dataSource, key, description);
                }

                @Override
                public String read(String key) throws SQLException {
                    return OneShotBackfillSupport.readConfig(dataSource, key);
                }

                @Override
                public void write(String key, String value, String description) throws SQLException {
                    OneShotBackfillSupport.upsertConfig(dataSource, key, value, description);
                }

                @Override
                public void delete(String key) throws SQLException {
                    OneShotBackfillSupport.deleteConfig(dataSource, key);
                }
            };
        }
    }

    @FunctionalInterface
    interface Work {
        void run() throws Exception;
    }

    @FunctionalInterface
    interface ItemWork {
        void run(String label) throws Exception;
    }

    enum UnitResult {
        OK,
        /** 最终失败，已记入失败清单，调用方跳过继续。 */
        FAILED,
        /** 失败清单已满：调用方应中止本轮（游标不前移）。 */
        OVERFLOW
    }

    enum PassStatus {
        COMPLETED,
        /** 失败清单写满，中止；未写标记，下次启动从游标续跑。 */
        ABORTED
    }

    /** @param failed 本轮最终失败并记入清单的单元数（不含以前记下的） */
    record PassResult(PassStatus status, int ok, int failed) {}

    /** 重试轮结果。{@code exhausted}=次数用尽仍有失败项（已 ERROR 放弃）。 */
    record RetryResult(int retried, int recovered, int remaining, boolean exhausted) {
        static final RetryResult NONE = new RetryResult(0, 0, 0, false);
    }

    /** 落库的失败清单。 */
    record FailedState(int attempts, List<String> items) {

        static final FailedState EMPTY = new FailedState(0, List.of());

        String toJson() {
            try {
                return MAPPER.writeValueAsString(Map.of("attempts", attempts, "items", items));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        /** 宽松解析：null / 空 / 坏 JSON 一律当空（并 WARN），不让脏状态卡死回填。 */
        static FailedState parse(String json) {
            if (json == null || json.isBlank()) {
                return EMPTY;
            }
            try {
                var node = MAPPER.readTree(json);
                int attempts = node.path("attempts").asInt(0);
                List<String> items = new ArrayList<>();
                for (var it : node.path("items")) {
                    String v = it.asText("");
                    if (!v.isBlank()) {
                        items.add(v);
                    }
                }
                return new FailedState(Math.max(0, attempts), List.copyOf(items));
            } catch (Exception e) {
                log.warn("backfill failed-state unparsable, treating as empty: {} ({})", json, e.toString());
                return EMPTY;
            }
        }
    }

    private final Store store;
    private final String markerKey;
    private final String markerDescription;
    private final long backoffMs;

    private final Set<String> failed = new LinkedHashSet<>();
    private int attempts;
    private boolean failedDirty;

    ResumableBackfill(Store store, String markerKey, String markerDescription) {
        this(store, markerKey, markerDescription, DEFAULT_UNIT_BACKOFF_MS);
    }

    ResumableBackfill(Store store, String markerKey, String markerDescription, long backoffMs) {
        this.store = store;
        this.markerKey = markerKey;
        this.markerDescription = markerDescription;
        this.backoffMs = backoffMs;
    }

    String markerKey() {
        return markerKey;
    }

    String progressKey() {
        return markerKey + ".progress";
    }

    String failedKey() {
        return markerKey + ".failed";
    }

    // ==================================================================================
    // 状态
    // ==================================================================================

    boolean isDone() throws SQLException {
        return store.markerExists(markerKey);
    }

    /**
     * runner 线程上的廉价判断（两条主键查询）：还有没有活——标记没写（要跑主轮），或标记已写但还有
     * 未用尽重试次数的失败项（要跑重试轮）。都没有则调用方不必起线程。
     */
    boolean hasPendingWork() throws SQLException {
        if (!isDone()) {
            return true;
        }
        FailedState st = FailedState.parse(store.read(failedKey()));
        return !st.items().isEmpty() && st.attempts() < MAX_RETRY_ATTEMPTS;
    }

    /** 游标原值；从未落过 / 读不到返回 null。 */
    String readProgress() {
        try {
            return store.read(progressKey());
        } catch (SQLException e) {
            log.warn("{} progress read failed (starting from the beginning): {}", markerKey, e.getMessage());
            return null;
        }
    }

    /** 把 {@code .failed} 读进内存。主轮 / 重试轮开跑前调用。 */
    void loadFailed() {
        failed.clear();
        attempts = 0;
        failedDirty = false;
        try {
            FailedState st = FailedState.parse(store.read(failedKey()));
            failed.addAll(st.items());
            attempts = st.attempts();
        } catch (SQLException e) {
            log.warn("{} failed-list read failed (treating as empty): {}", markerKey, e.getMessage());
        }
    }

    Set<String> failedItems() {
        return Set.copyOf(failed);
    }

    int retryAttempts() {
        return attempts;
    }

    /** 落游标（及有变化的失败清单）。best-effort。 */
    void checkpoint(String cursor) {
        try {
            store.write(progressKey(), cursor, markerKey + " 回填进度游标");
        } catch (SQLException e) {
            log.warn("{} progress write failed (resume ability degraded): {}", markerKey, e.getMessage());
        }
        persistFailedIfDirty();
    }

    /**
     * 整轮跑完：落失败清单、写完成标记、清游标。标记写失败返回 false（游标已在末尾，下次启动秒过并补写）。
     */
    boolean complete() {
        persistFailedIfDirty();
        try {
            store.writeMarker(markerKey, markerDescription);
        } catch (SQLException e) {
            log.warn("{} completion marker write failed (next boot will finish quickly from the saved cursor): {}",
                    markerKey, e.getMessage());
            return false;
        }
        try {
            store.delete(progressKey());
        } catch (SQLException e) {
            log.debug("{} progress key cleanup failed: {}", markerKey, e.getMessage());
        }
        if (!failed.isEmpty()) {
            log.warn("{} done, but {} item(s) failed and were skipped; they stay in sys_config '{}' and will be retried "
                    + "(only those) on the next {} boot(s): {}",
                    markerKey, failed.size(), failedKey(), MAX_RETRY_ATTEMPTS, preview(failed));
        }
        return true;
    }

    // ==================================================================================
    // 单元执行
    // ==================================================================================

    /**
     * 执行一个单元：原地重试 {@value #UNIT_ATTEMPTS} 次，仍失败则记入失败清单。
     *
     * @param label 单元标识（日期 ISO / commit id / session id），进日志与失败清单
     * @throws InterruptedException 线程被中断（不算失败，不记清单）
     */
    UnitResult runUnit(String label, Work work) throws InterruptedException {
        Exception last = attempt(work);
        if (last == null) {
            return UnitResult.OK;
        }
        if (!failed.contains(label)) {
            if (failed.size() >= MAX_FAILED_ITEMS) {
                log.warn("{} failure list is full ({}); item {} failed: {}", markerKey, MAX_FAILED_ITEMS, label,
                        last.toString());
                return UnitResult.OVERFLOW;
            }
            failed.add(label);
            failedDirty = true;
        }
        log.warn("{} item {} failed after {} attempts; recorded in '{}' and skipped: {}",
                markerKey, label, UNIT_ATTEMPTS, failedKey(), last.toString());
        return UnitResult.FAILED;
    }

    /** 跑 {@code work}，最多 {@link #UNIT_ATTEMPTS} 次；成功返回 null，否则返回最后一次异常。 */
    private Exception attempt(Work work) throws InterruptedException {
        Exception last = null;
        for (int i = 1; i <= UNIT_ATTEMPTS; i++) {
            try {
                work.run();
                return null;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                if (i < UNIT_ATTEMPTS && backoffMs > 0) {
                    Thread.sleep(backoffMs * i);
                }
            }
        }
        return last;
    }

    // ==================================================================================
    // 主轮：按日期
    // ==================================================================================

    /**
     * 按日期回填一轮（capability_daily / git_commit_attribution 共用）：从游标续跑到 {@code to}（含），
     * 每天一个单元；单日失败记录后跳过；跑完写标记。
     *
     * @param earliest 数据最早日期（游标早于它时以它为准）
     */
    PassResult runDayPass(LocalDate earliest, LocalDate to, ItemWork perDay) throws InterruptedException {
        loadFailed();
        LocalDate start = resumeDay(readProgress(), earliest);
        if (start.isAfter(earliest)) {
            log.info("{} resuming from saved cursor {} (earliest data day {})", markerKey, start, earliest);
        }
        int ok = 0;
        int newlyFailed = 0;
        for (LocalDate d = start; !d.isAfter(to); d = d.plusDays(1)) {
            final String label = d.toString();
            UnitResult r = runUnit(label, () -> perDay.run(label));
            if (r == UnitResult.OVERFLOW) {
                persistFailedIfDirty();
                log.warn("{} aborted at {}: too many failures ({}); no marker written, will resume from the cursor on next boot",
                        markerKey, d, MAX_FAILED_ITEMS);
                return new PassResult(PassStatus.ABORTED, ok, newlyFailed);
            }
            if (r == UnitResult.OK) {
                ok++;
            } else {
                newlyFailed++;
            }
            checkpoint(d.plusDays(1).toString());
        }
        complete();
        return new PassResult(PassStatus.COMPLETED, ok, newlyFailed);
    }

    /** 游标（下一个待处理日期）解析：缺失 / 坏值 / 早于最早数据日 → 从最早数据日起。 */
    static LocalDate resumeDay(String progress, LocalDate earliest) {
        if (progress == null || progress.isBlank()) {
            return earliest;
        }
        try {
            LocalDate p = LocalDate.parse(progress.trim());
            return p.isBefore(earliest) ? earliest : p;
        } catch (RuntimeException e) {
            return earliest;
        }
    }

    /** 主键区间游标（“id 小于它的都已扫完”）解析：缺失 / 坏值 → {@code floor}。 */
    static long resumeId(String progress, long floor) {
        if (progress == null || progress.isBlank()) {
            return floor;
        }
        try {
            return Math.max(floor, Long.parseLong(progress.trim()));
        } catch (NumberFormatException e) {
            return floor;
        }
    }

    // ==================================================================================
    // 重试轮
    // ==================================================================================

    /**
     * 完成标记已写之后的启动：只重试失败清单里的单元。每个启动最多一轮；次数先落库再跑（崩溃也算），
     * 满 {@value #MAX_RETRY_ATTEMPTS} 次仍失败 → ERROR 放弃（清单保留供人工排查，不再重试）。
     */
    RetryResult retryFailed(ItemWork work) throws InterruptedException {
        loadFailed();
        if (failed.isEmpty()) {
            return RetryResult.NONE;
        }
        if (attempts >= MAX_RETRY_ATTEMPTS) {
            log.info("{} has {} permanently failed item(s) (retries exhausted); see sys_config '{}'",
                    markerKey, failed.size(), failedKey());
            return new RetryResult(0, 0, failed.size(), true);
        }
        attempts++;
        failedDirty = true;
        persistFailedIfDirty();
        int retried = 0;
        int recovered = 0;
        for (String label : new ArrayList<>(failed)) {
            retried++;
            Exception last = attempt(() -> work.run(label));
            if (last == null) {
                failed.remove(label);
                failedDirty = true;
                recovered++;
            } else {
                log.warn("{} retry {}/{}: item {} still failing: {}", markerKey, attempts, MAX_RETRY_ATTEMPTS, label,
                        last.toString());
            }
        }
        if (failed.isEmpty()) {
            try {
                store.delete(failedKey());
            } catch (SQLException e) {
                log.warn("{} failed-list cleanup failed: {}", markerKey, e.getMessage());
            }
            failedDirty = false;
            log.info("{} retry: all {} previously failed item(s) recovered", markerKey, recovered);
            return new RetryResult(retried, recovered, 0, false);
        }
        persistFailedIfDirty();
        boolean exhausted = attempts >= MAX_RETRY_ATTEMPTS;
        if (exhausted) {
            log.error("{} giving up on {} item(s) after {} retry boots; they will NOT be retried again "
                    + "(delete sys_config '{}' or bump the marker version to re-arm): {}",
                    markerKey, failed.size(), MAX_RETRY_ATTEMPTS, failedKey(), preview(failed));
        } else {
            log.warn("{} retry {}/{}: {} of {} item(s) recovered, {} still failing",
                    markerKey, attempts, MAX_RETRY_ATTEMPTS, recovered, retried, failed.size());
        }
        return new RetryResult(retried, recovered, failed.size(), exhausted);
    }

    // ==================================================================================

    private void persistFailedIfDirty() {
        if (!failedDirty) {
            return;
        }
        try {
            if (failed.isEmpty()) {
                store.delete(failedKey());
            } else {
                store.write(failedKey(), new FailedState(attempts, new ArrayList<>(failed)).toJson(),
                        markerKey + " 失败项清单（下次启动仅重试这些）");
            }
            failedDirty = false;
        } catch (SQLException e) {
            log.warn("{} failed-list write failed: {}", markerKey, e.getMessage());
        }
    }

    private static String preview(Set<String> items) {
        List<String> head = new ArrayList<>();
        for (String s : items) {
            if (head.size() >= 20) {
                head.add("… +" + (items.size() - 20) + " more");
                break;
            }
            head.add(s);
        }
        return head.toString();
    }
}
