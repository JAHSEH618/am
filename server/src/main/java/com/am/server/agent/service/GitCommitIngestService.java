package com.am.server.agent.service;

import com.am.server.agent.api.dto.GitCommitReportRequest;
import com.am.server.agent.security.SignatureContext;
import com.am.server.aggregator.GitCommitAttributionEngine;
import com.am.server.domain.agent.AgentDevice;
import com.am.server.domain.agent.AgentDeviceRepository;
import com.am.server.domain.git.GitCommit;
import com.am.server.domain.git.GitCommitFile;
import com.am.server.domain.git.GitCommitFileRepository;
import com.am.server.domain.git.GitCommitRepository;
import com.am.server.domain.git.GitPathNormalizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Git 提交流水接收服务（v2.2 Phase 3 + 文件级 diff 明细）。
 * <p>
 * 设计约定：{@code git_commit_file} 为文件级唯一事实来源；{@code path_stats_json} 仅作列表展示缓存，
 * 每次写入 file 行后由 file 明细派生，禁止只写 path_stats 而不写 git_commit_file。
 * gz
 */
@Service
public class GitCommitIngestService {

    private static final Logger log = LoggerFactory.getLogger(GitCommitIngestService.class);

    private final GitCommitRepository gitCommitRepository;
    private final GitCommitFileRepository gitCommitFileRepository;
    private final ObjectMapper objectMapper;
    private final AgentDeviceRepository deviceRepository;
    private final GitCommitAttributionEngine attributionEngine;
    private final GitCommitIngestService self;

    public GitCommitIngestService(GitCommitRepository gitCommitRepository,
                                  GitCommitFileRepository gitCommitFileRepository,
                                  ObjectMapper objectMapper,
                                  AgentDeviceRepository deviceRepository,
                                  GitCommitAttributionEngine attributionEngine,
                                  @Lazy GitCommitIngestService self) {
        this.gitCommitRepository = gitCommitRepository;
        this.gitCommitFileRepository = gitCommitFileRepository;
        this.objectMapper = objectMapper;
        this.deviceRepository = deviceRepository;
        this.attributionEngine = attributionEngine;
        this.self = self;
    }

    public IngestSummary handle(GitCommitReportRequest request, SignatureContext ctx) {
        if (request.getCommits() == null || request.getCommits().isEmpty()) {
            return new IngestSummary(0, 0, 0, 0, 0);
        }

        AgentDevice device = deviceRepository.findByAgentId(ctx.getAgentId()).orElse(null);
        Set<String> allowed = buildAllowedEmails(device, request.getReportedIdentityEmails());
        boolean enforceIdentity = !allowed.isEmpty();

        if (!enforceIdentity) {
            log.warn(
                    "git commit ingest: server-side author check disabled (empty git_user_email and reported_identity_emails) agent_id={}",
                    ctx.getAgentId());
        }

        int inserted = 0;
        int duplicates = 0;
        int detailsUpdated = 0;
        int ignoredMismatch = 0;
        int failed = 0;
        // 归因增量：本次落库/更新的 commit 按 (日期 → user) 收集，循环后交引擎去抖重算。
        // DETAIL_UPDATED 也入队——message_body 可能此时才到，B 档 trailer 判定会变。
        java.util.Map<java.time.LocalDate, Set<String>> attributionDates = new java.util.HashMap<>();

        for (GitCommitReportRequest.Item item : request.getCommits()) {
            try {
                switch (self.ingestOne(item, ctx, allowed, enforceIdentity)) {
                    case INSERTED -> inserted++;
                    case DUPLICATE -> duplicates++;
                    case DETAIL_UPDATED -> detailsUpdated++;
                    case IGNORED_IDENTITY -> ignoredMismatch++;
                }
                if (item.getCommitTime() != null) {
                    attributionDates
                            .computeIfAbsent(item.getCommitTime().toLocalDate(), d -> new HashSet<>())
                            .add(ctx.getUserCode());
                }
            } catch (Exception e) {
                failed++;
                log.warn("ingest commit failed repo={} hash={} err={}",
                        item.getRepoUrl(), item.getCommitHash(), e.toString());
            }
        }
        // 成功路径此前完全不打日志，"收到 0 条" 与 "根本没人上报" 在服务端日志里无法区分。
        log.info("git commit ingest: agent_id={} user_code={} received={} inserted={} duplicates={}"
                        + " details_updated={} ignored_identity_mismatch={} failed={}",
                ctx.getAgentId(), ctx.getUserCode(), request.getCommits().size(),
                inserted, duplicates, detailsUpdated, ignoredMismatch, failed);
        if (inserted + detailsUpdated > 0) {
            attributionEngine.enqueue(attributionDates);
        }
        return new IngestSummary(inserted, duplicates, ignoredMismatch, detailsUpdated, failed);
    }

