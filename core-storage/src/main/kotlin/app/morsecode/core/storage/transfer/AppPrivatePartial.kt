package app.morsecode.core.storage.transfer

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/*
 * An incomplete file in app-private storage.
 *
 * This is the destination everything that can be staged is staged into, and it is
 * the only one where the durability story is simple: it is a regular file the
 * app owns, so `FileChannel.force(true)` is a real fsync and a successful flush
 * means the bytes are on stable storage.
 *
 * That property is why SAF_STAGED is the default for SAF destinations. The
 * transfer itself gets fsync-grade durability for its whole duration, and only
 * the final bounded copy relies on a provider that may buffer. Writing straight
 * into the user's chosen document would trade that away for the sake of avoiding
 * one copy, and would leave a truncated file under its final name if anything
 * interrupted it.
 *
 * Writes are positional (`channel.write(buffer, position)`) rather than
 * sequential, because after a resume the first write is not at zero and a cursor
 * that assumed otherwise would append to the wrong place.
 */

/**
 * A partial backed by a real file.
 *
 * The caller owns this object and must close it; the descriptor underneath is
 * owned by it, once.
 */
public class AppPrivatePartial internal constructor(
    public val identity: PartialIdentity,
    private val resource: OwnedResource,
    private val channel: FileChannel,
) : PartialSink, Closeable {

    /** Times [close] has actually closed the underlying file. For tests. */
    public val closeCount: Int get() = resource.closeCount

    override fun length(): Long = channel.size()

    override fun writeAt(
        offset: Long,
        buffer: ByteArray,
        dataOffset: Int,
        length: Int,
    ): WriteOutcome = try {
        val source = ByteBuffer.wrap(buffer, dataOffset, length)
        var written = 0
        while (source.hasRemaining()) {
            val count = channel.write(source, offset + written.toLong())
            if (count <= 0) break
            written += count
        }
        if (written == length) {
            WriteOutcome.Written(written, channel.size())
        } else {
            WriteOutcome.Failed(
                TransferStorageError.Io(
                    operation = "write",
                    diagnostic = "wrote $written of $length bytes at $offset",
                ),
            )
        }
    } catch (e: IOException) {
        WriteOutcome.Failed(TransferStorageError.Io("write", e.message))
    }

    override fun flush(): FlushDurability = try {
        // A real fsync on a real file. This is the one place in the group where
        // "durable" may be claimed without qualification.
        channel.force(true)
        FlushDurability.DurableFlushSupported
    } catch (e: IOException) {
        FlushDurability.FlushFailed(TransferStorageError.Io("sync", e.message))
    }

    override fun truncateTo(offset: Long): TruncateOutcome = try {
        channel.truncate(offset)
        TruncateOutcome.Truncated(channel.size())
    } catch (e: IOException) {
        TruncateOutcome.Failed(TransferStorageError.Io("truncate", e.message))
    }

    /** Reads this partial back for verification, in bounded positional reads. */
    public fun verificationSource(): VerificationSource =
        VerificationSource { offset, buffer, dataOffset, length ->
            val target = ByteBuffer.wrap(buffer, dataOffset, length)
            var total = 0
            while (target.hasRemaining()) {
                val count = channel.read(target, offset + total.toLong())
                if (count <= 0) break
                total += count
            }
            if (total == 0 && length > 0) -1 else total
        }

    override fun close() {
        resource.close()
    }
}

/**
 * Where staged partials live.
 *
 * The on-disk name is derived from the identity, not the other way round: a
 * rename, or a run in a different locale, cannot orphan the recovery row.
 */
public class AppPrivatePartialStore(private val directory: File) {

    /** Allowed characters in a staged file name. Everything else becomes '-'. */
    private val safeNameCharacter = Regex("""[A-Za-z0-9._-]""")

    public fun fileNameFor(identity: PartialIdentity): String {
        val body = identity.value.map { char ->
            if (safeNameCharacter.matches(char.toString())) char else '-'
        }.joinToString("")
        return "morsec-$body.part"
    }

    public fun fileFor(identity: PartialIdentity): File =
        File(directory, fileNameFor(identity))

    public fun exists(identity: PartialIdentity): Boolean = fileFor(identity).exists()

    /** Length in bytes, or null when there is no partial at all. */
    public fun lengthOrNull(identity: PartialIdentity): Long? =
        fileFor(identity).takeIf { it.exists() }?.length()

    /**
     * Opens (creating if needed) the partial for [identity].
     *
     * If the descriptor cannot be turned into a channel, the descriptor is closed
     * before the failure is reported rather than leaked.
     */
    public fun open(identity: PartialIdentity): AppPrivatePartial {
        if (!directory.exists() && !directory.mkdirs()) {
            // Deliberately not a path in the message: this exception text can
            // reach a crash report.
            throw IOException(TransferStorageError.Io("open").safeMessage())
        }
        val file = fileFor(identity)
        // RandomAccessFile rather than FileOutputStream: a channel taken from a
        // FileOutputStream is write-only, so verification could not read the
        // bytes back. And rather than FileChannel.open, which needs
        // java.nio.file and therefore API 26 — this module supports API 23.
        val random = RandomAccessFile(file, "rw")
        val resource = OwnedResource(random)
        return try {
            val channel = resource.adopt(random.channel)
            AppPrivatePartial(identity, resource, channel)
        } catch (e: IOException) {
            resource.close()
            throw e
        }
    }

    /** Deletes the partial. Idempotent: deleting what is not there succeeds. */
    public fun delete(identity: PartialIdentity): Boolean {
        val file = fileFor(identity)
        return !file.exists() || file.delete()
    }
}
