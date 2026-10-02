package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Digest
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one destination where "durable" can be claimed without qualification.
 *
 * These tests use real files and a real fsync, because the claim being made is
 * about a real filesystem. No fixture here is large: the >4 GiB evidence comes
 * from a positional write at a 5 GiB offset, which on any Linux filesystem
 * produces a sparse file of no practical size rather than five gigabytes of
 * zeros.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppPrivatePartialTest {

    private val transfer = TransferId("transfer-0001")
    private val identity = PartialIdentity.of(transfer, DestinationStrategy.SAF_STAGED)

    private fun store(): AppPrivatePartialStore =
        AppPrivatePartialStore(Files.createTempDirectory("partials-").toFile())

    private fun payload(size: Int, seed: Int = 7) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    private fun ByteArray.fillEqual(offset: Int, other: ByteArray) {
        for (i in other.indices) {
            assertEquals(other[i].toInt(), this[offset + i].toInt())
        }
    }

    // --- writing ------------------------------------------------------------

    @Test
    fun `a partial starts empty and records what is written`() {
        val store = store()
        assertFalse(store.exists(identity))
        assertNull(store.lengthOrNull(identity))

        val partial = store.open(identity)
        assertEquals(0L, partial.length())

        val data = payload(4_096)
        val outcome = partial.writeAt(0L, data, 0, data.size)

        assertTrue(outcome is WriteOutcome.Written)
        assertEquals(4_096, (outcome as WriteOutcome.Written).bytes)
        assertEquals(4_096L, outcome.length)
        assertEquals(4_096L, partial.length())
        partial.close()
        assertEquals(4_096L, store.lengthOrNull(identity))
    }

    @Test
    fun `a resumed write lands at its offset rather than at zero`() {
        // The whole reason writes are positional: after a resume the first write
        // is not at zero, and a cursor would append to the wrong place.
        val store = store()
        val partial = store.open(identity)
        val head = payload(1_024, seed = 1)
        val tail = payload(1_024, seed = 2)

        partial.writeAt(0L, head, 0, head.size)
        partial.writeAt(1_024L, tail, 0, tail.size)

        assertEquals(2_048L, partial.length())
        val readBack = ByteArray(2_048)
        val source = partial.verificationSource()
        assertEquals(2_048, source.read(0L, readBack, 0, 2_048))
        readBack.fillEqual(0, head)
        readBack.fillEqual(1_024, tail)
        partial.close()
    }

    @Test
    fun `truncating discards the unacknowledged tail`() {
        val store = store()
        val partial = store.open(identity)
        partial.writeAt(0L, payload(2_048), 0, 2_048)

        val outcome = partial.truncateTo(1_024L)

        assertTrue(outcome is TruncateOutcome.Truncated)
        assertEquals(1_024L, (outcome as TruncateOutcome.Truncated).length)
        assertEquals(1_024L, partial.length())
        partial.close()
    }

    @Test
    fun `a partial can be reopened and continues where it left off`() {
        val store = store()
        store.open(identity).use { it.writeAt(0L, payload(1_024), 0, 1_024) }

        val reopened = store.open(identity)
        assertEquals(1_024L, reopened.length())
        reopened.writeAt(1_024L, payload(1_024, seed = 9), 0, 1_024)
        assertEquals(2_048L, reopened.length())
        reopened.close()
    }

    // --- flushing ------------------------------------------------------------

    @Test
    fun `a flush on an app-private file reports a real durable flush`() {
        val store = store()
        val partial = store.open(identity)
        partial.writeAt(0L, payload(512), 0, 512)

        val outcome = partial.flush()

        assertEquals(FlushDurability.DurableFlushSupported, outcome)
        assertTrue(FlushDurability.allowsCheckpoint(outcome))
        assertEquals("durable", FlushDurability.rowValue(outcome))
        partial.close()
    }

    @Test
    fun `a flush that fails is reported as a failure and blocks progress`() {
        val store = store()
        val partial = store.open(identity)
        partial.close()

        // A closed channel cannot be fsynced; the failure has to come back as a
        // typed outcome rather than as an exception escaping into the caller.
        val outcome = partial.flush()

        assertTrue(outcome is FlushDurability.FlushFailed)
        assertFalse(FlushDurability.allowsCheckpoint(outcome))
        assertFalse(FlushDurability.allowsAcknowledgement(outcome))
    }

    // --- ownership -----------------------------------------------------------

    @Test
    fun `closing a partial twice closes the file once`() {
        val store = store()
        val partial = store.open(identity)
        partial.writeAt(0L, payload(64), 0, 64)

        partial.close()
        partial.close()

        assertEquals(1, partial.closeCount)
        // Closing twice must not throw, and the second close must not reach a
        // file descriptor that may since have been handed to someone else.
        assertTrue(runCatching { partial.length() }.isFailure)
    }

    @Test
    fun `deleting a partial that is not there succeeds`() {
        val store = store()
        assertTrue(store.delete(identity))
        assertTrue(store.delete(identity))
    }

    @Test
    fun `deleting removes the file`() {
        val store = store()
        store.open(identity).use { it.writeAt(0L, payload(64), 0, 64) }
        assertTrue(store.exists(identity))
        assertTrue(store.delete(identity))
        assertFalse(store.exists(identity))
    }

    // --- names ---------------------------------------------------------------

    @Test
    fun `the staged file name is derived from the identity and contains no surprises`() {
        val store = store()
        val name = store.fileNameFor(identity)
        assertTrue(name.startsWith("morsec-"))
        assertTrue(name.endsWith(".part"))
        // Nothing that could be read as a path or as a traversal marker.
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.contains(".."))
    }

    // --- verification over a real file ---------------------------------------

    @Test
    fun `a written partial verifies against its real digest`() {
        val store = store()
        val partial = store.open(identity)
        val data = payload(200_000)
        partial.writeAt(0L, data, 0, data.size)

        val expected = Sha256Digest.of(data)
        assertNotNull(expected)

        val result = DestinationVerifier.verify(
            source = partial.verificationSource(),
            totalBytes = data.size.toLong(),
            expected = expected,
            buffer = ByteArray(8_192),
        )

        assertTrue(result is VerifyResult.Matched)
        assertEquals(data.size.toLong(), (result as VerifyResult.Matched).bytes)
        partial.close()
    }

    @Test
    fun `verification over a shorter range matches only that range`() {
        val store = store()
        val partial = store.open(identity)
        val data = payload(10_000)
        partial.writeAt(0L, data, 0, data.size)

        // Claim the file is one byte shorter than it is: verification must read
        // exactly what it was told and reach a verdict on that basis.
        val short = data.copyOf(data.size - 1)
        val result = DestinationVerifier.verify(
            source = partial.verificationSource(),
            totalBytes = short.size.toLong(),
            expected = Sha256Digest.of(short),
        )

        assertTrue(result is VerifyResult.Matched)
        partial.close()
    }

    // --- beyond 4 GiB ----------------------------------------------------------

    @Test
    fun `a write past the 32-bit boundary is positioned correctly`() {
        // A positional write at a 5 GiB offset. On a Linux filesystem this is a
        // sparse file, so the test costs no meaningful disk space or time while
        // still proving that the offset is a Long and not an Int.
        val store = store()
        val partial = store.open(identity)
        val offset = 5_368_709_120L
        val data = payload(1_024, seed = 3)

        val outcome = partial.writeAt(offset, data, 0, data.size)

        assertTrue(outcome is WriteOutcome.Written)
        assertEquals(5_368_710_144L, partial.length())
        assertTrue("length must exceed the 32-bit range", partial.length() > Int.MAX_VALUE)

        val readBack = ByteArray(1_024)
        assertEquals(1_024, partial.verificationSource().read(offset, readBack, 0, 1_024))
        readBack.fillEqual(0, data)
        partial.close()
    }

    @Test
    fun `truncating past the 32-bit boundary works in Long`() {
        val store = store()
        val partial = store.open(identity)
        val offset = 5_368_709_120L
        partial.writeAt(offset, payload(2_048, seed = 4), 0, 2_048)

        val outcome = partial.truncateTo(offset + 1_024L)

        assertTrue(outcome is TruncateOutcome.Truncated)
        assertEquals(5_368_710_144L, partial.length())
        partial.close()
    }
}
