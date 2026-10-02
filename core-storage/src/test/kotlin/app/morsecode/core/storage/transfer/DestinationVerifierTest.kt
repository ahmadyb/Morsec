package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.integrity.Sha256Digest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Incremental verification, split into the two kinds of test ADR-0003 §7 asks
 * for.
 *
 * Real SHA-256 correctness is proved on bounded bytes against the published
 * digests for the empty string and for "abc" — from outside the implementation,
 * so a bug in the accumulator cannot agree with itself.
 *
 * Long-range behaviour is proved over a virtual source with a counting digester:
 * no bytes are produced and nothing is hashed, but the byte accounting, the
 * buffer bound and the progress policy are all exercised at 8 TiB - 1, which is
 * the protocol maximum and the number an `Int` accumulator would silently wrap
 * past. Hashing 8 TiB in CI would take hours and would prove nothing that the
 * bounded test had not already proved about the digest.
 */
class DestinationVerifierTest {

    /** SHA-256 of the empty string, published by NIST. */
    private val emptyHex =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /** SHA-256 of "abc", published by NIST. */
    private val abcHex =
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    /** Serves a fixed byte array, honouring the buffer bound and EOF. */
    private class ByteSource(private val data: ByteArray) : VerificationSource {
        var maxRequested = 0
            private set

        override fun read(offset: Long, buffer: ByteArray, dataOffset: Int, length: Int): Int {
            maxRequested = maxOf(maxRequested, length)
            if (offset >= data.size) return -1
            val count = minOf(length.toLong(), data.size - offset).toInt()
            System.arraycopy(data, offset.toInt(), buffer, dataOffset, count)
            return count
        }
    }

    /** Counts bytes instead of hashing them. */
    private class CountingDigester(private val resultHex: String) : ChunkDigester {
        var bytes = 0L
            private set
        var updates = 0
            private set
        var maxChunk = 0
            private set

        override fun update(buffer: ByteArray, offset: Int, length: Int) {
            bytes += length.toLong()
            updates++
            maxChunk = maxOf(maxChunk, length)
        }

        override fun digest(): Sha256Digest = Sha256Digest.fromHex(resultHex)!!
    }

    /** Claims a length without supplying any bytes. */
    private class VirtualSource(private val total: Long) : VerificationSource {
        var calls = 0
            private set
        var maxRequested = 0
            private set

        override fun read(offset: Long, buffer: ByteArray, dataOffset: Int, length: Int): Int {
            calls++
            maxRequested = maxOf(maxRequested, length)
            val remaining = total - offset
            return if (remaining <= 0L) -1 else minOf(remaining, length.toLong()).toInt()
        }
    }

    // --- real digests on bounded data ---------------------------------------

    @Test
    fun `the empty file verifies to the published empty digest`() {
        val result = DestinationVerifier.verify(
            source = ByteSource(ByteArray(0)),
            totalBytes = 0L,
            expected = Sha256Digest.fromHex(emptyHex),
        )
        assertTrue(result is VerifyResult.Matched)
        assertEquals(emptyHex, (result as VerifyResult.Matched).digest.hex)
        assertEquals(0L, result.bytes)
        assertTrue(result.allowsCommit)
    }

    @Test
    fun `a zero-byte file with no expected digest is accepted on count alone`() {
        val result = DestinationVerifier.verify(
            source = ByteSource(ByteArray(0)),
            totalBytes = 0L,
            expected = null,
        )
        assertTrue(result is VerifyResult.VerifiedWithoutExpected)
        assertEquals(emptyHex, (result as VerifyResult.VerifiedWithoutExpected).digest.hex)
        assertTrue(result.allowsCommit)
    }

    @Test
    fun `a zero-byte file is not confused with a missing one`() {
        // Verifying nothing must reach a verdict rather than failing: a
        // zero-byte transfer is legal and must be commitable.
        val result = DestinationVerifier.verify(ByteSource(ByteArray(0)), 0L, null)
        assertFalse(result is VerifyResult.Failed)
        assertTrue(result.allowsCommit)
    }

    @Test
    fun `a real three-byte file matches the published digest`() {
        val result = DestinationVerifier.verify(
            source = ByteSource("abc".toByteArray()),
            totalBytes = 3L,
            expected = Sha256Digest.fromHex(abcHex),
        )
        assertTrue(result is VerifyResult.Matched)
        assertEquals(abcHex, (result as VerifyResult.Matched).digest.hex)
        assertEquals(3L, result.bytes)
    }

    @Test
    fun `a mismatch is returned and never allows a commit`() {
        val result = DestinationVerifier.verify(
            source = ByteSource("abd".toByteArray()),
            totalBytes = 3L,
            expected = Sha256Digest.fromHex(abcHex),
        )
        assertTrue(result is VerifyResult.Mismatched)
        val mismatch = result as VerifyResult.Mismatched
        assertEquals(abcHex, mismatch.expected.hex)
        assertFalse("a mismatch must never be published as complete", mismatch.allowsCommit)
    }

