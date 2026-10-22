/*
 * The human interaction the entered-code pairing needs, expressed without any UI.
 *
 * WHY A CONTRACT AND NOT A CALLBACK INTO THE PROVIDER
 *   The exchange must be testable without a screen, and the screen must not be able to influence
 *   anything cryptographic beyond the three answers below. So the exchange asks for exactly three
 *   things and accepts exactly one kind of answer each: display this code, give me the code a human
 *   typed, and tell me whether the peer named on screen is the intended one. Nothing else crosses.
 *
 * WHY IT IS SEPARATE FROM THE LEGACY CONTRACT
 *   `PairingInteraction` is the SAS contract: it publishes a derived proof for comparison and waits
 *   for a decision about it. The entered code is a different interaction -- one side displays a
 *   secret, the other types it -- and reusing the old interface would mean a screen could satisfy a
 *   PAKE requirement by displaying something that was never typed. The two coexist until the atomic
 *   switch, and the live coordinator still uses the old one.
 *
 * CANCELLATION AND EXPIRY ARE TYPED
 *   Every call carries an absolute deadline on the exchange's monotonic clock and reports "no
 *   answer" as null (or false), never as an exception and never as an implicit approval. The exchange
 *   maps that to a typed outcome; a screen cannot make a pairing succeed by failing to answer.
 *
 * WHAT THE CODE OBJECT GUARANTEES
 *   [app.morsecode.core.transfer.session.EnteredPairingCode] never renders itself through toString,
 *   is wiped by clearSensitive, and is the only object that can turn into the `char[]` the library
 *   takes. This contract passes that object, never a String, so an implementation cannot
 *   accidentally log what a human typed.
 *
 * STATUS
 *   Experimental and not independently cryptographically reviewed. The owner accepted that risk on
 *   2026-10-19; see doc/security/owner-risk-waiver-2026-10-19.md.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EnteredPairingCode
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecurePeerRole

/**
 * What the human is being asked to approve, in the terms a person can check.
 *
 * Deliberately small: the peer, the session and which attempt it is. It carries no digest, no tag
 * and no key, because an approval prompt that shows a cryptographic value invites a user to approve
 * something they cannot actually evaluate.
 */
internal class IntendedPeerContext(
    val controlSessionId: String,
    val secureSessionId: SessionId,
    val attemptOrdinal: Int,
    val localRole: SecurePeerRole,
    val localPeerInstanceId: String,
    val remotePeerInstanceId: String,
) {
    override fun toString(): String =
        "IntendedPeerContext(ordinal=$attemptOrdinal, ids=[redacted])"
}

/**
 * The three questions an entered-code pairing asks a human.
 *
 * Implementations are expected to be non-blocking only in the sense that they return when the human
 * answers or the deadline passes. They are called from the thread driving the exchange.
 */
internal interface EnteredCodePairingInteraction {

    /**
     * Initiator: shows the generated code.
     *
     * True when the code was displayed and the user has not cancelled. A false result ends the
     * pairing before a single frame is sent -- there is nothing to authenticate if nobody can read
     * the code.
     */
    fun publishGeneratedCode(code: EnteredPairingCode, expiresAtElapsedMillis: Long): Boolean

    /**
     * Responder: obtains the code a human typed for this attempt.
     *
     * Asked once per attempt, not once per pairing, because a retry after a wrong entry is only
     * meaningful if the human can type a different code. Null means the user cancelled or the code
     * window closed; the exchange treats both as terminal and distinguishes them from a wrong code.
     */
    fun requestEnteredCode(attemptOrdinal: Int, expiresAtElapsedMillis: Long): EnteredPairingCode?

    /**
     * Both: confirms that the peer named on screen is the one the user meant to pair with.
     *
     * Null means the deadline passed with no answer. An unanswered approval is not an approval.
     */
    fun requestIntendedPeerApproval(
        context: IntendedPeerContext,
        expiresAtElapsedMillis: Long,
    ): SecureApprovalDecision?

    /** True once the user or the owning lease has cancelled this pairing. */
    fun isCancelled(): Boolean
}
