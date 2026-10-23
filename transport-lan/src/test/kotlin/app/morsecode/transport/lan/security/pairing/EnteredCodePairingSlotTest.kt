/*
 * The per-session slot: one pairing, one bridge, one close.
 *
 * The gate is a real `AtomicBoolean` shared with whatever else the session uses it for, so these
 * tests also pin the cross-path rule: a session that has started a comparison cannot start an
 * entered-code exchange through the same flag.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EnteredPairingCode
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecurePeerRole
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class EnteredCodePairingSlotTest {

    private val sessionId = SessionId("0123456789abcdef0123456789abcdef")

    @Test
    fun aSessionGetsExactlyOneBridge() {
        val gate = AtomicBoolean(false)
        val slot = slot(gate)

        val first = slot.open(RecordingPrompter())
        assertNotNull(first)
        assertNull("a second pairing on the same session must be refused", slot.open(RecordingPrompter()))
        assertTrue("the shared gate stays taken", gate.get())
        assertEquals(first, slot.current())
    }

    @Test
    fun aGateTakenByAnotherPathRefusesTheEntrance() {
        val gate = AtomicBoolean(true)
        val slot = slot(gate)

        assertNull("the SAS operation holding the gate must keep this path out", slot.open(RecordingPrompter()))
        assertNull(slot.current())
    }

    @Test
    fun aClosedSessionRefusesAndClosesNothingTwice() {
        val gate = AtomicBoolean(false)
        var sessionClosed = false
        val prompter = RecordingPrompter()
        val slot = slot(gate) { sessionClosed }

        val bridge = requireNotNull(slot.open(prompter))
        sessionClosed = true
        slot.closeOnce()
        slot.closeOnce()

        assertTrue(bridge.isClosed())
        assertEquals("the prompter is told exactly once", 1, prompter.closeCalls.get())
        assertNull(slot.current())
        assertNull("a shut-down slot never opens again", slot.open(RecordingPrompter()))
    }

    @Test
    fun closingResolvesAPendingPromptWithNoAnswer() {
        val gate = AtomicBoolean(false)
        val slot = slot(gate)
        val bridge = requireNotNull(slot.open(RecordingPrompter()))
        val answer = java.util.concurrent.atomic.AtomicReference<SecureApprovalDecision?>()

        val waiting = Thread {
            answer.set(
                bridge.requestIntendedPeerApproval(
                    IntendedPeerContext(
                        controlSessionId = "ctl-session",
                        secureSessionId = sessionId,
                        attemptOrdinal = 1,
                        localRole = SecurePeerRole.RESPONDER,
                        localPeerInstanceId = "00000000000000000000000000000002",
                        remotePeerInstanceId = "00000000000000000000000000000001",
                    ),
                    expiresAtElapsedMillis = 600_000L,
                ),
            )
        }
        waiting.start()
        // Give the prompt a moment to be raised, then shut the session down under it.
        awaitPromptOn(bridge)
        slot.closeOnce()
        waiting.join(10_000L)

        assertFalse(waiting.isAlive)
        assertNull("a shutdown is not an approval", answer.get())
    }

    @Test
    fun aPromptRaisedThroughTheSlotIsBoundToItsSessionAndRole() {
        val gate = AtomicBoolean(false)
        val slot = slot(gate)
        val bridge = requireNotNull(slot.open(RecordingPrompter()))
        val prompt = Thread { bridge.requestEnteredCode(attemptOrdinal = 1, expiresAtElapsedMillis = 60_000L) }
        prompt.start()
        awaitPromptOn(bridge)

        val challenge = requireNotNull(bridge.outstandingChallenge())
        assertEquals(sessionId, challenge.sessionId)
        assertEquals(SecurePeerRole.RESPONDER, challenge.localRole)
        slot.closeOnce()
        prompt.join(10_000L)
        assertFalse(prompt.isAlive)
    }

    private fun slot(
        gate: AtomicBoolean,
        isSessionClosed: () -> Boolean = { false },
    ): EnteredCodePairingSlot = EnteredCodePairingSlot(
        sessionId = sessionId,
        localRole = SecurePeerRole.RESPONDER,
        clock = TestClock(startMillis = 1_000L),
        gate = gate,
        isSessionClosed = isSessionClosed,
    )

    /** Waits until the bridge reports an outstanding prompt, without asserting on its content. */
    private fun awaitPromptOn(bridge: EnteredCodePairingBridge) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (bridge.outstandingChallenge() != null) return
            Thread.sleep(2L)
        }
        throw AssertionError("the bridge never raised a prompt")
    }

    /** Records prompts and answers nothing, which is the case a shutdown has to resolve. */
    private class RecordingPrompter : EnteredCodePairingPrompter {
        val closeCalls = AtomicInteger(0)
        private val challenges = java.util.concurrent.LinkedBlockingQueue<EnteredCodeChallenge>()

        override fun onGeneratedCode(challenge: EnteredCodeChallenge, code: EnteredPairingCode) {
            challenges.put(challenge)
        }

        override fun onEnteredCodeRequired(challenge: EnteredCodeChallenge, attemptOrdinal: Int) {
            challenges.put(challenge)
        }

        override fun onIntendedPeerApprovalRequired(
            challenge: EnteredCodeChallenge,
            context: IntendedPeerContext,
        ) {
            challenges.put(challenge)
        }

        override fun onPairingClosed() {
            closeCalls.incrementAndGet()
        }
    }
}
