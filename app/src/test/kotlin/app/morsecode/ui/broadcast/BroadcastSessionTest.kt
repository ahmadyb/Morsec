package app.morsecode.ui.broadcast

import app.morsecode.core.model.Peer
import app.morsecode.ui.transfer.TransferAllLabel
import app.morsecode.ui.transfer.TransferDirection
import app.morsecode.ui.transfer.TransferRowAction
import app.morsecode.ui.transfer.TransferState
import app.morsecode.ui.transfer.VerificationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The broadcast model: the arithmetic, the transitions and the invariants a fan-out has to
 * hold.
 *
 * These are value tests rather than screen tests, because the failures this milestone exists
 * to prevent are all arithmetic and identity failures: a batch counted twice, a byte total
 * multiplied by a phone that was listed twice, a combined speed that includes a phone that is
 * held, a completion claimed while a delivery is still queued, and one phone's trouble showing
 * up on another phone's rows.
 *
 * Nothing here starts a coroutine or waits for a clock: there is no engine, and every function
 * under test is pure.
 */
class BroadcastSessionTest {

    private val ravi: Peer = BroadcastFixtures.ravi
    private val pixel: Peer = BroadcastFixtures.pixel
    private val samsung: Peer = BroadcastFixtures.samsung
    private val batch: List<BroadcastFile> = BroadcastFixtures.batch

    // The fixture files are the reference's three; their ids are what a delivery key holds,
    // so the tests name them through the fixture rather than by their file names.
    private val holiday: String = BroadcastFixtures.holiday.id
    private val photo: String = BroadcastFixtures.photo.id
    private val live: String = BroadcastFixtures.live.id

    // ---------------------------------------------------------------- the batch

    @Test
    fun `batch size is the files, counted once each`() {
        assertEquals(212_100_000L, BroadcastMath.batchBytes(batch))
    }

    @Test
    fun `a repeated file id is one file and is not counted twice`() {
        val repeated = listOf(file("a", 100L), file("a", 100L), file("b", 40L))

        assertEquals(2, BroadcastMath.distinctFiles(repeated).size)
        assertEquals("the batch is 140 bytes, not 240", 140L, BroadcastMath.batchBytes(repeated))
    }

    @Test
    fun `an empty batch has no size`() {
        assertEquals(0L, BroadcastMath.batchBytes(emptyList()))
    }

    @Test
    fun `a negative file size is clamped rather than subtracted`() {
        assertEquals(0L, file("a", -5L).sizeBytes)
        assertEquals(10L, BroadcastMath.batchBytes(listOf(file("a", -5L), file("b", 10L))))
    }

    @Test
    fun `a zero byte file is still a file`() {
        assertEquals(0L, BroadcastMath.batchBytes(listOf(file("empty", 0L))))
        assertEquals(1, BroadcastMath.distinctFiles(listOf(file("empty", 0L))).size)
    }

    // ---------------------------------------------------------------- recipients and totals

    @Test
    fun `total bytes to send is the batch once per phone`() {
        assertEquals(636_300_000L, BroadcastMath.totalBytesToSend(212_100_000L, 3))
        assertEquals(424_200_000L, BroadcastMath.totalBytesToSend(212_100_000L, 2))
    }

    @Test
    fun `a total that would overflow is clamped, not wrapped into a negative`() {
        val huge = BroadcastMath.totalBytesToSend(Long.MAX_VALUE, 2)

        assertEquals(Long.MAX_VALUE, huge)
        assertTrue("a huge total must never read as nothing to send", huge > 0L)
    }

    @Test
    fun `zero recipients mean nothing to send`() {
        assertEquals(0L, BroadcastMath.totalBytesToSend(1_000L, 0))
        assertEquals(0L, BroadcastMath.totalBytesToSend(0L, 5))
        assertEquals(0L, BroadcastMath.totalBytesToSend(-1_000L, -3))
    }

    @Test
    fun `a repeated recipient id is one phone`() {
        val twice = listOf(ravi, ravi, pixel)

        assertEquals(listOf("r", "p"), BroadcastMath.distinctRecipients(twice).map { it.peerId })
    }

    @Test
    fun `a duplicated recipient cannot inflate the bytes a batch owes`() {
        val once = sessions(listOf(ravi, pixel))
        val twice = sessions(listOf(ravi, ravi, pixel))

        assertEquals(once.uniqueRecipients.size, twice.uniqueRecipients.size)
        assertEquals(once.bytesToSend, twice.bytesToSend)
    }

