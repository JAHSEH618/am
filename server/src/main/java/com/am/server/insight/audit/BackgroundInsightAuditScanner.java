package com.am.server.insight.audit;

import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.insight.config.InsightProperties;
import com.am.server.system.ActiveTargetTypesProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按主键游标扫描 {@code ai_session}，每批拉取有限行丢给 LLM 审计池；进度落在本地 JSON 文件。
 * gz
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackgroundInsightAuditScanner {

    private final InsightProperties insightProperties;
    private final AiSessionRepository sessionRepository;
    private final SessionAuditService sessionAuditService;
    private final InsightSessionAuditStateService auditStateService;
    private final ActiveTargetTypesProvider activeTargetTypesProvider;
    private final AuditScanCursorStore cursorStore;
    private final ExecutorService backgroundAuditExecutor;

    @Scheduled(fixedDelayString = "${aiwatch.insight.audit-scan-fixed-delay-ms:30000}")
    public void tick() {
        if (!insightProperties.isAuditScanEnabled()) {
            return;
        }
        Collection<String> typesCol = activeTargetTypesProvider.getActiveTypes();
        if (typesCol == null || typesCol.isEmpty()) {
            return;
        }
        List<String> types = new ArrayList<>(typesCol);
        Path cursorFile = insightProperties.resolveAuditScanCursorPath();
        long lastId = cursorStore.readLastId(cursorFile);
        int batch = Math.max(1, insightProperties.getAuditScanBatchSize());
        int budget = insightProperties.getAuditBackgroundMaxLlmCallsPerTick();
        int slotsByBudget = budget <= 0 ? batch : Math.max(1, budget / 2);
        int fetchLimit = Math.min(batch, slotsByBudget);
        int threshold = insightProperties.getReauditMessageThreshold();

        List<Long> ids = sessionRepository.findIdsNeedingInsightAudit(lastId, types, threshold, fetchLimit);
        if (ids.isEmpty()) {
            cursorStore.writeLastId(cursorFile, 0L);
            return;
        }
        long maxIdInBatch = ids.stream().mapToLong(Long::longValue).max().orElse(lastId);

        Map<Long, AiSession> sessions = sessionRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(AiSession::getId, Function.identity(), (a, b) -> a));

        LocalDateTime leaseUntil = LocalDateTime.now().plusMinutes(
                Math.max(1, insightProperties.getAuditScanLeaseMinutes()));

        List<Future<?>> futures = new ArrayList<>(ids.size());
        for (Long id : ids) {
            AiSession session = sessions.get(id);
            if (session == null) {
                continue;
            }
            futures.add(backgroundAuditExecutor.submit(() -> processOne(session.getId(), leaseUntil)));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                log.warn("background audit task get: {}", e.getMessage());
            }
        }

        cursorStore.writeLastId(cursorFile, maxIdInBatch);
    }

    private void processOne(Long sessionId, LocalDateTime leaseUntil) {
        try {
            auditStateService.markRunning(sessionId, leaseUntil);
        } catch (Exception e) {
            log.warn("markRunning failed session={}: {}", sessionId, e.getMessage());
            return;
        }
        AiSession fresh = sessionRepository.findById(sessionId).orElse(null);
        if (fresh == null) {
            return;
        }
        try {
            boolean audited = sessionAuditService.auditSingleIfNeeded(fresh);
            if (audited) {
                auditStateService.markDoneAfterAudit(sessionId);
            } else {
                auditStateService.markDoneIfAlreadySatisfied(sessionId);
            }
        } catch (Exception e) {
            log.warn("background audit failed session={}: {}", sessionId, e.getMessage());
            auditStateService.markFailed(sessionId);
        }
    }
}
