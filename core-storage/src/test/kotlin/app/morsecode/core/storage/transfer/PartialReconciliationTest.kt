package app.morsecode.core.storage.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every shape a partial can be in after an interruption.
 *
 * The four ordinary shapes — equal, longer, shorter, missing — are all ordinary
 * consequences of a process death, and each one is asserted here to produce both
 * the right action and the right resume offset. The load-bearing claim is the
 * direction the offset moves: it may stay where it was or go backwards, never
 * forwards. Bytes beyond the confirmed frontier were never acknowledged, so
 * keeping them would be inventing progress.
 */
class PartialReconciliationTest {

    // --- equal -------------------------------------------------------------

    @Test
    fun `a file that matches the checkpoint resumes from the checkpoint`() {
        val decision = PartialReconciliation.reconcile(
            persistedOffset = 1_048_576L,
            actualLength = 1_048_576L,
            totalBytes = 4_194_304L,
        )
        assertEquals(ReconciliationReason.EQUAL, decision.reason)
        assertEquals(ReconciliationAction.RESUME_AT_CHECKPOINT, decision.action)
        assertEquals(1_048_576L, decision.resumeOffset)
        assertNull(decision.truncateTo)
        assertNull(decision.error)
    }

    @Test
    fun `a finished file that matches is still just a resume point`() {
        val decision = PartialReconciliation.reconcile(
            persistedOffset = 4_194_304L,
            actualLength = 4_194_304L,
            totalBytes = 4_194_304L,
        )
        assertEquals(ReconciliationReason.EQUAL, decision.reason)
        assertEquals(4_194_304L, decision.resumeOffset)
    }

    // --- longer ------------------------------------------------------------

    @Test
    fun `a longer file is truncated back to the confirmed frontier`() {
        // Bytes past the frontier survived the crash but were never
        // acknowledged. They are not progress.
        val decision = PartialReconciliation.reconcile(
            persistedOffset = 1_000L,
            actualLength = 1_500L,
            totalBytes = 4_096L,
        )
        assertEquals(ReconciliationReason.LONGER, decision.reason)
        assertEquals(ReconciliationAction.TRUNCATE_TO_CHECKPOINT, decision.action)
        assertEquals(1_000L, decision.resumeOffset)
        assertEquals(1_000L, decision.truncateTo)
        assertTrue(decision.requiresTruncation)
    }

    @Test
    fun `truncation never moves the frontier forwards, whatever the file says`() {
        val decision = PartialReconciliation.reconcile(
            persistedOffset = 0L,
            actualLength = 4_096L,
            totalBytes = 4_096L,
        )
        assertEquals(ReconciliationReason.LONGER, decision.reason)
        assertEquals(0L, decision.resumeOffset)
    }

    // --- shorter -----------------------------------------------------------

    @Test
    fun `a shorter file resumes from what is actually there`() {
        // The checkpoint over-claimed: a flush was acknowledged without being
        // durable, or the process died mid-write. The real end wins.
        val decision = PartialReconciliation.reconcile(
            persistedOffset = 1_500L,
            actualLength = 1_000L,
            totalBytes = 4_096L,
        )
        assertEquals(ReconciliationReason.SHORTER, decision.reason)
        assertEquals(ReconciliationAction.RESUME_AT_ACTUAL_LENGTH, decision.action)
        assertEquals(1_000L, decision.resumeOffset)
        assertNull(decision.truncateTo)
    }

    @Test
    fun `a shorter file never resumes past its real end`() {
        // Resuming at the persisted 1_500 over a 1_000-byte file would leave a
        // 500-byte hole that verification would report as a clean mismatch with
        // no way to explain it.
        val decision = PartialReconciliation.reconcile(
            persistedOffset = 5_368_709_120L,
            actualLength = 5_368_708_000L,
            totalBytes = 8_796_093_022_207L,
        )
        assertEquals(ReconciliationReason.SHORTER, decision.reason)
        assertEquals(5_368_708_000L, decision.resumeOffset)
        assertTrue(decision.resumeOffset < 5_368_709_120L)
    }

    // --- missing -----------------------------------------------------------

    @Test
    fun `a missing partial with nothing confirmed is an ordinary start`() {
        val decision = PartialReconciliation.reconcile(
            persistedOffset = 0L,
            actualLength = null,
            totalBytes = 4_096L,
        )
        assertEquals(ReconciliationReason.MISSING, decision.reason)
        assertEquals(ReconciliationAction.RESUME_AT_CHECKPOINT, decision.action)
        assertEquals(0L, decision.resumeOffset)
    }