    @Test
    fun `a duplicated file cannot inflate the bytes a batch owes`() {
        val once = BroadcastSession(
            id = "t",
            sender = BroadcastFixtures.self,
            files = listOf(file("a", 100L)),
            recipients = listOf(ravi, pixel),
        )
        val twice = once.copy(files = listOf(file("a", 100L), file("a", 100L)))

        assertEquals(200L, once.bytesToSend)
        assertEquals("two phones, one file, one hundred bytes each", 200L, twice.bytesToSend)
    }

    @Test
    fun `a device a broadcast may not address is not a recipient`() {
        val withLaptop = listOf(ravi, BroadcastFixtures.laptop)

        assertEquals(listOf("r"), BroadcastMath.distinctRecipients(withLaptop).map { it.peerId })
        assertFalse("a browser or laptop is not a phone that installs the app", BroadcastFixtures.laptop.isBroadcastEligible)
    }

    // ---------------------------------------------------------------- starting

    @Test
    fun `a broadcast starts with two phones and a batch, every delivery queued`() {
        val started = BroadcastSession.start(
            id = "t",
            sender = BroadcastFixtures.self,
            files = batch,
            recipients = listOf(ravi, pixel),
        )

        assertNotNull(started)
        assertEquals(listOf("r", "p"), started!!.uniqueRecipients.map { it.peerId })
        assertEquals(3, started.uniqueFiles.size)
        assertEquals("three files to two phones is six deliveries", 6, started.allDeliveries.size)
        assertTrue(started.allDeliveries.all { it.state == TransferState.QUEUED })
        assertFalse("nothing has happened, so it is not complete", started.result.complete)
    }

    @Test
    fun `zero phones, one phone and an empty batch cannot start a broadcast`() {
        assertNull("no phones is not a broadcast", start(recipients = emptyList()))
        assertNull("one phone is a send, not a broadcast", start(recipients = listOf(ravi)))
        assertNull(
            "a laptop does not make a second recipient",
            start(recipients = listOf(ravi, BroadcastFixtures.laptop)),
        )
        assertNull("a batch with nothing in it is not a batch", start(files = emptyList()))
    }

    @Test
    fun `a selection of phones can be asked whether it could start, before anything exists`() {
        assertFalse(BroadcastSession.canStart(emptyList()))
        assertFalse(BroadcastSession.canStart(listOf(ravi)))
        assertFalse(BroadcastSession.canStart(listOf(ravi, BroadcastFixtures.laptop)))
        assertTrue(BroadcastSession.canStart(listOf(ravi, pixel)))
    }

    // ---------------------------------------------------------------- one phone's trouble

    @Test
    fun `cancelling one phone's delivery leaves every other delivery object untouched`() {
        val session = mixed()
        val sick = DeliveryKey("s", holiday)
        val healthy = listOf(
            DeliveryKey("r", holiday),
            DeliveryKey("p", holiday),
            DeliveryKey("s", photo),
        )

        val after = BroadcastRules.apply(session, sick, TransferRowAction.CANCEL)

        assertEquals(TransferState.CANCELLED, after.delivery(sick)!!.state)
        healthy.forEach { key ->
            assertSame("$key must keep its identity, not merely its value", session.delivery(key), after.delivery(key))
        }
    }

    @Test
    fun `a failed phone does not fail the batch for the phones that are fine`() {
        val session = mixed()
        val after = BroadcastRules.apply(session, DeliveryKey("p", holiday), TransferRowAction.CANCEL)

        assertEquals("the cancelled delivery is counted once", 1, after.result.cancelled)
        assertEquals("the batch is not complete while other files still run", false, after.result.complete)
        assertEquals(
            "Ravi's delivered file is still delivered",
            TransferState.DONE,
            after.delivery(DeliveryKey("r", holiday))!!.state,
        )
        assertEquals(
            "and the same delivery that failed for one phone is still fine for the other",
            TransferState.DONE,
            after.delivery(DeliveryKey("r", photo))!!.state,
        )
    }

    @Test
    fun `two phones can be at different points in the same file, with their own percentages`() {
        val session = BroadcastSession(
            id = "t",
            sender = BroadcastFixtures.self,
            files = listOf(file("a", 100_000_000L)),
            recipients = listOf(ravi, pixel),
            deliveries = listOf(
                delivery(
                    "r", "a", TransferState.SENDING,
                    transferred = 25_000_000L, speed = 1_000_000L, totalBytes = 100_000_000L,
                ),
                delivery("p", "a", TransferState.QUEUED, totalBytes = 100_000_000L),
            ).associateBy { it.key },
        )

        val file = session.deliveriesFor("a")
        assertEquals(0.25f, file.first().fraction, 0.0001f)
        assertEquals(0f, file.last().fraction, 0.0001f)
        assertEquals("one file, two independent positions", listOf(0.25f, 0f), file.map { it.fraction })
    }

