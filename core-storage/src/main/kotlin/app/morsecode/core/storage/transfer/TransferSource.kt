package app.morsecode.core.storage.transfer

import android.net.Uri
import app.morsecode.core.transfer.identity.RelativeTransferPath
import java.io.Closeable
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/*
 * The source side of a transfer.
 *
 * This is the Android half of what `TransferEffect.RequestSourceStream` asks
 * for, and it exists because the core must not know what a Uri is. The core
 * names an offset; this module decides what that offset means for a
 * MediaStore row, a SAF document or an app-private file, and owns the
 * descriptor while the answer is being read.
 *
 * Three things it refuses to do:
 *
 *  * Expose an absolute path. `key` is opaque, `uri` is a content or document
 *    Uri, and `relativePath` is validated by `RelativeTransferPath` in the pure
 *    core. Nothing here hands a filesystem path to a caller who could log it.
 *  * Offer a stream that is not positioned. `openAt` either returns a handle
 *    sitting at the requested offset or a typed failure; there is no
 *    "opened, caller must now skip" state that a caller can forget.
 *  * Leave a descriptor dangling. See `OwnedFileDescriptor`.
 *
 * Positions and sizes are `Long` throughout. The maximum file size the protocol
 * admits is 8 TiB - 1, so an `Int` anywhere in this path would silently wrap
 * somewhere between 2 GiB and 4 GiB on a 32-bit-friendly device.
 */

/** Opaque, stable identity for a source. Never a path, never a Uri string. */
public data class TransferSourceKey(public val value: String) {
    init {
        require(value.isNotBlank()) { "TransferSourceKey must not be blank" }
    }
}

/** The outcome of opening a source. */
public sealed interface SourceOpenResult {
    /** Positioned exactly at [offset]; the caller owns the handle and must close it. */
    public data class Opened(
        public val handle: SourceHandle,
        public val offset: Long,
    ) : SourceOpenResult

    public data class Failed(public val error: TransferStorageError) : SourceOpenResult
}

/**
 * A source positioned at a known offset, owned by whoever opened it.
 *
 * `read` follows the `InputStream` contract exactly: -1 is end of file, a
 * positive value is that many bytes, and 0 means no progress. The
 * non-seekable reposition path treats that 0 as meaningful and bounded
 * (see `NonSeekableRepositioner`) rather than looping on it forever.
 */
public sealed interface SourceHandle : Closeable {
    /** Whether the underlying object can be positioned directly. */
    public val isSeekable: Boolean

    /** Offset this handle was opened at. */
    public val openedAtOffset: Long

    /** Bytes read through this handle since it was opened. */
    public val bytesRead: Long

    /** Current offset. Derived, never guessed. */
    public val position: Long get() = openedAtOffset + bytesRead

    /** Reads up to [length] bytes into [buffer] at [offset]; -1 at end of file. */
    public fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

internal class ChannelSourceHandle(
    private val resource: Closeable,
    private val channel: FileChannel,
    override val openedAtOffset: Long,
) : SourceHandle {

    override val isSeekable: Boolean get() = true

    private var read: Long = 0L
    override val bytesRead: Long get() = read

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val target = ByteBuffer.wrap(buffer, offset, length)
        val count = channel.read(target)
        if (count > 0) read += count.toLong()
        return count
    }

    override fun close() {
        resource.close()
    }
}

internal class StreamSourceHandle(
    private val resource: Closeable,
    private val stream: InputStream,
    override val openedAtOffset: Long,
) : SourceHandle {

    override val isSeekable: Boolean get() = false

    private var read: Long = 0L
    override val bytesRead: Long get() = read

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = stream.read(buffer, offset, length)
        if (count > 0) read += count.toLong()
        return count
    }

    override fun close() {
        resource.close()
    }
}

/**
 * One file a transfer can read from.
 *
 * Implementations are cheap to hold and stateless; the expensive part is the
 * descriptor, which is created only by [openAtZero] or [openAt] and is owned by
 * the returned handle.
 */
public interface TransferSource {

    /** Opaque stable key. Two reads of the same file produce the same key. */
    public val key: TransferSourceKey

    /** Content, document or file Uri. Carried for display and for grant checks. */
    public val uri: Uri

    /** File name shown to the user. Never contains a separator. */
    public val displayName: String

    /** Where this file sits inside the batch, validated by the pure core. */
    public val relativePath: RelativeTransferPath

    /** MIME type, or empty when it could not be determined. */
    public val mimeType: String

    /** Exact size in bytes, or -1 when the source cannot say. */
    public val sizeBytes: Long

    /** Last-modified in epoch millis, or null when unavailable. */
    public val lastModifiedEpochMillis: Long?

    /** Whether [openAt] can position directly rather than by skipping. */
    public val isSeekable: Boolean

    /**
     * Re-reads the source's identity. Cheap: a query or a stat, never a read of
     * the content.
     */
    public fun fingerprint(): SourceFingerprintResult

    /** Opens at offset 0. */
    public fun openAtZero(): SourceOpenResult = openAt(0L)

    /**
     * Opens positioned at [offset].
     *
     * For a seekable source this is a `FileChannel.position` call. For a
     * non-seekable one it reopens and discards bytes under the bounded
     * zero-progress policy, and a failure to reach the offset is returned as a
     * typed error rather than as a handle at the wrong position.
     */
    public fun openAt(offset: Long): SourceOpenResult
}
