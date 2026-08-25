package com.am.server.service;

import com.am.server.domain.git.GitCommitFileRepository;
import com.am.server.system.SystemConfigService;
import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import com.am.server.system.scheduling.ScheduledTaskDefinition;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code git_commit_file.patch_gzip} 的保留期清理。
 *
 * <p><b>为什么要有保留期</b>：patch blob 是全库最大的一块（现网 3.5G，占 am 库一半以上），
 * 而它只服务一个功能——控制台里点开单个文件看 diff 的抽屉
 * （{@code GET /api/v1/git-commits/patch}）。归因、渗透率、insight 报告、能力统计、
 * 文件列表本身，没有任何一处读它。而真实使用里没人会去翻两个月前某个 commit 的 diff，
 * 要看历史 diff 的人会直接去代码仓库——{@code repo_url} 本来就存着。
 *
 * <p><b>只清 blob，不删行</b>：命中的行保留 path / change_type / 增删行数，
 * 所以文件列表、统计口径、归因全都不受影响；只有"点开看 diff"对过期 commit 降级，
 * 前端按 {@code truncate_reason='expired'} 显示"已超过保留期"，与"从未采集"区分开。
 *
 * <p><b>唯一会删行的地方</b>是孤儿清理（{@code sweepOrphans}）：父 {@code git_commit} 已不存在的
 * 文件行不属于任何 commit，保留期逻辑（要 {@code JOIN git_commit}）结构上也永远选不中它们。
 *
 * gz
 */
@Component
@RequiredArgsConstructor
public class GitCommitPatchRetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(GitCommitPatchRetentionCleaner.class);

    public static final String TASK_CODE = "git_patch_retention";
    public static final String CONFIG_KEY_RETENTION_DAYS = "git.patch_retention_days";

    /** 默认保留天数。0 或负数 = 关闭清理（保留全量）。 */
    private static final int DEFAULT_RETENTION_DAYS = 60;

    /** 单批处理的 commit 数。清理会把命中行 has_patch 置 0，故下一批自然取到新的 commit。 */
    private static final int COMMIT_BATCH = 200;

    /** 单次运行的批次上限：存量首清可能是几万个 commit，删不完留给下一拍。 */
    private static final int MAX_BATCHES = 500;

    /** 孤儿文件行单批删除条数。 */
    private static final int ORPHAN_BATCH = 1000;

    /**
     * 孤儿清理单次运行的批次上限。存量约 200 万行 / 8.8G，一次删完会产生同量级 binlog 写放大
     * （ROW 格式要记 MEDIUMBLOB 前镜像），所以每拍最多 30 万行、分几天摊平。
     */
    private static final int MAX_ORPHAN_BATCHES = 300;

    private final GitCommitFileRepository gitCommitFileRepository;
    private final SystemConfigService systemConfigService;
    private final DynamicScheduledTaskManager scheduledTaskManager;

    @PostConstruct
    public void init() {
        systemConfigService.seedIfAbsent(CONFIG_KEY_RETENTION_DAYS,
                String.valueOf(DEFAULT_RETENTION_DAYS), "int", "git", false,
                "commit diff patch 保留天数；超期只清空 patch_gzip，文件行与统计口径保留。0 = 不清理");
        scheduledTaskManager.register(
                new ScheduledTaskDefinition(
                        TASK_CODE,
                        "Commit diff patch 保留期清理",
                        ScheduledTaskDefinition.CATEGORY_BUSINESS,
                        "0 45 3 * * *",
                        true,
                        true,
                        true,
                        "每天 03:45 清空超过保留期的 commit diff（默认 60 天，见 sys_config "
                                + CONFIG_KEY_RETENTION_DAYS + "）。只清 patch_gzip，"
                                + "文件行、增删行数、归因口径均不受影响；另顺带删除父 commit 已不存在的孤儿文件行。",
                        "Asia/Shanghai"),
                this::cleanup);
    }

    public int cleanup() {
        // 孤儿行是纯垃圾，与保留期开关无关，所以放在开关判断之前。
        int swept = sweepOrphans();
        int retentionDays = systemConfigService.getInt(CONFIG_KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS);
        if (retentionDays <= 0) {
            log.info("git patch retention: disabled (retention_days={})", retentionDays);
            return swept;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int cleared = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            List<Long> commitIds =
                    gitCommitFileRepository.findCommitIdsWithExpiredPatches(cutoff, COMMIT_BATCH);
            if (commitIds.isEmpty()) {
                break;
            }
            cleared += gitCommitFileRepository.expirePatchesByCommitIds(commitIds);
        }
        if (cleared > 0) {
            log.info("git patch retention: cleared {} file patches older than {} ({} days)",
                    cleared, cutoff, retentionDays);
        }
        return cleared + swept;
    }

    /**
     * 删除父 commit 已不存在的文件行。
     *
     * <p>这批数据是 {@code GitCommitFileRepository#deleteByCommitId} 缺 {@code flushAutomatically}
     * 时攒下的：父行 INSERT 被 {@code em.clear()} 丢弃、子行照落，于是 {@code commit_id} 指向
     * 一个永远不会存在的 id。它们不属于任何 commit，既不参与任何统计，也永远不会被
     * {@link #cleanup()} 的保留期逻辑选中（那条 SQL 要 {@code JOIN git_commit}）。
     */
    private int sweepOrphans() {
        int deleted = 0;
        long afterId = 0L;
        for (int i = 0; i < MAX_ORPHAN_BATCHES; i++) {
            List<Long> ids = gitCommitFileRepository.findOrphanFileIds(afterId, ORPHAN_BATCH);
            if (ids.isEmpty()) {
                break;
            }
            afterId = ids.get(ids.size() - 1);
            deleted += gitCommitFileRepository.deleteByIdIn(ids);
        }
        if (deleted > 0) {
            log.info("git orphan file rows: deleted {} rows whose parent commit no longer exists"
                    + " (cap {} per run)", deleted, ORPHAN_BATCH * MAX_ORPHAN_BATCHES);
        }
        return deleted;
    }
}
