/*
 * The entered-code exchange, driven end to end over two in-memory channels with real frames.
 *
 * Every case here runs the exchange as a caller sees it: construct, `run()`, inspect the typed
 * result. Nothing reaches into the exchange's state, and no round is faked: a case that needs the
 * peer to misbehave uses [ScriptedResponder], which performs the real rounds up to the point it is
 * told to deviate.
 *
 * These tests do not and cannot show that the construction is secure. See
 * doc/security/owner-risk-waiver-2026-10-19.md. Nothing here is authority.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.session.PakeApprovalDecision
import app.morsecode.core.transfer.session.PakeAttemptVerdict
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.transport.lan.security.SecurePairingRunResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class PairingAttemptExchangeTest {

    // --- the honest path ------------------------------------------------------------------

    @Test(timeout = 30_000L)
    public fun `an honest exchange confirms on both sides`() {
        val fixture = ExchangeFixture()
        val initiatorInteraction = TestInteraction(fixture.board)
        val responderInteraction = TestInteraction(fixture.board)

        val (fromInitiator, fromResponder) = runPair(
            fixture.initiator(initiatorInteraction),
            fixture.responder(responderInteraction),
        )

        val initiatorResult = confirmed(fromInitiator)
        val responderResult = confirmed(fromResponder)
        assertEquals(
            "both sides must agree on the attempt",
            initiatorResult.attemptId,
            responderResult.attemptId,
        )
        assertEquals(1, initiatorResult.attemptOrdinal)
        assertEquals(PakeApprovalDecision.APPROVE, initiatorResult.peerDecision)
        assertEquals(PakeApprovalDecision.APPROVE, responderResult.peerDecision)
        assertEquals(
            "the initiator must have validated the responder's approval",
            SecurePeerRole.RESPONDER,
            initiatorResult.peerApproval.role,
        )
        assertEquals(
            "the responder must have validated the initiator's approval",
            SecurePeerRole.INITIATOR,
            responderResult.peerApproval.role,
        )
        assertTrue("the initiator's session must be confirmed", initiatorResult.session.isConfirmed())
        assertTrue("the responder's session must be confirmed", responderResult.session.isConfirmed())
        assertEquals("the responder is asked for the code once", listOf(1), responderInteraction.enteredOrdinals)
    }

    @Test(timeout = 30_000L)
    public fun `a retry confirms with a fresh attempt and a fresh session`() {
        val fixture = ExchangeFixture()
        val responderInteraction = TestInteraction(fixture.board).apply {
            enteredCodeFor = { ordinal, published ->
                if (ordinal == 1) wrongCode(published) else published
            }
        }

        val (fromInitiator, fromResponder) = runPair(
            fixture.initiator(TestInteraction(fixture.board)),
            fixture.responder(responderInteraction),
        )

        val initiatorResult = confirmed(fromInitiator)
        val responderResult = confirmed(fromResponder)
        assertEquals("the second attempt is the one that confirms", 2, initiatorResult.attemptOrdinal)
        assertEquals(2, responderResult.attemptOrdinal)
        assertEquals(initiatorResult.attemptId, responderResult.attemptId)
        assertEquals(
            "the human must be asked again after typing the code wrong",
            listOf(1, 2),
            responderInteraction.enteredOrdinals,
        )
    }

    // --- budget and failure handshake ------------------------------------------------------

    @Test(timeout = 30_000L)
    public fun `three failed attempts end the pairing on both sides`() {
        val fixture = ExchangeFixture()
        val responderInteraction = TestInteraction(fixture.board).apply {
            enteredCodeFor = { _, published -> wrongCode(published) }
        }

        val (fromInitiator, fromResponder) = runPair(
            fixture.initiator(TestInteraction(fixture.board)),
            fixture.responder(responderInteraction),
        )

        assertEquals(PairingExchangeFailure.ATTEMPTS_EXHAUSTED, failed(fromInitiator).reason)
        assertEquals(PairingExchangeFailure.ATTEMPTS_EXHAUSTED, failed(fromResponder).reason)
        assertEquals(3, failed(fromInitiator).attemptsCharged)
        assertEquals(3, failed(fromResponder).attemptsCharged)
        assertEquals(
            "the budget is three attempts, not four",
            listOf(1, 2, 3),
            responderInteraction.enteredOrdinals,
        )
    }

    @Test(timeout = 30_000L)
    public fun `a failure record is accepted where each round belongs`() {
        for (round in 1..3) {
            val fixture = ExchangeFixture()
            val exchange = fixture.initiator(TestInteraction(fixture.board))
            val scripted = ScriptedResponder(
                fixture.responderIo,
                fixture,
                ScriptedBehaviour.FailAtRound(round),
            )

            val result = runAgainstScripted(exchange, { scripted.run() })

            val failure = failed(result)
            assertEquals(
                "round $round: a peer failure must be charged as one attempt, not refused",
                1,
                failure.attemptsCharged,
            )
            assertEquals(
                "round $round: a retry that finds the peer gone is a lost connection",
                PairingExchangeFailure.CONNECTION_LOST,
                failure.reason,
            )
        }
    }

    @Test(timeout = 30_000L)
    public fun `a duplicate failure record is refused rather than counted twice`() {
        val fixture = ExchangeFixture()
        val exchange = fixture.initiator(TestInteraction(fixture.board), ExchangeFixture.SHORT)
        val scripted = ScriptedResponder(
            fixture.responderIo,
            fixture,
            ScriptedBehaviour.DuplicateFailureAtRound(2),
        )

        val result = failed(runAgainstScripted(exchange, { scripted.run() }))

        assertEquals(
            "the second record names an attempt that is no longer current",
            PairingExchangeFailure.PROTOCOL,
            result.reason,
        )
        assertEquals(1, result.attemptsCharged)
    }

    @Test(timeout = 30_000L)
    public fun `a peer that refuses to retry ends the pairing even though this side would`() {
        val fixture = ExchangeFixture()
        val exchange = fixture.initiator(TestInteraction(fixture.board))
        // This side is still under its budget and has not been cancelled, so its own verdict is
        // retry; the peer's verdict is abort, and one abort is enough.
        val scripted = ScriptedResponder(
            fixture.responderIo,
            fixture,
            ScriptedBehaviour.FailAtRound(2, PakeAttemptVerdict.ABORT),
        )

        val result = failed(runAgainstScripted(exchange, { scripted.run() }))

        assertEquals(PairingExchangeFailure.ABORTED_BY_PEER, result.reason)
        assertEquals(1, result.attemptsCharged)
    }

    @Test(timeout = 30_000L)
    public fun `a stale round frame from the previous attempt is discarded by identity`() {
        val fixture = ExchangeFixture()
        val exchange = fixture.initiator(TestInteraction(fixture.board))
        val scripted = ScriptedResponder(fixture.responderIo, fixture, ScriptedBehaviour.StaleThenHonestOnRetry)

        val result = confirmed(runAgainstScripted(exchange, { scripted.run() }))

        assertEquals(
            "the retry must confirm even though the abandoned attempt's frame arrived first",
            2,
            result.attemptOrdinal,
        )
        assertTrue(result.session.isConfirmed())
    }

    // --- attempt identity ------------------------------------------------------------------

    @Test(timeout = 30_000L)
    public fun `an attempt ordinal that skips ahead is refused`() {
        val fixture = ExchangeFixture()
        fixture.board.publish("7KQF2XPD")
        val exchange = fixture.responder(TestInteraction(fixture.board), ExchangeFixture.SHORT)
        val scripted = ScriptedInitiator(fixture.initiatorIo, fixture)

        val result = failed(
            runAgainstScripted(
                exchange,
                { scripted.send(scripted.attemptStart(scripted.attemptId(0x51), 2)) },
            ),
        )

        assertEquals(
            "the ledger expects ordinal one and the peer presented two",
            PairingExchangeFailure.PROTOCOL,
            result.reason,
        )
        assertEquals("no attempt was charged", 0, result.attemptsCharged)
    }

    @Test(timeout = 30_000L)
    public fun `a reused attempt identifier is refused on the second presentation`() {
        val fixture = ExchangeFixture()
        fixture.board.publish("7KQF2XPD")
        val exchange = fixture.responder(TestInteraction(fixture.board), ExchangeFixture.SHORT)
        val scripted = ScriptedInitiator(fixture.initiatorIo, fixture)

        val result = failed(
            runAgainstScripted(
                exchange,
                {
                    val id = scripted.attemptId(0x61)
                    scripted.send(scripted.attemptStart(id, 1))
                    scripted.failure(id)
                    scripted.readVerdict()
                    scripted.send(scripted.attemptStart(id, 1))
                },
            ),
        )

        assertEquals(
            "an identifier is spent once, however the attempt ended",
            PairingExchangeFailure.PROTOCOL,
            result.reason,
        )
        assertEquals(1, result.attemptsCharged)
    }

    // --- cancellation, deadlines, connection loss ------------------------------------------

    @Test(timeout = 30_000L)
    public fun `a cancellation during a round is not charged to the peer`() {
        val fixture = ExchangeFixture()
        val interaction = TestInteraction(fixture.board)
        interaction.onPublish = {
            val canceller = Thread {
                Thread.sleep(300L)
                interaction.cancelled = true
            }
            canceller.isDaemon = true
            canceller.start()
        }
        // The peer goes quiet after round one, so the exchange is certainly parked in a read when
        // the cancellation lands. Timing against a peer that might finish first would make this
        // test measure how fast the machine is.
        val peer = ScriptedResponder(fixture.responderIo, fixture, ScriptedBehaviour.SilentAtRound(2))

        val result = failed(
            runAgainstScripted(
                exchange = fixture.initiator(interaction, ExchangeFixture.SHORT),
                scripted = { peer.run() },
            ),
        )

        assertEquals(
            "a cancellation is a typed outcome, not a deadline",
            PairingExchangeFailure.CANCELLED,
            result.reason,
        )
        assertEquals("a local stop never spends the budget", 0, result.attemptsCharged)
    }

    @Test(timeout = 30_000L)
    public fun `silence where round three belongs is a deadline`() {
        val fixture = ExchangeFixture()
        val exchange = fixture.initiator(TestInteraction(fixture.board), ExchangeFixture.SHORT)
        val scripted = ScriptedResponder(fixture.responderIo, fixture, ScriptedBehaviour.SilentAtRound(3))

        val result = failed(runAgainstScripted(exchange, { scripted.run() }))

        assertEquals(PairingExchangeFailure.TIMEOUT, result.reason)
        assertEquals("a deadline is not a failed proof", 0, result.attemptsCharged)
    }

    @Test(timeout = 30_000L)
    public fun `a closed connection is reported as a lost connection`() {
        val fixture = ExchangeFixture()
        val exchange = fixture.initiator(TestInteraction(fixture.board))
        val scripted = ScriptedResponder(fixture.responderIo, fixture, ScriptedBehaviour.CloseAtRound(2))

        val result = failed(runAgainstScripted(exchange, { scripted.run() }))

        assertEquals(PairingExchangeFailure.CONNECTION_LOST, result.reason)
        assertEquals(0, result.attemptsCharged)
    }

    // --- approvals -------------------------------------------------------------------------

    @Test(timeout = 30_000L)
    public fun `a local rejection is sent and ends the pairing`() {
        val fixture = ExchangeFixture()
        val initiatorInteraction = TestInteraction(fixture.board).apply {
            approval = SecureApprovalDecision.REJECT
        }

        val (fromInitiator, fromResponder) = runPair(
            fixture.initiator(initiatorInteraction),
            fixture.responder(TestInteraction(fixture.board)),
        )

        assertEquals(PairingExchangeFailure.APPROVAL_REJECTED, failed(fromInitiator).reason)
        assertEquals(
            "the peer must learn it was refused rather than wait",
            PairingExchangeFailure.APPROVAL_REJECTED,
            failed(fromResponder).reason,
        )
    }

    @Test(timeout = 30_000L)
    public fun `a remote rejection is reported to the peer that asked`() {
        val fixture = ExchangeFixture()
        val responderInteraction = TestInteraction(fixture.board).apply {
            approval = SecureApprovalDecision.REJECT
        }

        val (fromInitiator, fromResponder) = runPair(
            fixture.initiator(TestInteraction(fixture.board)),
            fixture.responder(responderInteraction),
        )

        assertEquals(PairingExchangeFailure.APPROVAL_REJECTED, failed(fromInitiator).reason)
        assertEquals(PairingExchangeFailure.APPROVAL_REJECTED, failed(fromResponder).reason)
    }

    @Test(timeout = 30_000L)
    public fun `an unanswered approval is never an approval`() {
        val fixture = ExchangeFixture()
        val initiatorInteraction = TestInteraction(fixture.board).apply {
            approval = null
        }

        val (fromInitiator, fromResponder) = runPair(
            fixture.initiator(initiatorInteraction, ExchangeFixture.SHORT),
            fixture.responder(TestInteraction(fixture.board), ExchangeFixture.SHORT),
        )

        assertEquals(PairingExchangeFailure.APPROVAL_TIMEOUT, failed(fromInitiator).reason)
        assertTrue("the peer must not confirm either", fromResponder is PairingExchangeResult.Failed)
        assertEquals(0, failed(fromResponder).attemptsCharged)
    }

    @Test(timeout = 30_000L)
    public fun `an approval reflected back is refused`() {
        val fixture = ExchangeFixture()
        val exchange = fixture.initiator(TestInteraction(fixture.board))
        val scripted = ScriptedResponder(fixture.responderIo, fixture, ScriptedBehaviour.EchoApproval)

        val result = failed(runAgainstScripted(exchange, { scripted.run() }))

        assertEquals(
            "a record naming the sender's own role cannot satisfy the remote decision",
            PairingExchangeFailure.PROTOCOL,
            result.reason,
        )
    }

    // --- lifecycle -------------------------------------------------------------------------

    @Test(timeout = 30_000L)
    public fun `a confirmed pairing closes its channel exactly once`() {
        val fixture = ExchangeFixture()
        val (fromInitiator, fromResponder) = runPair(
            fixture.initiator(TestInteraction(fixture.board)),
            fixture.responder(TestInteraction(fixture.board)),
        )
        confirmed(fromInitiator)
        confirmed(fromResponder)
        assertEquals(1, fixture.initiatorIo.closes)
        assertEquals(1, fixture.responderIo.closes)
    }

    @Test(timeout = 30_000L)
    public fun `a refused pairing releases everything once`() {
        val fixture = ExchangeFixture()
        val interaction = TestInteraction(fixture.board).apply {
            publishAccepted = false
        }

        val result = fixture.initiator(interaction).run()

        assertEquals(PairingExchangeFailure.CANCELLED, failed(result).reason)
        assertEquals("closing must be idempotent and complete", 1, fixture.initiatorIo.closes)
    }

    @Test(timeout = 30_000L)
    public fun `the result carries no authority`() {
        val confirmedType = PairingExchangeResult.Confirmed::class.java
        val suspicious = confirmedType.declaredMethods.map { it.returnType.name }.filter { name ->
            name.contains("Authority") || name.contains("Payload") ||
                name.contains("RecordLayer") || name.contains("RunResult")
        }
        assertTrue(
            "a confirmed exchange must not hand out any of these: $suspicious",
            suspicious.isEmpty(),
        )
        assertFalse(
            "a confirmed exchange is not a pairing run result",
            SecurePairingRunResult::class.java.isAssignableFrom(confirmedType),
        )
        assertFalse(
            "and it is certainly not the authenticated case",
            SecurePairingRunResult.Authenticated::class.java.isAssignableFrom(confirmedType),
        )
        assertNotEquals(
            "the two result kinds must not be interchangeable",
            PairingExchangeResult.Confirmed::class.java,
            PairingExchangeResult.Failed::class.java,
        )
    }
}
