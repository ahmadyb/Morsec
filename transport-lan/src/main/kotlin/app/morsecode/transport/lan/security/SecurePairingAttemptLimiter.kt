package app.morsecode.transport.lan.security

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Outcome of asking the limiter whether a pairing attempt may proceed. */
internal sealed interface PairingAdmission {
    /** The attempt may start; the caller must later call [SecurePairingAttemptLimiter.releaseConcurrency]. */
    data object Granted : PairingAdmission

    /** Refused. [retryAfterMillis] is a monotonic-relative delay, 0 when the identity is dead. */
    data class Refused(
        val reason: RefusalReason,
        val retryAfterMillis: Long,
    ) : PairingAdmission

    enum class RefusalReason {
        /** This peer identity burned its start budget. */
        IDENTITY_START_BUDGET_EXHAUSTED,

        /** This source address burned its start budget. */
        SOURCE_START_BUDGET_EXHAUSTED,

        /** This identity/source pair burned its start budget. */
        PAIR_START_BUDGET_EXHAUSTED,

        /** Repeated failed pairings invalidated the discovery identity. */
        IDENTITY_INVALIDATED,

        /** Repeated failed pairings invalidated this identity/source combination. */
        PAIR_INVALIDATED,

        /** Too many pairings are already in flight process-wide. */
        CONCURRENCY_LIMIT_REACHED,

        /** The per-identity/source monotonic cooldown has not elapsed. */
        COOLDOWN_ACTIVE,

        /**
         * The discovery lease that owned this scope has stopped.
         *
         * An in-flight pairing can outlive the instant the lease is stopped. Without this it would
         * be able to acquire a fresh permit from a scope that no longer exists, which is exactly the
         * way a limit gets bypassed by a concurrent session.
         */
        SCOPE_CLOSED,
    }
}

/**
 * Why one admitted pairing attempt ended, and which budget it therefore consumes.
 *
 * Before this existed the coordinator called `recordFailure` for every non-authenticated result, so
 * a local cancellation, a local provider failure and a genuine transcript mismatch were all
 * indistinguishable. That is wrong in both directions: it lets a peer burn the *user's own* budget
 * by making the local side fail, and it hides a real authentication attack inside noise.
 *
 * Budget policy, in force as documented:
 *
 * - [AUTHENTICATED] -- one explicit reset: failure escalation and cooldown clear, start counters do
 *   not. A peer cannot launder a start budget by succeeding once.
 * - [AUTHENTICATION_MISMATCH], [TRANSCRIPT_MISMATCH], [KEY_CONFIRMATION_FAILURE] -- the full
 *   security budget. These are the only outcomes that can invalidate an identity.
 * - [USER_REJECTION], [APPROVAL_EXPIRED] -- a bounded pairing-attempt budget: cooldown applies, but
 *   these never invalidate, because a user declining is not evidence of an attack.
 * - [HANDSHAKE_TIMEOUT], [PEER_DISCONNECT] -- a bounded pair/source budget, so a half-open peer
 *   cannot hold a slot indefinitely, but the identity is not condemned for a network drop.
 * - [LOCAL_CANCELLATION], [LOCAL_FAILURE] -- no budget at all. The peer is not penalised for
 *   something this device did or suffered. Concurrency is still released.
 * - [RESOURCE_REFUSED] -- no budget; concurrency is released and retry guidance is returned.
 */
internal enum class PairingAttemptOutcome {
    AUTHENTICATED,
    AUTHENTICATION_MISMATCH,
    TRANSCRIPT_MISMATCH,
    KEY_CONFIRMATION_FAILURE,
    USER_REJECTION,
    APPROVAL_EXPIRED,
    HANDSHAKE_TIMEOUT,
    PEER_DISCONNECT,
    LOCAL_CANCELLATION,
    LOCAL_FAILURE,
    RESOURCE_REFUSED,
}

