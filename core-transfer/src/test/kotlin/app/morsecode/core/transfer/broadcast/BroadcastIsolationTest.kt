package app.morsecode.core.transfer.broadcast

import app.morsecode.core.transfer.Tf
import app.morsecode.core.transfer.plus
import app.morsecode.core.transfer.identity.FileId
import app.morsecode.core.transfer.identity.RecipientId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.model.TransferSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Broadcast isolation: one file to N recipients, where nothing one recipient does
 * can change what another is measured against.
 *
 * The product rule the UI shows is enforced here rather than assumed — expected
 * deliveries = distinct files × distinct recipients — and every aggregate number
 * for a recipient is computed from that recipient's own rows only. There is no
 * shared counter a failing peer can poison.
 */
class BroadcastIsolationTest {

    private val aggregator = BroadcastAggregator

    // --- the product rule ------------------------------------------------------------------

    @Test fun `an empty batch summarises as empty`() {
        val summary = aggregator.summarize(emptyList())
        assertEquals(BroadcastStatus.EMPTY, summary.status)
        assertEquals(0, summary.expectedDeliveries)
        assertEquals(0, summary.expectedBytes)
        assertFalse(summary.allVerified)
    }

    @Test fun `one file to two recipients is two expected deliveries`() {
        val summary = aggregator.summarize(
            listOf(
                Tf.broadcastQueued(TransferId("tr-a1"), Tf.recipientA),
                Tf.broadcastQueued(TransferId("tr-b1"), Tf.recipientB),
            ),
        )
        assertEquals(2, summary.expectedDeliveries)
        assertEquals(1, summary.fileIds.size)
        assertEquals(2, summary.recipients.size)
        assertEquals(Tf.THREE_CHUNKS * 2, summary.expectedBytes)
    }

    @Test fun `two files to three recipients is six expected deliveries`() {
        val snapshots = mutableListOf<TransferSnapshot>()
        for (recipient in listOf(Tf.recipientA, Tf.recipientB, RecipientId("peer-c"))) {
            for (file in listOf(FileId("f1"), FileId("f2"))) {
                snapshots += Tf.broadcastQueued(
                    transferId = TransferId("tr-${recipient.value}-${file.value}"),
                    recipientId = recipient,
                    fileId = file,
                )
            }
        }
        val summary = aggregator.summarize(snapshots)
        assertEquals(6, summary.expectedDeliveries)
        assertEquals(2, summary.fileIds.size)
        assertEquals(3, summary.recipients.size)
        for (progress in summary.recipients) {
            assertEquals("every recipient is measured against the whole batch", 2, progress.expectedDeliveries)
        }
    }

    @Test fun `a batch with no recipients is empty rather than divided by zero`() {
        val summary = aggregator.summarize(listOf(Tf.queued()))
        assertEquals(BroadcastStatus.EMPTY, summary.status)
        assertEquals(0, summary.expectedDeliveries)
    }

    // --- distinctness ------------------------------------------------------------------------

    @Test fun `a duplicated recipient does not look like two receivers`() {
        val summary = aggregator.summarize(
            listOf(
                Tf.broadcastQueued(TransferId("tr-a1"), Tf.recipientA),
                Tf.broadcastQueued(TransferId("tr-a2"), Tf.recipientA),
            ),
        )
        assertEquals(1, summary.recipients.size)
        assertEquals(1, summary.expectedDeliveries)
    }

    @Test fun `a duplicated file does not inflate the batch size`() {
        val summary = aggregator.summarize(
            listOf(
                Tf.broadcastQueued(TransferId("tr-a1"), Tf.recipientA, fileId = FileId("f1")),
                Tf.broadcastQueued(TransferId("tr-b1"), Tf.recipientB, fileId = FileId("f1")),
            ),
        )
        assertEquals(1, summary.fileIds.size)
        assertEquals(2, summary.expectedDeliveries)
        assertEquals(Tf.THREE_CHUNKS * 2, summary.expectedBytes)
    }

