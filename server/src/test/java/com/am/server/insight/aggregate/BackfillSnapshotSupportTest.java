package com.am.server.insight.aggregate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackfillSnapshotSupportTest {

    @Test
    void resolveReportedSnapshot_atTailUsesStored() {
        assertEquals(867, BackfillSnapshotSupport.resolveReportedSnapshot(867, 1000, true));
    }

    @Test
    void resolveReportedSnapshot_midBackfillUsesIncoming() {
        assertEquals(1000, BackfillSnapshotSupport.resolveReportedSnapshot(500, 1000, false));
    }

    @Test
    void reconcileForDisplay_clampsSmallGap() {
        assertEquals(867, BackfillSnapshotSupport.reconcileForDisplay(867, 1000));
    }

    @Test
    void reconcileForDisplay_keepsLargeGap() {
        assertEquals(1000, BackfillSnapshotSupport.reconcileForDisplay(500, 1000));
    }

    @Test
    void backfillComplete_whenStoredMeetsEffectiveSnapshot() {
        assertTrue(BackfillSnapshotSupport.backfillComplete(867, 867));
        assertFalse(BackfillSnapshotSupport.backfillComplete(500, 1000));
    }
}
