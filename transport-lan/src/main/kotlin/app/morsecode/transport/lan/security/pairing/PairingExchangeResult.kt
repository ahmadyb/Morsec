/*
 * What one pairing exchange returns, and the budgets it runs under.
 *
 * THE RESULT IS NOT AUTHORITY
 *   A `Confirmed` here means the two peers validated each other's round-three tag and exchanged
 *   agreeing approvals. That is real, and it is still not permission to do anything: the record
 *   layer has not been derived, mutual key confirmation has not run, and no authority type exists
 *   yet. The result is deliberately internal and cannot be constructed outside this module, and it
 *   names no authority, no record layer and no payload capability.
 *
 *   Keeping it narrow is what stops the next increment from skipping a step. A coordinator that
 *   wants an authenticated session still has to derive the record layer, run `SecureKeyConfirmation`
 *   in both directions, and present that evidence to the authority constructor; a `Confirmed`
 *   exchange result cannot stand in for any of it.
 *
 * WHY FAILURES ARE TYPED LOCALLY AND COARSE REMOTELY
 *   The remote peer learns one of two things: retry or abort. Locally, an operator needs to know
 *   whether a pairing timed out, was cancelled, or was refused by a human, so the local reason is
 *   specific. The two are different audiences and the split is deliberate -- [PairingExchangeFailure]
 *   never travels on the wire.
 *
 * STATUS
 *   Experimental and not independently cryptographically reviewed. The owner accepted that risk on
 *   2026-10-19; see doc/security/owner-risk-waiver-2026-10-19.md.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.session.PakeApproval
import app.morsecode.core.transfer.session.PakeApprovalDecision
import app.morsecode.core.transfer.session.PakeAttemptId
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.transport.lan.security.jpake.ModpJpakeSession

/**
 * Why an exchange ended without confirmation.
 *
 * Local diagnostics only. None of these values is sent to the peer: the peer receives
 * [app.morsecode.core.transfer.session.PakeAttemptVerdict], which has two values and no causes.
 */
internal enum class PairingExchangeFailure {
    /** The user or the owning lease cancelled, or the generated code was never displayed. */
    CANCELLED,

    /** The generated code's display-and-entry window closed. A retry is no longer meaningful. */
    CODE_EXPIRED,

    /** Three attempts were spent and neither side may try again with this code. */
    ATTEMPTS_EXHAUSTED,

    /** A bounded wait elapsed. */
    TIMEOUT,

    /** The peer closed the connection, or a write could not be completed. */
    CONNECTION_LOST,

    /** A frame arrived that this exchange will not accept at this point. */
    PROTOCOL,

    /** The PAKE library refused a payload: proof, confirmation tag or identity. */
    CRYPTO_FAILED,

    /** The peer said it will not retry. */
    ABORTED_BY_PEER,

    /** The local human said this is not the intended peer. */
    APPROVAL_REJECTED,

    /**
     * The peer's approval record said this is not the intended peer.
     *
     * Kept apart from [APPROVAL_REJECTED] because the two are different events for the admission
     * budgets: a local refusal is the local user changing their mind, while the remote record is
     * the peer's answer and must not be charged to the local user's account either.
     */
    PEER_APPROVAL_REJECTED,

    /** The local approval question was not answered before its deadline. */
    APPROVAL_TIMEOUT,

    /** A local provider failed: entropy, clock or the PAKE library raised. */
    PROVIDER_FAILURE,

    /** An invariant of this class was violated. Never reachable from received input. */
    INTERNAL,
}

/** The outcome of one pairing exchange. */
internal sealed interface PairingExchangeResult {

    /**
     * Rounds one to three validated on both sides and both approvals agreed.
     *
     * Not authority. See the file header. [session] stays open and its ownership passes to the
     * caller, which is why the exchange does not close it on this path: the coordinator needs the
     * confirmed session as evidence when it builds the authority. Every other path closes it.
     *
     * The attempt behind [session] is left *unsettled* on purpose. Issuing
     * [app.morsecode.transport.lan.security.AuthenticatedControlAuthority] requires an attempt that
     * has not been settled, so the exchange cannot settle it: the caller settles it once the record
     * layer, both key confirmations and the authority itself have succeeded. The ledger is left
     * valid for the same reason -- invalidation wipes the code instance the authority binds to -- so
     * the caller also owns the final settlement of the code's budget.
     *
     * The members carry no visibility modifier because the enclosing interface is `internal`, which
     * is what confines them: nothing outside this module can name the type or hold a value of it.
     */
    class Confirmed internal constructor(
        val attemptId: PakeAttemptId,
        val attemptOrdinal: Int,
        val localDecision: SecureApprovalDecision,
        val peerDecision: PakeApprovalDecision,
        val peerApproval: PakeApproval,
        val session: ModpJpakeSession,
    ) : PairingExchangeResult {
        override fun toString(): String =
            "PairingExchangeResult.Confirmed(ordinal=$attemptOrdinal, ids=[redacted])"
    }

