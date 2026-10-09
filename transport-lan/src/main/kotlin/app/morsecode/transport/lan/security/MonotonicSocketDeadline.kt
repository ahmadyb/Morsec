package app.morsecode.transport.lan.security

import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Schedules the single expiry task of a [MonotonicSocketDeadline].
 *
 * The seam exists so a deterministic test scheduler can drive expiry without sleeping, and so the
 * production scheduler can be shut down explicitly instead of leaking a timer thread.
 */
internal fun interface DeadlineScheduler {
    public fun schedule(delayMillis: Long, task: Runnable): DeadlineTask
}

/** A scheduled expiry task that can still be withdrawn. */
internal fun interface DeadlineTask {
    public fun cancel()
}

/**
 * The single process-wide timer used by production deadlines.
 *
 * One daemon thread is enough: every task it runs is non-blocking (it only closes a socket and sets
 * a flag), and [shutdown] releases it when the transport module is torn down.
 */
internal object SharedDeadlineScheduler : DeadlineScheduler {
    @Volatile
    private var executor: ScheduledExecutorService? = null

    override fun schedule(delayMillis: Long, task: Runnable): DeadlineTask {
        val target = executor()
        val future: ScheduledFuture<*> = target.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
        return DeadlineTask { future.cancel(false) }
    }

    private fun executor(): ScheduledExecutorService = synchronized(this) {
        val existing = executor
        if (existing != null && !existing.isShutdown) {
            existing
        } else {
            val created = Executors.newSingleThreadScheduledExecutor(
                ThreadFactory { runnable ->
                    Thread(runnable, "morsec-secure-deadline").apply { isDaemon = true }
                },
            )
            executor = created
            created
        }
    }

    /** Test and teardown hook. Never called on a production request path. */
    public fun shutdown() = synchronized(this) {
        executor?.shutdownNow()
        executor = null
    }

    internal fun isRunning(): Boolean = executor?.isShutdown == false
}

/** How a deadline terminated. Exactly one of these is ever produced per deadline. */
internal sealed interface DeadlineOutcome {
    /** The guarded operation finished, or was cancelled, before the deadline fired. */
    public data object Resolved : DeadlineOutcome

    /** The total budget elapsed first and the owning socket was abortively closed. */
    public data object Expired : DeadlineOutcome
}

/**
 * One total, monotonic deadline for one blocking transport operation.
 *
 * Part B needs total deadlines rather than per-read inactivity timeouts: a peer that dribbles a byte
 * every few seconds never trips an inactivity timeout but still pins a worker forever. The budget
 * therefore covers the whole operation — TLS handshake, pairing hello, approval wait, confirmation
 * exchange, secure record I/O, or bounded close — and expiry closes the socket that the operation
 * actually owns, which is what unblocks `read`/`write` on a blocking stream.
 *
 * Design guarantees that the review points exercise:
 *  - the budget is a single total span measured against an injected monotonic source;
 *  - expiry closes exactly the [Socket] instance captured at construction, never a later socket that
 *    happens to reuse the same address or file descriptor;
 *  - the expiry task is withdrawn on success, so no timer outlives its operation;
 *  - expiry and completion are resolved by one compare-and-set, so a race produces exactly one
 *    [DeadlineOutcome] and at most one abortive close;
 *  - no state advances after expiry: [isExpired] is sticky and [complete] reports [DeadlineOutcome.Expired].
 *
 * This type deliberately does not touch coroutines or worker pools; it is a bounded primitive that
 * the transport adapters arm around their blocking calls.
 */
