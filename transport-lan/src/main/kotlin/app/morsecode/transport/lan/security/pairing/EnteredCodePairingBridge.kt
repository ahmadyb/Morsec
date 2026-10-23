/*
 * The screen-facing half of the entered-code pairing: challenges in, answers out.
 *
 * WHAT PROBLEM THIS SOLVES
 *   `EnteredCodePairingInteraction` is what the exchange calls: publish this code, ask for the code
 *   a human typed, ask whether the peer is the intended one. Those calls are blocking, one per
 *   prompt, on the thread driving the pairing. A screen cannot be called from there, and passing it
 *   a callback that returns before the human answers would let a pairing proceed on silence.
 *
 *   So this class sits between them. Each interaction call becomes exactly one [EnteredCodeChallenge]
 *   handed to a [EnteredCodePairingPrompter], and the call returns only when that challenge is
 *   answered, expires, or the pairing is cancelled or shut down. Answering goes back through the
 *   challenge object, never through a callback the prompter can retain.
 *
 * WHAT A CHALLENGE GUARANTEES
 *   - Single use. Settling is a compare-and-set, so an answer that loses a race returns false
 *     instead of overwriting the one that won.
 *   - Bound to one session and one role. The challenge carries both, the bridge refuses a handle it
 *     did not issue, and a stale handle from an earlier pairing cannot answer a later prompt.
 *   - Bounded. Every challenge carries the absolute deadline it was created with, on the same
 *     monotonic clock the exchange uses. Nothing here waits on wall-clock time.
 *   - Not a string. The generated code is passed as [EnteredPairingCode] and the typed code arrives
 *     as a `CharArray` that is parsed and never retained, so no intermediate `String` of a code
 *     exists outside the one a view renders, and neither ever reaches a log line or a `toString`.
 *     The caller's array is left to the caller to wipe: copying it here and wiping the copy would
 *     suggest a guarantee this class cannot make about a buffer it does not own.
 *
 * WHAT IT IS NOT
 *   There is no approval by default. An unanswered prompt expires; it does not approve. There is no
 *   test or demo mode in this file, no environment check and no flag: the only way a decision is
 *   recorded is [EnteredCodePairingBridge.submitDecision] with a challenge this bridge issued.
 *
 * REACHABILITY
 *   The live path. `LanControlSession.beginSecurePairing` opens exactly one bridge per control
 *   session through `EnteredCodePairingSlot`, and the coordinator asks its questions through it.
 *   Production installs a prompter that answers nothing, because this build has no pairing screen:
 *   a prompt therefore stays outstanding until its own deadline, which is the honest outcome for a
 *   human check nobody can answer. Everything here is internal to `:transport-lan`.
 *
 * STATUS
 *   Experimental, project-defined and not independently cryptographically reviewed. The owner
 *   accepted that risk on 2026-10-19; see doc/security/owner-risk-waiver-2026-10-19.md.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EnteredPairingCode
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecurePeerRole
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Which of the three questions a challenge represents. */
internal enum class EnteredCodeChallengeKind {
    /** Initiator: show the generated code. Answered by acknowledging that it is on screen. */
    GENERATED_CODE,

    /** Responder: collect what a human typed. Answered with the symbols, not a decision. */
    ENTERED_CODE,

    /** Both: confirm the peer on screen is the intended one. Answered with approve or reject. */
    INTENDED_PEER,
}

/**
 * One outstanding question, and the only key that can answer it.
 *
 * Deliberately opaque: it carries no code, no digest and no key, so holding one proves only that its
 * holder was shown a prompt. [toString] names the kind and nothing else, because a challenge can
 * end up in a log line through an innocuous string template.
 */
internal class EnteredCodeChallenge private constructor(
    val kind: EnteredCodeChallengeKind,
    val sessionId: SessionId,
    val localRole: SecurePeerRole,
    val attemptOrdinal: Int,
    val expiresAtElapsedMillis: Long,
) {
    private val settled = AtomicBoolean(false)

    fun isSettled(): Boolean = settled.get()

    /** True only for the first call; every later answer loses. */
    internal fun settle(): Boolean = settled.compareAndSet(false, true)

    override fun toString(): String =
        "EnteredCodeChallenge(kind=${kind.name}, ordinal=$attemptOrdinal, ids=[redacted])"

    internal companion object {
        fun create(
            kind: EnteredCodeChallengeKind,
            sessionId: SessionId,
            localRole: SecurePeerRole,
            attemptOrdinal: Int,
            expiresAtElapsedMillis: Long,
        ): EnteredCodeChallenge = EnteredCodeChallenge(
            kind = kind,
            sessionId = sessionId,
            localRole = localRole,
            attemptOrdinal = attemptOrdinal,
            expiresAtElapsedMillis = expiresAtElapsedMillis,
        )
    }
}

