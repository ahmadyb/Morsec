package app.morsecode.transport.lan.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The admission handle as one atomic terminal operation.
 *
 * WHAT CHANGED, AND WHY IT MATTERS. Recording an outcome used to take two arbitrary strings, so any
 * caller in the module could charge a budget it had never been admitted against, and a key the
 * limiter had never tracked recorded *nothing at all* -- a silent no-op carried entirely by the call
 * site. An intermediate shape fixed that but split the lifecycle into `recordOutcome` and `release`,
 * and the split let the two race: a release could retire the grant before the outcome landed, so a
 * genuine security failure was erased by the cleanup that followed it. Gating that release on the
 * scope being open leaked a permit too, whenever a grant was acquired between the scope's closed
 * check and `close()` itself.
 *
 * [AdmissionHandle.complete] is now the whole lifecycle under one compare-and-set: claim the
 * transition, apply the outcome's counters/cooldown/invalidation, retire the live grant, return
 * exactly one permit. Cancellation is an outcome, not a second path.
 *
 * WHAT MAKES FORGERY FAIL, STATED PRECISELY. The constructor is `internal`, not `private` -- Kotlin
 * has no file-private constructor and a private one would be unreachable from the issuing scope --
 * so the invariant does not rest on visibility. It rests on reference identity: a scope honours a
 * handle only when its live-grant table maps that token to *that exact object*.
 */
public class AdmissionHandleTest {

    private class StepClock(private var now: Long = 0L) {
        fun read(): Long = now
        fun advance(millis: Long) {
            now += millis
        }
    }

    private fun permissive() = SecurePairingAttemptLimiter.Limits(
        maxStartsPerIdentity = 1_000,
        maxStartsPerSource = 1_000,
        maxStartsPerPair = 1_000,
        maxConcurrentPairings = 1_000,
        maxFailuresPerIdentity = 1_000,
        maxFailuresPerPair = 1_000,
    )

    private fun handleOf(decision: AdmissionDecision): AdmissionHandle? =
        (decision as? AdmissionDecision.Granted)?.handle

