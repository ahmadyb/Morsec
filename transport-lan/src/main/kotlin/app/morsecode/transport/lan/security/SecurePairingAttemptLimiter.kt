package app.morsecode.transport.lan.security

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Outcome of asking the limiter whether a pairing attempt may proceed. */
internal sealed interface PairingAdmission {
    /** The attempt may start; the caller must later call [SecurePairingAttemptLimiter.releaseConcurrency]. */
    public data object Granted : PairingAdmission

    /** Refused. [retryAfterMillis] is a monotonic-relative delay, 0 when the identity is dead. */
    public data class Refused(
        public val reason: RefusalReason,
        public val retryAfterMillis: Long,
    ) : PairingAdmission

    public enum class RefusalReason {
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
    public data class Limits(
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
    public fun tryStart(peerIdentityKey: String, sourceKey: String): PairingAdmission {
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
    public fun releaseConcurrency() {
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
    public fun recordFailure(peerIdentityKey: String, sourceKey: String) {
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

    /** Records a success, which clears the failure escalation for this identity and pair. */
    @Synchronized
    public fun recordSuccess(peerIdentityKey: String, sourceKey: String) {
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
    public fun invalidateIdentity(peerIdentityKey: String) {
        identities[peerIdentityKey]?.invalidated?.set(monotonicMillis())
        invalidatedIdentities.add(peerIdentityKey)
    }

    public fun isIdentityInvalidated(peerIdentityKey: String): Boolean =
        invalidatedIdentities.contains(peerIdentityKey) || identities[peerIdentityKey]?.invalidated?.get() != 0L

    public fun concurrentPairings(): Int = inFlight.get()

    /** Drops every counter. Used by lease teardown; also documents the process-local reset. */
    @Synchronized
    public fun reset() {
        identities.clear()
        sources.clear()
        pairs.clear()
        invalidatedIdentities.clear()
        inFlight.set(0)
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
