package app.morsecode.transport.lan.security

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lease-owned admission scope.
 *
 * Everything here runs on an injected clock with latch/barrier synchronisation. No test sleeps to
 * reach a state and no assertion depends on wall-clock elapsed time.
 */
public class PairingAdmissionScopeTest {

    private class StepClock(var now: Long = 0L) {
        fun read(): Long = now
        fun advance(millis: Long) {
            now += millis
        }
    }

    private fun permissive() = SecurePairingAttemptLimiter.Limits(
        maxStartsPerIdentity = 1_000,
        maxStartsPerSource = 1_000,
        maxStartsPerPair = 1_000,
        maxFailuresPerIdentity = 3,
        maxFailuresPerPair = 1_000,
        maxConcurrentPairings = 1_000,
    )

    /**
     * Handles issued by [start], in issue order. The scope no longer exposes a string-keyed
     * outcome or release path, so a test has to hold the handle the scope actually gave it --
     * which is precisely the property under test.
     */
    private val issued = java.util.concurrent.ConcurrentLinkedDeque<AdmissionHandle>()

    private fun PairingAdmissionScope.start(identity: String, source: String): AdmissionDecision =
        tryStart(identity, source).also {
            if (it is AdmissionDecision.Granted) issued.addLast(it.handle)
        }

    /** The most recently issued handle, without retiring it. */
    private fun peekLast(): AdmissionHandle? = issued.lastOrNull()

    /** Stands in for the removed scope-level release: returns the most recently issued permit. */
    private fun releaseLast(): AdmissionReceipt? {
        // Abandonment is an outcome, not a separate release: LOCAL_CANCELLATION costs the peer
        // nothing, and because there is only one terminal call it cannot erase a security result.
        while (true) {
            val handle = issued.pollLast() ?: return null
            val receipt = handle.complete(PairingAttemptOutcome.LOCAL_CANCELLATION)
            if (receipt != AdmissionReceipt.AlreadyCompleted) return receipt
        }
    }

    private fun granted(admission: AdmissionDecision): Boolean = admission is AdmissionDecision.Granted

    private fun reason(admission: AdmissionDecision): PairingAdmission.RefusalReason? =
        (admission as? AdmissionDecision.Refused)?.reason

    @Test
    public fun `a fresh scope admits and a closed scope refuses`() {
        val clock = StepClock()
        val scope = PairingAdmissionScope(clock::read)
        assertFalse(scope.isClosed)
        assertTrue(granted(scope.start("peer-a", "src-a")))
        releaseLast()

        scope.close()

        assertTrue(scope.isClosed)
        assertEquals(
            PairingAdmission.RefusalReason.SCOPE_CLOSED,
            reason(scope.start("peer-a", "src-a")),
        )
        assertEquals(
            "a closed scope refuses every identity, not just ones it already knew",
            PairingAdmission.RefusalReason.SCOPE_CLOSED,
            reason(scope.start("peer-never-seen", "src-never-seen")),
        )
    }

    @Test
    public fun `close releases outstanding permits and drops the counters`() {
        val clock = StepClock()
        val scope = PairingAdmissionScope(clock::read, permissive())
        // One identity, three mismatches: that is what reaches the failure budget. Three distinct
        // identities once each would leave every counter at one and invalidate nothing.
        repeat(3) {
            assertTrue(granted(scope.start("peer-esc", "src-esc")))
            assertEquals(
                AdmissionReceipt.Completed,
                peekLast()?.complete(PairingAttemptOutcome.TRANSCRIPT_MISMATCH),
            )
            releaseLast()
            clock.advance(120_000)
        }
        assertTrue(scope.isIdentityInvalidated("peer-esc"))

        // Two permits left deliberately outstanding, so close has something real to release.
        assertTrue(granted(scope.start("peer-open-1", "src-open-1")))
        assertTrue(granted(scope.start("peer-open-2", "src-open-2")))
        assertEquals(2, scope.concurrentPairings())

        scope.close()

        assertEquals(0, scope.concurrentPairings())
        assertFalse(
            "teardown, not exhaustion, is what clears the invalidated identity",
            scope.isIdentityInvalidated("peer-esc"),
        )
    }

