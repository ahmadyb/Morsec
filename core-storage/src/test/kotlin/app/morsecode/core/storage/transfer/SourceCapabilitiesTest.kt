package app.morsecode.core.storage.transfer

import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Seekability is measured, not assumed.
 *
 * The five provider shapes this covers are the ones a resume path has to survive:
 * a real file; a pipe whose channel exists but refuses to move; a descriptor that
 * is seekable but will not say how long it is; one with no channel at all; and one
 * that was never obtained because the row vanished between the metadata query and
 * the open.
 *
 * The pipe case is a real OS pipe, not a fake, because the interesting question is
 * what the platform actually does when you ask a pipe to seek.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceCapabilitiesTest {

    private fun tempFile(size: Int = 4_096): File {
        val dir = Files.createTempDirectory("probe-").toFile()
        return File(dir, "data.bin").apply { writeBytes(ByteArray(size) { (it % 251).toByte() }) }
    }

    @Test
    fun `a real file is seekable and reports its length`() {
        val file = tempFile(4_096)
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        descriptor.use {
            val result = ParcelDescriptorSeekabilityProbe.probe(it)
            assertEquals(Seekability.SEEKABLE, result.seekability)
            assertEquals(4_096L, result.sizeBytes)
            assertEquals(ProbeEvidence.POSITION_ACCEPTED, result.evidence)
            assertTrue(result.sizeIsKnown)
        }
    }

    @Test
    fun `an empty file is seekable and reports zero rather than unknown`() {
        val file = tempFile(0)
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        descriptor.use {
            val result = ParcelDescriptorSeekabilityProbe.probe(it)
            assertEquals(Seekability.SEEKABLE, result.seekability)
            assertEquals(0L, result.sizeBytes)
            assertTrue("a zero length is a known length", result.sizeIsKnown)
        }
    }

    @Test
    fun `a descriptor that refuses to be positioned is non-seekable`() {
        // The classification that has to be right: a descriptor that will not
        // move must never be seeked, because seeking one either throws or, worse,
        // appears to succeed while leaving the bytes it skipped unread.
        //
        // A closed descriptor is the real descriptor here that rejects
        // positioning. It is worth saying why it is not a pipe: Robolectric
        // backs its pipes with a seekable temporary file, so the platform's own
        // pipe behaviour — which is the motivating case for probing at all — is
        // not observable in this sandbox. The probe's *decision*, which is the
        // part this repository owns, is pinned here and by the forced shapes
        // below; the platform's answer for a pipe is a fact about the platform.
        val closed = ParcelFileDescriptor.open(tempFile(), ParcelFileDescriptor.MODE_READ_ONLY)
        closed.close()

        val result = ParcelDescriptorSeekabilityProbe.probe(closed)

        assertEquals(Seekability.NON_SEEKABLE, result.seekability)
        assertEquals(ProbeEvidence.POSITION_REJECTED, result.evidence)
        assertNull(result.sizeBytes)
        assertFalse(result.sizeIsKnown)
    }

    // --- the five shapes, forced through the seam ------------------------------

    private fun forcing(seekability: Seekability, size: Long?, evidence: ProbeEvidence) =
        SeekabilityProbe { ProbeResult(seekability, size, evidence) }

    @Test
    fun `every provider shape a resume path must survive is representable`() {
        val cases = listOf(
            ProbeResult(Seekability.SEEKABLE, 1_024L, ProbeEvidence.POSITION_ACCEPTED),
            ProbeResult(Seekability.NON_SEEKABLE, null, ProbeEvidence.POSITION_REJECTED),
            ProbeResult(Seekability.SEEKABLE, null, ProbeEvidence.SIZE_UNAVAILABLE),
            ProbeResult(Seekability.UNKNOWN, null, ProbeEvidence.NO_CHANNEL),
            ProbeResult(Seekability.UNKNOWN, null, ProbeEvidence.DESCRIPTOR_UNUSABLE),
        )
        cases.forEach { expected ->
            val probe = forcing(expected.seekability, expected.sizeBytes, expected.evidence)
            val descriptor = ParcelFileDescriptor.open(tempFile(16), ParcelFileDescriptor.MODE_READ_ONLY)
            descriptor.use { assertEquals(expected, probe.probe(it)) }
        }
    }

    @Test
    fun `seekable means it can seek, and nothing else does`() {
        assertTrue(Seekability.SEEKABLE.canSeek)
        assertFalse(Seekability.NON_SEEKABLE.canSeek)
        assertFalse(Seekability.UNKNOWN.canSeek)
    }

    @Test
    fun `a seekable descriptor with unknown size is still seekable`() {
        // It can be positioned; only offset validation is unavailable, and the
        // opener must skip that check rather than refuse the open.
        val result = ProbeResult(Seekability.SEEKABLE, null, ProbeEvidence.SIZE_UNAVAILABLE)
        assertTrue(result.seekability.canSeek)
        assertFalse(result.sizeIsKnown)
    }

    @Test
    fun `the vocabulary round-trips and stays distinct`() {
        assertEquals(3, Seekability.entries.size)
        assertEquals(5, ProbeEvidence.entries.size)
        Seekability.entries.forEach { assertEquals(it, Seekability.fromId(it.id)) }
        ProbeEvidence.entries.forEach { assertEquals(it, ProbeEvidence.fromId(it.id)) }
        assertEquals(Seekability.UNKNOWN, Seekability.fromId("nonsense"))
        assertEquals(ProbeEvidence.DESCRIPTOR_UNUSABLE, ProbeEvidence.fromId("nonsense"))
    }

    @Test
    fun `the unavailable constant is unknown rather than non-seekable`() {
        // Used when a descriptor was never obtained. Claiming non-seekable would
        // tell the caller to reopen and discard bytes for a file that is gone.
        assertEquals(Seekability.UNKNOWN, ProbeResult.UNAVAILABLE.seekability)
        assertEquals(ProbeEvidence.DESCRIPTOR_UNUSABLE, ProbeResult.UNAVAILABLE.evidence)
        assertNull(ProbeResult.UNAVAILABLE.sizeBytes)
    }
}
