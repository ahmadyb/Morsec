/*
 * Per-attempt identity for the experimental MODP J-PAKE exchange.
 *
 * WHY AN ATTEMPT HAS ITS OWN IDENTITY
 *   A J-PAKE payload is self-contained. Round one carries group elements, two zero-knowledge proofs
 *   and the participant id string, and the proof hashes nothing else. Two attempts that share a
 *   participant id therefore describe the same cryptographic identity, and a round-one frame from
 *   attempt one validates perfectly well inside attempt two. Binding only the code instance is not
 *   enough: the code instance is deliberately stable across the three attempts a user is allowed to
 *   make with one displayed code, which is exactly the window a replay wants.
 *
 *   So every attempt gets a fresh 16-byte identifier, and the identifier participates in the
 *   authentication context. Because the participant ids are derived from that context, fresh attempt
 *   id => fresh participant ids => a frame from a previous attempt names the wrong participant and
 *   is refused before any group operation happens. Freshness is not a convention the caller is asked
 *   to respect; it is what makes the identifiers differ.
 *
 * WHAT AN ATTEMPT ID IS NOT
 *   It is not a secret and not a capability. It travels in clear inside frames that TLS already
 *   protects, and knowing it grants nothing: the code is the secret, and the attempt id only makes
 *   each attempt's derived identity unique. [toString] redacts it anyway, because an identifier that
 *   reaches a log line is one that outlives the session it described.
 *
 * STATUS
 *   Experimental, project-defined and not independently cryptographically reviewed. The owner
 *   accepted that risk on 2026-10-19; see doc/security/owner-risk-waiver-2026-10-19.md.
 */

package app.morsecode.core.transfer.session

import java.security.MessageDigest

/** A single-use identifier for one full PAKE exchange. */
public class PakeAttemptId private constructor(value: ByteArray) {

    /**
     * A private copy, taken before anything else can touch the caller's buffer.
     *
     * Storing the caller's array directly made [generate]'s wipe erase the identifier it had just
     * produced: every attempt then carried sixteen zero bytes, which is the same identifier for
     * every attempt and an authentication context that no longer changes between them.
     */
    private val value: ByteArray = value.copyOf()

    init {
        require(value.size == BYTE_COUNT) { "an attempt id is exactly $BYTE_COUNT bytes" }
    }

    /** Copy of the raw bytes, for the MPS3 field and for record framing. */
    public fun bytes(): ByteArray = value.copyOf()

    /** Lowercase hexadecimal, for the authentication context's debugging aids and for tests. */
    public fun hex(): String {
        val digits = "0123456789abcdef"
        val builder = StringBuilder(value.size * 2)
        for (byte in value) {
            val unsigned = byte.toInt() and 0xFF
            builder.append(digits[unsigned ushr 4]).append(digits[unsigned and 0x0F])
        }
        return builder.toString()
    }

    override fun equals(other: Any?): Boolean =
        other is PakeAttemptId && MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    /** Redacted: see the file header. The identifier must not reach a log through interpolation. */
    override fun toString(): String = "PakeAttemptId([redacted])"

    public companion object {
        public const val BYTE_COUNT: Int = 16

        /** Draws a fresh identifier from the supplied entropy source. */
        public fun generate(entropy: SecureEntropy): PakeAttemptId {
            val bytes = ByteArray(BYTE_COUNT)
            entropy.nextBytes(bytes)
            return try {
                PakeAttemptId(bytes)
            } finally {
                // The constructor copies first; the local array must not outlive this call.
                bytes.fill(0)
            }
        }

        /** Reconstructs an identifier from a received field, or null if the width is wrong. */
        public fun fromBytes(bytes: ByteArray): PakeAttemptId? =
            if (bytes.size == BYTE_COUNT) PakeAttemptId(bytes.copyOf()) else null
    }
}

/**
 * What settling an attempt did to the budget.
 *
 * [CHARGED] means the failure counted and budget remains; [EXHAUSTED] means it counted and the code
 * is now spent, so the caller must invalidate the displayed code and require fresh discovery;
 * [ALREADY_SETTLED] means the attempt had already reached a terminal state and nothing changed.
 * There is no value that restores budget.
 */