    @Test
    fun `updating one phone's delivery does not disturb the other deliveries of the same file`() {
        val session = mixed()
        val before = session.deliveriesFor(holiday).associateBy { it.recipientId }

        val after = BroadcastRules.apply(session, DeliveryKey("p", holiday), TransferRowAction.PAUSE)

        assertEquals(TransferState.PAUSED, after.delivery(DeliveryKey("p", holiday))!!.state)
        assertEquals(0L, after.delivery(DeliveryKey("p", holiday))!!.speedBytesPerSecond)
        assertSame(before.getValue("r"), after.delivery(DeliveryKey("r", holiday)))
        assertSame(before.getValue("s"), after.delivery(DeliveryKey("s", holiday)))
    }

    @Test
    fun `updating one file's delivery does not disturb that phone's other files`() {
        val session = mixed()
        val before = session.deliveriesTo("r").associateBy { it.fileId }

        val after = BroadcastRules.apply(session, DeliveryKey("r", live), TransferRowAction.CANCEL)

        assertEquals(TransferState.CANCELLED, after.delivery(DeliveryKey("r", live))!!.state)
        assertSame(before.getValue(holiday), after.delivery(DeliveryKey("r", holiday)))
        assertSame(before.getValue(photo), after.delivery(DeliveryKey("r", photo)))
    }

    @Test
    fun `holding one phone leaves the others moving and lowers only the combined speed`() {
        val session = BroadcastSession(
            id = "speeds",
            sender = BroadcastFixtures.self,
            files = listOf(file("a", 100_000_000L)),
            recipients = listOf(ravi, pixel),
            deliveries = listOf(
                delivery("r", "a", TransferState.SENDING, transferred = 10_000_000L, speed = 4_800_000L, totalBytes = 100_000_000L),
                delivery("p", "a", TransferState.SENDING, transferred = 10_000_000L, speed = 5_400_000L, totalBytes = 100_000_000L),
            ).associateBy { it.key },
        )
        assertEquals("two phones moving at once add up", 10_200_000L, session.result.combinedThroughput)

        val after = BroadcastRules.apply(session, DeliveryKey("p", "a"), TransferRowAction.PAUSE)

        assertSame("the phone that is fine is not replaced", session.delivery(DeliveryKey("r", "a")), after.delivery(DeliveryKey("r", "a")))
        assertEquals("and it keeps its speed", 4_800_000L, after.delivery(DeliveryKey("r", "a"))!!.speedBytesPerSecond)
        assertEquals("only what still moves is counted", 4_800_000L, after.result.combinedThroughput)
    }

    @Test
    fun `resuming one phone returns it to the state its direction calls moving, at its offset`() {
        val held = mixed()
        val key = DeliveryKey("s", holiday)
        assertEquals(TransferState.PAUSED, held.delivery(key)!!.state)

        val resumed = BroadcastRules.apply(held, key, TransferRowAction.RESUME)

        assertEquals(TransferState.SENDING, resumed.delivery(key)!!.state)
        assertEquals("the bytes it had are kept", 83_500_000L, resumed.delivery(key)!!.transferred)
        assertEquals(
            "and no other phone was touched",
            TransferState.SENDING,
            resumed.delivery(DeliveryKey("p", holiday))!!.state,
        )
    }

    @Test
    fun `a paused delivery that is resumed in the receiving direction receives again`() {
        val incoming = mixed().copy(
            deliveries = mixed().deliveries + (
                DeliveryKey("r", holiday) to
                    delivery("r", holiday, TransferState.PAUSED, transferred = 83_500_000L, incoming = true)
                ),
        )

        val resumed = BroadcastRules.apply(incoming, DeliveryKey("r", holiday), TransferRowAction.RESUME)

        assertEquals(TransferState.RECEIVING, resumed.delivery(DeliveryKey("r", holiday))!!.state)
    }