internal class MonotonicSocketDeadline(
    private val label: String,
    private val socket: Socket,
    budgetMillis: Long,
    private val monotonicMillis: () -> Long,
    private val scheduler: DeadlineScheduler = SharedDeadlineScheduler,
) {
    init {
        require(budgetMillis > 0L) { "deadline budget must be positive: $budgetMillis" }
        require(label.isNotBlank()) { "deadline label must identify the guarded operation" }
    }

    private val startedAtMillis: Long = monotonicMillis()
    private val expiresAtMillis: Long = saturatingAdd(startedAtMillis, budgetMillis)

    /** Sticky record of the budget that was armed; asserted by tests and by diagnostics. */
    public val totalBudgetMillis: Long = budgetMillis

    /**
     * One settled outcome per deadline. A single compare-and-set over this field is what makes
     * expiry and completion mutually exclusive, and it lets a repeated [complete] report the
     * outcome that was actually reached instead of reporting "expired" merely because it lost a
     * race against an earlier call.
     */
    private enum class Phase { PENDING, RESOLVED, EXPIRED }

    private val phase = AtomicReference(Phase.PENDING)
    private val lock = Any()

    @Volatile
    private var task: DeadlineTask? = null

    @Volatile
    private var closeCount: Int = 0

    /** True once the deadline fired. Never returns to false. */
    public fun isExpired(): Boolean = phase.get() == Phase.EXPIRED

    /** Number of abortive closes performed by this deadline. Always 0 or 1. */
    public fun closeCount(): Int = closeCount

    /** Milliseconds left in the total budget at the injected monotonic time, floored at zero. */
    public fun remainingMillis(): Long {
        val now = monotonicMillis()
        val left = expiresAtMillis - now
        return if (left <= 0L) 0L else left
    }

    /**
     * Starts the timer. [onExpiry] runs on the scheduler thread after the socket is closed, so an
     * adapter can release its worker or mark its attempt as timed out.
     */
    public fun arm(onExpiry: (() -> Unit)? = null) {
        synchronized(lock) {
            if (phase.get() != Phase.PENDING) return
            if (task != null) return
            val delay = remainingMillis()
            val scheduled = scheduler.schedule(if (delay <= 0L) 0L else delay) { expireNow(onExpiry) }
            // Completion may have raced ahead while the task was being scheduled.
            if (phase.get() != Phase.PENDING) {
                scheduled.cancel()
                task = null
            } else {
                task = scheduled
            }
        }
    }

    /**
     * Ends the guarded operation. Returns [DeadlineOutcome.Expired] if the deadline already fired,
     * which tells the caller that the socket is gone and that no further state may advance.
     */
    public fun complete(): DeadlineOutcome {
        synchronized(lock) {
            task?.cancel()
            task = null
        }
        // Settle if still pending, then report whatever phase is settled. Calling this twice after
        // a normal completion returns Resolved both times; it returns Expired only if the deadline
        // genuinely fired, which is the distinction callers need before touching the socket.
        phase.compareAndSet(Phase.PENDING, Phase.RESOLVED)
        return if (phase.get() == Phase.EXPIRED) DeadlineOutcome.Expired else DeadlineOutcome.Resolved
    }

    private fun expireNow(onExpiry: (() -> Unit)?) {
        if (!phase.compareAndSet(Phase.PENDING, Phase.EXPIRED)) return
        synchronized(lock) {
            task = null
        }
        abortiveClose(socket)
        closeCount = 1
        if (onExpiry != null) {
            try {
                onExpiry()
            } catch (_: RuntimeException) {
                // A misbehaving callback must not prevent the socket from staying closed.
            }
        }
    }

    /**
     * Abortive close: discard any unread send buffer, then shut both directions and close. The
     * shutdown calls are what actually interrupt a peer thread blocked in `read`. Every step is
     * individually guarded so one failure cannot leave the socket open.
     */
    private fun abortiveClose(target: Socket) {
        runCatching { target.setSoLinger(true, 0) }
        runCatching { if (!target.isClosed) target.shutdownOutput() }
        runCatching { if (!target.isClosed) target.shutdownInput() }
        runCatching { target.close() }
    }

    private fun saturatingAdd(value: Long, duration: Long): Long =
        if (value > Long.MAX_VALUE - duration) Long.MAX_VALUE else value + duration

    override fun toString(): String =
        "MonotonicSocketDeadline(label=$label, budget=${totalBudgetMillis}ms, expired=${isExpired()})"
}
