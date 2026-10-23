/*
 * The per-session slot that holds the entered-code bridge.
 *
 * WHAT IT IS FOR
 *   A control session may run exactly one pairing, and a screen may hold exactly one live prompt at
 *   a time. The bridge already enforces the second; this class enforces the first, and it owns the
 *   shutdown path so that "the session closed" and "the prompt is still waiting for an answer"
 *   cannot disagree.
 *
 * THE GATE
 *   [gate] is the control session's own `pairingStarted` flag. Opening a bridge is what claims the
 *   session's single pairing, so one compare-and-set decides it and a refusal costs nothing. The
 *   flag outlived the SAS path it was introduced with and stays the single source of truth for
 *   "this session has already started a pairing".
 *
 * ONE CLOSE, AND IT RESOLVES
 *   [closeOnce] detaches the bridge and closes it. A bridge that is already closing resolves its own
 *   pending prompt exactly once, which is what makes a shutdown look like a cancellation to the
 *   exchange rather than like a deadline it has to wait out. Calling it twice is a no-op.
 *
 * WHAT IT CANNOT DO
 *   It cannot create an approval. It has no clock of its own, no default answer and no test mode: an
 *   unanswered prompt still expires, and the only way a decision reaches the exchange is through a
 *   challenge the bridge issued.
 *
 * REACHABILITY
 *   One instance per control session, owned by `LanControlSession`. `beginSecurePairing` opens it,
 *   `close()` shuts it down exactly once, and the coordinator receives the bridge it returned.
 *
 * STATUS
 *   Experimental, project-defined and not independently cryptographically reviewed. The owner
 *   accepted that risk on 2026-10-19; see doc/security/owner-risk-waiver-2026-10-19.md.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.SecurePeerRole
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds at most one entered-code bridge for one control session.
 *
 * @param gate the shared pairing gate. Its first compare-and-set decides which path may run.
 * @param isSessionClosed consulted before the gate is taken and again after the bridge is stored, so
 *   a close that races with [open] cannot leave a live bridge behind a dead session.
 */
internal class EnteredCodePairingSlot(
    private val sessionId: SessionId,
    private val localRole: SecurePeerRole,
    private val clock: MonotonicClock,
    private val gate: AtomicBoolean,
    private val isSessionClosed: () -> Boolean,
) {
    private val active = AtomicReference<EnteredCodePairingBridge?>(null)
    private val closed = AtomicBoolean(false)

    /** The bridge currently answering for this session, or null. */
    fun current(): EnteredCodePairingBridge? = active.get()

    /**
     * Opens the bridge, or reports why it cannot be opened.
     *
     * Null means: the session is closed or closing, or a pairing of either kind already started.
     * The gate is taken before the bridge exists, so a refused open charges nothing.
     */
    fun open(prompter: EnteredCodePairingPrompter): EnteredCodePairingBridge? {
        if (closed.get() || isClosed()) return null
        if (!gate.compareAndSet(false, true)) return null
        val bridge = EnteredCodePairingBridge(
            sessionId = sessionId,
            localRole = localRole,
            clock = clock,
            prompter = prompter,
        )
        active.set(bridge)
        // A close between the check above and this point must not leave a live bridge behind.
        if (closed.get() || isClosed()) {
            if (active.compareAndSet(bridge, null)) {
                bridge.close()
                return null
            }
        }
        return bridge
    }

    /**
     * Cancels the pairing for this session, at most once.
     *
     * The bridge resolves its pending prompt with no answer, so the exchange sees a cancellation.
     * Later [open] calls are refused for the life of the session, because a slot that has been shut
     * down belongs to a session that is gone.
     */
    fun closeOnce() {
        if (!closed.compareAndSet(false, true)) return
        active.getAndSet(null)?.close()
    }

    override fun toString(): String =
        "EnteredCodePairingSlot(role=${localRole.name}, closed=${closed.get()}, ids=[redacted])"

    private fun isClosed(): Boolean = try {
        isSessionClosed()
    } catch (_: RuntimeException) {
        // A predicate that cannot answer is treated as a closed session: the safe reading is that
        // no new prompt may be raised.
        true
    }
}