    @Test
    fun `a verifying delivery is not holdable, not cancellable and not retryable`() {
        val verifying = BroadcastSession(
            id = "t",
            sender = BroadcastFixtures.self,
            files = listOf(file("a", 100L)),
            recipients = listOf(ravi, pixel),
            deliveries = listOf(
                delivery("r", "a", TransferState.VERIFYING, transferred = 100L),
                delivery("p", "a", TransferState.VERIFYING, transferred = 100L),
            ).associateBy { it.key },
        )

        assertEquals(verifying, BroadcastRules.apply(verifying, DeliveryKey("r", "a"), TransferRowAction.PAUSE))
        assertEquals(verifying, BroadcastRules.apply(verifying, DeliveryKey("r", "a"), TransferRowAction.RETRY))
        assertEquals(
            "the model has no way to cancel a verification",
            verifying,
            BroadcastRules.apply(verifying, DeliveryKey("r", "a"), TransferRowAction.CANCEL),
        )
        assertTrue("a verifying delivery is still unfinished", verifying.result.pending > 0)
        assertFalse(verifying.result.complete)
    }

    @Test
    fun `a failed delivery is retried from the beginning with a clean checksum answer`() {
        val session = mixed()
        val key = DeliveryKey("s", photo)

        assertEquals(TransferState.FAILED, session.delivery(key)!!.state)

        val retried = BroadcastRules.apply(session, key, TransferRowAction.RETRY)

        assertEquals(
            "an outgoing retry re-queues the file from the beginning, for the engine to run",
            TransferState.QUEUED,
            retried.delivery(key)!!.state,
        )
        assertEquals(0L, retried.delivery(key)!!.transferred)
        assertEquals("and the old checksum answer is cleared", VerificationOutcome.PENDING, retried.delivery(key)!!.verification)
        assertEquals(
            "the retry moves nothing on its own, and nobody else was retried with it",
            session.result.active,
            retried.result.active,
        )
        assertEquals(
            "every other delivery is the same object it was",
            session.allDeliveries.filter { it.key != key },
            retried.allDeliveries.filter { it.key != key },
        )
    }

    // ---------------------------------------------------------------- the whole batch

    @Test
    fun `pausing the batch holds everything and resuming lets it all go again`() {
        val session = mixed()

        val paused = BroadcastRules.pauseAll(session)
        assertTrue("nothing is moving any more", paused.allDeliveries.none { it.state.isActive })
        assertTrue(paused.allDeliveries.any { it.state == TransferState.PAUSED })

        val resumed = BroadcastRules.resumeAll(paused)
        assertTrue("and nothing is held any more", resumed.allDeliveries.none { it.state == TransferState.PAUSED })
        assertEquals(
            "a delivered file stays delivered",
            TransferState.DONE,
            resumed.delivery(DeliveryKey("r", holiday))!!.state,
        )
        assertEquals(
            "a skipped file stays skipped until someone retries it",
            TransferState.SKIPPED,
            resumed.delivery(DeliveryKey("s", live))!!.state,
        )
    }

    @Test
    fun `pausing one phone's share leaves the other phones identical`() {
        val session = mixed()
        val others = session.allDeliveries.filter { it.recipientId != "p" }

        val after = BroadcastRules.pauseAll(session, recipientId = "p")

        others.forEach { delivery -> assertSame("${delivery.key}", delivery, after.delivery(delivery.key)) }
        assertTrue(after.deliveriesTo("p").none { it.state.isActive })
    }

    @Test
    fun `the whole-batch control holds everything, or lets everything go`() {
        val session = mixed()

        assertTrue(session.allAction.enabled)
        val paused = BroadcastRules.toggleAll(session)
        assertTrue(paused.allDeliveries.none { it.state.isActive })
        assertTrue(paused.allAction.enabled)
        assertEquals("everything held means the control offers to resume", TransferAllLabel.RESUME_ALL, paused.allAction.label)
    }

    @Test
    fun `one phone's whole-batch control answers for that phone alone`() {
        val session = mixed()

        // Samsung has a held delivery and nothing moving, so its own control offers to resume.
        assertEquals(TransferAllLabel.RESUME_ALL, session.allActionFor("s").label)
        assertTrue(session.allActionFor("s").enabled)

        val resumed = BroadcastRules.toggleAll(session, recipientId = "s")
        assertTrue(resumed.deliveriesTo("s").none { it.state == TransferState.PAUSED })
        assertEquals(
            "and nobody else was moved",
            TransferState.SENDING,
            resumed.delivery(DeliveryKey("p", holiday))!!.state,
        )
    }

