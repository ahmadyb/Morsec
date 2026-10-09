package app.morsecode.transport.lan.security

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Manual scheduler: nothing fires until the test fires it, so no test sleeps on a real timer. */
private class ManualDeadlineScheduler : DeadlineScheduler {
    val pending = mutableListOf<Runnable>()
    val scheduledDelays = mutableListOf<Long>()
    val cancelCount = AtomicInteger(0)
    var closed = false

    override fun schedule(delayMillis: Long, task: Runnable): DeadlineTask {
        if (closed) return DeadlineTask { cancelCount.incrementAndGet() }
        synchronized(pending) {
            pending.add(task)
            scheduledDelays.add(delayMillis)
        }
        return DeadlineTask {
            cancelCount.incrementAndGet()
            synchronized(pending) { pending.remove(task) }
        }
    }

    fun fireAll() {
        val snapshot = synchronized(pending) { pending.toList().also { pending.clear() } }
        snapshot.forEach { it.run() }
    }

    fun pendingCount(): Int = synchronized(pending) { pending.size }
}

private class FakeMonotonic(var now: Long = 0L) {
    fun read(): Long = now
}

class MonotonicSocketDeadlineTest {

    private fun unusedSocket(): Socket = Socket()

    @Test
    fun `budget must be positive`() {
        val clock = FakeMonotonic()
        val socket = unusedSocket()
        try {
            var threw = false
            try {
                MonotonicSocketDeadline("tls", socket, 0L, clock::read, ManualDeadlineScheduler())
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("zero budget must be rejected", threw)
        } finally {
            runCatching { socket.close() }
        }
    }

    @Test
    fun `completion before expiry resolves once and withdraws the timer`() {
        val clock = FakeMonotonic()
        val scheduler = ManualDeadlineScheduler()
        val socket = unusedSocket()
        try {
            val deadline = MonotonicSocketDeadline("hello", socket, 5_000L, clock::read, scheduler)
            deadline.arm()
            assertEquals(1, scheduler.pendingCount())

            assertEquals(DeadlineOutcome.Resolved, deadline.complete())
            assertEquals(1, scheduler.cancelCount.get())
            assertEquals(0, scheduler.pendingCount())

            // A second completion must not produce a second outcome or a second close.
            assertEquals(DeadlineOutcome.Resolved, deadline.complete())
            assertEquals(1, scheduler.cancelCount.get())
            assertFalse(deadline.isExpired())
            assertEquals(0, deadline.closeCount())
            assertFalse("completed deadline must not close the socket", socket.isClosed)
        } finally {
            runCatching { socket.close() }
        }
    }

    @Test
    fun `arming after completion schedules nothing`() {
        val clock = FakeMonotonic()
        val scheduler = ManualDeadlineScheduler()
        val socket = unusedSocket()
        try {
            val deadline = MonotonicSocketDeadline("approval", socket, 5_000L, clock::read, scheduler)
            assertEquals(DeadlineOutcome.Resolved, deadline.complete())
            deadline.arm()
            assertEquals("no timer may outlive a resolved deadline", 0, scheduler.pendingCount())
        } finally {
            runCatching { socket.close() }
        }
    }

    @Test
    fun `expiry closes exactly the owning socket and never a later reused one`() {
        val clock = FakeMonotonic()
        val scheduler = ManualDeadlineScheduler()
        val owned = unusedSocket()
        val reused = unusedSocket()
        try {
            val deadline = MonotonicSocketDeadline("record-io", owned, 5_000L, clock::read, scheduler)
            deadline.arm()

            scheduler.fireAll()

            assertTrue("deadline must report expiry", deadline.isExpired())
            assertEquals("exactly one abortive close", 1, deadline.closeCount())
            assertTrue("the owned socket must be closed", owned.isClosed)
            assertNotSame(owned, reused)
            assertFalse("a later socket must never be closed by an earlier deadline", reused.isClosed)

            // Expiry is sticky and further completion reports the expired outcome.
            assertEquals(DeadlineOutcome.Expired, deadline.complete())
            assertEquals(1, deadline.closeCount())
        } finally {
            runCatching { owned.close() }
            runCatching { reused.close() }
        }
    }

    @Test
    fun `expiry callback runs after the socket is closed`() {
        val clock = FakeMonotonic()
        val scheduler = ManualDeadlineScheduler()
        val socket = unusedSocket()
        val observedClosedInCallback = AtomicReference<Boolean>()
        try {
            val deadline = MonotonicSocketDeadline("close", socket, 5_000L, clock::read, scheduler)
            deadline.arm { observedClosedInCallback.set(socket.isClosed) }
            scheduler.fireAll()
            assertEquals(true, observedClosedInCallback.get())
        } finally {
            runCatching { socket.close() }
        }
    }

    @Test
    fun `a throwing expiry callback cannot leave the socket open`() {
        val clock = FakeMonotonic()
        val scheduler = ManualDeadlineScheduler()
        val socket = unusedSocket()
        try {
            val deadline = MonotonicSocketDeadline("close", socket, 5_000L, clock::read, scheduler)
            deadline.arm { throw IllegalStateException("callback failed") }
            scheduler.fireAll()
            assertTrue(socket.isClosed)
            assertEquals(1, deadline.closeCount())
        } finally {
            runCatching { socket.close() }
        }
    }

    @Test
    fun `expiry and completion racing resolve exactly once`() {
        val clock = FakeMonotonic()
        val scheduler = ManualDeadlineScheduler()
        val socket = unusedSocket()
        try {
            val deadline = MonotonicSocketDeadline("race", socket, 5_000L, clock::read, scheduler)
            deadline.arm()

            // Expiry wins the race, then the worker tries to finish normally.
            scheduler.fireAll()
            assertEquals(DeadlineOutcome.Expired, deadline.complete())
            assertEquals(1, deadline.closeCount())

            // Firing again must not close a second time.
            scheduler.fireAll()
            assertEquals(1, deadline.closeCount())
        } finally {
            runCatching { socket.close() }
        }
    }

    @Test
    fun `remaining time falls to zero as the monotonic clock advances`() {
        val clock = FakeMonotonic(now = 1_000L)
        val deadline = MonotonicSocketDeadline(
            "confirmation",
            unusedSocket(),
            5_000L,
            clock::read,
            ManualDeadlineScheduler(),
        )
        try {
            assertEquals(5_000L, deadline.remainingMillis())
            clock.now = 3_500L
            assertEquals(2_500L, deadline.remainingMillis())
            clock.now = 99_999L
            assertEquals(0L, deadline.remainingMillis())
            assertEquals(5_000L, deadline.totalBudgetMillis)
        } finally {
            deadline.complete()
        }
    }

    @Test
    fun `re-arming is idempotent`() {
        val clock = FakeMonotonic()
        val scheduler = ManualDeadlineScheduler()
        val socket = unusedSocket()
        val deadline = MonotonicSocketDeadline("tls", socket, 5_000L, clock::read, scheduler)
        try {
            deadline.arm()
            deadline.arm()
            deadline.arm()
            assertEquals("one operation owns one timer", 1, scheduler.pendingCount())
        } finally {
            deadline.complete()
            runCatching { socket.close() }
        }
    }

    /**
     * Integration evidence rather than a fake-clock assertion: a thread genuinely blocked in
     * `read()` on a connected socket must be released when the deadline abortively closes it. A
     * unit test over a stub socket cannot show this, and the review explicitly asks for it.
     */
    @Test
    fun `expiry unblocks a real blocking socket read`() {
        val server = ServerSocket(0)
        val client = Socket()
        val unblocked = CountDownLatch(1)
        val readFailed = AtomicReference<Throwable?>()
        try {
            client.connect(java.net.InetSocketAddress("127.0.0.1", server.localPort), 2_000)
            val accepted = server.accept()
            client.soTimeout = 0 // no inactivity timeout: only the total deadline can end this read

            val reader = Thread {
                try {
                    client.getInputStream().read()
                } catch (throwable: Throwable) {
                    readFailed.set(throwable)
                } finally {
                    unblocked.countDown()
                }
            }.apply { isDaemon = true }
            reader.start()

            // Give the reader time to actually block; the deadline, not this sleep, ends the read.
            Thread.sleep(150L)
            assertTrue("reader should still be blocked before expiry", unblocked.count > 0)

            val deadline = MonotonicSocketDeadline(
                "secure-record-io",
                client,
                5_000L,
                { System.nanoTime() / 1_000_000L },
                SharedDeadlineScheduler,
            )
            deadline.arm()
            assertTrue("real read must be unblocked by abortive close", unblocked.await(5, TimeUnit.SECONDS))
            assertTrue("the socket must be closed", client.isClosed)
            assertEquals(1, deadline.closeCount())
            assertTrue(
                "the blocked read must fail rather than return normally: ${readFailed.get()}",
                readFailed.get() != null,
            )
            deadline.complete()
            runCatching { accepted.close() }
        } finally {
            runCatching { client.close() }
            runCatching { server.close() }
        }
    }

    @Test
    fun `shared scheduler can be shut down and restarted without leaking a thread`() {
        SharedDeadlineScheduler.shutdown()
        assertFalse(SharedDeadlineScheduler.isRunning())
        val clock = FakeMonotonic()
        val socket = unusedSocket()
        try {
            val deadline = MonotonicSocketDeadline("tls", socket, 5_000L, clock::read, SharedDeadlineScheduler)
            deadline.arm()
            assertTrue(SharedDeadlineScheduler.isRunning())
            assertEquals(DeadlineOutcome.Resolved, deadline.complete())
        } finally {
            SharedDeadlineScheduler.shutdown()
            runCatching { socket.close() }
        }
        assertFalse("shutdown must release the timer thread", SharedDeadlineScheduler.isRunning())
    }
}