/** True when the outcome represents the peer defeating or failing the security exchange itself. */
internal val PairingAttemptOutcome.isSecurityFailure: Boolean
    get() = when (this) {
        PairingAttemptOutcome.AUTHENTICATION_MISMATCH,
        PairingAttemptOutcome.TRANSCRIPT_MISMATCH,
        PairingAttemptOutcome.KEY_CONFIRMATION_FAILURE -> true
        else -> false
    }

/** Maps a produced failure code onto the outcome taxonomy. Total: no code falls through unmapped. */
internal fun pairingOutcomeFor(code: app.morsecode.core.transfer.session.SessionFailureCode) = when (code) {
    app.morsecode.core.transfer.session.SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID ->
        PairingAttemptOutcome.TRANSCRIPT_MISMATCH
    app.morsecode.core.transfer.session.SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED ->
        PairingAttemptOutcome.KEY_CONFIRMATION_FAILURE
    app.morsecode.core.transfer.session.SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED ->
        PairingAttemptOutcome.USER_REJECTION
    app.morsecode.core.transfer.session.SessionFailureCode.SECURE_SESSION_APPROVAL_EXPIRED ->
        PairingAttemptOutcome.APPROVAL_EXPIRED
    app.morsecode.core.transfer.session.SessionFailureCode.PROTOCOL_VERSION_UNSUPPORTED,
    app.morsecode.core.transfer.session.SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED,
    app.morsecode.core.transfer.session.SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
    app.morsecode.core.transfer.session.SessionFailureCode.PEER_IDENTITY_MISMATCH,
    app.morsecode.core.transfer.session.SessionFailureCode.HANDSHAKE_INVALID,
    app.morsecode.core.transfer.session.SessionFailureCode.HANDSHAKE_VERSION_UNSUPPORTED ->
        PairingAttemptOutcome.AUTHENTICATION_MISMATCH
    app.morsecode.core.transfer.session.SessionFailureCode.CONTROL_TIMEOUT ->
        PairingAttemptOutcome.HANDSHAKE_TIMEOUT
    app.morsecode.core.transfer.session.SessionFailureCode.CONTROL_CONNECT_FAILED,
    app.morsecode.core.transfer.session.SessionFailureCode.NETWORK_UNAVAILABLE ->
        PairingAttemptOutcome.PEER_DISCONNECT
    app.morsecode.core.transfer.session.SessionFailureCode.OPERATION_CANCELLED ->
        PairingAttemptOutcome.LOCAL_CANCELLATION
    app.morsecode.core.transfer.session.SessionFailureCode.CONTROL_CAPACITY_REACHED,
    app.morsecode.core.transfer.session.SessionFailureCode.OPERATION_QUEUE_FULL,
    app.morsecode.core.transfer.session.SessionFailureCode.SECURE_SESSION_LIMIT_REACHED,
    app.morsecode.core.transfer.session.SessionFailureCode.SECURE_SESSION_ALREADY_STARTED,
    app.morsecode.core.transfer.session.SessionFailureCode.SECURE_SESSION_REQUIRED ->
        PairingAttemptOutcome.RESOURCE_REFUSED
    else -> PairingAttemptOutcome.LOCAL_FAILURE
}

/**
 * Process-local throttle for authenticated LAN pairing attempts.
 *
 * WHAT THIS IS: a denial-of-service bound. It caps how many pairing handshakes one peer identity,
 * one source address, or one identity/source pair can start, caps concurrent handshakes, and imposes
 * a monotonically growing cooldown after failures. Exhausted identities are invalidated so the peer
 * must run discovery again rather than replaying a cached identity.
 *
 * WHAT THIS IS NOT: authentication, and not a durable rate limit. All state lives in memory, so it
 * resets when the process dies or the discovery lease ends; a peer that changes source address
 * resets the per-source counters; and nothing here survives an app restart. The security of the
 * pairing rests on the SAS comparison and the TLS transcript binding, never on these counters.
 * Both limitations are restated in the Part B security document on purpose.
 */