    @Test
    fun `a batch where nothing can be held offers a disabled control rather than a wrong one`() {
        val session = mixed().copy(
            deliveries = mixed().allDeliveries.map { it.copy(state = TransferState.DONE) }.associateBy { it.key },
        )

        assertFalse(session.allAction.enabled)
        assertEquals(TransferAllLabel.PAUSE_ALL, session.allAction.label)
    }

    @Test
    fun `ending a broadcast stops what is unfinished and keeps what finished`() {
        val session = mixed()

        val after = BroadcastRules.end(session)

        assertEquals("nothing is left to run", 0, after.result.pending)
        assertTrue(after.result.complete)
        assertEquals("the delivered files stay delivered", TransferState.DONE, after.delivery(DeliveryKey("r", holiday))!!.state)
        assertEquals(
            "a held file ends cancelled, keeping where it stopped",
            TransferState.CANCELLED,
            after.delivery(DeliveryKey("s", holiday))!!.state,
        )
        assertEquals("83.5 MB", 83_500_000L, after.delivery(DeliveryKey("s", holiday))!!.transferred)
    }

    @Test
    fun `ending one phone's session leaves the batch open for the others`() {
        val session = mixed()

        val after = BroadcastRules.end(session, recipientId = "p")

        assertTrue("that phone is finished", after.resultFor("p").complete)
        assertFalse("the batch as a whole is not", after.result.complete)
        assertEquals(
            "every other phone's deliveries are exactly as they were",
            session.allDeliveries.filterKeys { it.recipientId != "p" },
            after.allDeliveries.filterKeys { it.recipientId != "p" },
        )
        assertTrue(
            "and the others still have work in hand",
            after.allDeliveries.any { it.recipientId != "p" && it.state != TransferState.DONE },
        )
    }

    // ---------------------------------------------------------------- clearing

    @Test
    fun `clearing removes the deliveries that arrived and keeps everything else`() {
        val session = mixed()

        val after = BroadcastRules.clearCompleted(session)

        assertTrue(after.deliveries.values.none { it.state == TransferState.DONE })
        assertEquals(
            "a failed delivery stays, because there is still something to do about it",
            TransferState.FAILED,
            after.delivery(DeliveryKey("s", photo))!!.state,
        )
        assertEquals(
            "a skipped delivery stays too",
            TransferState.SKIPPED,
            after.delivery(DeliveryKey("s", live))!!.state,
        )
        assertEquals("the batch is unchanged", session.batchBytes, after.batchBytes)
        assertEquals("the phones are unchanged", session.uniqueRecipients.size, after.uniqueRecipients.size)
    }

    @Test
    fun `clearing one phone clears that phone alone`() {
        val session = mixed()

        val after = BroadcastRules.clearCompleted(session, recipientId = "p")

        assertTrue(after.deliveriesTo("p").none { it.state == TransferState.DONE })
        assertTrue(
            "another phone's delivered rows are still there",
            after.deliveriesTo("r").any { it.state == TransferState.DONE },
        )
    }

    // ---------------------------------------------------------------- completion

    @Test
    fun `completion needs every expected delivery to be terminal`() {
        val running = mixed()
        assertFalse(running.result.complete)

        val finished = BroadcastFixtures.completedSession()
        assertTrue(finished.result.complete)
    }

    @Test
    fun `an empty broadcast is never complete`() {
        val empty = BroadcastSession(id = "x", sender = BroadcastFixtures.self)

        assertEquals(0, empty.result.expected)
        assertFalse("there is nothing to have finished", empty.result.complete)
        assertFalse(empty.result.fullySuccessful)
    }

    @Test
    fun `full success needs every delivery verified, not merely delivered`() {
        val allDone = BroadcastFixtures.completedSession()
        assertTrue(allDone.result.fullySuccessful)

        val unchecked = allDone.withDeliveries(
            allDone.allDeliveries.map { it.copy(verification = VerificationOutcome.PENDING) },
        )

        assertTrue("it is complete", unchecked.result.complete)
        assertFalse("but an unchecked delivery is not a success", unchecked.result.fullySuccessful)
    }

    @Test
    fun `a checksum that did not match is a problem, not a delivery`() {
        val session = BroadcastFixtures.completedSession()
        val mismatched = session.withDelivery(
            session.delivery(DeliveryKey("p", photo))!!.copy(verification = VerificationOutcome.MISMATCH),
        )

        assertEquals(1, mismatched.result.mismatched)
        assertEquals(1, mismatched.result.problems)
        assertEquals("it still arrived, so it is delivered", 9, mismatched.result.delivered)
        assertFalse("but the batch may not claim full success", mismatched.result.fullySuccessful)
    }