    /** No confirmation. [attemptsCharged] is this side's spend against the generated code. */
    class Failed(
        val reason: PairingExchangeFailure,
        val attemptsCharged: Int,
    ) : PairingExchangeResult {
        override fun toString(): String =
            "PairingExchangeResult.Failed(reason=${reason.name}, attemptsCharged=$attemptsCharged)"
    }
}

/**
 * What a refusal means for the admission budgets, in the terms the limiter charges.
 *
 * The pairing exchange knows why it stopped; the limiter only knows whether the peer defeated the
 * security exchange. Rather than re-derive the second from a coarse failure code -- which cannot
 * tell an exhausted attempt budget from a capacity refusal, or a local timeout from a proof
 * failure -- the coordinator classifies once, where the reason is still known, and the live path
 * maps the class to an outcome. Every member names who pays for the attempt.
 *
 * Deliberately not a wire type: none of this crosses the connection, and the peer learns only
 * `RETRY` or `ABORT`.
 */
internal enum class PakeRefusalClass {
    /** The attempt budget for this code is spent. Charged: this is what a guessing peer costs. */
    ATTEMPTS_EXHAUSTED,

    /** A proof, tag, approval binding or protocol invariant failed. Charged. */
    AUTHENTICATION_FAILURE,

    /** The peer's key confirmation did not verify, or was absent. Charged. */
    KEY_CONFIRMATION_FAILURE,

    /** The transcript, suite or profile the two sides agreed on is not the required one. Charged. */
    TRANSCRIPT_MISMATCH,

    /** The local human declined. Not charged: a change of mind is not an attack. */
    USER_REJECTION,

    /** The remote human declined. Not charged, and not evidence against the peer's identity. */
    PEER_REJECTION,

    /** The local user or the owning lease cancelled. Not charged. */
    LOCAL_CANCELLATION,

    /** A local prompt or code window expired unanswered. Not charged. */
    LOCAL_EXPIRY,

    /** The peer stopped answering inside a bounded wait. Not charged to the identity. */
    PEER_UNRESPONSIVE,

    /** The peer's connection went away mid-exchange. Not charged to the identity. */
    PEER_DISCONNECTED,

    /** A local provider, transport or invariant failed. Not charged: the peer did not cause it. */
    LOCAL_FAILURE,
}

/**
 * Every bound the exchange applies, in one place.
 *
 * The two deadlines that matter are different in kind. A round that has to cross a human — the
 * first one, where the responder is still typing, and the approval itself — needs a human-scale
 * budget, while the later rounds only have to cover group arithmetic. Giving all of them the
 * human-scale number would let a stalled peer hold a worker for minutes; giving all of them the
 * arithmetic number would fail an honest pairing whenever someone types slowly.
 */
internal class PairingExchangeBudgets(
    /** Covers the attempt-start record, the first round and the failure-handshake answer. */
    val peerResponseMillis: Long,
    /** Covers rounds two and three, which involve no human. */
    val roundMillis: Long,
    /** How long a generated code stays displayable and enterable. */
    val codeLifetimeMillis: Long,
    /** How long the intended-peer question may stay unanswered. */
    val approvalMillis: Long,
) {
    init {
        require(peerResponseMillis > 0L) { "peer response budget must be positive" }
        require(roundMillis > 0L) { "round budget must be positive" }
        require(codeLifetimeMillis > 0L) { "code lifetime must be positive" }
        require(approvalMillis > 0L) { "approval budget must be positive" }
    }

    companion object {
        /**
         * Production budgets.
         *
         * The human-scale ones reuse the approval lifetime the rest of Part B already uses rather
         * than inventing a second number for the same kind of wait.
         */
        val DEFAULT: PairingExchangeBudgets = PairingExchangeBudgets(
            peerResponseMillis = SecureSessionLimits.APPROVAL_LIFETIME_MILLIS,
            roundMillis = SecureSessionLimits.CONFIRMATION_DEADLINE_MILLIS,
            codeLifetimeMillis = SecureSessionLimits.APPROVAL_LIFETIME_MILLIS,
            approvalMillis = SecureSessionLimits.APPROVAL_LIFETIME_MILLIS,
        )
    }
}