    /**
     * 存量回填：从 path_stats_json 生成 git_commit_file 行（无 patch）。
     */
    @Transactional
    public void backfillFilesFromPathStats(GitCommit row, List<GitCommitReportRequest.PathStat> stats) {
        if (row == null || row.getId() == null || stats == null || stats.isEmpty()) {
            return;
        }
        if (gitCommitFileRepository.countByCommitId(row.getId()) > 0) {
            return;
        }
        List<GitCommitReportRequest.FileDetail> details = fromPathStats(stats);
        replaceFiles(row.getId(), details);
        row.setPathStatsJson(buildPathStatsJson(details));
        if (row.getDetailStatus() == null || row.getDetailStatus().isBlank()) {
            row.setDetailStatus("partial");
            row.setDetailSkipReason("backfill_from_path_stats");
        }
        gitCommitRepository.save(row);
    }

    private enum CommitIngestOutcome {
        INSERTED,
        DUPLICATE,
        DETAIL_UPDATED,
        IGNORED_IDENTITY
    }

    /** public 以便 Spring 代理生效，保证 save + replaceFiles 同事务。 */
    @Transactional
    public CommitIngestOutcome ingestOne(GitCommitReportRequest.Item item,
                                         SignatureContext ctx,
                                         Set<String> allowed,
                                         boolean enforceIdentity) {
        if (enforceIdentity) {
            String ae = normalizeEmail(item.getAuthorEmail());
            if (ae.isEmpty() || !allowed.contains(ae)) {
                return CommitIngestOutcome.IGNORED_IDENTITY;
            }
        }

        List<GitCommitReportRequest.FileDetail> details = resolveFileDetails(item);
        boolean hasDetails = !details.isEmpty();
        boolean incomingHasRichFiles = item.getFiles() != null && !item.getFiles().isEmpty();

        Optional<GitCommit> existing = gitCommitRepository
                .findByRepoUrlAndCommitHash(item.getRepoUrl(), item.getCommitHash());
        if (existing.isPresent()) {
            if (!hasDetails) {
                return CommitIngestOutcome.DUPLICATE;
            }
            long existingFileRows = gitCommitFileRepository.countByCommitId(existing.get().getId());
            if (existingFileRows > 0 && !incomingHasRichFiles) {
                return CommitIngestOutcome.DUPLICATE;
            }
            if (existingFileRows > 0) {
                long existingPatchRows =
                        gitCommitFileRepository.countByCommitIdAndHasPatch(existing.get().getId(), 1);
                if (!isRicherThanStored(details, existingFileRows, existingPatchRows)) {
                    return CommitIngestOutcome.DUPLICATE;
                }
            }
            GitCommit row = existing.get();
            applyDetailFields(row, item, details);
            replaceFiles(row.getId(), details);
            gitCommitRepository.save(row);
            return CommitIngestOutcome.DETAIL_UPDATED;
        }

        GitCommit row = new GitCommit();
        row.setAgentId(ctx.getAgentId());
        row.setUserCode(ctx.getUserCode());
        row.setHostHash(ctx.getHostHash() == null ? "" : ctx.getHostHash());
        row.setRepoUrl(item.getRepoUrl());
        row.setCommitHash(item.getCommitHash());
        row.setCommitTime(item.getCommitTime());
        row.setAuthorName(item.getAuthorName());
        row.setAuthorEmail(item.getAuthorEmail());
        row.setMessageSubject(item.getMessageSubject());
        row.setBranchName(item.getBranchName());
        row.setFilesChanged(nz(item.getFilesChanged()));
        row.setLinesAdded(nz(item.getLinesAdded()));
        row.setLinesDeleted(nz(item.getLinesDeleted()));

        applyDetailFields(row, item, details);

        gitCommitRepository.save(row);
        if (hasDetails) {
            replaceFiles(row.getId(), details);
        }
        return CommitIngestOutcome.INSERTED;
    }

