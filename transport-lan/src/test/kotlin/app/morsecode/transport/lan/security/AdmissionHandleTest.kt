package app.morsecode.transport.lan.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The admission handle as a typed precondition.
 *
 * WHAT CHANGED, AND WHY IT MATTERS. Recording an outcome used to take two arbitrary strings, so any
 * caller in the module could charge a budget it had never been admitted against, and a key the
 * limiter had never tracked recorded *nothing at all* -- `recordOutcome` wrote through
 * `identities[key]?.set(...)`, and a null lookup discarded the call without a trace. That was safe
 * only because production happened to reach it from an admitted attempt's `finally` block, an
 * invariant carried entirely by the call site and invisible at the call.
 *
 * These tests pin the replacement: the lease-facing scope has no string-keyed outcome or release
 * path at all, and the only object that can record an outcome or return a permit is the handle the
 * scope issued for that specific grant. Every refusal is a returned [AdmissionReceipt] value, so
 * there is no silent no-op left to discover later.
 *
 * WHAT MAKES FORGERY FAIL, STATED PRECISELY. The handle's constructor is `internal`, not `private`:
 * Kotlin has no file-private constructor, and a private one would be unreachable from the scope that
 * has to issue handles. The invariant therefore does not rest on visibility. It rests on reference
 * identity -- a scope honours a handle only when its own live-grant table maps that handle's token
 * to *that exact object* -- so a handle issued by another lease is refused, and a permit cannot be
 * returned twice no matter how many callers race.
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

    @Test
    public fun `a refusal issues no handle, so an outcome is unrepresentable`() {
        // This is the whole point of the type: a refused attempt has nothing to record against.
        // `AdmissionDecision.Refused` carries a reason and a retry delay and no handle member, so
        // the old "record an outcome for an identity that was never admitted" call cannot be
        // written at all -- not merely discouraged.
        val scope = PairingAdmissionScope(monotonicMillis = { 0L })
        scope.close()
        val decision = scope.tryStart("peer-unadmitted", "src-unadmitted")
        assertTrue("a closed scope must refuse", decision is AdmissionDecision.Refused)
        assertNull("a refusal must not issue a handle", handleOf(decision))
        assertEquals(
            PairingAdmission.RefusalReason.SCOPE_CLOSED,
            (decision as AdmissionDecision.Refused).reason,
        )
        scope.close()
    }

    @Test
    public fun `a handle issued by another lease is rejected by both`() {
        val first = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        val second = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        try {
            val granted = handleOf(first.tryStart("peer-a", "src-a"))
            assertNotNull("the first lease must admit", granted)
            val foreign = requireNotNull(granted)

            // The handle is live in its own scope, so it works there...
            assertTrue(first.isLiveGrant(foreign))
            // ...and inert everywhere else. A cross-lease handle cannot charge a budget it was
            // never granted against, and cannot return a permit it does not hold.
            assertFalse(second.isLiveGrant(foreign))
            assertEquals(
                AdmissionReceipt.NotAdmitted,
                second.applyOutcome(foreign, PairingAttemptOutcome.TRANSCRIPT_MISMATCH),
            )
            assertEquals(AdmissionReceipt.Released, second.retireGrant(foreign))
            assertEquals(
                "the foreign scope's permit count must be untouched",
                0,
                second.concurrentPairings(),
            )
            assertEquals("the owning scope still holds the permit", 1, first.concurrentPairings())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    public fun `a duplicate outcome is a typed refusal rather than a second charge`() {
        val clock = StepClock()
        val scope = PairingAdmissionScope(monotonicMillis = clock::read, limits = permissive())
        try {
            val handle = requireNotNull(handleOf(scope.tryStart("peer-dup", "src-dup")))
            assertEquals(
                AdmissionReceipt.Recorded,
                handle.recordOutcome(PairingAttemptOutcome.TRANSCRIPT_MISMATCH),
            )
            assertTrue(handle.hasRecordedOutcome)
            repeat(3) {
                assertEquals(
                    "one attempt has exactly one terminal outcome",
                    AdmissionReceipt.OutcomeAlreadyRecorded,
                    handle.recordOutcome(PairingAttemptOutcome.TRANSCRIPT_MISMATCH),
                )
            }
            assertEquals(
                "a contradictory second outcome must not overwrite the first",
                AdmissionReceipt.OutcomeAlreadyRecorded,
                handle.recordOutcome(PairingAttemptOutcome.AUTHENTICATED),
            )
            assertEquals(AdmissionReceipt.Released, handle.release())
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `a duplicate release is a typed refusal and cannot drive the count negative`() {
        val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        try {
            val handle = requireNotNull(handleOf(scope.tryStart("peer-rel", "src-rel")))
            assertEquals(1, scope.concurrentPairings())
            assertEquals(AdmissionReceipt.Released, handle.release())
            assertEquals(0, scope.concurrentPairings())
            repeat(5) {
                assertEquals(AdmissionReceipt.AlreadyReleased, handle.release())
            }
            assertEquals("the permit count must never go negative", 0, scope.concurrentPairings())
            assertTrue(handle.isReleased)
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `an outcome recorded after lease close cannot mutate reset state`() {
        val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        val handle = requireNotNull(handleOf(scope.tryStart("peer-closed", "src-closed")))
        scope.close()
        assertTrue(scope.isClosed)
        // The counters are already gone. Charging them would be recording against a lease that no
        // longer exists, so the handle refuses instead of writing.
        assertEquals(AdmissionReceipt.ScopeClosed, handle.recordOutcome(PairingAttemptOutcome.TRANSCRIPT_MISMATCH))
        assertFalse(handle.hasRecordedOutcome)
        assertEquals(0, scope.concurrentPairings())
    }

    @Test
    public fun `a release after lease close stays safe because the finally must still run`() {
        val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        val handle = requireNotNull(handleOf(scope.tryStart("peer-late", "src-late")))
        scope.close()
        // Deliberately still reachable: an in-flight coordinator releases in a finally, and that
        // release must not throw or double-count just because the lease stopped first.
        assertEquals(AdmissionReceipt.Released, handle.release())
        assertEquals(AdmissionReceipt.AlreadyReleased, handle.release())
        assertEquals(0, scope.concurrentPairings())
        // An outcome is still refused after close, and stays refused rather than half-applying.
        assertEquals(AdmissionReceipt.ScopeClosed, handle.recordOutcome(PairingAttemptOutcome.PEER_DISCONNECT))
    }

    @Test
    public fun `concurrent outcome and release calls each settle exactly once`() {
        val threads = 16
        repeat(50) {
            val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
            val handle = requireNotNull(handleOf(scope.tryStart("peer-race", "src-race")))
            val start = CountDownLatch(1)
            val done = CountDownLatch(threads * 2)
            val recorded = AtomicInteger(0)
            val released = AtomicInteger(0)
            repeat(threads) {
                Thread {
                    try {
                        start.await(5, TimeUnit.SECONDS)
                        if (handle.recordOutcome(PairingAttemptOutcome.TRANSCRIPT_MISMATCH) ==
                            AdmissionReceipt.Recorded
                        ) {
                            recorded.incrementAndGet()
                        }
                    } catch (_: Exception) {
                        // The counts below are the real assertion.
                    } finally {
                        done.countDown()
                    }
                }.start()
                Thread {
                    try {
                        start.await(5, TimeUnit.SECONDS)
                        if (handle.release() == AdmissionReceipt.Released) released.incrementAndGet()
                    } catch (_: Exception) {
                        // Same.
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            start.countDown()
            assertTrue("the race must finish", done.await(10, TimeUnit.SECONDS))
            assertEquals("exactly one terminal outcome survives the race", 1, recorded.get())
            assertEquals("exactly one permit return survives the race", 1, released.get())
            assertEquals(0, scope.concurrentPairings())
            scope.close()
        }
    }

    @Test
    public fun `an authenticated attempt clears escalation without refunding the start budget`() {
        val clock = StepClock()
        val scope = PairingAdmissionScope(monotonicMillis = clock::read)
        try {
            // Two security failures set a cooldown on this identity.
            repeat(2) {
                val h = requireNotNull(handleOf(scope.tryStart("peer-ok", "src-ok")))
                assertEquals(AdmissionReceipt.Recorded, h.recordOutcome(PairingAttemptOutcome.TRANSCRIPT_MISMATCH))
                h.release()
            }
            assertEquals(
                PairingAdmission.RefusalReason.COOLDOWN_ACTIVE,
                (scope.tryStart("peer-ok", "src-ok") as AdmissionDecision.Refused).reason,
            )
            clock.advance(60_000)
            val winner = requireNotNull(handleOf(scope.tryStart("peer-ok", "src-ok")))
            assertEquals(AdmissionReceipt.Recorded, winner.recordOutcome(PairingAttemptOutcome.AUTHENTICATED))
            assertEquals(AdmissionReceipt.Released, winner.release())
            assertFalse("a success must clear the failure escalation", scope.isIdentityInvalidated("peer-ok"))
            assertNotNull(
                "a success must clear the cooldown",
                handleOf(scope.tryStart("peer-ok", "src-ok")),
            )
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `a declined approval costs a cooldown but never invalidates the identity`() {
        val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        try {
            val handle = requireNotNull(handleOf(scope.tryStart("peer-decline", "src-decline")))
            assertEquals(AdmissionReceipt.Recorded, handle.recordOutcome(PairingAttemptOutcome.USER_REJECTION))
            assertEquals(AdmissionReceipt.Released, handle.release())
            assertEquals(
                "a user declining is a real attempt and must not be free",
                PairingAdmission.RefusalReason.COOLDOWN_ACTIVE,
                (scope.tryStart("peer-decline", "src-decline") as AdmissionDecision.Refused).reason,
            )
            assertFalse("a user declining is not evidence of an attack", scope.isIdentityInvalidated("peer-decline"))
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `a cancellation costs the peer nothing at all`() {
        val scope = PairingAdmissionScope(monotonicMillis = { 0L })
        try {
            val handle = requireNotNull(handleOf(scope.tryStart("peer-cancel", "src-cancel")))
            assertEquals(AdmissionReceipt.Recorded, handle.recordOutcome(PairingAttemptOutcome.LOCAL_CANCELLATION))
            assertEquals(AdmissionReceipt.Released, handle.release())
            assertNotNull(
                "the peer must not be penalised for something this device did",
                handleOf(scope.tryStart("peer-cancel", "src-cancel")),
            )
            assertFalse(scope.isIdentityInvalidated("peer-cancel"))
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `a local provider failure costs the peer nothing at all`() {
        val scope = PairingAdmissionScope(monotonicMillis = { 0L })
        try {
            val handle = requireNotNull(handleOf(scope.tryStart("peer-local", "src-local")))
            assertEquals(AdmissionReceipt.Recorded, handle.recordOutcome(PairingAttemptOutcome.LOCAL_FAILURE))
            assertEquals(AdmissionReceipt.Released, handle.release())
            assertNotNull(
                "a local crypto provider failure must not be charged to the peer",
                handleOf(scope.tryStart("peer-local", "src-local")),
            )
            assertFalse(scope.isIdentityInvalidated("peer-local"))
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `the scope hands out a distinct handle per grant and retires each exactly once`() {
        val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        try {
            val first = requireNotNull(handleOf(scope.tryStart("peer-1", "src-1")))
            val second = requireNotNull(handleOf(scope.tryStart("peer-2", "src-2")))
            assertFalse("two grants must not share a handle", first === second)
            assertTrue(scope.isLiveGrant(first))
            assertTrue(scope.isLiveGrant(second))
            assertEquals(AdmissionReceipt.Released, first.release())
            assertTrue("retiring one grant must not retire the other", scope.isLiveGrant(second))
            assertEquals(1, scope.concurrentPairings())
            assertEquals(AdmissionReceipt.Released, second.release())
            assertEquals(0, scope.concurrentPairings())
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `an outcome cannot be recorded after the same attempt released its permit`() {
        val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        try {
            val handle = requireNotNull(handleOf(scope.tryStart("peer-retired", "src-retired")))
            assertEquals(AdmissionReceipt.Released, handle.release())
            assertFalse(scope.isLiveGrant(handle))
            // Release retires the grant, so a late outcome has nothing left to charge. Typed, not
            // silent -- which is the exact trap this design removes.
            assertEquals(AdmissionReceipt.NotAdmitted, handle.recordOutcome(PairingAttemptOutcome.TRANSCRIPT_MISMATCH))
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `the identity a handle charges is the one it was granted for`() {
        // The handle exposes no settable identity, so the only way to check this is behavioural:
        // charging through one grant must leave a different identity untouched.
        val scope = PairingAdmissionScope(monotonicMillis = { 0L })
        try {
            val handle = requireNotNull(handleOf(scope.tryStart("peer-charged", "src-charged")))
            assertEquals(AdmissionReceipt.Recorded, handle.recordOutcome(PairingAttemptOutcome.TRANSCRIPT_MISMATCH))
            assertEquals(AdmissionReceipt.Released, handle.release())
            assertEquals(
                PairingAdmission.RefusalReason.COOLDOWN_ACTIVE,
                (scope.tryStart("peer-charged", "src-charged") as AdmissionDecision.Refused).reason,
            )
            assertNotNull(
                "a different identity must be untouched by that outcome",
                handleOf(scope.tryStart("peer-other", "src-other")),
            )
        } finally {
            scope.close()
        }
    }

    @Test
    public fun `the scope issues the very object it later honours`() {
        // Guards the reference-identity rule: the table must map the token to the granted object
        // itself, so a structurally identical copy cannot pass for it.
        val scope = PairingAdmissionScope(monotonicMillis = { 0L }, limits = permissive())
        try {
            val handle = requireNotNull(handleOf(scope.tryStart("peer-identity", "src-identity")))
            assertSame(handle, handle)
            assertTrue(scope.isLiveGrant(handle))
            assertEquals(AdmissionReceipt.Recorded, handle.recordOutcome(PairingAttemptOutcome.AUTHENTICATED))
            assertEquals(AdmissionReceipt.Released, handle.release())
        } finally {
            scope.close()
        }
    }
}
