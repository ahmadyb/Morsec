package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Digest
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    private val tempDirectories = mutableListOf<File>()

    private fun store(): AppPrivatePartialStore {
        val directory = Files.createTempDirectory("partials-").toFile()
        tempDirectories += directory
        return AppPrivatePartialStore(directory)
    }

    /**
     * Removes every staged file, including the sparse one holding a 5 GiB
     * apparent size. A test that leaves a multi-gigabyte file behind on a CI
     * runner is a test that will eventually break someone else's build.
     */
    @After
    fun removeStagedFiles() {
        tempDirectories.forEach { it.deleteRecursively() }
        tempDirectories.clear()
    }

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

    @Test
    fun `identity encoding is injective for punctuation-bearing staging keys`() {
        val store = store()
        val colonIdentity = PartialIdentity("saf:grant")
        val atIdentity = PartialIdentity("saf@grant")

        val colonName = store.fileNameFor(colonIdentity)
        val atName = store.fileNameFor(atIdentity)

        assertNotEquals(colonName, atName)
        assertTrue(colonName.startsWith("morsec-~1-"))
        assertTrue(atName.startsWith("morsec-~1-"))
    }

    // --- verification over a real file ---------------------------------------

    @Test
    fun `a written partial verifies against its real digest`() {
        val store = store()
        val partial = store.open(identity)
        val data = payload(200_000)
        partial.writeAt(0L, data, 0, data.size)

        // SHA-256 of payload(200_000, seed = 7), computed independently with
        // Python's hashlib. Not with Sha256Digest.of(), which is not a hash
        // function: it wraps exactly 32 raw digest bytes and returns null for
        // anything else, so feeding it file content silently produced no
        // expected digest at all.
        val expected = Sha256Digest.fromHex(
            "3d46a15a54cf33991f077e628582a654e184f139cf03fcbcd7c89fb4213e1023",
        )
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
        // exactly what it was told and reach a verdict on that basis. The
        // expected digest is SHA-256 of the first 9,999 bytes of
        // payload(10_000, seed = 7), again computed outside the implementation.
        val expected = Sha256Digest.fromHex(
            "5cf8415bad23d6be1c92edeb3bd85baeee4263f564a71833326076baf8570635",
        )
        val result = DestinationVerifier.verify(
            source = partial.verificationSource(),
            totalBytes = 9_999L,
            expected = expected,
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
    fun `the over-4 GiB test does not physically write five gigabytes`() {
        // Writing 5 GiB in CI would be slow enough to matter and would fill a
        // runner's disk. A positional write at a 5 GiB offset produces a sparse
        // file on any Linux filesystem, so the test proves Long offset handling
        // for free. This asserts that the file really is sparse rather than
        // trusting it: if a filesystem ever stops cooperating, this fails loudly
        // instead of quietly writing five gigabytes.
        val store = store()
        val partial = store.open(identity)
        val file = store.fileFor(identity)
        val filesystem = file.parentFile!!
        val freeBefore = filesystem.usableSpace

        partial.writeAt(5_368_709_120L, payload(1_024, seed = 3), 0, 1_024)
        partial.flush()

        // Measured rather than assumed: the JDK's unix: attribute view does not
        // expose allocated blocks, so physical consumption is measured the direct
        // way, as free space before minus free space after. A sparse file costs
        // kilobytes; a dense one would cost five gigabytes and would fill a
        // runner's disk before anyone noticed.
        val consumed = freeBefore - filesystem.usableSpace
        val apparent = file.length()

        assertEquals(5_368_710_144L, apparent)
        assertTrue(
            "apparent size is $apparent bytes but $consumed bytes of real space were " +
                "consumed; a sparse file should cost kilobytes, not gigabytes",
            consumed < 1_073_741_824L,
        )
        partial.close()
    }

    @Test
    fun `a verification failure still closes the partial`() {
        val store = store()
        val partial = store.open(identity)
        partial.writeAt(0L, payload(4_096), 0, 4_096)

        // Verifying more bytes than the file holds must fail, and the failure
        // must not leave the descriptor open behind it.
        val result = partial.use { open ->
            DestinationVerifier.verify(
                source = open.verificationSource(),
                totalBytes = 8_192L,
                expected = null,
            )
        }

        assertTrue(result is VerifyResult.Failed)
        assertEquals(1, partial.closeCount)
    }

    @Test
    fun `a cancellation closes the partial exactly once`() {
        val store = store()
        val partial = store.open(identity)

        val outcome = runCatching {
            partial.use { open ->
                open.writeAt(0L, payload(1_024), 0, 1_024)
                throw java.io.IOException(TransferStorageError.Cancelled.safeMessage())
            }
        }

        assertTrue(outcome.isFailure)
        assertEquals(1, partial.closeCount)
        // A cancelled transfer discards the staged file rather than leaving it.
        assertTrue(store.delete(identity))
        assertFalse(store.exists(identity))
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