    @Test
    fun `verification reads through a buffer far smaller than the file`() {
        val data = ByteArray(1_000_000) { (it % 97).toByte() }
        val source = ByteSource(data)
        val buffer = ByteArray(4_096)

        val result = DestinationVerifier.verify(
            source = source,
            totalBytes = data.size.toLong(),
            expected = null,
            buffer = buffer,
        )

        assertTrue(result is VerifyResult.VerifiedWithoutExpected)
        assertEquals(data.size.toLong(), (result as VerifyResult.VerifiedWithoutExpected).bytes)
        assertTrue(
            "no read may exceed the buffer, whatever the file size: ${source.maxRequested}",
            source.maxRequested <= 4_096,
        )
    }

    // --- long-range accounting, no bytes hashed --------------------------------

    @Test
    fun `the protocol maximum verifies with Long accounting and a bounded buffer`() {
        val total = 8_796_093_022_207L // 8 TiB - 1
        val source = VirtualSource(total)
        val digester = CountingDigester(abcHex)
        val buffer = ByteArray(8 * 1024 * 1024)

        val result = DestinationVerifier.verify(
            source = source,
            totalBytes = total,
            expected = null,
            buffer = buffer,
            newDigester = { digester },
        )

        assertTrue(result is VerifyResult.VerifiedWithoutExpected)
        assertEquals(total, digester.bytes)
        assertTrue("accounting must exceed the 32-bit range", digester.bytes > Int.MAX_VALUE)
        assertTrue(
            "no pass may exceed the buffer: ${digester.maxChunk}",
            digester.maxChunk <= buffer.size,
        )
        assertTrue(
            "no read request may exceed the buffer: ${source.maxRequested}",
            source.maxRequested <= buffer.size,
        )
    }

    @Test
    fun `a five gibibyte file costs one update per buffer, not one per byte`() {
        val total = 5_368_709_120L
        val digester = CountingDigester(abcHex)
        val buffer = ByteArray(65_536)

        DestinationVerifier.verify(
            source = VirtualSource(total),
            totalBytes = total,
            expected = null,
            buffer = buffer,
            newDigester = { digester },
        )

        assertEquals(81_920, digester.updates)
        assertEquals(total, digester.bytes)
    }

    // --- failure handling ------------------------------------------------------

    @Test
    fun `a source that ends early fails rather than digesting what it got`() {
        val result = DestinationVerifier.verify(
            source = ByteSource("ab".toByteArray()),
            totalBytes = 3L,
            expected = Sha256Digest.fromHex(abcHex),
        )
        assertTrue(result is VerifyResult.Failed)
        val error = (result as VerifyResult.Failed).error
        assertTrue(error is TransferStorageError.StateConflict)
        assertFalse(result.allowsCommit)
    }

    @Test
    fun `a source that never progresses stops at the documented threshold`() {
        val digester = CountingDigester(abcHex)
        var calls = 0

        val result = DestinationVerifier.verify(
            source = VerificationSource { _, _, _, _ -> calls++; 0 },
            totalBytes = 1_024L,
            expected = null,
            newDigester = { digester },
        )

        assertTrue(result is VerifyResult.Failed)
        assertTrue((result as VerifyResult.Failed).error is TransferStorageError.ZeroProgress)
        assertEquals(DestinationVerifier.MAX_ZERO_PROGRESS_STEPS, calls)
        assertEquals(0L, digester.bytes)
    }

    @Test
    fun `a source that over-reports a read is refused rather than trusted`() {
        val result = DestinationVerifier.verify(
            source = VerificationSource { _, _, _, length -> length + 1 },
            totalBytes = 1_024L,
            expected = null,
        )
        assertTrue(result is VerifyResult.Failed)
        assertTrue((result as VerifyResult.Failed).error is TransferStorageError.ProviderFailure)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative total is rejected rather than silently verifying nothing`() {
        DestinationVerifier.verify(ByteSource(ByteArray(0)), -1L, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty buffer is rejected rather than causing a zero-progress loop`() {
        DestinationVerifier.verify(ByteSource(ByteArray(8)), 8L, null, ByteArray(0))
    }

    @Test
    fun `each verify call gets a fresh digester rather than continuing an old one`() {
        val digesters = mutableListOf<CountingDigester>()
        val factory: () -> ChunkDigester = { CountingDigester(abcHex).also { digesters += it } }

        DestinationVerifier.verify(VirtualSource(100L), 100L, null, newDigester = factory)
        DestinationVerifier.verify(VirtualSource(100L), 100L, null, newDigester = factory)

        assertEquals(2, digesters.size)
        assertEquals(100L, digesters[0].bytes)
        assertEquals(100L, digesters[1].bytes)
    }
}