internal class SecurePairingAttemptLimiter(
    private val monotonicMillis: () -> Long,
    private val limits: Limits = Limits(),
) {
    /** Tunable bounds. Defaults match the values documented in `doc/security/milestone-4-part-b.md`. */
    internal data class Limits(
        val maxStartsPerIdentity: Int = 3,
        val maxStartsPerSource: Int = 8,
        val maxStartsPerPair: Int = 2,
        val maxFailuresPerIdentity: Int = 3,
        val maxFailuresPerPair: Int = 2,
        val baseCooldownMillis: Long = 2_000L,
        val maxCooldownMillis: Long = 60_000L,
        val maxTrackedIdentities: Int = 64,
        val maxTrackedSources: Int = 128,
        val maxTrackedPairs: Int = 128,
        val maxConcurrentPairings: Int = 2,
        val maxTrackedSoftAttempts: Int = 64,
    )

    private class Counter {
        val starts = AtomicInteger(0)
        val failures = AtomicInteger(0)
        val nextAllowedAtMillis = AtomicLong(0L)
        val invalidated = AtomicLong(0L)
    }

    private val identities = ConcurrentHashMap<String, Counter>()
    private val sources = ConcurrentHashMap<String, Counter>()
    private val pairs = ConcurrentHashMap<String, Counter>()
    private val inFlight = AtomicInteger(0)
    private val softAttempts = AtomicInteger(0)
    private val invalidatedIdentities = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * Shared fallback used once a map reaches its cap. It is deliberately NOT inserted into the map,
     * so rotating identities cannot grow the maps without bound. The trade-off is explicit: past the
     * cap, unknown identities share one budget, which throttles the flooder at the cost of also
     * throttling other newcomers. That is acceptable for a DoS bound and would not be acceptable for
     * authentication, which is exactly why this class is not part of the trust root.
     */
    private val overflowCounter = Counter()

    /**
     * Decides whether a pairing may start. Grants exactly one concurrency slot when it returns
     * [PairingAdmission.Granted]; every grant must be paired with [releaseConcurrency].
     */
    @Synchronized
    internal fun tryStart(peerIdentityKey: String, sourceKey: String): PairingAdmission {
        require(peerIdentityKey.isNotBlank()) { "peer identity key must not be blank" }
        require(sourceKey.isNotBlank()) { "source key must not be blank" }
        val now = monotonicMillis()

        if (invalidatedIdentities.contains(peerIdentityKey)) {
            return refused(PairingAdmission.RefusalReason.IDENTITY_INVALIDATED, 0L)
        }

        val identity = counterFor(identities, peerIdentityKey, limits.maxTrackedIdentities)
        val source = counterFor(sources, sourceKey, limits.maxTrackedSources)
        val pairKey = pairKey(peerIdentityKey, sourceKey)
        val pair = counterFor(pairs, pairKey, limits.maxTrackedPairs)

        if (identity.invalidated.get() != 0L) {
            invalidatedIdentities.add(peerIdentityKey)
            return refused(PairingAdmission.RefusalReason.IDENTITY_INVALIDATED, 0L)
        }
        if (pair.invalidated.get() != 0L) {
            return refused(PairingAdmission.RefusalReason.PAIR_INVALIDATED, 0L)
        }

        val cooldownUntil = maxOf(identity.nextAllowedAtMillis.get(), pair.nextAllowedAtMillis.get())
        if (now < cooldownUntil) {
            return refused(PairingAdmission.RefusalReason.COOLDOWN_ACTIVE, cooldownUntil - now)
        }

        if (identity.starts.get() >= limits.maxStartsPerIdentity) {
            return refused(PairingAdmission.RefusalReason.IDENTITY_START_BUDGET_EXHAUSTED, 0L)
        }
        if (source.starts.get() >= limits.maxStartsPerSource) {
            return refused(PairingAdmission.RefusalReason.SOURCE_START_BUDGET_EXHAUSTED, 0L)
        }
        if (pair.starts.get() >= limits.maxStartsPerPair) {
            return refused(PairingAdmission.RefusalReason.PAIR_START_BUDGET_EXHAUSTED, 0L)
        }
        if (inFlight.get() >= limits.maxConcurrentPairings) {
            return refused(PairingAdmission.RefusalReason.CONCURRENCY_LIMIT_REACHED, 0L)
        }

        identity.starts.incrementAndGet()
        source.starts.incrementAndGet()
        pair.starts.incrementAndGet()
        inFlight.incrementAndGet()
        return PairingAdmission.Granted
    }

    /** Releases a concurrency slot. Idempotent per grant: callers must invoke it exactly once. */
    internal fun releaseConcurrency() {
        while (true) {
            val current = inFlight.get()
            if (current <= 0) return
            if (inFlight.compareAndSet(current, current - 1)) return
        }
    }

    /**
     * Records a failed pairing and applies the documented failure policy: a monotonic cooldown for
     * the identity and the pair, and permanent (process-local) invalidation once the failure
     * budgets are exhausted. An invalidated identity can only pair again after rediscovery.
     */
    @Synchronized
    internal fun recordFailure(peerIdentityKey: String, sourceKey: String) {
        val identity = counterFor(identities, peerIdentityKey, limits.maxTrackedIdentities)
        val pair = counterFor(pairs, pairKey(peerIdentityKey, sourceKey), limits.maxTrackedPairs)
        val now = monotonicMillis()

        val identityFailures = identity.failures.incrementAndGet()
        val pairFailures = pair.failures.incrementAndGet()

        val nowOnIdentity = identity.invalidated.get() == 0L && identityFailures >= limits.maxFailuresPerIdentity
        val nowOnPair = pair.invalidated.get() == 0L && pairFailures >= limits.maxFailuresPerPair
        if (nowOnIdentity || nowOnPair) {
            if (nowOnIdentity) identity.invalidated.set(now)
            if (nowOnPair) pair.invalidated.set(now)
            if (nowOnIdentity) invalidatedIdentities.add(peerIdentityKey)
            return
        }

        val cooldown = cooldownFor(maxOf(identityFailures, pairFailures), now)
        identity.nextAllowedAtMillis.set(cooldown)
        pair.nextAllowedAtMillis.set(cooldown)
    }

    /**
     * Applies the documented budget policy for one admitted attempt's outcome.
     *
     * The limiter owns no threads and no timers. Cooldown is derived lazily from the injected
     * monotonic clock at admission time, so lease teardown has no pending task to cancel -- that is
     * a property of the design, not an unchecked assumption.
     */
    @Synchronized
    internal fun recordOutcome(
        peerIdentityKey: String,
        sourceKey: String,
        outcome: PairingAttemptOutcome,
    ) {
        when (outcome) {
            PairingAttemptOutcome.AUTHENTICATED -> recordSuccess(peerIdentityKey, sourceKey)
            PairingAttemptOutcome.AUTHENTICATION_MISMATCH,
            PairingAttemptOutcome.TRANSCRIPT_MISMATCH,
            PairingAttemptOutcome.KEY_CONFIRMATION_FAILURE -> recordFailure(peerIdentityKey, sourceKey)
            PairingAttemptOutcome.USER_REJECTION,
            PairingAttemptOutcome.APPROVAL_EXPIRED -> recordSoftAttempt(peerIdentityKey, sourceKey)
            PairingAttemptOutcome.HANDSHAKE_TIMEOUT,
            PairingAttemptOutcome.PEER_DISCONNECT -> recordPairBudgetFailure(peerIdentityKey, sourceKey)
            PairingAttemptOutcome.LOCAL_CANCELLATION,
            PairingAttemptOutcome.LOCAL_FAILURE,
            PairingAttemptOutcome.RESOURCE_REFUSED -> Unit
        }
    }

    /**
     * Cooldown without failure escalation. A declined approval is a real pairing attempt and must
     * not be free, but it must also never be the thing that invalidates an identity.
     */
    @Synchronized
    internal fun recordSoftAttempt(peerIdentityKey: String, sourceKey: String) {
        val now = monotonicMillis()
        val attempts = softAttempts.incrementAndGet()
        if (attempts > limits.maxTrackedSoftAttempts) return
        val cooldown = cooldownFor(attempts, now)
        identities[peerIdentityKey]?.nextAllowedAtMillis?.set(cooldown)
        pairs[pairKey(peerIdentityKey, sourceKey)]?.nextAllowedAtMillis?.set(cooldown)
    }

    /**
     * Failure against the identity/source pair only. A peer that goes half-open after security work
     * has begun should cost that route something, but a network drop is not evidence against the
     * identity, so the identity's failure budget is left alone.
     */
    @Synchronized
    internal fun recordPairBudgetFailure(peerIdentityKey: String, sourceKey: String) {
        val pair = counterFor(pairs, pairKey(peerIdentityKey, sourceKey), limits.maxTrackedPairs)
        val now = monotonicMillis()
        val failures = pair.failures.incrementAndGet()
        if (pair.invalidated.get() == 0L && failures >= limits.maxFailuresPerPair) {
            pair.invalidated.set(now)
            return
        }
        pair.nextAllowedAtMillis.set(cooldownFor(failures, now))
    }

    /**
     * Lease teardown. Releases every permit this lease handed out and drops all counters, which is
     * what makes the next lease genuinely newly scoped. This is a process-local policy reset, not a
     * security proof: a peer that simply waits for the lease to end starts from a clean slate.
     */
    @Synchronized
    internal fun releaseAllConcurrency() {
        inFlight.set(0)
    }

    /** Records a success, which clears the failure escalation for this identity and pair. */
    @Synchronized
    internal fun recordSuccess(peerIdentityKey: String, sourceKey: String) {
        identities[peerIdentityKey]?.failures?.set(0)
        identities[peerIdentityKey]?.nextAllowedAtMillis?.set(0L)
        pairs[pairKey(peerIdentityKey, sourceKey)]?.failures?.set(0)
        pairs[pairKey(peerIdentityKey, sourceKey)]?.nextAllowedAtMillis?.set(0L)
    }

    /**
     * Drops a discovery identity, which is what the coordinator calls when a peer's start budget is
     * exhausted: the cached identity can no longer pair and the peer must be rediscovered.
     */
    @Synchronized
    internal fun invalidateIdentity(peerIdentityKey: String) {
        identities[peerIdentityKey]?.invalidated?.set(monotonicMillis())
        invalidatedIdentities.add(peerIdentityKey)
    }

    internal fun isIdentityInvalidated(peerIdentityKey: String): Boolean {
        if (invalidatedIdentities.contains(peerIdentityKey)) return true
        // An untracked identity is not an invalidated one; `null != 0L` would say otherwise.
        val stamp = identities[peerIdentityKey]?.invalidated?.get() ?: return false
        return stamp != 0L
    }

    internal fun concurrentPairings(): Int = inFlight.get()

    /** Drops every counter. Used by lease teardown; also documents the process-local reset. */
    @Synchronized
    internal fun reset() {
        identities.clear()
        sources.clear()
        pairs.clear()
        invalidatedIdentities.clear()
        inFlight.set(0)
        softAttempts.set(0)
    }

    private fun pairKey(peerIdentityKey: String, sourceKey: String): String =
        peerIdentityKey + '\u0000' + sourceKey

    /** Returns the tracked counter, or the shared overflow counter once the map is full. */
    private fun counterFor(map: ConcurrentHashMap<String, Counter>, key: String, cap: Int): Counter {
        map[key]?.let { return it }
        if (map.size >= cap) return overflowCounter
        val created = Counter()
        val previous = map.putIfAbsent(key, created)
        return previous ?: created
    }

    /** `2000ms << (failures - 1)`, capped. The shift is bounded so it cannot overflow. */
    private fun cooldownFor(failures: Int, now: Long): Long {
        val exponent = (failures - 1).coerceIn(0, 20)
        val duration = (limits.baseCooldownMillis shl exponent).coerceAtMost(limits.maxCooldownMillis)
        return if (now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration
    }

    private fun refused(reason: PairingAdmission.RefusalReason, retryAfter: Long) =
        PairingAdmission.Refused(reason, if (retryAfter < 0L) 0L else retryAfter)
}

/**
 * The result of an [AdmissionHandle] operation, as a value rather than as a silent no-op.
 *
 * The limiter previously expressed "this mutation was refused" by returning `Unit`: `recordOutcome`
 * wrote through `identities[key]?.set(...)`, so an outcome recorded for an identity the limiter had
 * never tracked did nothing at all. That was safe only because production reached it exclusively
 * from an admitted attempt's `finally` block -- an invariant carried entirely by the call site. This
 * type moves the refusal into the return value, where a caller cannot fail to notice it.
 */
internal sealed interface AdmissionReceipt {
    /**
     * The terminal transition was claimed, the outcome was applied to the budget policy, and the
     * concurrency permit was returned. This is the only fully successful result.
     */
    data object Completed : AdmissionReceipt

    /**
     * The owning lease closed, so its counters were already gone and the outcome could not be
     * applied. The permit was still returned.
     */
    data object ScopeClosed : AdmissionReceipt

    /**
     * The scope no longer recognises this handle. The permit was still returned.
     */
    data object NotAdmitted : AdmissionReceipt

    /**
     * This handle already completed. Nothing was applied and no second permit was returned.
     */
    data object AlreadyCompleted : AdmissionReceipt
}

/**
 * Opaque proof that one specific attempt was admitted by one specific lease, and the single
 * terminal operation that attempt is allowed to perform.
 *
 * WHY ONE OPERATION AND NOT TWO. An earlier shape split this into `recordOutcome` and `release`.
 * That split let the two race: a release could retire the grant before the outcome was recorded,
 * and because the outcome checked liveness first, *every* contender was then refused -- so a real
 * security failure could be erased by the very cleanup that followed it. Worse, gating the release
 * on the scope being open leaked a permit whenever a grant was acquired in the window between the
 * scope's own closed check and `close()`.
 *
 * [complete] is therefore the whole lifecycle, and it is atomic under one compare-and-set:
 *  1. claim the terminal transition -- exactly one caller ever wins;
 *  2. apply the outcome's counters, cooldown and invalidation, if the scope can still take it;
 *  3. retire the live grant so the handle can never be honoured again;
 *  4. return exactly one concurrency permit, unconditionally.
 *
 * Cancellation is an outcome ([PairingAttemptOutcome.LOCAL_CANCELLATION]), not a separate release,
 * so there is no second path that can race and erase a security result.
 *
 * WHAT MAKES FORGERY FAIL, STATED PRECISELY. The constructor is `internal`, not `private`: Kotlin
 * has no file-private constructor, and a private one would be unreachable from the scope that has
 * to issue handles. The invariant therefore does not rest on visibility. It rests on reference
 * identity -- a scope honours a handle only when its own live-grant table maps that handle's token
 * to *that exact object* -- so a second object carrying identical identity and source strings is
 * refused, and a handle issued by another lease is refused.
 */
internal class AdmissionHandle internal constructor(
    private val owner: PairingAdmissionScope,
    private val token: Long,
) {
    private val terminal = AtomicBoolean(false)

    /** Visible to the issuing scope only, so it can key its live-grant table. */
    internal fun token(): Long = token

    /** True once the terminal transition has been claimed, whether or not the outcome was applied. */
    internal val isCompleted: Boolean get() = terminal.get()

    /** The outcome this handle completed with, or null before completion. */
    @Volatile
    internal var terminalOutcome: PairingAttemptOutcome? = null
        private set

    /**
     * The one terminal operation. Idempotent: every call after the first returns
     * [AdmissionReceipt.AlreadyCompleted] without applying anything or returning a second permit.
     *
     * The permit is returned even when the outcome could not be applied. A permit that is not
     * returned is a permanent reduction in pairing capacity, which is a worse failure than a
     * budget entry that could not be written to a scope that no longer exists.
     */
    internal fun complete(outcome: PairingAttemptOutcome): AdmissionReceipt {
        if (!terminal.compareAndSet(false, true)) return AdmissionReceipt.AlreadyCompleted
        val receipt = when {
            owner.isClosed -> AdmissionReceipt.ScopeClosed
            // Re-check closed after a liveness failure: close() clears the grant table after
            // setting the flag, so a concurrent teardown can lose the race here. Reporting that as
            // "the scope went away" is accurate; reporting it as an unrecognised handle is not.
            !owner.isLiveGrant(this) ->
                if (owner.isClosed) AdmissionReceipt.ScopeClosed else AdmissionReceipt.NotAdmitted
            else -> owner.applyOutcome(this, outcome)
        }
        terminalOutcome = outcome
        owner.retireGrant(this)
        return receipt
    }
}

/**
 * The scope's answer to [PairingAdmissionScope.tryStart].
 *
 * Deliberately not the limiter's own [PairingAdmission]: the limiter is the counting primitive and
 * keeps its string-keyed API, while the scope is the authority that issues handles. Keeping the two
 * apart is what removes the string-keyed outcome path from the lease-facing surface entirely.
 */
internal sealed interface AdmissionDecision {
    /** Admitted. The caller must record one outcome and release the handle exactly once each. */
    data class Granted(val handle: AdmissionHandle) : AdmissionDecision

    /** Refused. No handle exists, so there is nothing that could record an outcome. */
    data class Refused(
        val reason: PairingAdmission.RefusalReason,
        val retryAfterMillis: Long,
    ) : AdmissionDecision
}

/**
 * One discovery lease's pairing-admission scope.
 *
 * The lease creates exactly one of these when explicit discovery starts, hands that same instance
 * to every pairing coordinator it spawns, and closes it when it stops. Closing releases every
 * permit the scope handed out exactly once and drops every counter, which is what makes a restarted
 * lease genuinely newly scoped rather than inheriting the previous lease's exhaustion.
 *
 * A closed scope refuses new attempts instead of silently admitting them. The limiter alone cannot
 * express that: its counters are just numbers, and a lease that merely called `reset()` would hand
 * an in-flight pairing a clean budget to spend after the lease it belonged to had ended.
 *
 * WHAT THIS IS NOT: a durable or cross-process limit. All state is in memory and dies with the
 * process, and discovery advertisements are unauthenticated, so a fresh scope is a documented
 * policy reset and not evidence of a fresh peer. Pairing security rests on the SAS comparison and
 * the TLS transcript binding, never on these counters.
 */
internal class PairingAdmissionScope(
    monotonicMillis: () -> Long,
    limits: SecurePairingAttemptLimiter.Limits = SecurePairingAttemptLimiter.Limits(),
) : AutoCloseable {
    private val limiter = SecurePairingAttemptLimiter(monotonicMillis, limits)
    private val closed = AtomicBoolean(false)

    internal val isClosed: Boolean get() = closed.get()

    private val liveGrants = ConcurrentHashMap<Long, AdmissionHandle>()
    private val nextToken = AtomicLong(0L)
    private val liveKeys = ConcurrentHashMap<Long, Pair<String, String>>()

    /**
     * Decides admission and, on a grant, issues the only object that can later record that
     * attempt's outcome and return its permit.
     */
    internal fun tryStart(peerIdentityKey: String, sourceKey: String): AdmissionDecision {
        if (closed.get()) {
            return AdmissionDecision.Refused(PairingAdmission.RefusalReason.SCOPE_CLOSED, 0L)
        }
        return when (val decision = limiter.tryStart(peerIdentityKey, sourceKey)) {
            is PairingAdmission.Granted -> {
                val token = nextToken.incrementAndGet()
                val handle = AdmissionHandle(this, token)
                liveGrants[token] = handle
                liveKeys[token] = peerIdentityKey to sourceKey
                AdmissionDecision.Granted(handle)
            }
            is PairingAdmission.Refused ->
                AdmissionDecision.Refused(decision.reason, decision.retryAfterMillis)
        }
    }

    /**
     * Reference-identity check, not a token lookup. Only the exact object this scope issued passes;
     * a copy carrying the same token does not.
     */
    internal fun isLiveGrant(handle: AdmissionHandle): Boolean =
        !closed.get() && liveGrants[handle.token()] === handle

    /**
     * Applies one outcome to the identity this handle was granted for.
     *
     * The reference-identity check is repeated here rather than trusted from the caller. Token
     * numbers restart at zero in every scope, so two leases hand out colliding tokens; a caller
     * that reached this method directly with a handle from another lease would otherwise charge
     * *this* scope's identity for an attempt it never admitted. Checking the token alone was a real
     * hole, not a theoretical one.
     */
    internal fun applyOutcome(handle: AdmissionHandle, outcome: PairingAttemptOutcome): AdmissionReceipt {
        if (closed.get()) return AdmissionReceipt.ScopeClosed
        if (!isLiveGrant(handle)) {
            // close() sets the flag and then clears the grant table, so a concurrent teardown can
            // win between this method's closed check and its liveness check. That is still "the
            // scope went away", and reporting it as an unrecognised handle would make the receipt
            // depend on which of two equivalent checks happened to catch it. The permit is returned
            // either way, so nothing leaks -- but the caller deserves a stable answer.
            return if (closed.get()) AdmissionReceipt.ScopeClosed else AdmissionReceipt.NotAdmitted
        }
        val key = liveKeys[handle.token()] ?: return AdmissionReceipt.NotAdmitted
        limiter.recordOutcome(key.first, key.second, outcome)
        return AdmissionReceipt.Completed
    }

    /**
     * Retires the grant and returns exactly one permit.
     *
     * DELIBERATELY NOT GATED ON [closed]. A grant can be acquired in the window between this
     * scope's own closed check in [tryStart] and `close()` running; that grant still holds a real
     * permit, and the limiter's floor at zero makes a post-reset release harmless. Gating this call
     * on `closed` is what leaked a permit under a concurrent grant/release/close churn -- the count
     * settled at one instead of zero and never recovered.
     */
    internal fun retireGrant(handle: AdmissionHandle) {
        liveGrants.remove(handle.token())
        liveKeys.remove(handle.token())
        limiter.releaseConcurrency()
    }

    internal fun invalidateIdentity(peerIdentityKey: String) {
        if (closed.get()) return
        limiter.invalidateIdentity(peerIdentityKey)
    }

    internal fun concurrentPairings(): Int = limiter.concurrentPairings()

    internal fun isIdentityInvalidated(peerIdentityKey: String): Boolean =
        limiter.isIdentityInvalidated(peerIdentityKey)

    /**
     * Idempotent, and the release happens exactly once no matter how many callers race in.
     *
     * The limiter owns no threads and no timers -- cooldown is derived lazily from the injected
     * clock at admission time -- so there is no pending task to cancel here. That is a property of
     * the design, not an assumption this method makes.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Retiring the grants first means a handle that races in during teardown is refused
        // rather than mutating counters that are about to be dropped.
        liveGrants.clear()
        liveKeys.clear()
        limiter.releaseAllConcurrency()
        limiter.reset()
    }
}