public enum class PakeAttemptCharge {
    CHARGED,
    EXHAUSTED,
    ALREADY_SETTLED,
}

/**
 * One PAKE exchange, issued by a [PakeAttemptLedger] and settled exactly once.
 *
 * Settlement is terminal and idempotent. The three settle paths are deliberately distinct because
 * they have different consequences for the budget, and collapsing them into one `finish(ok)`
 * boolean is how a local cancellation ends up charged to the peer:
 *
 *  - [charge] -- the exchange failed for a reason attributable to received input. Counts.
 *  - [succeed] -- all three rounds validated on both sides. Does not count against the budget.
 *  - [abandon] -- local cancellation, local interaction failure, local provider failure or an
 *    internal implementation failure. Does not count, because none of them tells an attacker
 *    anything about the code.
 *
 * The attempt is a handle, not the ledger's state: settling twice is safe and the second call
 * reports [PakeAttemptCharge.ALREADY_SETTLED] rather than double-charging.
 */
public class PakeAttempt internal constructor(
    private val ledger: PakeAttemptLedger,
    public val id: PakeAttemptId,
    public val ordinal: Int,
    codeInstanceId: ByteArray,
) {
    private val codeInstance: ByteArray = codeInstanceId.copyOf()
    private var settled: Boolean = false
    private var succeeded: Boolean = false

    init {
        require(ordinal in 1..PakeAttemptLedger.MAX_PAKE_ATTEMPTS) { "attempt ordinal out of range" }
        require(codeInstance.size == PakeAttemptLedger.CODE_INSTANCE_BYTES) {
            "code instance id must be ${PakeAttemptLedger.CODE_INSTANCE_BYTES} bytes"
        }
    }

    /** Copy of the code-instance identifier this attempt belongs to. */
    public fun codeInstanceBytes(): ByteArray = codeInstance.copyOf()

    @Synchronized
    public fun isSettled(): Boolean = settled

    @Synchronized
    public fun hasSucceeded(): Boolean = succeeded

    /** Counts a failure attributable to received input. See the class comment. */
    public fun charge(): PakeAttemptCharge = ledger.settleCharged(this)

    /** Records a fully validated exchange. True only for the call that actually settled it. */
    public fun succeed(): Boolean = ledger.settleUncharged(this, success = true)

    /** Records a local stop that says nothing about the peer. Never charged, never counts. */
    public fun abandon(): Boolean = ledger.settleUncharged(this, success = false)

    internal fun markSettled(success: Boolean): Boolean {
        if (settled) return false
        settled = true
        succeeded = success
        return true
    }

    internal fun clearCodeInstance() {
        codeInstance.fill(0)
    }

    override fun toString(): String =
        "PakeAttempt(ordinal=$ordinal, settled=$settled, ids=[redacted])"
}

/**
 * The three-attempt budget for one generated code.
 *
 * SCOPE, STATED PRECISELY
 *   This bounds PAKE exchanges. It is not the lease-owned `SecurePairingAttemptLimiter`, which
 *   bounds coordinator outcomes and is completed exactly once per run. The distinction matters
 *   operationally: a user who mistypes the code three times burns this budget and produces exactly
 *   one charged coordinator outcome, not three.
 *
 * WHAT COUNTS
 *   A charged attempt. The caller decides which failures are chargeable, but the only defensible
 *   rule is the one written into [PakeAttempt]: failures attributable to received input count, and
 *   local stops do not. Counting local stops would let a peer spend a victim's budget by simply
 *   refusing to answer, which converts the limiter into a denial-of-service tool.
 *
 * ORDINAL REUSE
 *   The ordinal is `charged + 1`, so it stays inside 1..3. An abandoned attempt therefore leaves the
 *   ordinal unchanged for the next attempt -- but never reuses its identifier, and the identifier is
 *   what MPS3 and the authentication context bind. Ordinal reuse is a numbering statement, not a
 *   replay opportunity, and the tests pin both halves of that.
 *
 * ONE OUTSTANDING ATTEMPT
 *   [beginAttempt] refuses while a previous attempt is unsettled. That is not bookkeeping taste: two
 *   live exchanges against one code are two guesses, and the budget is only meaningful if the caller
 *   cannot run them concurrently and then count the winner.
 *
 * PROCESS-LOCAL
 *   State resets when the process dies. Documented rather than hidden: at 40 bits of entered-code
 *   entropy the entropy is the real bound and this is the throttle.
 */