    @Test fun `recipients and files are reported in a stable order`() {
        val summary = aggregator.summarize(
            listOf(
                Tf.broadcastQueued(TransferId("tr-c"), RecipientId("peer-c")),
                Tf.broadcastQueued(TransferId("tr-a"), Tf.recipientA),
                Tf.broadcastQueued(TransferId("tr-b"), Tf.recipientB),
            ),
        )
        assertEquals(
            listOf(Tf.recipientA, Tf.recipientB, RecipientId("peer-c")),
            summary.recipients.map { it.recipientId },
        )
    }

    // --- isolation -------------------------------------------------------------------------------

    @Test fun `one recipient completing does not complete the batch`() {
        val summary = aggregator.summarize(
            listOf(
                completed(Tf.recipientA),
                Tf.broadcastQueued(TransferId("tr-b1"), Tf.recipientB),
            ),
        )
        assertEquals(BroadcastStatus.IN_PROGRESS, summary.status)
        assertEquals(1, summary.completedDeliveries)
        assertEquals(1, summary.recipients.first { it.recipientId == Tf.recipientA }.completedDeliveries)
        assertEquals(0, summary.recipients.first { it.recipientId == Tf.recipientB }.completedDeliveries)
        assertFalse(summary.allVerified)
    }

    @Test fun `one recipient failing finally leaves the other untouched`() {
        val id = TransferId("tr-a1")
        val failed = Tf.broadcastQueued(id, Tf.recipientA) +
            Tf.beginNegotiation(transferId = id, recipientId = Tf.recipientA) +
            Tf.peerAccepted(transferId = id, recipientId = Tf.recipientA) +
            Tf.permissionRevoked(transferId = id, recipientId = Tf.recipientA)
        val summary = aggregator.summarize(
            listOf(failed, Tf.broadcastQueued(TransferId("tr-b1"), Tf.recipientB)),
        )
        val a = summary.recipients.first { it.recipientId == Tf.recipientA }
        val b = summary.recipients.first { it.recipientId == Tf.recipientB }
        assertEquals(1, a.failedDeliveries)
        assertEquals(0, a.completedDeliveries)
        assertEquals(0, b.failedDeliveries)
        assertEquals(0, b.pendingDeliveries + b.settledDeliveries - 1)
        assertEquals(1, b.pendingDeliveries)
        assertEquals(0, b.confirmedBytes)
    }

    @Test fun `a failure is structurally isolated from every other recipient`() {
        val failed = Tf.broadcastQueued(TransferId("tr-a1"), Tf.recipientA)
        assertTrue(aggregator.isIsolatedFrom(failed, Tf.broadcastQueued(TransferId("tr-b1"), Tf.recipientB)))
        assertFalse(aggregator.isIsolatedFrom(failed, Tf.broadcastQueued(TransferId("tr-a2"), Tf.recipientA)))
    }

    @Test fun `confirmed bytes are counted per recipient, never shared`() {
        val id = TransferId("tr-a1")
        val partial = Tf.broadcastQueued(id, Tf.recipientA) +
            Tf.beginNegotiation(transferId = id, recipientId = Tf.recipientA) +
            Tf.peerAccepted(transferId = id, recipientId = Tf.recipientA) +
            Tf.chunkSent(
                offset = 0L,
                length = Tf.chunk.value,
                transferId = id,
                recipientId = Tf.recipientA,
            ) +
            Tf.chunkAck(
                offset = 0L,
                length = Tf.chunk.value,
                confirmedOffset = Tf.chunk.value.toLong(),
                transferId = id,
                recipientId = Tf.recipientA,
            )
        val summary = aggregator.summarize(
            listOf(partial, Tf.broadcastQueued(TransferId("tr-b1"), Tf.recipientB)),
        )
        val a = summary.recipients.first { it.recipientId == Tf.recipientA }
        val b = summary.recipients.first { it.recipientId == Tf.recipientB }
        assertEquals(Tf.chunk.value.toLong(), a.confirmedBytes)
        assertEquals(0L, b.confirmedBytes)
        assertEquals(Tf.chunk.value.toLong(), summary.confirmedBytes)
    }

