package app.morsecode.transport.lan.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The establishment observation seam's own contract.
 *
 * WHAT IS TESTED HERE, AND WHAT IS PINNED ELSEWHERE. These tests cover the interface: the production
 * default is shared and inert, every default body is a no-op that cannot throw, and a counting
 * observer sees each event exactly once. The two properties that matter most -- that an observer
 * defect cannot leak a permit, and that production selects the inert instance -- live in the
 * provider's private wiring, so they are pinned statically by verifier group 19 and mutation-tested
 * there rather than asserted against a re-implementation of the wrapper, which would prove nothing
 * about the code that ships.
 *
 * The capacity scenarios this seam exists to enable -- four permits held, four tasks queued, a ninth
 * rejected by the production AbortPolicy, permits returning exactly once -- require driving the real
 * provider under Robolectric and are not yet written.
 */
public class ControlEstablishmentObserverTest {

    private class CountingObserver : ControlEstablishmentObserver {
        val submitted = AtomicInteger(0)
        val rejected = AtomicInteger(0)
        val acquired = AtomicInteger(0)
        val released = AtomicInteger(0)

        override fun onControlTaskSubmitted() {
            submitted.incrementAndGet()
        }

        override fun onControlTaskRejected() {
            rejected.incrementAndGet()
        }

        override fun onControlPermitAcquired() {
            acquired.incrementAndGet()
        }

        override fun onControlPermitReleased() {
            released.incrementAndGet()
        }

        fun total(): Int = submitted.get() + rejected.get() + acquired.get() + released.get()
    }

    @Test
    public fun `the production default is one shared inert instance`() {
        // One shared object, so the production path pays a field read per event and allocates
        // nothing. Two reads of the default must be the same instance, not two equal ones.
        assertSame(ControlEstablishmentObserver.Inert, ControlEstablishmentObserver.Inert)
    }

    @Test
    public fun `every default body is a no-op that cannot throw`() {
        val inert = ControlEstablishmentObserver.Inert
        // No assertion is needed beyond "this does not throw": an inert observer that threw would
        // turn a diagnostic seam into a failure mode on the production establishment path.
        inert.onControlTaskSubmitted()
        inert.onControlTaskRejected()
        inert.onControlPermitAcquired()
        inert.onControlPermitReleased()
    }

    @Test
    public fun `a counting observer sees each event exactly once`() {
        val observer = CountingObserver()
        observer.onControlTaskSubmitted()
        observer.onControlPermitAcquired()
        observer.onControlPermitReleased()
        assertEquals(1, observer.submitted.get())
        assertEquals(1, observer.acquired.get())
        assertEquals(1, observer.released.get())
        assertEquals(0, observer.rejected.get())
        assertEquals(3, observer.total())
    }

    @Test
    public fun `acquisitions and releases are separately countable so a leak is visible`() {
        // The asymmetry is the whole point: a leaked permit shows up as acquired > released, and a
        // double release as released > acquired. A single "capacity changed" event could not tell
        // those apart.
        val observer = CountingObserver()
        repeat(4) {
            observer.onControlPermitAcquired()
            observer.onControlPermitReleased()
        }
        assertEquals(4, observer.acquired.get())
        assertEquals(4, observer.released.get())
        observer.onControlPermitAcquired()
        assertEquals(
            "an unreturned permit must be observable as an imbalance",
            1,
            observer.acquired.get() - observer.released.get(),
        )
    }

    @Test
    public fun `a rejection is a distinct event from an acquisition`() {
        // A ninth attempt must be countable as refused without ever having taken a permit, which is
        // exactly the distinction the capacity bound depends on.
        val observer = CountingObserver()
        repeat(4) { observer.onControlPermitAcquired() }
        observer.onControlTaskRejected()
        assertEquals(4, observer.acquired.get())
        assertEquals(1, observer.rejected.get())
        assertEquals(0, observer.released.get())
    }

    @Test
    public fun `a partially overriding observer leaves the remaining events as no-ops`() {
        // Production and tests alike may care about one event only; the rest must stay inert rather
        // than becoming abstract methods every implementation is forced to write.
        var releases = 0
        val partial = object : ControlEstablishmentObserver {
            override fun onControlPermitReleased() {
                releases++
            }
        }
        partial.onControlTaskSubmitted()
        partial.onControlTaskRejected()
        partial.onControlPermitAcquired()
        partial.onControlPermitReleased()
        assertEquals(1, releases)
    }

    @Test
    public fun `an observer that throws is containable by a wrapping call site`() {
        // The production wrapper is private to the lease and is pinned statically. What is asserted
        // here is the property that makes the wrapper sufficient: a throwing observer throws
        // RuntimeException, which is what the wrapper catches -- not an Error, which would escape.
        val hostile = object : ControlEstablishmentObserver {
            override fun onControlPermitAcquired() {
                throw IllegalStateException("observer defect")
            }
        }
        val thrown = runCatching { hostile.onControlPermitAcquired() }.exceptionOrNull()
        assertTrue("an observer defect must be a RuntimeException the wrapper can contain",
            thrown is RuntimeException)
    }
}
