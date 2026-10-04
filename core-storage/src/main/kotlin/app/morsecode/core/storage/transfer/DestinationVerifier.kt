package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.integrity.Sha256Accumulator
import app.morsecode.core.transfer.integrity.Sha256Digest

/*
 * Full-file verification over the staged destination.
 *
 * This runs after the last byte is confirmed and before anything is committed,
 * and it is the only thing standing between "the bytes arrived" and "the file is
 * real". It reads the destination back in bounded passes and folds every byte
 * into an incremental digest.
 *
 * Three properties matter.
 *
 *  1. Memory is bounded and constant. The buffer is fixed regardless of file
 *     size, so an 8 TiB file verifies in the same heap as a 4 KiB one. Nothing
 *     here reads the whole file into memory or into a ByteArray.
 *  2. Offsets are `Long` and the accounting is checked. The loop counts what it
 *     actually read and refuses to produce a verdict if that total is not
 *     exactly the file size, because a digest over a short read is a valid digest
 *     of the wrong data.
 *  3. A mismatch is returned, never published. The verifier does not rename,
 *     move, log, notify or commit anything; a mismatch comes back as a value and
 *     the caller decides. Digest bytes are transient verification material and
 *     must not be persisted or logged; only typed state and byte counts belong in
 *     recovery diagnostics.
 *
 * The digest is injectable so the loop can be tested over ranges no CI machine
 * should actually hash. That is not weaker testing, it is testing the right
 * thing with the right tool: the loop's job is byte accounting, buffer bounds
 * and progress handling, and a fake digester proves those over 8 TiB in
 * milliseconds. The digester's own correctness is proved separately, on bounded
 * real bytes, against the real SHA-256 implementation.
 */

/** The read surface verification needs. Offsets are absolute. */
public fun interface VerificationSource {
    /** Follows the `InputStream` contract: -1 at end of file, 0 for no progress. */
    public fun read(offset: Long, buffer: ByteArray, dataOffset: Int, length: Int): Int
}

/** Incremental digest over a stream of chunks. */
public interface ChunkDigester {
    public fun update(buffer: ByteArray, offset: Int, length: Int)
    public fun digest(): Sha256Digest
}

/** The real one: incremental SHA-256, no buffering of the file. */
public class Sha256Digester : ChunkDigester {
    private val accumulator = Sha256Accumulator()

    override fun update(buffer: ByteArray, offset: Int, length: Int) {
        accumulator.update(buffer, offset, length)
    }

    override fun digest(): Sha256Digest = accumulator.digest()
}

/** What verification concluded. */
public sealed interface VerifyResult {
    /** An expected digest was supplied and the file matched it. */
    public data class Matched(
        public val digest: Sha256Digest,
        public val bytes: Long,
    ) : VerifyResult

    /**
     * No expected digest was supplied, so the file is accepted on byte count
     * alone. Recorded honestly: this is not the same guarantee as [Matched].
     */
    public data class VerifiedWithoutExpected(
        public val digest: Sha256Digest,
        public val bytes: Long,
    ) : VerifyResult

    /**
     * The file does not match. Returned, never published: the caller decides
     * whether to discard, re-fetch or tell the user.
     */
    public data class Mismatched(
        public val expected: Sha256Digest,
        public val observed: Sha256Digest,
        public val bytes: Long,
    ) : VerifyResult

    public data class Failed(public val error: TransferStorageError) : VerifyResult

    /** True only when the file may be committed. */
    public val allowsCommit: Boolean
        get() = this is Matched || this is VerifiedWithoutExpected
}

public object DestinationVerifier {

    /** Read size per pass. Constant, so heap use does not grow with file size. */
    public const val VERIFY_BUFFER_BYTES: Int = 65_536

    /** Consecutive no-progress reads tolerated before giving up. */
    public const val MAX_ZERO_PROGRESS_STEPS: Int = 64

    /**
     * Verifies [totalBytes] bytes read through [source].
     *
     * [expected] may be null when the sender supplied no digest, which yields
     * [VerifyResult.VerifiedWithoutExpected] rather than a silent pass.
     */
    public fun verify(
        source: VerificationSource,
        totalBytes: Long,
        expected: Sha256Digest?,
        buffer: ByteArray = ByteArray(VERIFY_BUFFER_BYTES),
        maxZeroProgressSteps: Int = MAX_ZERO_PROGRESS_STEPS,
        newDigester: () -> ChunkDigester = { Sha256Digester() },
    ): VerifyResult {
        require(totalBytes >= 0L) { "totalBytes must not be negative, was $totalBytes" }
        require(buffer.isNotEmpty()) { "buffer must not be empty" }
        require(maxZeroProgressSteps >= 1) { "maxZeroProgressSteps must be at least 1" }

        val digester = newDigester()

        var offset = 0L
        var zeroSteps = 0
        val capacity = buffer.size

        while (offset < totalBytes) {
            val remaining = totalBytes - offset
            val toRead = if (remaining < capacity.toLong()) remaining.toInt() else capacity
            val count = source.read(offset, buffer, 0, toRead)

            when {
                count < 0 -> return VerifyResult.Failed(
                    TransferStorageError.StateConflict(
                        reason = "verification_source_ended_early",
                        diagnostic = "source ended at $offset of $totalBytes bytes",
                    ),
                )

                count == 0 -> {
                    zeroSteps++
                    if (zeroSteps >= maxZeroProgressSteps) {
                        return VerifyResult.Failed(
                            TransferStorageError.ZeroProgress(
                                steps = zeroSteps,
                                bytesRemaining = totalBytes - offset,
                            ),
                        )
                    }
                }

                count > toRead -> return VerifyResult.Failed(
                    TransferStorageError.ProviderFailure(
                        provider = "verification_source",
                        diagnostic = "read returned $count for a request of $toRead",
                    ),
                )

                else -> {
                    digester.update(buffer, 0, count)
                    offset += count.toLong()
                    zeroSteps = 0
                }
            }
        }

        if (offset != totalBytes) {
            return VerifyResult.Failed(
                TransferStorageError.StateConflict(
                    reason = "verification_length_mismatch",
                    diagnostic = "read $offset of $totalBytes bytes",
                ),
            )
        }

        val observed = digester.digest()
        return when {
            expected == null -> VerifyResult.VerifiedWithoutExpected(observed, offset)
            expected.contentEquals(observed) -> VerifyResult.Matched(observed, offset)
            else -> VerifyResult.Mismatched(expected, observed, offset)
        }
    }
}