    @Test
    public fun `concurrent and repeated close release exactly once`() {
        val clock = StepClock()
        val scope = PairingAdmissionScope(clock::read, permissive())
        assertTrue(granted(scope.start("peer-race", "src-race")))
        assertTrue(granted(scope.start("peer-race2", "src-race2")))
        assertEquals(2, scope.concurrentPairings())

        // Four threads race to close, started from a barrier so they cannot serialise by accident.
        val start = CyclicBarrier(4)
        val done = CountDownLatch(4)
        val closers = (1..4).map {
            Thread {
                try {
                    start.await(5, TimeUnit.SECONDS)
                    scope.close()
                } catch (_: Exception) {
                    // A broken barrier fails the await assertions below, not this thread.
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true }
        }
        closers.forEach { it.start() }
        // The main thread must not await the barrier: it has four parties, all of them closers, and
        // a fifth arrival would join the next cycle and time out. done is the synchronisation.
        assertTrue("all closers must finish", done.await(5, TimeUnit.SECONDS))
        closers.forEach { it.join(5_000) }

        assertEquals(
            "release must happen exactly once, not once per closer",
            0,
            scope.concurrentPairings(),
        )
        assertTrue(scope.isClosed)

        // Repeated close after the race must also be a no-op, never a second release.
        scope.close()
        scope.close()
        assertEquals(0, scope.concurrentPairings())
    }

    @Test
    public fun `a closed scope ignores outcomes and invalidation`() {
        val clock = StepClock()
        val scope = PairingAdmissionScope(clock::read, permissive())
        scope.close()
        // Admission is refused outright, so no handle is issued and an outcome cannot even be
        // expressed -- the string-keyed path that used to allow it is gone from the scope.
        assertEquals(PairingAdmission.RefusalReason.SCOPE_CLOSED, reason(scope.start("peer-x", "src-x")))
        assertTrue("a closed scope must issue no handle", issued.isEmpty())
        scope.invalidateIdentity("peer-x")
        assertFalse(scope.isIdentityInvalidated("peer-x"))
        assertEquals(0, scope.concurrentPairings())
    }

    @Test
    public fun `concurrency release stays reachable after close`() {
        val clock = StepClock()
        val scope = PairingAdmissionScope(clock::read, permissive())
        assertTrue(granted(scope.start("peer-inflight", "src-inflight")))
        scope.close()
        // An in-flight coordinator releases in a finally, after the lease stopped. That path must
        // remain reachable and must not drive the count negative.
        releaseLast()
        releaseLast()
        assertEquals(0, scope.concurrentPairings())
    }

    @Test
    public fun `a new scope is freshly scoped after the previous one closed`() {
        val clock = StepClock()
        val first = PairingAdmissionScope(clock::read, permissive())
        repeat(3) {
            assertTrue(granted(first.start("peer-fresh", "src-fresh")))
            assertEquals(
                AdmissionReceipt.Completed,
                peekLast()?.complete(PairingAttemptOutcome.TRANSCRIPT_MISMATCH),
            )
            releaseLast()
            clock.advance(120_000)
        }
        assertTrue(first.isIdentityInvalidated("peer-fresh"))
        first.close()

        val second = PairingAdmissionScope(clock::read)
        assertTrue(
            "a restarted lease gets a fresh scope -- a process-local policy reset, not proof of a " +
                "fresh peer, because discovery advertisements are unauthenticated",
            granted(second.start("peer-fresh", "src-fresh")),
        )
        second.close()
        assertEquals(0, second.concurrentPairings())
    }

    @Test
    public fun `forged identities cannot grow scope memory without bound`() {
        val clock = StepClock()
        // Default maxTrackedIdentities is 64. Past that, new keys share one overflow counter rather
        // than allocating a counter each, so the map cannot be grown by a forger.
        val scope = PairingAdmissionScope(clock::read)
        var refusals = 0
        repeat(500) { index ->
            when (val admission = scope.start("forged-$index", "forged-src-$index")) {
                is AdmissionDecision.Granted -> assertEquals(
                    AdmissionReceipt.Completed,
                    admission.handle.complete(PairingAttemptOutcome.LOCAL_CANCELLATION),
                )
                is AdmissionDecision.Refused -> refusals += 1
            }
            clock.advance(1)
        }
        assertTrue(
            "an unbounded forged-identity flood must eventually be refused, not grow the map",
            refusals > 0,
        )
        scope.close()
        assertEquals(0, scope.concurrentPairings())
    }

    @Test
    public fun `lease stop racing an in-flight pairing releases the permit exactly once`() {
        val clock = StepClock()
        // The specific hazard: the lease closes while a pairing is still running, and that pairing
        // later reaches the release in its finally. Either the close or the pairing may win, but
        // the permit must be accounted for exactly once -- never twice, never negatively.
        repeat(100) { iteration ->
            val scope = PairingAdmissionScope(clock::read, permissive())
            val inflight = requireNotNull((scope.start("peer-$iteration", "src-$iteration") as? AdmissionDecision.Granted)?.handle)
            issued.clear()
            assertEquals(1, scope.concurrentPairings())

            val start = CyclicBarrier(2)
            val done = CountDownLatch(2)
            val closer = Thread {
                try {
                    start.await(5, TimeUnit.SECONDS)
                    scope.close()
                } catch (_: Exception) {
                    // A broken barrier fails the awaits below, not this thread.
                } finally {
                    done.countDown()
                }
            }
            val releaser = Thread {
                try {
                    start.await(5, TimeUnit.SECONDS)
                    inflight.complete(PairingAttemptOutcome.LOCAL_CANCELLATION)
                } catch (_: Exception) {
                    // Same: the assertions below are the real check.
                } finally {
                    done.countDown()
                }
            }
            closer.isDaemon = true
            releaser.isDaemon = true
            closer.start()
            releaser.start()
            assertTrue("iteration $iteration: racers must finish", done.await(5, TimeUnit.SECONDS))
            closer.join(5_000)
            releaser.join(5_000)

            assertEquals(
                "iteration $iteration: the permit count must settle at exactly zero",
                0,
                scope.concurrentPairings(),
            )
            assertTrue("iteration $iteration: the scope must be closed", scope.isClosed)
        }
    }

    @Test
    public fun `a late pairing completion cannot drive the permit count negative`() {
        val clock = StepClock()
        val scope = PairingAdmissionScope(clock::read, permissive())
        val late = requireNotNull((scope.start("peer-late", "src-late") as? AdmissionDecision.Granted)?.handle)
        issued.clear()
        scope.close()
        // The pairing's finally runs after lease stop, and again if some path is re-entered. The
        // first release is a harmless no-op against the reset limiter; the rest are typed refusals.
        // The scope is already closed, so the outcome cannot be written; what must hold is that
        // the permit still comes back and the repeated calls stay typed rather than throwing.
        assertEquals(AdmissionReceipt.ScopeClosed, late.complete(PairingAttemptOutcome.PEER_DISCONNECT))
        repeat(4) {
            assertEquals(AdmissionReceipt.AlreadyCompleted, late.complete(PairingAttemptOutcome.PEER_DISCONNECT))
        }
        assertEquals(0, scope.concurrentPairings())
        assertTrue(scope.isClosed)
    }

    @Test
    public fun `permit count never exceeds capacity under concurrent grant release and close`() {
        val clock = StepClock()
        val capacity = 4
        val scope = PairingAdmissionScope(
            monotonicMillis = clock::read,
            limits = SecurePairingAttemptLimiter.Limits(
                maxStartsPerIdentity = 100_000,
                maxStartsPerSource = 100_000,
                maxStartsPerPair = 100_000,
                maxConcurrentPairings = capacity,
            ),
        )
        val violation = AtomicReference<String?>(null)
        val start = CyclicBarrier(5)
        // One worker signals that the grant/release churn is over; only then does the closer run.
        // That is deliberate synchronisation, not a sleep chosen to win a race.
        val churnDone = CountDownLatch(1)
        val done = CountDownLatch(5)

        val workers = (0 until 4).map { worker ->
            Thread {
                try {
                    start.await(5, TimeUnit.SECONDS)
                    repeat(60) { round ->
                        if (granted(scope.start("peer-$worker-$round", "src-$worker-$round"))) {
                            val observed = scope.concurrentPairings()
                            if (observed < 0 || observed > capacity) {
                                violation.compareAndSet(null, "observed $observed outside 0..$capacity")
                            }
                            releaseLast()
                        }
                    }
                    if (worker == 0) churnDone.countDown()
                } catch (_: Exception) {
                    // The assertions below are the real check.
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true }
        }
        val closer = Thread {
            try {
                start.await(5, TimeUnit.SECONDS)
                churnDone.await(5, TimeUnit.SECONDS)
                scope.close()
            } catch (_: Exception) {
                // Same.
            } finally {
                done.countDown()
            }
        }.apply { isDaemon = true }

        workers.forEach { it.start() }
        closer.start()
        assertTrue("all racers must finish", done.await(10, TimeUnit.SECONDS))
        workers.forEach { it.join(5_000) }
        closer.join(5_000)

        val seen = violation.get()
        assertTrue("permit capacity was violated: $seen", seen == null)
        assertEquals("the permit count must settle at exactly zero", 0, scope.concurrentPairings())
        assertTrue(scope.isClosed)
    }
}
