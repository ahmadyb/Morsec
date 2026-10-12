package app.morsecode.transport.lan.security

/**
 * Read-only observation of control **establishment** capacity.
 *
 * WHY THIS EXISTS. The bounded owner of control establishment -- a four-worker executor, a
 * four-entry bounded queue, a fair four-permit semaphore and exactly-once release guards -- lives in
 * private state inside the discovery lease. Nothing outside it can see how many permits are held,
 * whether a task was queued or rejected, or whether a release happened once or twice. That makes the
 * capacity bounds untestable from the outside even though they are security-relevant: a leaked permit
 * permanently reduces pairing capacity, and a double release lets a fifth session start.
 *
 * WHAT THIS IS NOT, AND WHY THE LIST IS LONG.
 *
 * - It is **not** a capacity owner. It holds no executor, no queue, no socket, no permit and no
 *   timer. It cannot admit, refuse, delay or release anything.
 * - It is **not** an authority. It cannot issue an authenticated session or touch the pairing
 *   coordinator's outcome.
 * - It is **not** a scheduler and cannot become one: every method returns `Unit` and receives no
 *   handle it could retain to alter control flow.
 * - It is **not** a public application API. `internal` to this module, and the production default is
 *   [Inert], which does nothing.
 *
 * EXCEPTION SAFETY. A defect in an observer must not cost production capacity. Every call site wraps
 * the call and swallows `RuntimeException`, so a throwing observer cannot leak a permit, skip a
 * cleanup step or change a result. That wrapping is pinned statically rather than trusted.
 *
 * ALLOCATION. The default [Inert] instance is a single shared object with default no-op bodies, so
 * production pays one field read per event and allocates nothing.
 */
internal interface ControlEstablishmentObserver {
    /** An establishment task was handed to the bounded executor. */
    fun onControlTaskSubmitted() { }

    /** The bounded executor refused a task; the caller maps this to `OPERATION_QUEUE_FULL`. */
    fun onControlTaskRejected() { }

    /** A control-establishment permit was acquired from the fair semaphore. */
    fun onControlPermitAcquired() { }

    /** A control-establishment permit was returned. Guarded to happen at most once per attempt. */
    fun onControlPermitReleased() { }

    companion object {
        /** The production default. Shared, stateless, and inert. */
        val Inert: ControlEstablishmentObserver = object : ControlEstablishmentObserver { }
    }
}
