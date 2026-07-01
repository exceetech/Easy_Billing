package com.example.easy_billing.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [SyncSnapshot] — the in-memory sync-health model added in
 * Phase 2. No Android dependencies, so these run on the host with `./gradlew test`.
 */
class SyncSnapshotTest {

    @Test
    fun fullySynced_whenAllZero() {
        val s = SyncSnapshot(pending = 0, failed = 0, blockedByProduct = 0)
        assertTrue(s.isFullySynced)
        assertFalse(s.hasProblems)
        assertEquals("All data synced", s.summary())
    }

    @Test
    fun pendingOnly_isNotAProblem() {
        // Routine pending work must not be treated as a problem (no user nag).
        val s = SyncSnapshot(pending = 3)
        assertFalse(s.hasProblems)
        assertFalse(s.isFullySynced)
        assertEquals("3 record(s) waiting to sync", s.summary())
    }

    @Test
    fun failed_isAProblem() {
        val s = SyncSnapshot(failed = 2)
        assertTrue(s.hasProblems)
        assertEquals("2 record(s) couldn't sync — will retry automatically", s.summary())
    }

    @Test
    fun blockedByProduct_isAProblem() {
        val s = SyncSnapshot(blockedByProduct = 1)
        assertTrue(s.hasProblems)
        assertEquals(
            "1 product(s) still uploading — dependent records will sync after",
            s.summary()
        )
    }

    @Test
    fun failedAndBlocked_combinedMessage() {
        val s = SyncSnapshot(failed = 4, blockedByProduct = 2)
        assertTrue(s.hasProblems)
        assertEquals(
            "4 record(s) failed and 2 product(s) still uploading — will retry",
            s.summary()
        )
    }
}
