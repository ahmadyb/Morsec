package app.morsecode.transport.lan.security

import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecureApprovalResult
import app.morsecode.core.transfer.session.SecurePairingApprovalRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The two displayed proofs, exchanged out of band the way two humans exchange them by reading both
 * screens and reading them aloud to each other.
 *
 * Bounded on purpose: [awaitPeerProof] waits on a latch with an explicit timeout rather than
 * blocking forever, so a peer that never displays a proof produces a refusal instead of a hang.
 */
internal class ProofRendezvous {
    private val shown = ConcurrentHashMap<String, String>()
    private val latch = CountDownLatch(2)

    fun publish(alias: String, proof: String) {
        shown.putIfAbsent(alias, proof)
        latch.countDown()
    }

    fun awaitPeerProof(selfAlias: String, timeoutSeconds: Long): String? {
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) return null
        return shown.entries.firstOrNull { it.key != selfAlias }?.value
    }
}

/**
 * Models the interaction contract the pairing UX must actually implement:
 *
 *     show proof -> wait for an explicit decision -> approve only if the expected proofs match
 *
 * There is no unconditional approval in this class, and that is the point. The previous
 * test interaction called `decide(handle, APPROVE)` without ever looking at the proof, and the
 * honest test asserted proof equality only afterwards. That ordering meant a pairing whose two
 * proofs differed would still have been approved by the test harness; the assertion would have
 * failed, but the approval had already been granted. Approval is now gated on the comparison, so a
 * mismatch produces a refusal rather than a red assertion after the fact.
 *
 * A controller built without a [rendezvous] can never approve: it has no peer proof to compare
 * against. Hostile tests use exactly that, so a hostile endpoint cannot be approved by accident.
 */
internal class ProofComparisonController(
    private val alias: String,
    private val rendezvous: ProofRendezvous?,
) : PairingInteraction {
    @Volatile
    private var request: SecurePairingApprovalRequest? = null

    /** The proof as it would be shown to the user. Alias-only; never a digest, key or handle. */
    @Volatile
    var proofAtDisplay: String? = null
        private set

    /** The decision actually taken, or null if approval was never reached. */
    @Volatile
    var decisionTaken: SecureApprovalDecision? = null
        private set

    override fun publishApproval(
        machine: SecureSessionStateMachine,
        request: SecurePairingApprovalRequest,
    ): Boolean {
        this.request = request
        val text = request.proof.displayText()
        proofAtDisplay = text
        rendezvous?.publish(alias, text)
        return machine.state().name == "AWAITING_LOCAL_APPROVAL"
    }

    override fun awaitApproval(machine: SecureSessionStateMachine): Boolean {
        val exactRequest = request ?: return false
        val ownProof = proofAtDisplay ?: return false
        // Show, then wait for the peer's proof, then compare. No peer proof means no approval.
        val peerProof = rendezvous?.awaitPeerProof(alias, DECISION_TIMEOUT_SECONDS) ?: return false
        val decision = if (ownProof == peerProof) {
            SecureApprovalDecision.APPROVE
        } else {
            SecureApprovalDecision.REJECT
        }
        decisionTaken = decision
        val result = machine.decide(exactRequest.handle, decision)
        return result == SecureApprovalResult.Applied || result == SecureApprovalResult.AlreadyApplied
    }

    override fun isCancelled(): Boolean = false

    fun dispose() {
        request?.proof?.clearSensitive()
        request = null
        proofAtDisplay = null
    }

    private companion object {
        const val DECISION_TIMEOUT_SECONDS = 20L
    }
}