public class PakeAttemptLedger(
    codeInstanceId: ByteArray,
    private val maxAttempts: Int = MAX_PAKE_ATTEMPTS,
) {
    private val codeInstance: ByteArray = codeInstanceId.copyOf()
    private var charged: Int = 0
    private var invalidated: Boolean = false
    private var outstanding: PakeAttempt? = null

    init {
        require(codeInstance.size == CODE_INSTANCE_BYTES) {
            "code instance id must be $CODE_INSTANCE_BYTES bytes"
        }
        require(maxAttempts in 1..MAX_PAKE_ATTEMPTS) {
            "a generated code permits at most $MAX_PAKE_ATTEMPTS attempts"
        }
    }

    /**
     * Starts a new exchange, or returns null when the budget is spent or a previous attempt has not
     * been settled.
     *
     * A null here is final for this code: the caller must invalidate the displayed code, close the
     * connection and require fresh discovery. There is no reset path, by design -- an API that could
     * reset the budget would be the attack it is meant to bound.
     */
    @Synchronized
    public fun beginAttempt(entropy: SecureEntropy): PakeAttempt? {
        if (invalidated) return null
        if (charged >= maxAttempts) {
            invalidate()
            return null
        }
        if (outstanding?.isSettled() == false) return null
        val attempt = PakeAttempt(
            ledger = this,
            id = PakeAttemptId.generate(entropy),
            ordinal = charged + 1,
            codeInstanceId = codeInstance,
        )
        outstanding = attempt
        return attempt
    }

    /**
     * Settles an attempt as a charged failure.
     *
     * Only a settle that actually transitioned this attempt spends budget, so a second call on the
     * same handle cannot count twice.
     */
    @Synchronized
    internal fun settleCharged(attempt: PakeAttempt): PakeAttemptCharge {
        if (outstanding !== attempt) return PakeAttemptCharge.ALREADY_SETTLED
        if (!attempt.markSettled(success = false)) return PakeAttemptCharge.ALREADY_SETTLED
        outstanding = null
        attempt.clearCodeInstance()
        charged += 1
        if (charged >= maxAttempts) {
            invalidate()
            return PakeAttemptCharge.EXHAUSTED
        }
        return PakeAttemptCharge.CHARGED
    }

    /** Settles an attempt without spending budget. True only for the call that transitioned it. */
    @Synchronized
    internal fun settleUncharged(attempt: PakeAttempt, success: Boolean): Boolean {
        if (outstanding !== attempt) return false
        if (!attempt.markSettled(success)) return false
        outstanding = null
        attempt.clearCodeInstance()
        return true
    }

    /** Spends the code without starting another attempt. Idempotent. */
    @Synchronized
    public fun invalidate() {
        invalidated = true
        outstanding = null
        codeInstance.fill(0)
    }

    @Synchronized
    public fun remaining(): Int = if (invalidated) 0 else (maxAttempts - charged)

    @Synchronized
    public fun isExhausted(): Boolean = invalidated

    @Synchronized
    public fun attemptsCharged(): Int = charged

    override fun toString(): String =
        "PakeAttemptLedger(remaining=${remaining()}, exhausted=$invalidated)"

    public companion object {
        /** Three full exchanges per generated code. */
        public const val MAX_PAKE_ATTEMPTS: Int = 3

        /** Same width as the attempt id; both are 128-bit handles bound into the context. */
        public const val CODE_INSTANCE_BYTES: Int = 16
    }
}