    @Test fun `a cancelled recipient does not cancel the batch for the others`() {
        val id = TransferId("tr-a1")
        val cancelled = Tf.broadcastQueued(id, Tf.recipientA) +
            Tf.cancelLocally(transferId = id, recipientId = Tf.recipientA)
        val summary = aggregator.summarize(
            listOf(cancelled, completed(Tf.recipientB)),
        )
        assertEquals(1, summary.cancelledDeliveries)
        assertEquals(1, summary.completedDeliveries)
        assertEquals(BroadcastStatus.COMPLETE_PARTIAL, summary.status)
        assertFalse(summary.allVerified)
    }

    @Test fun `a skipped recipient does not skip the batch for the others`() {
        val skipped = skipped(Tf.recipientA)
        val summary = aggregator.summarize(listOf(skipped, completed(Tf.recipientB)))
        assertEquals(1, summary.skippedDeliveries)
        assertEquals(1, summary.completedDeliveries)
        assertEquals(BroadcastStatus.COMPLETE_PARTIAL, summary.status)
    }

    // --- terminal statuses --------------------------------------------------------------------------

    @Test fun `every recipient completing and verifying completes the batch`() {
        val summary = aggregator.summarize(
            listOf(completed(Tf.recipientA), completed(Tf.recipientB)),
        )
        assertEquals(BroadcastStatus.COMPLETE_ALL_VERIFIED, summary.status)
        assertTrue(summary.allVerified)
        assertEquals(2, summary.completedDeliveries)
        assertEquals(2, summary.settledDeliveries)
        for (progress in summary.recipients) {
            assertTrue(progress.isComplete)
            assertTrue(progress.isFullyVerified)
        }
    }

    @Test fun `every delivery settling without all completing is a partial batch`() {
        val summary = aggregator.summarize(
            listOf(completed(Tf.recipientA), skipped(Tf.recipientB)),
        )
        assertEquals(BroadcastStatus.COMPLETE_PARTIAL, summary.status)
        assertFalse(summary.allVerified)
        assertEquals(2, summary.settledDeliveries)
        assertEquals(1, summary.completedDeliveries)
    }

    @Test fun `a batch is not complete while any delivery is still moving`() {
        val summary = aggregator.summarize(
            listOf(completed(Tf.recipientA), Tf.broadcastQueued(TransferId("tr-b1"), Tf.recipientB)),
        )
        assertEquals(BroadcastStatus.IN_PROGRESS, summary.status)
        assertEquals(1, summary.pendingDeliveries)
    }

    // --- determinism ------------------------------------------------------------------------------------

    @Test fun `the same batch summarises identically every time`() {
        val snapshots = listOf(
            completed(Tf.recipientA),
            Tf.broadcastQueued(TransferId("tr-b1"), Tf.recipientB),
            Tf.broadcastQueued(TransferId("tr-c1"), RecipientId("peer-c")),
        )
        val first = aggregator.summarize(snapshots)
        repeat(20) { assertEquals(first, aggregator.summarize(snapshots)) }
        assertEquals(first, aggregator.summarize(snapshots.reversed()))
    }

    @Test fun `summarising does not mutate the snapshots it was given`() {
        val snapshots = listOf(
            Tf.broadcastQueued(TransferId("tr-a1"), Tf.recipientA),
            Tf.broadcastQueued(TransferId("tr-b1"), Tf.recipientB),
        )
        val before = snapshots.toList()
        aggregator.summarize(snapshots)
        assertEquals(before, snapshots)
    }

    @Test fun `every broadcast status round trips through its id`() {
        for (status in BroadcastStatus.entries) {
            assertEquals(status, BroadcastStatus.fromId(status.id))
        }
        assertEquals(BroadcastStatus.EMPTY, BroadcastStatus.fromId("nonsense"))
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private fun completed(recipient: RecipientId): TransferSnapshot =
        Tf.completed(
            transferId = TransferId("tr-${recipient.value}-done"),
            recipientId = recipient,
        )

    private fun skipped(recipient: RecipientId): TransferSnapshot {
        val id = TransferId("tr-${recipient.value}-skip")
        return Tf.broadcastQueued(id, recipient) + Tf.skip(transferId = id, recipientId = recipient)
    }
}
