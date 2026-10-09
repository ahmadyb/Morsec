package app.morsecode.transport.lan.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class StepClock(var now: Long = 0L) {
    fun read(): Long = now
    fun advance(millis: Long) {
        now += millis
    }
}

class SecurePairingAttemptLimiterTest {

    private fun granted(admission: PairingAdmission): Boolean = admission is PairingAdmission.Granted

    private fun reason(admission: PairingAdmission): PairingAdmission.RefusalReason? =
        (admission as? PairingAdmission.Refused)?.reason

    @Test
    fun `first attempt from a fresh identity is granted`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(clock::read)
        assertTrue(granted(limiter.tryStart("peer-a", "192.168.1.10")))
        assertEquals(1, limiter.concurrentPairings())
        limiter.releaseConcurrency()
        assertEquals(0, limiter.concurrentPairings())
    }

    @Test
    fun `per-identity start budget is exhausted after three starts`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(clock::read)
        assertTrue(granted(limiter.tryStart("peer-a", "src-1")))
        limiter.releaseConcurrency()
        assertTrue(granted(limiter.tryStart("peer-a", "src-2")))
        limiter.releaseConcurrency()
        assertTrue(granted(limiter.tryStart("peer-a", "src-3")))
        limiter.releaseConcurrency()

        val fourth = limiter.tryStart("peer-a", "src-4")
        assertEquals(
            PairingAdmission.RefusalReason.IDENTITY_START_BUDGET_EXHAUSTED,
            reason(fourth),
        )
        // A different identity is unaffected by peer-a's exhaustion.
        assertTrue(granted(limiter.tryStart("peer-b", "src-1")))
    }

    @Test
    fun `per-source start budget is exhausted after eight starts`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(clock::read)
        repeat(8) { index ->
            val admission = limiter.tryStart("peer-$index", "same-source")
            assertTrue("start $index should be granted", granted(admission))
            limiter.releaseConcurrency()
        }
        val ninth = limiter.tryStart("peer-8", "same-source")
        assertEquals(PairingAdmission.RefusalReason.SOURCE_START_BUDGET_EXHAUSTED, reason(ninth))
    }

    @Test
    fun `per identity-and-source pair budget is exhausted after two starts`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(clock::read)
        assertTrue(granted(limiter.tryStart("peer-a", "src-1")))
        limiter.releaseConcurrency()
        assertTrue(granted(limiter.tryStart("peer-a", "src-1")))
        limiter.releaseConcurrency()
        val third = limiter.tryStart("peer-a", "src-1")
        assertEquals(PairingAdmission.RefusalReason.PAIR_START_BUDGET_EXHAUSTED, reason(third))
        // Same identity, different source, still has identity budget only up to its own cap.
        assertTrue(granted(limiter.tryStart("peer-a", "src-2")))
    }

    @Test
    fun `process-wide concurrency is bounded at two`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(clock::read)
        assertTrue(granted(limiter.tryStart("peer-a", "src-1")))
        assertTrue(granted(limiter.tryStart("peer-b", "src-2")))
        val third = limiter.tryStart("peer-c", "src-3")
        assertEquals(PairingAdmission.RefusalReason.CONCURRENCY_LIMIT_REACHED, reason(third))
        limiter.releaseConcurrency()
        assertTrue(granted(limiter.tryStart("peer-c", "src-3")))
    }

    @Test
    fun `releasing more slots than were granted cannot go negative`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(clock::read)
        limiter.releaseConcurrency()
        limiter.releaseConcurrency()
        assertEquals(0, limiter.concurrentPairings())
        assertTrue(granted(limiter.tryStart("peer-a", "src-1")))
        assertEquals(1, limiter.concurrentPairings())
    }

    @Test
    fun `failure applies a monotonically growing cooldown`() {
        val clock = StepClock()
        // Raise the pair-failure budget so this test observes identity-level escalation alone.
        val limiter = SecurePairingAttemptLimiter(
            clock::read,
            SecurePairingAttemptLimiter.Limits(maxFailuresPerPair = 100),
        )
        assertTrue(granted(limiter.tryStart("peer-a", "src-1")))
        limiter.releaseConcurrency()
        limiter.recordFailure("peer-a", "src-1")

        // Cooldown 1 is 2000ms << 0.
        val during = limiter.tryStart("peer-a", "src-1")
        assertEquals(PairingAdmission.RefusalReason.COOLDOWN_ACTIVE, reason(during))
        assertEquals(2_000L, (during as PairingAdmission.Refused).retryAfterMillis)

        clock.advance(2_000L)
        assertTrue(granted(limiter.tryStart("peer-a", "src-1")))
        limiter.releaseConcurrency()
        limiter.recordFailure("peer-a", "src-1")

        // Cooldown 2 is 2000ms << 1 = 4000ms, strictly greater than the first.
        val second = limiter.tryStart("peer-a", "src-1")
        assertEquals(PairingAdmission.RefusalReason.COOLDOWN_ACTIVE, reason(second))
        assertEquals(4_000L, (second as PairingAdmission.Refused).retryAfterMillis)
    }

    @Test
    fun `cooldown is capped at the documented maximum`() {
        val clock = StepClock()
        val limits = SecurePairingAttemptLimiter.Limits(
            maxFailuresPerIdentity = 100,
            maxFailuresPerPair = 100,
        )
        val limiter = SecurePairingAttemptLimiter(clock::read, limits)
        // Drive the exponent high enough that an uncapped shift would overflow.
        repeat(40) {
            limiter.recordFailure("peer-a", "src-1")
            clock.advance(70_000L)
        }
        assertTrue(granted(limiter.tryStart("peer-a", "src-1")))
        limiter.releaseConcurrency()
        limiter.recordFailure("peer-a", "src-1")
        val refused = limiter.tryStart("peer-a", "src-1")
        assertEquals(PairingAdmission.RefusalReason.COOLDOWN_ACTIVE, reason(refused))
        assertEquals(60_000L, (refused as PairingAdmission.Refused).retryAfterMillis)
    }

    @Test
    fun `three identity failures invalidate the identity permanently`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(clock::read)
        // One source per attempt, so identity-level escalation is what trips here.
        repeat(3) { index ->
            assertTrue(granted(limiter.tryStart("peer-a", "src-$index")))
            limiter.releaseConcurrency()
            limiter.recordFailure("peer-a", "src-$index")
            clock.advance(120_000L)
        }
        assertTrue(limiter.isIdentityInvalidated("peer-a"))
        val attempt = limiter.tryStart("peer-a", "src-1")
        assertEquals(PairingAdmission.RefusalReason.IDENTITY_INVALIDATED, reason(attempt))
        assertEquals(0L, (attempt as PairingAdmission.Refused).retryAfterMillis)
        // Waiting does not resurrect an invalidated identity: rediscovery is required.
        clock.advance(3_600_000L)
        assertEquals(PairingAdmission.RefusalReason.IDENTITY_INVALIDATED, reason(limiter.tryStart("peer-a", "src-1")))
        // Explicit invalidation of a healthy identity has the same effect.
        limiter.invalidateIdentity("peer-b")
        assertEquals(PairingAdmission.RefusalReason.IDENTITY_INVALIDATED, reason(limiter.tryStart("peer-b", "src-1")))
    }

    @Test
    fun `two failures from one identity-source pair invalidate that pair only`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(clock::read)
        repeat(2) {
            assertTrue(granted(limiter.tryStart("peer-a", "src-1")))
            limiter.releaseConcurrency()
            limiter.recordFailure("peer-a", "src-1")
            clock.advance(120_000L)
        }
        // The pair is dead, but the identity is not globally invalidated until three identity failures.
        assertFalse(limiter.isIdentityInvalidated("peer-a"))
        val samePair = limiter.tryStart("peer-a", "src-1")
        assertEquals(PairingAdmission.RefusalReason.PAIR_INVALIDATED, reason(samePair))
        // Waiting does not resurrect an invalidated pair.
        clock.advance(3_600_000L)
        assertEquals(PairingAdmission.RefusalReason.PAIR_INVALIDATED, reason(limiter.tryStart("peer-a", "src-1")))
        // A different source path for the same identity is still usable.
        assertTrue(granted(limiter.tryStart("peer-a", "src-2")))
    }

    @Test
    fun `success clears the failure escalation`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(clock::read)
        assertTrue(granted(limiter.tryStart("peer-a", "src-1")))
        limiter.releaseConcurrency()
        limiter.recordFailure("peer-a", "src-1")
        clock.advance(5_000L)
        assertTrue(granted(limiter.tryStart("peer-a", "src-1")))
        limiter.releaseConcurrency()
        limiter.recordSuccess("peer-a", "src-1")

        // After a success the next failure restarts at the base cooldown, not the escalated one.
        limiter.recordFailure("peer-a", "src-1")
        val refused = limiter.tryStart("peer-a", "src-1")
        assertEquals(2_000L, (refused as PairingAdmission.Refused).retryAfterMillis)
        assertFalse(limiter.isIdentityInvalidated("peer-a"))
    }

    @Test
    fun `reset drops every counter and invalidation`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(clock::read)
        limiter.invalidateIdentity("peer-a")
        assertTrue(granted(limiter.tryStart("peer-b", "src-1")))
        limiter.reset()
        assertEquals(0, limiter.concurrentPairings())
        assertFalse(limiter.isIdentityInvalidated("peer-a"))
        assertTrue(granted(limiter.tryStart("peer-a", "src-1")))
    }

    @Test
    fun `rotating identities cannot grow the tracked maps without bound`() {
        val clock = StepClock()
        val limits = SecurePairingAttemptLimiter.Limits(maxTrackedIdentities = 4)
        val limiter = SecurePairingAttemptLimiter(clock::read, limits)
        repeat(500) { index ->
            limiter.tryStart("peer-$index", "src-$index")
            limiter.releaseConcurrency()
        }
        // Once the cap is reached, newcomers share one budget, so flooding is still throttled.
        val later = limiter.tryStart("peer-late", "src-late")
        assertTrue(
            "flooding must eventually be refused, got $later",
            later is PairingAdmission.Refused,
        )
    }

    @Test
    fun `keys must not be blank`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(clock::read)
        var threw = false
        try {
            limiter.tryStart("  ", "src-1")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
