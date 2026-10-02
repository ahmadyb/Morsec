package app.morsecode.core.storage.transfer

/*
 * Repositioning a stream that cannot seek.
 *
 * A ContentResolver pipe, a document provider's stream and anything else behind
 * `openInputStream` has no `position()`. Reaching offset N means reopening and
 * throwing away N bytes, and while doing that the only progress signal available
 * is what `read()` returns.
 *
 * The contract this loop is written against, stated explicitly because it is the
 * part that is usually guessed:
 *
 *   * `read() == -1` is end of file. Nothing more will ever arrive.
 *   * `read() > 0` is that many bytes of real progress.
 *   * `read() == 0` is *no progress* — legal, not an error, and not end of file.
 *     A pipe whose writer is slower than the reader returns 0 repeatedly and
 *     would return it forever if the writer never produced another byte.
 *
 * So zero is counted. After MAX_ZERO_PROGRESS_STEPS consecutive zero-length
 * reads the loop gives up and reports `ZeroProgress` rather than spinning. The
 * threshold is a fixed, documented constant rather than a heuristic, and it is
 * asserted by a test that feeds the loop a stream which always returns 0 and
 * counts the reads: the loop must stop at exactly that many, not one fewer and
 * not one more.
 */

/** The minimal read surface the reposition loop needs, so tests need no Android. */
public fun interface SourceStream {
    /** Follows the `InputStream` contract: -1 at end of file, 0 for no progress. */
    public fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/** Outcome of discarding bytes to reach an offset. */
public sealed interface RepositionResult {
    /** The stream is at the requested offset; [discarded] bytes were thrown away. */
    public data class Positioned(public val discarded: Long) : RepositionResult

    /**
     * End of file was reached first, after discarding [discarded] bytes. The
     * source is shorter than the requested offset, which is a real and
     * reportable condition rather than a reason to keep reading.
     */
    public data class EndOfFile(public val discarded: Long) : RepositionResult

    public data class Failed(public val error: TransferStorageError) : RepositionResult
}

public object NonSeekableRepositioner {

    /**
     * Consecutive `read() == 0` results tolerated before giving up.
     *
     * Sized so a pipe that is merely slow gets many chances to produce a byte
     * while a pipe that will never produce one cannot hold a thread forever.
     */
    public const val MAX_ZERO_PROGRESS_STEPS: Int = 64

    /**
     * Bytes discarded per `read` call. Bounded and constant, so a 5 GiB skip
     * costs the same heap as a 64 KiB one; only the number of calls differs.
     */
    public const val SKIP_BUFFER_BYTES: Int = 65_536

    /**
     * Discards bytes from [stream] until [targetOffset] has been consumed.
     *
     * Never returns a result that claims a position it did not reach.
     */
    public fun reposition(
        stream: SourceStream,
        targetOffset: Long,
        buffer: ByteArray = ByteArray(SKIP_BUFFER_BYTES),
    ): RepositionResult {
        require(targetOffset >= 0L) { "targetOffset must not be negative, was $targetOffset" }
        require(buffer.isNotEmpty()) { "buffer must not be empty" }

        if (targetOffset == 0L) return RepositionResult.Positioned(0L)

        var discarded = 0L
        var zeroSteps = 0
        val capacity = buffer.size

        while (discarded < targetOffset) {
            val remaining = targetOffset - discarded
            val toRead = if (remaining < capacity.toLong()) remaining.toInt() else capacity
            val count = stream.read(buffer, 0, toRead)

            when {
                count < 0 -> return RepositionResult.EndOfFile(discarded)

                count == 0 -> {
                    zeroSteps++
                    if (zeroSteps >= MAX_ZERO_PROGRESS_STEPS) {
                        return RepositionResult.Failed(
                            TransferStorageError.ZeroProgress(
                                steps = zeroSteps,
                                bytesRemaining = targetOffset - discarded,
                            ),
                        )
                    }
                }

                else -> {
                    discarded += count.toLong()
                    zeroSteps = 0
                }
            }
        }

        return RepositionResult.Positioned(discarded)
    }
}