    /**
     * 本次上报的文件明细是否比库里已存的更"富"——只有更富才值得走 {@link #replaceFiles}。
     *
     * <p>commit 是不可变的：同一个 (repo_url, commit_hash) 的 diff 永远一样，所以重报本身
     * 不带来任何新信息。而 replaceFiles 是 delete + insert 全表重写，ROW 格式 binlog 会把
     * 每行的前镜像（DELETE）和后镜像（INSERT）都记一遍——含 {@code patch_gzip} MEDIUMBLOB，
     * 于是一次零变更的重报要写 2 倍 blob 字节。客户端游标只在上报成功后推进
     * （{@code agent/internal/reporter/gitlog.go}），一旦出现超时重发就会反复触发这条路径。
     *
     * <p>"更富"的两种情况：拿到了更多 patch（此前是 path_stats 合成的无 patch 行），
     * 或者拿到了更多文件行（此前的明细被截断过）。两者都不满足时直接判 DUPLICATE。
     */
    static boolean isRicherThanStored(List<GitCommitReportRequest.FileDetail> incoming,
                                      long storedFileRows,
                                      long storedPatchRows) {
        long incomingPatchRows = incoming.stream()
                .filter(GitCommitReportRequest.FileDetail::isHasPatch)
                .count();
        return incomingPatchRows > storedPatchRows || incoming.size() > storedFileRows;
    }

    /**
     * 优先 agent enrich 的 files；否则用 path_stats 合成无 patch 的 file 行。
     */
    static List<GitCommitReportRequest.FileDetail> resolveFileDetails(GitCommitReportRequest.Item item) {
        if (item.getFiles() != null && !item.getFiles().isEmpty()) {
            return item.getFiles();
        }
        return fromPathStats(item.getPathStats());
    }

    private static List<GitCommitReportRequest.FileDetail> fromPathStats(List<GitCommitReportRequest.PathStat> stats) {
        if (stats == null || stats.isEmpty()) {
            return List.of();
        }
        List<GitCommitReportRequest.FileDetail> out = new ArrayList<>(stats.size());
        int order = 0;
        for (GitCommitReportRequest.PathStat ps : stats) {
            if (ps.getPath() == null || ps.getPath().isBlank()) {
                continue;
            }
            GitCommitReportRequest.FileDetail f = new GitCommitReportRequest.FileDetail();
            f.setPath(ps.getPath());
            f.setChangeType("M");
            f.setLinesAdded(ps.getLinesAdded());
            f.setLinesDeleted(ps.getLinesDeleted());
            f.setSortOrder(order++);
            out.add(f);
        }
        return out;
    }

    private void applyDetailFields(GitCommit row,
                                   GitCommitReportRequest.Item item,
                                   List<GitCommitReportRequest.FileDetail> details) {
        row.setMessageBody(truncate(item.getMessageBody(), 8192));
        if (item.getParentHashes() != null && !item.getParentHashes().isEmpty()) {
            try {
                row.setParentHashesJson(objectMapper.writeValueAsString(item.getParentHashes()));
            } catch (JsonProcessingException e) {
                row.setParentHashesJson(null);
            }
        }
        row.setIsMerge(Boolean.TRUE.equals(item.getIsMerge()) ? 1 : 0);
        row.setDetailStatus(item.getDetailStatus());
        row.setDetailSkipReason(item.getDetailSkipReason());
        if (!details.isEmpty()) {
            row.setDetailCollectedAt(LocalDateTime.now());
            row.setPathStatsJson(buildPathStatsJson(details));
        }
    }