/**
 * What a screen implements to be asked the three questions.
 *
 * Called on the pairing thread, and expected to return promptly: present the prompt and return.
 * The answer arrives later through the bridge. Implementations must not block here, must not retain
 * a code beyond displaying it, and must not answer on the human's behalf.
 */
internal interface EnteredCodePairingPrompter {
    /** Show [code]. Acknowledge with [EnteredCodePairingBridge.acknowledgeGeneratedCode]. */
    fun onGeneratedCode(challenge: EnteredCodeChallenge, code: EnteredPairingCode)

    /** Ask for the code a human typed. Answer with [EnteredCodePairingBridge.submitEnteredCode]. */
    fun onEnteredCodeRequired(challenge: EnteredCodeChallenge, attemptOrdinal: Int)

    /**
     * Ask whether the peer shown in [context] is the intended one. Answer with
     * [EnteredCodePairingBridge.submitDecision].
     */
    fun onIntendedPeerApprovalRequired(challenge: EnteredCodeChallenge, context: IntendedPeerContext)

    /** Every prompt is withdrawn. Nothing the prompter still holds can answer anything. */
    fun onPairingClosed()
}

/**
 * The entered-code interaction the exchange calls, implemented over a prompter.
 *
 * One bridge per pairing session. It holds at most one outstanding challenge; a second prompt while
 * one is unanswered is refused rather than queued, because two live prompts on one session is either
 * a bug in the caller or an attempt to get two answers for one question.
 */