    @Test
    fun `a partial broadcast is complete without being successful`() {
        val partial = BroadcastFixtures.partialSession()
        val result = partial.result

        assertTrue("nothing is still running", result.complete)
        assertFalse("one delivery failed and one was skipped", result.fullySuccessful)
        assertEquals(1, result.failed)
        assertEquals(1, result.skipped)
        assertEquals(2, result.problems)
        assertEquals(7, result.delivered)
    }

    // ---------------------------------------------------------------- counts

    @Test
    fun `the aggregate counts come from the deliveries and add up`() {
        val result = mixed().result

        assertEquals(9, result.expected)
        assertEquals(3, result.delivered)
        assertEquals(3, result.verified)
        assertEquals(1, result.failed)
        assertEquals(1, result.skipped)
        assertEquals(1, result.paused)
        assertEquals(2, result.active)
        assertEquals(1, result.queued)
        assertEquals(
            "every delivery is in exactly one bucket",
            result.expected,
            result.delivered + result.failed + result.cancelled + result.skipped + result.pending,
        )
    }

    @Test
    fun `a delivery that has only arrived counts as delivered but not as verified`() {
        val session = BroadcastSession(
            id = "t",
            sender = BroadcastFixtures.self,
            files = listOf(file("a", 100L)),
            recipients = listOf(ravi, pixel),
            deliveries = listOf(
                delivery("r", "a", TransferState.DONE, transferred = 100L, totalBytes = 100L, verification = VerificationOutcome.VERIFIED),
                delivery("p", "a", TransferState.DONE, transferred = 100L, totalBytes = 100L, verification = VerificationOutcome.PENDING),
            ).associateBy { it.key },
        )

        assertEquals(2, session.result.delivered)
        assertEquals(1, session.result.verified)
        assertTrue(session.result.complete)
        assertFalse(session.result.fullySuccessful)
    }

    @Test
    fun `confirmed bytes add up over the deliveries, and the total owed is the batch per phone`() {
        val session = mixed()

        assertEquals(318_100_000L, session.result.confirmedBytes)
        assertEquals(636_300_000L, session.result.expectedBytes)
        assertEquals(636_300_000L, session.bytesToSend)
    }

    @Test
    fun `combined throughput counts only the deliveries that are moving`() {
        val deliveries = listOf(
            delivery("r", "a", TransferState.SENDING, speed = 4_800_000L),
            delivery("p", "a", TransferState.RECEIVING, speed = 5_400_000L),
            delivery("s", "a", TransferState.PAUSED),
            delivery("s", "b", TransferState.FAILED),
            delivery("r", "b", TransferState.DONE),
            delivery("p", "b", TransferState.QUEUED),
            delivery("s", "c", TransferState.VERIFYING),
        )

        assertEquals(10_200_000L, BroadcastMath.combinedThroughput(deliveries))
    }

    @Test
    fun `a moving delivery that reports no speed is left out rather than counted as zero`() {
        val deliveries = listOf(
            delivery("r", "a", TransferState.SENDING),
            delivery("p", "a", TransferState.SENDING, speed = 2_000_000L),
        )

        assertEquals(2_000_000L, BroadcastMath.combinedThroughput(deliveries))
    }

    // ---------------------------------------------------------------- percentages

    @Test
    fun `a phone's percentage is its own confirmed bytes over the batch, clamped to a whole`() {
        assertEquals(0.5f, BroadcastMath.fraction(106_050_000L, 212_100_000L), 0.0001f)
        assertEquals("more confirmed than owed still reads as a full bar", 1f, BroadcastMath.fraction(999_999L, 100L), 0.0001f)
        assertEquals("nothing confirmed is an empty bar", 0f, BroadcastMath.fraction(0L, 100L), 0.0001f)
        assertEquals("nothing owed is an empty bar rather than a division", 0f, BroadcastMath.fraction(10L, 0L), 0.0001f)
        assertEquals("a negative total is no total", 0f, BroadcastMath.fraction(10L, -5L), 0.0001f)
        assertEquals("a negative confirmed figure cannot make a bar negative", 0f, BroadcastMath.fraction(-10L, 100L), 0.0001f)
    }