    private String buildPathStatsJson(List<GitCommitReportRequest.FileDetail> files) {
        List<GitCommitReportRequest.PathStat> stats = new ArrayList<>(files.size());
        for (GitCommitReportRequest.FileDetail f : files) {
            if (f.getPath() == null || f.getPath().isBlank()) {
                continue;
            }
            GitCommitReportRequest.PathStat ps = new GitCommitReportRequest.PathStat();
            ps.setPath(GitPathNormalizer.normalize(f.getPath()));
            ps.setLinesAdded(f.getLinesAdded());
            ps.setLinesDeleted(f.getLinesDeleted());
            stats.add(ps);
        }
        try {
            return objectMapper.writeValueAsString(stats);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void replaceFiles(Long commitId, List<GitCommitReportRequest.FileDetail> files) {
        gitCommitFileRepository.deleteByCommitId(commitId);
        int order = 0;
        List<GitCommitFile> toSave = new ArrayList<>();
        for (GitCommitReportRequest.FileDetail f : files) {
            String path = GitPathNormalizer.normalize(f.getPath());
            if (path.isBlank()) {
                continue;
            }
            GitCommitFile row = new GitCommitFile();
            row.setCommitId(commitId);
            row.setPath(truncate(path, 1024));
            String oldPath = f.getOldPath() == null ? null : GitPathNormalizer.normalize(f.getOldPath());
            row.setOldPath(oldPath == null || oldPath.isBlank() ? null : truncate(oldPath, 1024));
            row.setChangeType(f.getChangeType() == null || f.getChangeType().isBlank() ? "M" : f.getChangeType().substring(0, 1));
            row.setLinesAdded(f.getLinesAdded());
            row.setLinesDeleted(f.getLinesDeleted());
            row.setIsBinary(f.isBinary() ? 1 : 0);
            row.setHasPatch(f.isHasPatch() ? 1 : 0);
            row.setPatchTruncated(f.isPatchTruncated() ? 1 : 0);
            row.setTruncateReason(f.getTruncateReason());
            row.setSortOrder(f.getSortOrder() > 0 ? f.getSortOrder() : order);
            if (f.isHasPatch() && f.getPatchGzipBase64() != null && !f.getPatchGzipBase64().isBlank()) {
                try {
                    row.setPatchGzip(Base64.getDecoder().decode(f.getPatchGzipBase64().trim()));
                    row.setPatchBytes(f.getPatchBytes() > 0 ? f.getPatchBytes() : row.getPatchGzip().length);
                } catch (IllegalArgumentException e) {
                    row.setHasPatch(0);
                    row.setPatchGzip(null);
                }
            }
            toSave.add(row);
            order++;
        }
        if (!toSave.isEmpty()) {
            gitCommitFileRepository.saveAll(toSave);
        }
    }

    private static Set<String> buildAllowedEmails(AgentDevice device, List<String> reported) {
        Set<String> set = new HashSet<>();
        if (device != null && device.getGitUserEmail() != null) {
            String n = normalizeEmail(device.getGitUserEmail());
            if (!n.isEmpty()) {
                set.add(n);
            }
        }
        if (reported != null) {
            for (String e : reported) {
                String n = normalizeEmail(e);
                if (!n.isEmpty()) {
                    set.add(n);
                }
            }
        }
        return set;
    }

    private static String normalizeEmail(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    /**
     * {@code failed} = 逐条落库抛异常的条数。整个请求仍返 200（其余提交已入库），
     * 但客户端据此保留 gitlog cursor 下轮重报——否则这些提交会永久丢失。
     */
    public record IngestSummary(int inserted, int duplicates, int ignoredIdentityMismatch, int detailsUpdated,
                                int failed) {
    }
}