internal class EnteredCodePairingBridge(
    private val sessionId: SessionId,
    private val localRole: SecurePeerRole,
    private val clock: MonotonicClock,
    private val prompter: EnteredCodePairingPrompter,
) : EnteredCodePairingInteraction {

    private val closed = AtomicBoolean(false)
    private val active = AtomicReference<Prompt?>(null)

    /**
     * One outstanding prompt.
     *
     * The answer fields are written before the latch is released and read after it is observed, and
     * the latch gives that visibility. Nothing else is shared between the two threads.
     */
    private class Prompt(
        val challenge: EnteredCodeChallenge,
        val latch: CountDownLatch = CountDownLatch(1),
    ) {
        @Volatile
        var acknowledged: Boolean = false

        @Volatile
        var entered: EnteredPairingCode? = null

        @Volatile
        var decision: SecureApprovalDecision? = null
    }

    fun isClosed(): Boolean = closed.get()

    /** The control session this bridge answers for. Safe to expose: it is already public on the wire. */
    fun sessionId(): SessionId = sessionId

    fun localRole(): SecurePeerRole = localRole

    fun outstandingChallenge(): EnteredCodeChallenge? = active.get()?.challenge

    override fun publishGeneratedCode(code: EnteredPairingCode, expiresAtElapsedMillis: Long): Boolean {
        val prompt = begin(
            kind = EnteredCodeChallengeKind.GENERATED_CODE,
            attemptOrdinal = 0,
            expiresAtElapsedMillis = expiresAtElapsedMillis,
        ) ?: return false
        return try {
            prompter.onGeneratedCode(prompt.challenge, code)
            await(prompt) && prompt.acknowledged
        } catch (_: RuntimeException) {
            // A screen that throws is a screen that did not show the code. The pairing must not
            // continue on the strength of a prompt nobody saw.
            release(prompt)
            false
        } finally {
            clearActive(prompt)
        }
    }

    override fun requestEnteredCode(attemptOrdinal: Int, expiresAtElapsedMillis: Long): EnteredPairingCode? {
        val prompt = begin(
            kind = EnteredCodeChallengeKind.ENTERED_CODE,
            attemptOrdinal = attemptOrdinal,
            expiresAtElapsedMillis = expiresAtElapsedMillis,
        ) ?: return null
        return try {
            prompter.onEnteredCodeRequired(prompt.challenge, attemptOrdinal)
            if (!await(prompt)) return null
            // Already parsed and normalised by the answer, so this call cannot fail: a code that
            // does not parse never settles the prompt in the first place.
            prompt.entered
        } catch (_: RuntimeException) {
            null
        } finally {
            clearActive(prompt)
        }
    }

    override fun requestIntendedPeerApproval(
        context: IntendedPeerContext,
        expiresAtElapsedMillis: Long,
    ): SecureApprovalDecision? {
        val prompt = begin(
            kind = EnteredCodeChallengeKind.INTENDED_PEER,
            attemptOrdinal = context.attemptOrdinal,
            expiresAtElapsedMillis = expiresAtElapsedMillis,
        ) ?: return null
        return try {
            prompter.onIntendedPeerApprovalRequired(prompt.challenge, context)
            if (!await(prompt)) return null
            // Null is not an approval: an unanswered prompt expires, and a decision that is absent
            // here is an absence rather than a default.
            prompt.decision
        } catch (_: RuntimeException) {
            null
        } finally {
            clearActive(prompt)
        }
    }

    override fun isCancelled(): Boolean = closed.get()

    // --- answers ------------------------------------------------------------------------------

    /** True when this bridge issued the challenge, it is the outstanding one, and it was unanswered. */
    fun acknowledgeGeneratedCode(challenge: EnteredCodeChallenge): Boolean =
        answer(challenge, EnteredCodeChallengeKind.GENERATED_CODE) { prompt ->
            prompt.acknowledged = true
            prompt.latch.countDown()
        }

    /**
     * Supplies the typed symbols.
     *
     * False when the challenge is unknown, already answered, expired, or the symbols cannot be a
     * code. A malformed entry does not settle the challenge: the human mistyped, the prompt is
     * still open, and they may try again until the deadline. The array is not retained.
     */
    fun submitEnteredCode(challenge: EnteredCodeChallenge, symbols: CharArray): Boolean {
        if (symbols.isEmpty()) return false
        val parsed = EnteredPairingCode.parse(symbols) ?: return false
        return answer(challenge, EnteredCodeChallengeKind.ENTERED_CODE) { prompt ->
            prompt.entered = parsed
            prompt.latch.countDown()
        }
    }

    /** Records an explicit decision for an approval prompt. Null is not accepted: it is not a decision. */
    fun submitDecision(challenge: EnteredCodeChallenge, decision: SecureApprovalDecision): Boolean =
        answer(challenge, EnteredCodeChallengeKind.INTENDED_PEER) { prompt ->
            prompt.decision = decision
            prompt.latch.countDown()
        }

    /**
     * Withdraws everything and refuses every later answer.
     *
     * Called when the control session closes, the lease is released or the provider shuts down. It
     * resolves each waiting call exactly once: a pending prompt is settled with no answer, so the
     * exchange sees a cancellation rather than a deadline it has to wait out.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val pending = active.getAndSet(null)
        if (pending != null) {
            pending.challenge.settle()
            pending.latch.countDown()
        }
        try {
            prompter.onPairingClosed()
        } catch (_: RuntimeException) {
            // A prompter that fails on close must not keep the bridge open.
        }
    }

    override fun toString(): String =
        "EnteredCodePairingBridge(closed=${closed.get()}, role=${localRole.name}, ids=[redacted])"

    // --- internals ----------------------------------------------------------------------------

    private fun begin(
        kind: EnteredCodeChallengeKind,
        attemptOrdinal: Int,
        expiresAtElapsedMillis: Long,
    ): Prompt? {
        if (closed.get()) return null
        val now = nowOrNull() ?: return null
        if (expiresAtElapsedMillis <= now) return null
        val challenge = EnteredCodeChallenge.create(
            kind = kind,
            sessionId = sessionId,
            localRole = localRole,
            attemptOrdinal = attemptOrdinal,
            expiresAtElapsedMillis = expiresAtElapsedMillis,
        )
        val prompt = Prompt(challenge)
        // At most one outstanding prompt. A caller that asks twice without answering the first is
        // refused; queueing would let one question collect two answers.
        if (!active.compareAndSet(null, prompt)) return null
        return prompt
    }

    private fun await(prompt: Prompt): Boolean {
        val deadline = prompt.challenge.expiresAtElapsedMillis
        while (true) {
            if (closed.get()) return false
            val now = nowOrNull() ?: return false
            val remaining = deadline - now
            if (remaining <= 0L) return false
            val slice = minOf(remaining, CANCELLATION_SLICE_MILLIS)
            if (prompt.latch.await(slice, TimeUnit.MILLISECONDS)) return true
        }
    }

    private fun answer(
        challenge: EnteredCodeChallenge,
        expected: EnteredCodeChallengeKind,
        apply: (Prompt) -> Unit,
    ): Boolean {
        if (closed.get()) return false
        if (challenge.kind != expected) return false
        if (challenge.sessionId != sessionId || challenge.localRole != localRole) return false
        val prompt = active.get() ?: return false
        // Identity, not equality: the challenge is a capability, and a structurally identical one
        // built elsewhere is not the prompt this bridge is waiting on.
        if (prompt.challenge !== challenge) return false
        val now = nowOrNull() ?: return false
        if (now >= challenge.expiresAtElapsedMillis) return false
        if (!challenge.settle()) return false
        apply(prompt)
        return true
    }

    private fun release(prompt: Prompt) {
        prompt.challenge.settle()
        prompt.latch.countDown()
        clearActive(prompt)
    }

    private fun clearActive(prompt: Prompt) {
        active.compareAndSet(prompt, null)
    }

    private fun nowOrNull(): Long? = try {
        clock.nowMillis().takeIf { it >= 0L }
    } catch (_: RuntimeException) {
        null
    }

    private companion object {
        /**
         * Longest a waiting call sleeps before it re-checks cancellation.
         *
         * The same 50 ms the exchange uses; a cancelled pairing must not sit in an await for the
         * rest of a 60-second approval budget.
         */
        const val CANCELLATION_SLICE_MILLIS: Long = 50L
    }
}
