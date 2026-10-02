package app.morsecode.core.storage.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The durability capability table, and the rule that only a failed flush stops
 * the checkpoint and the acknowledgement.
 *
 * The claim under test is not "the enum has four values". It is that a flush
 * through a provider that succeeded but proves nothing is *not* treated as
 * equivalent to a failed flush — both are distinct from an fsync, and only one of
 * them suppresses progress. Conflating "unknown" with "failed" would make every
 * MediaStore transfer unacknowledgeable; conflating it with "durable" would make
 * an acknowledgement claim bytes that were never committed.
 */
class DestinationDurabilityTest {

    @Test
    fun `app-private and staged destinations can really fsync`() {
        assertEquals(FlushCapability.DURABLE, DestinationStrategy.APP_PRIVATE.declaredFlushCapability)
        assertEquals(FlushCapability.DURABLE, DestinationStrategy.SAF_STAGED.declaredFlushCapability)
    }

    @Test
    fun `staging is durable during the transfer even though the final copy is not`() {
        // The whole reason SAF_STAGED is the default: bytes are written to an
        // app-private file for the entire transfer, so the transfer gets a real
        // fsync, and only the final bounded copy relies on the provider.
        assertTrue(DestinationStrategy.SAF_STAGED.requiresFinalCopy)
        assertEquals(FlushCapability.DURABLE, DestinationStrategy.SAF_STAGED.declaredFlushCapability)
        assertTrue(DestinationStrategy.SAF_STAGED.hidesIncompleteFile)
    }

    @Test
    fun `provider-backed destinations declare that their flush proves nothing`() {
        assertEquals(
            FlushCapability.ATTEMPTED_GUARANTEE_UNKNOWN,
            DestinationStrategy.MEDIA_STORE_PENDING.declaredFlushCapability,
        )
        assertEquals(
            FlushCapability.ATTEMPTED_GUARANTEE_UNKNOWN,
            DestinationStrategy.SAF_DIRECT.declaredFlushCapability,
        )
    }

    @Test
    fun `writing a partial straight into SAF does not hide it`() {
        assertFalse(DestinationStrategy.SAF_DIRECT.hidesIncompleteFile)
        // The three alternatives all do, which is why SAF_DIRECT is not the default.
        assertTrue(DestinationStrategy.MEDIA_STORE_PENDING.hidesIncompleteFile)
        assertTrue(DestinationStrategy.SAF_STAGED.hidesIncompleteFile)
        assertTrue(DestinationStrategy.APP_PRIVATE.hidesIncompleteFile)
    }

    // --- the checkpoint rule --------------------------------------------

    @Test
    fun `a real fsync advances the checkpoint and releases the acknowledgement`() {
        val outcome: FlushDurability = FlushDurability.DurableFlushSupported
        assertTrue(FlushDurability.allowsCheckpoint(outcome))
        assertTrue(FlushDurability.allowsAcknowledgement(outcome))
    }

    @Test
    fun `a flush that proves nothing still advances the checkpoint`() {
        // Deliberate: refusing to checkpoint here would stall every MediaStore
        // and direct-SAF transfer forever. The unknown is recorded instead.
        val outcome: FlushDurability = FlushDurability.FlushAttemptedGuaranteeUnknown
        assertTrue(FlushDurability.allowsCheckpoint(outcome))
        assertTrue(FlushDurability.allowsAcknowledgement(outcome))
        assertEquals("unknown", FlushDurability.rowValue(outcome))
    }

    @Test
    fun `an unsupported flush advances the checkpoint because the write succeeded`() {
        val outcome: FlushDurability = FlushDurability.FlushUnsupported
        assertTrue(FlushDurability.allowsCheckpoint(outcome))
        assertTrue(FlushDurability.allowsAcknowledgement(outcome))
        assertEquals("unsupported", FlushDurability.rowValue(outcome))
    }

    @Test
    fun `a failed flush blocks both the checkpoint and the acknowledgement`() {
        val outcome: FlushDurability = FlushDurability.FlushFailed(
            TransferStorageError.Io("sync", "/secret/a.mp4"),
        )
        assertFalse("a failed flush must not advance the checkpoint", FlushDurability.allowsCheckpoint(outcome))
        assertFalse(
            "a failed flush must not release an acknowledgement",
            FlushDurability.allowsAcknowledgement(outcome),
        )
        assertEquals("failed", FlushDurability.rowValue(outcome))
    }

    @Test
    fun `no outcome other than failure blocks progress`() {
        val outcomes: List<FlushDurability> = listOf(
            FlushDurability.DurableFlushSupported,
            FlushDurability.FlushAttemptedGuaranteeUnknown,
            FlushDurability.FlushUnsupported,
            FlushDurability.FlushFailed(TransferStorageError.Io("sync")),
        )
        val blocking = outcomes.filterNot { FlushDurability.allowsCheckpoint(it) }
        assertEquals(1, blocking.size)
        assertTrue(blocking.single() is FlushDurability.FlushFailed)
    }

    @Test
    fun `row values are distinct so a restored row can tell them apart`() {
        val values = listOf(
            FlushDurability.DurableFlushSupported,
            FlushDurability.FlushAttemptedGuaranteeUnknown,
            FlushDurability.FlushUnsupported,
            FlushDurability.FlushFailed(TransferStorageError.Io("sync")),
        ).map { FlushDurability.rowValue(it) }
        assertEquals(4, values.distinct().size)
    }

    // --- vocabulary -------------------------------------------------------

    @Test
    fun `strategies and capabilities have distinct ids and round-trip`() {
        DestinationStrategy.entries.forEach {
            assertEquals(it, DestinationStrategy.fromId(it.id))
        }
        assertEquals(4, DestinationStrategy.entries.map { it.id }.distinct().size)
        FlushCapability.entries.forEach {
            assertEquals(it, FlushCapability.fromId(it.id))
        }
        assertEquals(DestinationStrategy.APP_PRIVATE, DestinationStrategy.fromId("nonsense"))
    }
}
