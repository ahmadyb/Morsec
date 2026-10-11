package app.morsecode.transport.lan.security

import java.util.concurrent.ConcurrentHashMap
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