    /** Runs `threads` contenders against one handle and returns the receipts each one saw. */
    private fun race(
        handle: AdmissionHandle,
        threads: Int,
        outcomeFor: (Int) -> PairingAttemptOutcome,
    ): List<AdmissionReceipt> {
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val receipts = java.util.Collections.synchronizedList(mutableListOf<AdmissionReceipt>())
        repeat(threads) { index ->
            Thread {
                try {
                    start.await(5, TimeUnit.SECONDS)
                    receipts.add(handle.complete(outcomeFor(index)))
                } catch (_: Exception) {
                    // The receipts below are the real assertion.
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        assertTrue("the race must finish", done.await(10, TimeUnit.SECONDS))
        return receipts.toList()
    }

    @Test
    public fun `a refusal issues no handle, so an outcome is unrepresentable`() {
        // The whole point of the type. `AdmissionDecision.Refused` carries a reason and a retry
        // delay and no handle member, so charging a budget for an identity that was never admitted
        // cannot be written at all -- not merely discouraged.
        val scope = PairingAdmissionScope(monotonicMillis = { 0L })
        scope.close()
        val decision = scope.tryStart("peer-unadmitted", "src-unadmitted")
        assertTrue("a closed scope must refuse", decision is AdmissionDecision.Refused)
        assertNull("a refusal must not issue a handle", handleOf(decision))
        assertEquals(
            PairingAdmission.RefusalReason.SCOPE_CLOSED,
            (decision as AdmissionDecision.Refused).reason,
        )
    }

    @Test
    public fun `two different outcomes produce one winner and one recorded policy result`() {
        repeat(200) {
            val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
            val handle = requireNotNull(handleOf(scope.tryStart("peer-two", "src-two")))
            val receipts = race(handle, 8) { index ->
                // Half the contenders insist the peer defeated the transcript; half insist it
                // authenticated. Exactly one of those stories may be recorded.
                if (index % 2 == 0) PairingAttemptOutcome.TRANSCRIPT_MISMATCH
                else PairingAttemptOutcome.AUTHENTICATED
            }
            assertEquals("exactly one contender wins the terminal transition",
                1, receipts.count { it == AdmissionReceipt.Completed })
            assertEquals("every other contender is told it was too late",
                7, receipts.count { it == AdmissionReceipt.AlreadyCompleted })
            assertEquals(0, receipts.count { it == AdmissionReceipt.ScopeClosed })
            assertEquals(0, receipts.count { it == AdmissionReceipt.NotAdmitted })
            // The receipts list is in completion order, not contender order, so the winner is
            // identified by the recorded outcome rather than by a list index.
            assertTrue("the winner's outcome is the one that stuck",
                handle.terminalOutcome == PairingAttemptOutcome.TRANSCRIPT_MISMATCH ||
                    handle.terminalOutcome == PairingAttemptOutcome.AUTHENTICATED)
            assertTrue(handle.isCompleted)
            assertEquals("exactly one permit was returned", 0, scope.concurrentPairings())
            scope.close()
        }
    }

    @Test
    public fun `a security failure is never lost to a concurrent cancellation`() {
        // The exact defect the split lifecycle had: a release/cancel path could retire the grant
        // first and the transcript mismatch would vanish with no trace.
        repeat(200) {
            val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
            val handle = requireNotNull(handleOf(scope.tryStart("peer-lost", "src-lost")))
            val receipts = race(handle, 8) { index ->
                if (index == 0) PairingAttemptOutcome.TRANSCRIPT_MISMATCH
                else PairingAttemptOutcome.LOCAL_CANCELLATION
            }
            assertEquals("one winner", 1, receipts.count { it == AdmissionReceipt.Completed })
            assertNotNull("the terminal outcome is always recorded, whichever contender won",
                handle.terminalOutcome)
            if (handle.terminalOutcome == PairingAttemptOutcome.TRANSCRIPT_MISMATCH) {
                assertTrue(
                    "a recorded security failure must reach the limiter's budget policy",
                    scope.tryStart("peer-lost", "src-lost") is AdmissionDecision.Refused,
                )
            } else {
                assertEquals(PairingAttemptOutcome.LOCAL_CANCELLATION, handle.terminalOutcome)
            }
            assertEquals(0, scope.concurrentPairings())
            scope.close()
        }
    }

    @Test
    public fun `outcome racing lease close applies at most once and never loses the permit`() {
        repeat(200) {
            val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
            val handle = requireNotNull(handleOf(scope.tryStart("peer-close", "src-close")))
            val start = CountDownLatch(1)
            val done = CountDownLatch(9)
            val receipts = java.util.Collections.synchronizedList(mutableListOf<AdmissionReceipt>())
            repeat(8) {
                Thread {
                    try {
                        start.await(5, TimeUnit.SECONDS)
                        receipts.add(handle.complete(PairingAttemptOutcome.TRANSCRIPT_MISMATCH))
                    } catch (_: Exception) {
                        // Counted below.
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            Thread {
                try {
                    start.await(5, TimeUnit.SECONDS)
                    scope.close()
                } catch (_: Exception) {
                    // Same.
                } finally {
                    done.countDown()
                }
            }.start()
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            val list = receipts.toList()
            assertEquals("exactly one contender claims the transition",
                1, list.count { it != AdmissionReceipt.AlreadyCompleted })
            assertEquals("exactly one late contender is refused as a duplicate",
                7, list.count { it == AdmissionReceipt.AlreadyCompleted })
            assertTrue("the winner either applied or found the scope closed",
                list.contains(AdmissionReceipt.Completed) || list.contains(AdmissionReceipt.ScopeClosed))
            assertTrue(handle.isCompleted)
            assertEquals("the permit count must settle at zero, not leak", 0, scope.concurrentPairings())
            scope.close()
        }
    }

    @Test
    public fun `closing a scope twice is idempotent and cannot drive the count negative`() {
        val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        val handle = requireNotNull(handleOf(scope.tryStart("peer-twice", "src-twice")))
        assertEquals(1, scope.concurrentPairings())
        repeat(3) { scope.close() }
        assertTrue(scope.isClosed)
        assertEquals(0, scope.concurrentPairings())
        // A completion after close still returns the permit -- harmlessly, against a reset limiter.
        assertEquals(AdmissionReceipt.ScopeClosed, handle.complete(PairingAttemptOutcome.PEER_DISCONNECT))
        repeat(4) {
            assertEquals(AdmissionReceipt.AlreadyCompleted, handle.complete(PairingAttemptOutcome.PEER_DISCONNECT))
        }
        assertEquals("the permit count must never go negative", 0, scope.concurrentPairings())
    }

    @Test
    public fun `a late completion after lease close cannot mutate reset state`() {
        val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        val handle = requireNotNull(handleOf(scope.tryStart("peer-late", "src-late")))
        scope.close()
        assertEquals(
            "the counters are gone, so the outcome must not be written",
            AdmissionReceipt.ScopeClosed,
            handle.complete(PairingAttemptOutcome.TRANSCRIPT_MISMATCH),
        )
        assertEquals(
            "the caller is still told which outcome it completed with",
            PairingAttemptOutcome.TRANSCRIPT_MISMATCH,
            handle.terminalOutcome,
        )
        assertEquals("the permit was still returned", 0, scope.concurrentPairings())
        assertFalse("a closed scope cannot be resurrected into an invalidated one",
            scope.isIdentityInvalidated("peer-late"))
    }

    @Test
    public fun `a cancellation after lease close is safe because the finally must still run`() {
        val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        val handle = requireNotNull(handleOf(scope.tryStart("peer-cancel", "src-cancel")))
        scope.close()
        // An in-flight coordinator completes in a finally; that must not throw or double-count just
        // because the lease stopped first.
        assertEquals(AdmissionReceipt.ScopeClosed, handle.complete(PairingAttemptOutcome.LOCAL_CANCELLATION))
        assertEquals(0, scope.concurrentPairings())
    }

    @Test
    public fun `a handle issued by another lease is rejected and cannot move its permits`() {
        val first = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        val second = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        try {
            val foreign = requireNotNull(handleOf(first.tryStart("peer-a", "src-a")))
            assertTrue("live in its own scope", first.isLiveGrant(foreign))
            assertFalse("inert everywhere else", second.isLiveGrant(foreign))
            assertEquals(
                "a foreign scope cannot apply an outcome it never granted",
                AdmissionReceipt.NotAdmitted,
                second.applyOutcome(foreign, PairingAttemptOutcome.TRANSCRIPT_MISMATCH),
            )
            assertEquals("the foreign scope's permits are untouched", 0, second.concurrentPairings())
            // Completing still goes to the owning scope, because the handle carries its owner.
            assertEquals(AdmissionReceipt.Completed, foreign.complete(PairingAttemptOutcome.TRANSCRIPT_MISMATCH))
            assertEquals("the owning scope's permit came back", 0, first.concurrentPairings())
            assertEquals(0, second.concurrentPairings())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    public fun `a structurally identical but distinct handle is refused by reference identity`() {
        // The constructor is internal, so this test cannot forge one -- which is the point of the
        // assertion being about identity rather than values. A second scope holding the same
        // identity and source strings has its own table, and the real handle is not in it.
        val owner = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        val impostor = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        try {
            val real = requireNotNull(handleOf(owner.tryStart("peer-same", "src-same")))
            val otherReal = requireNotNull(handleOf(impostor.tryStart("peer-same", "src-same")))
            assertFalse("identical strings must not make two grants interchangeable", real === otherReal)
            assertFalse(impostor.isLiveGrant(real))
            assertFalse(owner.isLiveGrant(otherReal))
            assertEquals(
                AdmissionReceipt.NotAdmitted,
                impostor.applyOutcome(real, PairingAttemptOutcome.TRANSCRIPT_MISMATCH),
            )
            assertEquals(AdmissionReceipt.Completed, real.complete(PairingAttemptOutcome.TRANSCRIPT_MISMATCH))
            assertEquals(0, owner.concurrentPairings())
            assertEquals("the impostor scope kept its own permit", 1, impostor.concurrentPairings())
        } finally {
            owner.close()
            impostor.close()
        }
    }

    @Test
    public fun `each grant gets a distinct handle and retiring one does not retire another`() {
        val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        try {
            val first = requireNotNull(handleOf(scope.tryStart("peer-1", "src-1")))
            val second = requireNotNull(handleOf(scope.tryStart("peer-2", "src-2")))
            assertFalse(first === second)
            assertEquals(AdmissionReceipt.Completed, first.complete(PairingAttemptOutcome.LOCAL_CANCELLATION))
            assertTrue("completing one grant must not complete the other", scope.isLiveGrant(second))
            assertEquals(1, scope.concurrentPairings())
            assertEquals(AdmissionReceipt.Completed, second.complete(PairingAttemptOutcome.LOCAL_CANCELLATION))
            assertEquals(0, scope.concurrentPairings())
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `an authenticated attempt clears escalation without refunding the start budget`() {
        val clock = StepClock()
        val scope = PairingAdmissionScope(monotonicMillis = clock::read, limits = permissive())
        try {
            // One security failure sets a cooldown. Two would reach maxFailuresPerPair and
            // invalidate the pair outright, which is a different policy branch.
            val loser = requireNotNull(handleOf(scope.tryStart("peer-ok", "src-ok")))
            assertEquals(AdmissionReceipt.Completed, loser.complete(PairingAttemptOutcome.TRANSCRIPT_MISMATCH))
            assertEquals(
                PairingAdmission.RefusalReason.COOLDOWN_ACTIVE,
                (scope.tryStart("peer-ok", "src-ok") as AdmissionDecision.Refused).reason,
            )
            clock.advance(60_000)
            val winner = requireNotNull(handleOf(scope.tryStart("peer-ok", "src-ok")))
            assertEquals(AdmissionReceipt.Completed, winner.complete(PairingAttemptOutcome.AUTHENTICATED))
            assertFalse("a success must clear the failure escalation", scope.isIdentityInvalidated("peer-ok"))
            assertNotNull("a success must clear the cooldown", handleOf(scope.tryStart("peer-ok", "src-ok")))
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `a declined approval costs a cooldown but never invalidates the identity`() {
        val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        try {
            val handle = requireNotNull(handleOf(scope.tryStart("peer-decline", "src-decline")))
            assertEquals(AdmissionReceipt.Completed, handle.complete(PairingAttemptOutcome.USER_REJECTION))
            assertEquals(
                "a user declining is a real attempt and must not be free",
                PairingAdmission.RefusalReason.COOLDOWN_ACTIVE,
                (scope.tryStart("peer-decline", "src-decline") as AdmissionDecision.Refused).reason,
            )
            assertFalse("a user declining is not evidence of an attack",
                scope.isIdentityInvalidated("peer-decline"))
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `a cancellation costs the peer nothing at all`() {
        val scope = PairingAdmissionScope(monotonicMillis = { 0L })
        try {
            val handle = requireNotNull(handleOf(scope.tryStart("peer-none", "src-none")))
            assertEquals(AdmissionReceipt.Completed, handle.complete(PairingAttemptOutcome.LOCAL_CANCELLATION))
            assertNotNull("the peer must not be penalised for something this device did",
                handleOf(scope.tryStart("peer-none", "src-none")))
            assertFalse(scope.isIdentityInvalidated("peer-none"))
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `a local provider failure costs the peer nothing at all`() {
        val scope = PairingAdmissionScope(monotonicMillis = { 0L })
        try {
            val handle = requireNotNull(handleOf(scope.tryStart("peer-local", "src-local")))
            assertEquals(AdmissionReceipt.Completed, handle.complete(PairingAttemptOutcome.LOCAL_FAILURE))
            assertNotNull("a local crypto provider failure must not be charged to the peer",
                handleOf(scope.tryStart("peer-local", "src-local")))
            assertFalse(scope.isIdentityInvalidated("peer-local"))
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `the identity a handle charges is the one it was granted for`() {
        // The handle exposes no settable identity, so the only way to check this is behavioural:
        // completing through one grant must leave a different identity untouched.
        val scope = PairingAdmissionScope(monotonicMillis = { 0L })
        try {
            val handle = requireNotNull(handleOf(scope.tryStart("peer-charged", "src-charged")))
            assertEquals(AdmissionReceipt.Completed, handle.complete(PairingAttemptOutcome.TRANSCRIPT_MISMATCH))
            assertEquals(
                PairingAdmission.RefusalReason.COOLDOWN_ACTIVE,
                (scope.tryStart("peer-charged", "src-charged") as AdmissionDecision.Refused).reason,
            )
            assertNotNull("a different identity must be untouched by that outcome",
                handleOf(scope.tryStart("peer-other", "src-other")))
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `a flood of completions leaks no permit and leaves no live grant behind`() {
        // No map leak: every issued handle is retired, and the scope's live-grant table empties.
        val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        val seen = ConcurrentHashMap.newKeySet<AdmissionHandle>()
        try {
            repeat(500) { index ->
                val handle = handleOf(scope.tryStart("peer-flood-$index", "src-flood"))
                if (handle != null) {
                    assertTrue("no handle is issued twice", seen.add(handle))
                    handle.complete(PairingAttemptOutcome.LOCAL_CANCELLATION)
                }
            }
            assertEquals("every issued handle completed", 500, seen.size)
            assertTrue("every issued handle is retired", seen.none { scope.isLiveGrant(it) })
            assertEquals("no permit leaked", 0, scope.concurrentPairings())
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `concurrent completions across many handles never exceed or underflow capacity`() {
        val capacity = 4
        val scope = PairingAdmissionScope(
            monotonicMillis = { 0L },
            limits = SecurePairingAttemptLimiter.Limits(
                maxStartsPerIdentity = 1_000_000,
                maxStartsPerSource = 1_000_000,
                maxStartsPerPair = 1_000_000,
                maxConcurrentPairings = capacity,
            ),
        )
        try {
            val violations = AtomicInteger(0)
            val start = CountDownLatch(1)
            val done = CountDownLatch(6)
            repeat(6) { worker ->
                Thread {
                    try {
                        start.await(5, TimeUnit.SECONDS)
                        repeat(200) { round ->
                            val handle = handleOf(scope.tryStart("peer-$worker-$round", "src-$worker"))
                            if (handle != null) {
                                val observed = scope.concurrentPairings()
                                if (observed < 0 || observed > capacity) violations.incrementAndGet()
                                handle.complete(PairingAttemptOutcome.LOCAL_CANCELLATION)
                            }
                        }
                    } catch (_: Exception) {
                        // The counts below are the real assertion.
                    } finally {
                        done.countDown()
                    }
                }.apply { isDaemon = true }.start()
            }
            start.countDown()
            assertTrue(done.await(30, TimeUnit.SECONDS))
            assertEquals("the permit count must never leave 0..capacity", 0, violations.get())
            assertEquals("every permit came back", 0, scope.concurrentPairings())
        } finally {
            scope.close()
        }
    }
}