    @Test
    fun `a missing partial with confirmed bytes starts over`() {
        val decision = PartialReconciliation.reconcile(
            persistedOffset = 2_048L,
            actualLength = null,
            totalBytes = 4_096L,
        )
        assertEquals(ReconciliationReason.MISSING, decision.reason)
        assertEquals(ReconciliationAction.RESTART_FROM_ZERO, decision.action)
        assertEquals(0L, decision.resumeOffset)
    }

    // --- implausible -------------------------------------------------------

    @Test
    fun `more bytes on disk than the file can hold is refused, not truncated`() {
        val decision = PartialReconciliation.reconcile(
            persistedOffset = 100L,
            actualLength = 8_000L,
            totalBytes = 4_096L,
        )
        assertEquals(ReconciliationReason.IMPLAUSIBLE, decision.reason)
        assertEquals(ReconciliationAction.REFUSE, decision.action)
        assertFalse(decision.isActionable)
        assertNotNull(decision.error)
        assertTrue(decision.error is TransferStorageError.StateConflict)
    }

    @Test
    fun `a checkpoint beyond the end of the file is refused`() {
        val decision = PartialReconciliation.reconcile(
            persistedOffset = 5_000L,
            actualLength = 1_000L,
            totalBytes = 4_096L,
        )
        assertEquals(ReconciliationAction.REFUSE, decision.action)
        assertNotNull(decision.error)
    }

    @Test
    fun `negative numbers are refused rather than clamped`() {
        assertTrue(
            PartialReconciliation.reconcile(-1L, 0L, 10L).action == ReconciliationAction.REFUSE,
        )
        assertTrue(
            PartialReconciliation.reconcile(0L, -1L, 10L).action == ReconciliationAction.REFUSE,
        )
        assertTrue(
            PartialReconciliation.reconcile(0L, 0L, -1L).action == ReconciliationAction.REFUSE,
        )
    }

    @Test
    fun `a refusal never carries a resume offset that could be used by accident`() {
        val decision = PartialReconciliation.reconcile(-1L, 0L, 10L)
        assertEquals(0L, decision.resumeOffset)
        assertNull(decision.truncateTo)
        assertFalse(decision.isActionable)
    }

    // --- the invariant, stated once --------------------------------------------

    @Test
    fun `no decision ever resumes ahead of the confirmed frontier`() {
        val persisted = listOf(0L, 1L, 1_000L, 1_048_576L, 5_368_709_120L)
        val actuals = listOf(null, 0L, 1L, 512L, 1_000L, 2_000L, 5_368_709_121L)
        val totals = listOf(1_000L, 4_096L, 8_796_093_022_207L)

        for (p in persisted) {
            for (a in actuals) {
                for (t in totals) {
                    if (p > t) continue
                    val decision = PartialReconciliation.reconcile(p, a, t)
                    if (!decision.isActionable) continue
                    val ceiling = minOf(p, t)
                    assertTrue(
                        "resumeOffset ${decision.resumeOffset} must not exceed min(persisted, total) $ceiling " +
                            "for persisted=$p actual=$a total=$t",
                        decision.resumeOffset <= ceiling,
                    )
                }
            }
        }
    }

    @Test
    fun `reconciling twice reaches the same answer`() {
        // Idempotent: a second reconciliation after a crash during recovery must
        // not produce a different plan.
        val first = PartialReconciliation.reconcile(1_000L, 1_500L, 4_096L)
        val afterTruncation = PartialReconciliation.reconcile(1_000L, 1_000L, 4_096L)
        assertEquals(ReconciliationAction.TRUNCATE_TO_CHECKPOINT, first.action)
        assertEquals(ReconciliationAction.RESUME_AT_CHECKPOINT, afterTruncation.action)
        assertEquals(1_000L, first.resumeOffset)
        assertEquals(1_000L, afterTruncation.resumeOffset)
    }

    @Test
    fun `reasons and actions have distinct ids and round-trip`() {
        assertEquals(5, ReconciliationReason.entries.size)
        assertEquals(5, ReconciliationAction.entries.size)
        ReconciliationReason.entries.forEach {
            assertEquals(it, ReconciliationReason.fromId(it.id))
        }
        ReconciliationAction.entries.forEach {
            assertEquals(it, ReconciliationAction.fromId(it.id))
        }
    }
}
