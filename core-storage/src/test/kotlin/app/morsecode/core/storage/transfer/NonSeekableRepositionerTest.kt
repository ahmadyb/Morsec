package app.morsecode.core.storage.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounded non-seekable skip.
 *
 * Two properties are asserted here that matter more than the happy path: the
 * loop cannot spin forever on a stream that returns 0, and `Long` accounting
 * stays correct well past the point where an `Int` would wrap. No real bytes are
 * produced anywhere in this file — the fakes only report how many bytes they
 * claim to have delivered — so nothing here costs more than a few milliseconds
 * even at the 8 TiB boundary.
 */
class NonSeekableRepositionerTest {

    /** Counts calls and returns a fixed number of "bytes" per call. */
    private class FakeStream(private val perCall: Int) : SourceStream {
        var calls = 0
            private set
        var lastLength = -1
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            calls++
            lastLength = length
            return if (perCall == 0) 0 else minOf(perCall, length)
        }
    }

    /** Returns 0 forever: the case that must terminate. */
    private class StuckStream : SourceStream {
        var calls = 0
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            calls++
            return 0
        }
    }

    /** Returns 0 for a while, then makes progress, forever: must never fail. */
    private class StutteringStream(private val zerosBeforeProgress: Int) : SourceStream {
        var calls = 0
            private set
        private var zeros = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            calls++
            return if (zeros < zerosBeforeProgress) {
                zeros++
                0
            } else {
                zeros = 0
                minOf(1_024, length)
            }
        }
    }

    /** Delivers [total] bytes and then reports end of file. */
    private class FiniteStream(private val total: Long) : SourceStream {
        var delivered = 0L
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val remaining = total - delivered
            if (remaining <= 0L) return -1
            val count = minOf(remaining, length.toLong()).toInt()
            delivered += count.toLong()
            return count
        }
    }

    // --- the zero-progress policy ---------------------------------------

    @Test
    fun `a stream that never progresses stops at exactly the documented threshold`() {
        val stream = StuckStream()
        val result = NonSeekableRepositioner.reposition(stream, targetOffset = 1_000L)

        assertTrue(result is RepositionResult.Failed)
        val error = (result as RepositionResult.Failed).error
        assertTrue(error is TransferStorageError.ZeroProgress)
        assertEquals(NonSeekableRepositioner.MAX_ZERO_PROGRESS_STEPS, (error as TransferStorageError.ZeroProgress).steps)
        assertEquals(1_000L, error.bytesRemaining)
        assertEquals(NonSeekableRepositioner.MAX_ZERO_PROGRESS_STEPS, stream.calls)
    }

    @Test
    fun `the threshold is a stated constant, not an accident`() {
        assertEquals(64, NonSeekableRepositioner.MAX_ZERO_PROGRESS_STEPS)
    }

    @Test
    fun `zero-progress counter resets on every byte of real progress`() {
        // 63 zeros then a byte, repeated: one short of the threshold each time,
        // so it must run indefinitely without ever reporting failure.
        val stream = StutteringStream(zerosBeforeProgress = 63)
        val result = NonSeekableRepositioner.reposition(stream, targetOffset = 64L * 1_024L * 4L)

        assertTrue("stuttering stream must reach the offset", result is RepositionResult.Positioned)
        assertEquals(64L * 1_024L * 4L, (result as RepositionResult.Positioned).discarded)
    }

    @Test
    fun `zero-progress failure names how many bytes were still owed`() {
        val stream = StuckStream()
        val result = NonSeekableRepositioner.reposition(stream, targetOffset = 5_368_709_120L)
        val error = (result as RepositionResult.Failed).error as TransferStorageError.ZeroProgress
        assertEquals(5_368_709_120L, error.bytesRemaining)
        assertContainsNoPath(error.safeMessage())
    }

    // --- end of file -----------------------------------------------------

    @Test
    fun `end of file before the offset reports how far it got`() {
        val stream = FiniteStream(total = 1_000L)
        val result = NonSeekableRepositioner.reposition(stream, targetOffset = 4_096L)

        assertTrue(result is RepositionResult.EndOfFile)
        assertEquals(1_000L, (result as RepositionResult.EndOfFile).discarded)
    }

    @Test
    fun `end of file is never reported as success`() {
        val result = NonSeekableRepositioner.reposition(FiniteStream(10L), 100L)
        assertTrue(result is RepositionResult.EndOfFile)
    }

    // --- long-range accounting --------------------------------------------

    @Test
    fun `a five gibibyte skip is counted in Long and uses a bounded buffer`() {
        val target = 5_368_709_120L // 5 GiB
        val stream = FakeStream(perCall = Int.MAX_VALUE)
        val buffer = ByteArray(NonSeekableRepositioner.SKIP_BUFFER_BYTES)

        val result = NonSeekableRepositioner.reposition(stream, target, buffer)

        assertTrue(result is RepositionResult.Positioned)
        assertEquals(target, (result as RepositionResult.Positioned).discarded)
        // Every read was bounded by the buffer, never by the remaining size.
        assertEquals(NonSeekableRepositioner.SKIP_BUFFER_BYTES, stream.lastLength)
        assertEquals(81_920, stream.calls)
    }

    @Test
    fun `the protocol maximum offset is reachable without Int overflow`() {
        // 8 TiB - 1, the largest file the protocol admits. An 8 MiB buffer keeps
        // the call count near a million, which is milliseconds, while proving the
        // accumulator never wraps.
        val target = 8_796_093_022_207L
        val stream = FakeStream(perCall = Int.MAX_VALUE)
        val buffer = ByteArray(8 * 1024 * 1024)

        val result = NonSeekableRepositioner.reposition(stream, target, buffer)

        assertTrue(result is RepositionResult.Positioned)
        assertEquals(target, (result as RepositionResult.Positioned).discarded)
        assertTrue("discarded must exceed the 32-bit range", result.discarded > Int.MAX_VALUE.toLong())
    }

    @Test
    fun `a zero offset costs no reads at all`() {
        val stream = StuckStream()
        val result = NonSeekableRepositioner.reposition(stream, 0L)
        assertTrue(result is RepositionResult.Positioned)
        assertEquals(0L, (result as RepositionResult.Positioned).discarded)
        assertEquals(0, stream.calls)
    }

    @Test
    fun `an exact multiple of the buffer finishes without an extra read`() {
        val buffer = ByteArray(1_024)
        val stream = FakeStream(perCall = 1_024)
        val result = NonSeekableRepositioner.reposition(stream, 4_096L, buffer)
        assertTrue(result is RepositionResult.Positioned)
        assertEquals(4_096L, (result as RepositionResult.Positioned).discarded)
        assertEquals(4, stream.calls)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative offset is rejected rather than silently clamped`() {
        NonSeekableRepositioner.reposition(StuckStream(), -1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty buffer is rejected rather than causing a zero-progress loop`() {
        NonSeekableRepositioner.reposition(StuckStream(), 10L, ByteArray(0))
    }

    private fun assertContainsNoPath(message: String) {
        assertTrue("message must not contain a path separator: $message", !message.contains('/'))
    }
}