    @Test
    fun `a phone that has received the whole batch reads a hundred percent and no further`() {
        assertEquals(1f, BroadcastFixtures.completedSession().fractionFor("p"), 0.0001f)

        val partway = mixed().fractionFor("p")
        assertTrue("a phone partway through is between nothing and everything", partway in 0f..1f)
        assertEquals("85.5 MB of a 212.1 MB batch", 0.403f, partway, 0.001f)
        assertEquals(
            "and a phone whose arrived deliveries have all been cleared reads nothing",
            0f,
            BroadcastRules.clearCompleted(BroadcastFixtures.completedSession(), recipientId = "p")
                .fractionFor("p"),
            0.0001f,
        )
    }

    @Test
    fun `a delivery cannot confirm more bytes than its file holds`() {
        val overwritten = BroadcastDelivery(
            recipientId = "r",
            fileId = "a",
            totalBytes = 100L,
            transferredBytes = 400L,
        )

        assertEquals(100L, overwritten.transferred)
        assertEquals(1f, overwritten.fraction, 0.0001f)
    }

    @Test
    fun `a zero byte file is delivered when it is done and empty when it is not`() {
        val zero = file("empty", 0L)

        assertEquals(
            "a file with nothing in it is complete the moment it is delivered",
            1f,
            BroadcastDelivery(recipientId = "r", fileId = "empty", totalBytes = 0L, state = TransferState.DONE).fraction,
            0.0001f,
        )
        assertEquals(0f, BroadcastDelivery.queued("r", zero).fraction, 0.0001f)
    }

    // ---------------------------------------------------------------- grouping

    @Test
    fun `a file's aggregate state is what the deliveries in it add up to`() {
        val session = mixed()

        assertEquals("holiday: one phone still holds it", BroadcastGroupState.IN_PROGRESS, session.state(holiday))
        assertEquals("live: one moving, one skipped", BroadcastGroupState.IN_PROGRESS, session.state(live))

        val allVerified = BroadcastFixtures.completedSession()
        assertEquals(BroadcastGroupState.VERIFIED, allVerified.state(photo))
        assertTrue(BroadcastGroupState.VERIFIED.isSuccessful)
    }

    @Test
    fun `a group that stopped everywhere without arriving is incomplete, not queued`() {
        val stopped = listOf(
            delivery("r", "a", TransferState.CANCELLED),
            delivery("p", "a", TransferState.FAILED),
        )

        assertEquals(BroadcastGroupState.INCOMPLETE, BroadcastGroupState.of(stopped))
        assertTrue(BroadcastGroupState.INCOMPLETE.isFinished)
        assertFalse(BroadcastGroupState.INCOMPLETE.isSuccessful)
    }

    @Test
    fun `a group with nothing recorded yet is queued and unfinished`() {
        assertEquals(BroadcastGroupState.QUEUED, BroadcastGroupState.of(emptyList()))
        assertFalse(BroadcastGroupState.QUEUED.isFinished)
        assertFalse(BroadcastGroupState.QUEUED.isSuccessful)
    }

    @Test
    fun `a group where everything arrived but a checksum is missing is complete, not verified`() {
        val unchecked = listOf(
            delivery("r", "a", TransferState.DONE, transferred = 100L, totalBytes = 100L, verification = VerificationOutcome.PENDING),
            delivery("p", "a", TransferState.DONE, transferred = 100L, totalBytes = 100L, verification = VerificationOutcome.PENDING),
        )

        assertEquals(BroadcastGroupState.COMPLETE, BroadcastGroupState.of(unchecked))
        assertTrue(BroadcastGroupState.COMPLETE.isFinished)
        assertFalse(BroadcastGroupState.COMPLETE.isSuccessful)
    }

    @Test
    fun `a phone's aggregate state is its own, not the batch's`() {
        val session = mixed()

        assertEquals("Ravi has nothing moving and a file waiting its turn", BroadcastGroupState.QUEUED, session.stateFor("r"))
        assertEquals("Samsung has nothing moving but is not finished either", BroadcastGroupState.HELD, session.stateFor("s"))
        assertEquals(
            "and the batch's own state is not copied onto either of them",
            BroadcastGroupState.IN_PROGRESS,
            BroadcastGroupState.of(session.allDeliveries),
        )
    }

    @Test
    fun `a broadcast in flight has nothing finished about it`() {
        val session = mixed()

        assertFalse(session.state(holiday).isFinished)
        assertFalse(session.result.complete)
    }

    @Test
    fun `ending a broadcast is the only thing that makes it complete`() {
        val session = mixed()

        assertFalse(session.result.complete)
        assertTrue(BroadcastRules.end(session).result.complete)
    }

