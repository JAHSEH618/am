package com.am.server.insight.aggregate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RevertSubjectSignalsTest {

    @Test
    void matches_git_default_revert_subject() {
        assertTrue(RevertSubjectSignals.matches("Revert \"feat: x\""));
        assertTrue(RevertSubjectSignals.matches("revert 'wip'"));
    }

    @Test
    void matches_conventional_revert_type() {
        assertTrue(RevertSubjectSignals.matches("revert: undo bad deploy"));
    }

    @Test
    void matches_rollback_word() {
        assertTrue(RevertSubjectSignals.matches("Emergency rollback of billing flag"));
    }

    @Test
    void matches_git_revert_prefix() {
        assertTrue(RevertSubjectSignals.matches("git revert deadbeef"));
    }

    @Test
    void ignores_routine_fix_hotfix_bugfix() {
        assertFalse(RevertSubjectSignals.matches("fix login redirect"));
        assertFalse(RevertSubjectSignals.matches("Fix: handle null"));
        assertFalse(RevertSubjectSignals.matches("hotfix: typo"));
        assertFalse(RevertSubjectSignals.matches("bugfix for race"));
        assertFalse(RevertSubjectSignals.matches(null));
    }
}
