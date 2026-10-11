package app.morsecode.transport.lan.security

import app.morsecode.core.transfer.session.SessionFailureCode
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

    // ---- Outcome budget policy -----------------------------------------------------------
    //
    // These use the injected StepClock only. Nothing here sleeps and nothing asserts on wall-clock
    // elapsed time: cooldown is derived from the fake clock, so the ordering under test is the
    // policy, not the scheduler.

    @Test
    fun `a local cancellation costs the peer nothing`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(clock::read)
        repeat(12) {
            assertTrue(granted(limiter.tryStart("peer-cancel", "src-cancel")))
            limiter.recordOutcome("peer-cancel", "src-cancel", PairingAttemptOutcome.LOCAL_CANCELLATION)
            limiter.releaseConcurrency()
            clock.advance(1)
        }
        assertFalse("cancellation must never invalidate", limiter.isIdentityInvalidated("peer-cancel"))
        assertTrue(
            "cancellation must never trigger a cooldown",
            granted(limiter.tryStart("peer-cancel", "src-cancel")),
        )
        limiter.releaseConcurrency()
    }

    @Test
    fun `a local implementation failure is not charged to the peer`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(clock::read)
        repeat(12) {
            assertTrue(granted(limiter.tryStart("peer-local", "src-local")))
            limiter.recordOutcome("peer-local", "src-local", PairingAttemptOutcome.LOCAL_FAILURE)
            limiter.releaseConcurrency()
            clock.advance(1)
        }
        assertFalse(limiter.isIdentityInvalidated("peer-local"))
        assertTrue(granted(limiter.tryStart("peer-local", "src-local")))
        limiter.releaseConcurrency()
    }

    @Test
    fun `a resource refusal releases concurrency without consuming a failure budget`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(clock::read)
        repeat(12) {
            assertTrue(granted(limiter.tryStart("peer-res", "src-res")))
            limiter.recordOutcome("peer-res", "src-res", PairingAttemptOutcome.RESOURCE_REFUSED)
            limiter.releaseConcurrency()
            clock.advance(1)
        }
        assertEquals(0, limiter.concurrentPairings())
        assertFalse(limiter.isIdentityInvalidated("peer-res"))
    }

    @Test
    fun `transcript mismatch is a security failure that escalates to invalidation`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(clock::read)
        repeat(3) {
            assertTrue(granted(limiter.tryStart("peer-tx", "src-tx")))
            limiter.recordOutcome("peer-tx", "src-tx", PairingAttemptOutcome.TRANSCRIPT_MISMATCH)
            limiter.releaseConcurrency()
            clock.advance(120_000)
        }
        assertTrue(
            "three transcript mismatches must invalidate the identity",
            limiter.isIdentityInvalidated("peer-tx"),
        )
        assertEquals(
            PairingAdmission.RefusalReason.IDENTITY_INVALIDATED,
            reason(limiter.tryStart("peer-tx", "src-tx")),
        )
    }

    @Test
    fun `user rejection applies cooldown but never invalidates`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(clock::read)
        var sawCooldown = false
        repeat(20) {
            when (val admission = limiter.tryStart("peer-decline", "src-decline")) {
                is PairingAdmission.Granted -> limiter.releaseConcurrency()
                is PairingAdmission.Refused -> {
                    assertEquals(
                        "a declined approval may only ever produce a cooldown",
                        PairingAdmission.RefusalReason.COOLDOWN_ACTIVE,
                        admission.reason,
                    )
                    sawCooldown = true
                    clock.advance(admission.retryAfterMillis + 1)
                }
            }
            limiter.recordOutcome("peer-decline", "src-decline", PairingAttemptOutcome.USER_REJECTION)
            clock.advance(1)
        }
        assertTrue("repeated declines must eventually impose a cooldown", sawCooldown)
        assertFalse(
            "a user declining is not evidence of an attack and must never invalidate",
            limiter.isIdentityInvalidated("peer-decline"),
        )
    }

    @Test
    fun `peer disconnect consumes the pair budget but not the identity budget`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(clock::read)
        // maxFailuresPerPair defaults to 2; maxFailuresPerIdentity defaults to 3.
        repeat(2) {
            assertTrue(granted(limiter.tryStart("peer-drop", "src-drop")))
            limiter.recordOutcome("peer-drop", "src-drop", PairingAttemptOutcome.PEER_DISCONNECT)
            limiter.releaseConcurrency()
            clock.advance(120_000)
        }
        assertFalse(
            "a network drop must not condemn the identity",
            limiter.isIdentityInvalidated("peer-drop"),
        )
        assertEquals(
            PairingAdmission.RefusalReason.PAIR_INVALIDATED,
            reason(limiter.tryStart("peer-drop", "src-drop")),
        )
        assertTrue(
            "the same identity on a different route must still be admissible",
            granted(limiter.tryStart("peer-drop", "src-other-route")),
        )
        limiter.releaseConcurrency()
    }

    @Test
    fun `success resets escalation but does not launder the start budget`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(
            monotonicMillis = clock::read,
            limits = SecurePairingAttemptLimiter.Limits(maxStartsPerIdentity = 2),
        )
        assertTrue(granted(limiter.tryStart("peer-ok", "src-ok")))
        limiter.recordOutcome("peer-ok", "src-ok", PairingAttemptOutcome.TRANSCRIPT_MISMATCH)
        limiter.releaseConcurrency()
        clock.advance(120_000)
        assertTrue(granted(limiter.tryStart("peer-ok", "src-ok")))
        limiter.recordOutcome("peer-ok", "src-ok", PairingAttemptOutcome.AUTHENTICATED)
        limiter.releaseConcurrency()
        assertFalse("success clears the failure escalation", limiter.isIdentityInvalidated("peer-ok"))
        assertEquals(
            "success must not refund the start budget",
            PairingAdmission.RefusalReason.IDENTITY_START_BUDGET_EXHAUSTED,
            reason(limiter.tryStart("peer-ok", "src-ok")),
        )
    }

    @Test
    fun `an exhausted identity stays exhausted for a replayed identical advertisement`() {
        val clock = StepClock()
        val limiter = SecurePairingAttemptLimiter(clock::read)
        repeat(3) {
            assertTrue(granted(limiter.tryStart("peer-replay", "src-replay")))
            limiter.recordOutcome("peer-replay", "src-replay", PairingAttemptOutcome.AUTHENTICATION_MISMATCH)
            limiter.releaseConcurrency()
            clock.advance(120_000)
        }
        limiter.invalidateIdentity("peer-replay")
        // Discovery advertisements are unauthenticated, so replaying the same one -- or replaying it
        // with a fresh nonce under the same identity -- cannot mean "new trustworthy peer".
        repeat(5) {
            assertEquals(
                PairingAdmission.RefusalReason.IDENTITY_INVALIDATED,
                reason(limiter.tryStart("peer-replay", "src-replay")),
            )
            assertEquals(
                PairingAdmission.RefusalReason.IDENTITY_INVALIDATED,
                reason(limiter.tryStart("peer-replay", "src-rotated")),
            )
            clock.advance(600_000)
        }
    }

    @Test
    fun `lease teardown releases permits and scopes state freshly`() {
        val clock = StepClock()
        val leaseOne = SecurePairingAttemptLimiter(clock::read)
        repeat(3) {
            assertTrue(granted(leaseOne.tryStart("peer-lease", "src-lease")))
            leaseOne.recordOutcome("peer-lease", "src-lease", PairingAttemptOutcome.TRANSCRIPT_MISMATCH)
        }
        assertEquals(3, leaseOne.concurrentPairings())
        assertTrue(leaseOne.isIdentityInvalidated("peer-lease"))

        // What lease stop does: release every permit handed out, then drop every counter.
        leaseOne.releaseAllConcurrency()
        leaseOne.reset()
        assertEquals(0, leaseOne.concurrentPairings())
        assertFalse(leaseOne.isIdentityInvalidated("peer-lease"))

        val leaseTwo = SecurePairingAttemptLimiter(clock::read)
        assertTrue(
            "a new lease is newly scoped -- this is a process-local policy reset, not a security proof",
            granted(leaseTwo.tryStart("peer-lease", "src-lease")),
        )
        leaseTwo.releaseAllConcurrency()
        leaseTwo.reset()
        assertEquals(0, leaseTwo.concurrentPairings())
    }

    @Test
    fun `every failure code maps to exactly one documented outcome`() {
        assertEquals(
            PairingAttemptOutcome.TRANSCRIPT_MISMATCH,
            pairingOutcomeFor(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID),
        )
        assertEquals(
            PairingAttemptOutcome.KEY_CONFIRMATION_FAILURE,
            pairingOutcomeFor(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED),
        )
        assertEquals(
            PairingAttemptOutcome.USER_REJECTION,
            pairingOutcomeFor(SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED),
        )
        assertEquals(
            PairingAttemptOutcome.APPROVAL_EXPIRED,
            pairingOutcomeFor(SessionFailureCode.SECURE_SESSION_APPROVAL_EXPIRED),
        )
        assertEquals(
            PairingAttemptOutcome.AUTHENTICATION_MISMATCH,
            pairingOutcomeFor(SessionFailureCode.PROTOCOL_VERSION_UNSUPPORTED),
        )
        assertEquals(
            PairingAttemptOutcome.HANDSHAKE_TIMEOUT,
            pairingOutcomeFor(SessionFailureCode.CONTROL_TIMEOUT),
        )
        assertEquals(
            PairingAttemptOutcome.PEER_DISCONNECT,
            pairingOutcomeFor(SessionFailureCode.CONTROL_CONNECT_FAILED),
        )
        assertEquals(
            PairingAttemptOutcome.LOCAL_CANCELLATION,
            pairingOutcomeFor(SessionFailureCode.OPERATION_CANCELLED),
        )
        assertEquals(
            PairingAttemptOutcome.RESOURCE_REFUSED,
            pairingOutcomeFor(SessionFailureCode.CONTROL_CAPACITY_REACHED),
        )
        // Total mapping: an unrelated code still lands on a defined outcome.
        assertEquals(
            PairingAttemptOutcome.LOCAL_FAILURE,
            pairingOutcomeFor(SessionFailureCode.INTERNAL_TRANSPORT_FAILURE),
        )
        assertEquals(11, PairingAttemptOutcome.entries.size)
    }

    @Test
    fun `only the three security outcomes are security failures`() {
        val security = PairingAttemptOutcome.entries.filter { it.isSecurityFailure }
        assertEquals(
            setOf(
                PairingAttemptOutcome.AUTHENTICATION_MISMATCH,
                PairingAttemptOutcome.TRANSCRIPT_MISMATCH,
                PairingAttemptOutcome.KEY_CONFIRMATION_FAILURE,
            ),
            security.toSet(),
        )
    }
}