    // ---------------------------------------------------------------- fixtures

    @Test
    fun `the reference fixture is the grid the reference draws, spread across phones`() {
        val session = BroadcastFixtures.session()

        assertEquals(listOf("r", "p", "s"), session.uniqueRecipients.map { it.peerId })
        assertEquals(listOf(holiday, photo, live), session.uniqueFiles.map { it.id })
        assertEquals(9, session.allDeliveries.size)
        assertEquals(
            "no two phones are in the same position, which is the point of the screen",
            TransferState.DONE,
            session.delivery(DeliveryKey("p", live))!!.state,
        )
        assertEquals(
            TransferState.PAUSED,
            session.delivery(DeliveryKey("s", holiday))!!.state,
        )
        assertEquals(
            TransferState.FAILED,
            session.delivery(DeliveryKey("s", live))!!.state,
        )
    }

    @Test
    fun `a fan-out restricted to the chosen phones keeps only those phones`() {
        val two = BroadcastFixtures.sessionFor(listOf(ravi, pixel))

        assertEquals(listOf("r", "p"), two.uniqueRecipients.map { it.peerId })
        assertEquals("two phones, three files", 6, two.allDeliveries.size)
        assertTrue(two.allDeliveries.all { it.recipientId == "r" || it.recipientId == "p" })
    }

    @Test
    fun `a fan-out restricted to nothing falls back to the whole sample rather than to none`() {
        val all = BroadcastFixtures.sessionFor(emptyList())

        assertEquals(3, all.uniqueRecipients.size)
        assertEquals(9, all.allDeliveries.size)
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A fan-out with one delivery in each of the interesting positions: a delivered file beside
     * a moving one beside a held one, a failure, a skip, a queue, and two different speeds.
     *
     * Written out rather than tabulated so that every test below can say which phone it is
     * talking about and what it expects that phone to be doing.
     */
    private fun mixed(): BroadcastSession = BroadcastSession(
        id = "mixed",
        sender = BroadcastFixtures.self,
        files = batch,
        recipients = listOf(ravi, pixel, samsung),
        deliveries = listOf(
            delivery("r", holiday, TransferState.DONE, transferred = 144_000_000L),
            delivery("p", holiday, TransferState.SENDING, transferred = 60_000_000L, speed = 5_400_000L),
            delivery("s", holiday, TransferState.PAUSED, transferred = 83_500_000L),
            delivery("r", photo, TransferState.DONE, transferred = 4_100_000L),
            delivery("p", photo, TransferState.DONE, transferred = 4_100_000L),
            delivery("s", photo, TransferState.FAILED, transferred = 1_000_000L),
            delivery("r", live, TransferState.QUEUED),
            delivery("p", live, TransferState.SENDING, transferred = 21_400_000L, speed = 4_800_000L),
            delivery("s", live, TransferState.SKIPPED),
        ).associateBy { it.key },
    )

    private fun sessions(recipients: List<Peer>): BroadcastSession = BroadcastSession(
        id = "sizes",
        sender = BroadcastFixtures.self,
        files = batch,
        recipients = recipients,
    )

    private fun start(
        files: List<BroadcastFile> = batch,
        recipients: List<Peer> = listOf(ravi, pixel),
    ): BroadcastSession? = BroadcastSession.start(
        id = "t",
        sender = BroadcastFixtures.self,
        files = files,
        recipients = recipients,
    )

    private fun file(id: String, bytes: Long): BroadcastFile =
        BroadcastFile(id = id, fileName = "$id.bin", kind = batch.first().kind, totalBytes = bytes)

    private fun delivery(
        recipientId: String,
        fileId: String,
        state: TransferState,
        transferred: Long = 0L,
        speed: Long = 0L,
        verification: VerificationOutcome = if (state == TransferState.DONE && transferred > 0L) {
            VerificationOutcome.VERIFIED
        } else {
            VerificationOutcome.PENDING
        },
        incoming: Boolean = false,
        totalBytes: Long = batch.firstOrNull { it.id == fileId }?.sizeBytes ?: 100L,
    ): BroadcastDelivery = BroadcastDelivery(
        recipientId = recipientId,
        fileId = fileId,
        totalBytes = totalBytes,
        transferredBytes = transferred,
        speedBytesPerSecond = if (state.isActive) speed else 0L,
        state = state,
        verification = verification,
        direction = if (incoming) TransferDirection.INCOMING else TransferDirection.OUTGOING,
    )
}
