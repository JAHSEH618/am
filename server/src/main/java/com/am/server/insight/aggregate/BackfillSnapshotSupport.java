package com.am.server.insight.aggregate;

/**
 * 回填进度：Agent {@code snapshot_message_count} 与 DB 已入库行数对齐。
 * <p>典型误报：bootstrap 写入 snapshot=1000，fast-forward 丢消息后 stored=867，
 * 游标已到尾部（recentMessages 空）——此时应以 stored 作为有效快照上界。
 */
public final class BackfillSnapshotSupport {

    /** 与 Agent {@code MaxMessagesPerSession} 对齐：gap 超过此值视为仍在分批回填中。 */
    public static final int RECONCILE_MAX_GAP = 200;

    private BackfillSnapshotSupport() {
    }

    /**
     * ingest 路径：Agent 游标已到快照尾部且 DB 已有数据时，用 stored 修正 stale snapshot。
     */
    public static int resolveReportedSnapshot(int storedCount, int incomingSnapshot, boolean cursorAtTail) {
        if (incomingSnapshot <= 0) {
            return Math.max(incomingSnapshot, storedCount);
        }
        if (cursorAtTail && storedCount > 0) {
            return storedCount;
        }
        return incomingSnapshot;
    }

    /**
     * 读路径 / 详情 API：stored 与 reported 差距在单 tick cap 内时，视为 stale snapshot 误报。
     */
    public static int reconcileForDisplay(int storedCount, int reportedSnapshot) {
        if (storedCount <= 0 || reportedSnapshot <= 0) {
            return reportedSnapshot;
        }
        if (storedCount >= reportedSnapshot) {
            return storedCount;
        }
        if (reportedSnapshot - storedCount <= RECONCILE_MAX_GAP) {
            return storedCount;
        }
        return reportedSnapshot;
    }

    public static boolean backfillComplete(int storedCount, int effectiveSnapshot) {
        if (effectiveSnapshot <= 0) {
            return true;
        }
        if (storedCount <= 0) {
            return false;
        }
        return storedCount >= effectiveSnapshot;
    }
}
