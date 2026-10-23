/*
 * The screen-facing bridge: one question at a time, single use, cancelled exactly once.
 *
 * The prompter here answers from a second thread, which is what a screen does: the prompt call
 * returns immediately and the human's answer arrives later through a handle. Everything asserted is
 * either what the waiting call returned or what the prompter was asked, so nothing depends on how
 * fast the scheduler runs.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EnteredPairingCode
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecurePeerRole
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class EnteredCodePairingBridgeTest {

    private val sessionId = SessionId("0123456789abcdef0123456789abcdef")

    @Test(timeout = 20_000L)
    fun anAcknowledgedCodeIsPublishedOnce() {
        val prompter = RecordingPrompter()
        val bridge = bridge(prompter)

        Thread {
            val challenge = prompter.awaitChallenge(EnteredCodeChallengeKind.GENERATED_CODE)
            assertTrue(bridge.acknowledgeGeneratedCode(challenge))
            // A second acknowledgement of the same prompt loses the compare-and-set.
            assertFalse(bridge.acknowledgeGeneratedCode(challenge))
        }.start()

        assertTrue(bridge.publishGeneratedCode(code("7KQF2XPD"), 60_000L))
        assertEquals(1, prompter.published.size)
        assertFalse(prompter.published.single().contains("7KQF2XPD"))
    }

    @Test(timeout = 20_000L)
    fun anEnteredCodeIsParsedAndReturned() {
        val prompter = RecordingPrompter()
        val bridge = bridge(prompter)

        Thread {
            val challenge = prompter.awaitChallenge(EnteredCodeChallengeKind.ENTERED_CODE)
            // Lower case and a hyphen: presentation, not a different code.
            assertTrue(bridge.submitEnteredCode(challenge, "7kqf-2xpd".toCharArray()))
        }.start()

        val entered = requireNotNull(bridge.requestEnteredCode(attemptOrdinal = 2, expiresAtElapsedMillis = 60_000L))
        assertTrue("the assembled code must be complete", entered.displayText().replace("-", "").length == 8)
        entered.clearSensitive()
    }

    @Test(timeout = 20_000L)
    fun aMalformedEntryDoesNotSettleThePrompt() {
        val prompter = RecordingPrompter()
        val bridge = bridge(prompter)

        Thread {
            val challenge = prompter.awaitChallenge(EnteredCodeChallengeKind.ENTERED_CODE)
            assertFalse(bridge.submitEnteredCode(challenge, "UUUUUUUU".toCharArray()))
            assertFalse(bridge.submitEnteredCode(challenge, CharArray(0)))
            // The prompt is still open, so the human can correct the typo.
            assertTrue(bridge.submitEnteredCode(challenge, "7KQF2XPD".toCharArray()))
        }.start()

        assertNotNull(bridge.requestEnteredCode(attemptOrdinal = 1, expiresAtElapsedMillis = 60_000L))
    }

    @Test(timeout = 20_000L)
    fun aChallengeOfTheWrongKindCannotBeAnswered() {
        val prompter = RecordingPrompter()
        val bridge = bridge(prompter)

        Thread {
            val challenge = prompter.awaitChallenge(EnteredCodeChallengeKind.ENTERED_CODE)
            // An approval answer must not satisfy a code prompt, or a screen could approve by typing.
            assertFalse(bridge.submitDecision(challenge, SecureApprovalDecision.APPROVE))
            assertFalse(bridge.acknowledgeGeneratedCode(challenge))
            assertTrue(bridge.submitEnteredCode(challenge, "7KQF2XPD".toCharArray()))
        }.start()

        assertNotNull(bridge.requestEnteredCode(attemptOrdinal = 1, expiresAtElapsedMillis = 60_000L))
    }

    @Test(timeout = 20_000L)
    fun aHandleFromAnotherBridgeIsRefused() {
        val otherPrompter = RecordingPrompter()
        val other = bridge(otherPrompter)
        val otherAnswer = java.util.concurrent.atomic.AtomicReference<EnteredPairingCode?>()
        val waiting = Thread {
            otherAnswer.set(other.requestEnteredCode(attemptOrdinal = 1, expiresAtElapsedMillis = 600_000L))
        }
        waiting.start()
        val foreign = otherPrompter.awaitChallenge(EnteredCodeChallengeKind.ENTERED_CODE)

        // A handle from another session cannot answer this bridge, whatever it is asked to do.
        val mine = bridge(RecordingPrompter())
        assertFalse(mine.submitEnteredCode(foreign, "7KQF2XPD".toCharArray()))
        assertFalse(mine.submitDecision(foreign, SecureApprovalDecision.APPROVE))

        other.close()
        waiting.join(10_000L)
        assertFalse(waiting.isAlive)
        assertNull(otherAnswer.get())
    }

    @Test(timeout = 20_000L)
    fun anApprovalReturnsTheRecordedDecisionAndNeverADefault() {
        val prompter = RecordingPrompter()
        val bridge = bridge(prompter)

        Thread {
            val challenge = prompter.awaitChallenge(EnteredCodeChallengeKind.INTENDED_PEER)
            assertTrue(bridge.submitDecision(challenge, SecureApprovalDecision.REJECT))
        }.start()

        val decision = bridge.requestIntendedPeerApproval(
            context = IntendedPeerContext(
                controlSessionId = "ctl-session",
                secureSessionId = sessionId,
                attemptOrdinal = 1,
                localRole = SecurePeerRole.RESPONDER,
                localPeerInstanceId = "00000000000000000000000000000002",
                remotePeerInstanceId = "00000000000000000000000000000001",
            ),
            expiresAtElapsedMillis = 60_000L,
        )
        assertEquals(SecureApprovalDecision.REJECT, decision)
    }

    @Test(timeout = 20_000L)
    fun anUnansweredApprovalExpiresToNull() {
        val prompter = RecordingPrompter()
        val bridge = bridge(prompter)

        val started = System.nanoTime()
        val decision = bridge.requestIntendedPeerApproval(context(), expiresAtElapsedMillis = 1_150L)
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertNull("silence is not approval", decision)
        assertTrue("the prompt must expire promptly, took ${elapsedMillis}ms", elapsedMillis < 5_000L)
    }

    @Test(timeout = 20_000L)
    fun closingResolvesThePendingPromptOnceAndRefusesLaterAnswers() {
        val prompter = RecordingPrompter()
        val bridge = bridge(prompter)
        val answer = java.util.concurrent.atomic.AtomicReference<EnteredPairingCode?>()
        val refusedAfterClose = java.util.concurrent.atomic.AtomicBoolean(false)

        val waiting = Thread {
            answer.set(bridge.requestEnteredCode(attemptOrdinal = 1, expiresAtElapsedMillis = 600_000L))
        }
        waiting.start()
        val challenge = prompter.awaitChallenge(EnteredCodeChallengeKind.ENTERED_CODE)

        bridge.close()
        bridge.close()
        waiting.join(10_000L)

        assertFalse("the waiting call must return, not hang", waiting.isAlive)
        assertNull("a shutdown is not an answer", answer.get())
        assertEquals("the prompter is told exactly once", 1, prompter.closeCalls.get())
        // A handle that survived the shutdown still cannot answer anything.
        refusedAfterClose.set(!bridge.submitEnteredCode(challenge, "7KQF2XPD".toCharArray()))
        assertTrue(refusedAfterClose.get())
    }

    @Test(timeout = 20_000L)
    fun aSecondPromptIsRefusedWhileOneIsOutstanding() {
        val prompter = RecordingPrompter()
        val bridge = bridge(prompter)

        val first = Thread {
            bridge.requestEnteredCode(attemptOrdinal = 1, expiresAtElapsedMillis = 3_000L)
        }
        first.start()
        // Wait until the first prompt is actually outstanding before asking again.
        prompter.awaitChallenge(EnteredCodeChallengeKind.ENTERED_CODE)
        assertNull(
            "one session must not carry two live prompts",
            bridge.requestIntendedPeerApproval(context(), expiresAtElapsedMillis = 3_000L),
        )
        first.join(10_000L)
        assertFalse(first.isAlive)
        assertEquals("a refused second prompt raises nothing", 1, prompter.prompts.get())
    }

    @Test(timeout = 20_000L)
    fun nothingInTheBridgeRendersACodeOrASessionId() {
        val prompter = RecordingPrompter()
        val bridge = bridge(prompter)
        val rendered = StringBuilder()
        rendered.append(bridge)
        Thread {
            val challenge = prompter.awaitChallenge(EnteredCodeChallengeKind.ENTERED_CODE)
            rendered.append(challenge)
            assertTrue(bridge.submitEnteredCode(challenge, "7KQF2XPD".toCharArray()))
        }.start()
        assertNotNull(bridge.requestEnteredCode(attemptOrdinal = 1, expiresAtElapsedMillis = 60_000L))

        val text = rendered.toString()
        assertFalse("the session id must not be rendered", text.contains(sessionId.value))
        assertFalse("a code must never be rendered", text.contains("7KQF2XPD"))
    }

    private fun bridge(prompter: EnteredCodePairingPrompter): EnteredCodePairingBridge =
        EnteredCodePairingBridge(
            sessionId = sessionId,
            localRole = SecurePeerRole.RESPONDER,
            clock = TestClock(startMillis = 1_000L),
            prompter = prompter,
        )

    private fun context(): IntendedPeerContext = IntendedPeerContext(
        controlSessionId = "ctl-session",
        secureSessionId = sessionId,
        attemptOrdinal = 1,
        localRole = SecurePeerRole.RESPONDER,
        localPeerInstanceId = "00000000000000000000000000000002",
        remotePeerInstanceId = "00000000000000000000000000000001",
    )

    private fun code(text: String): EnteredPairingCode = requireNotNull(EnteredPairingCode.parse(text.toCharArray()))

    /** Records what it was shown and exposes the handle for an answer thread to use. */
    private class RecordingPrompter : EnteredCodePairingPrompter {
        val published: MutableList<String> = CopyOnWriteArrayList()
        val closeCalls = AtomicInteger(0)
        val prompts = AtomicInteger(0)
        private val challenges = java.util.concurrent.LinkedBlockingQueue<EnteredCodeChallenge>()

        fun awaitChallenge(kind: EnteredCodeChallengeKind): EnteredCodeChallenge {
            while (true) {
                val challenge = challenges.poll(10, TimeUnit.SECONDS) ?: error("no prompt was raised")
                if (challenge.kind == kind) return challenge
            }
        }

        override fun onGeneratedCode(challenge: EnteredCodeChallenge, code: EnteredPairingCode) {
            published += code.toString()
            challenges.put(challenge)
        }

        override fun onEnteredCodeRequired(challenge: EnteredCodeChallenge, attemptOrdinal: Int) {
            prompts.incrementAndGet()
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
