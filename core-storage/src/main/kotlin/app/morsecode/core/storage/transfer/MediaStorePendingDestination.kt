package app.morsecode.core.storage.transfer

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import app.morsecode.core.transfer.integrity.Sha256Digest
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/*
 * A partial held in a pending MediaStore row, API 29 and above.
 *
 * This is the only mechanism on the platform that genuinely hides an incomplete
 * file: while `IS_PENDING` is set the item is invisible to other apps and to the
 * user's gallery, and clearing the flag is what publishes it. So the flag is the
 * commit, and the rules around it are the rules around a commit:
 *
 *   * it is set when the row is created and stays set through every write;
 *   * it is cleared only after verification passes;
 *   * clearing it is idempotent, because a crash between "the flag is cleared"
 *     and "we recorded that we cleared it" must not lose or duplicate the file.
 *
 * The Uri is the durable handle. It is the only thing that survives a process
 * death and lets the same pending row be reopened, so it is recorded with the
 * partial rather than held in memory.
 *
 * Durability is the part that is easiest to get wrong here. `channel.force(true)`
 * can be called and will usually return without error, and it is tempting to
 * report that as fsync-grade. Through a provider it is not: the bytes have been
 * handed to another process, which may have buffered them. So a successful flush
 * reports `FlushAttemptedGuaranteeUnknown` — never `DurableFlushSupported` — and
 * only the app-private strategies may claim durable. See ADR-0003 §3.
 */

/** Outcome of creating a pending row. */
public sealed interface PendingCreation {
    /** The pending row exists; [uri] is what must be recorded to reopen it. */
    public data class Created(public val uri: Uri) : PendingCreation

    public data class Refused(public val error: TransferStorageError) : PendingCreation
}

/** Outcome of reopening a pending row. */
public sealed interface PendingOpen {
    public data class Opened(public val partial: MediaStorePendingPartial) : PendingOpen
    public data class Refused(public val error: TransferStorageError) : PendingOpen
}

/** Outcome of publishing a pending row. */
public sealed interface Publication {
    /** Verification passed and the pending flag was cleared. */
    public data object Published : Publication

    /**
     * The flag was already clear. Reached after a restart that interrupted
     * publication, and treated as success rather than as an error to retry.
     */
    public data object AlreadyPublished : Publication

    /** Verification ran and did not pass. The item stays pending and hidden. */
    public data class Blocked(public val result: VerifyResult) : Publication

    /** Nothing was verified: the row could not be opened or is gone. */
    public data class Refused(public val error: TransferStorageError) : Publication
}

/**
 * An incomplete file in a pending MediaStore row.
 *
 * Writes are positional, because after a resume the first write is not at zero.
 *
 * Two channels are held over one descriptor rather than one: a channel taken
 * from a `FileOutputStream` is write-only, and verification has to be able to
 * read the bytes back. Both are used with positional reads and writes only, so
 * neither consumes or disturbs the descriptor's own file offset, and both are
 * owned by the one resource that closes them.
 */
public class MediaStorePendingPartial internal constructor(
    public val identity: PartialIdentity,
    public val uri: Uri,
    private val resource: OwnedSourceResource,
    private val writeChannel: FileChannel,
    private val readChannel: FileChannel,
) : PartialSink, Closeable {

    /** Times the descriptor underneath has actually been closed. For tests. */
    public val closeCount: Int get() = resource.descriptor().closeCount

    override fun length(): Long = try {
        writeChannel.size()
    } catch (e: IOException) {
        -1L
    }

    override fun writeAt(
        offset: Long,
        buffer: ByteArray,
        dataOffset: Int,
        length: Int,
    ): WriteOutcome = try {
        val source = ByteBuffer.wrap(buffer, dataOffset, length)
        var written = 0
        while (source.hasRemaining()) {
            val count = writeChannel.write(source, offset + written.toLong())
            if (count <= 0) break
            written += count
        }
        if (written == length) {
            WriteOutcome.Written(written, writeChannel.size())
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
    } catch (e: SecurityException) {
        // A grant withdrawn mid-transfer, which is a different recovery from a
        // full disk and must not be reported as one.
        WriteOutcome.Failed(TransferStorageError.PermissionRevoked("write"))
    }

    /**
     * Attempts the strongest flush available and reports what it actually means.
     *
     * Deliberately never `DurableFlushSupported`. The call may succeed; through a
     * provider that is evidence the provider accepted the bytes, not that any
     * device has committed them, and the row must record the weaker claim so a
     * later reconciliation of a short file is honest rather than mysterious.
     */
    override fun flush(): FlushDurability = try {
        writeChannel.force(true)
        FlushDurability.FlushAttemptedGuaranteeUnknown
    } catch (e: IOException) {
        FlushDurability.FlushFailed(TransferStorageError.Io("sync", e.message))
    } catch (e: SecurityException) {
        FlushDurability.FlushFailed(TransferStorageError.PermissionRevoked("write"))
    }

    override fun truncateTo(offset: Long): TruncateOutcome = try {
        writeChannel.truncate(offset)
        TruncateOutcome.Truncated(writeChannel.size())
    } catch (e: IOException) {
        TruncateOutcome.Failed(TransferStorageError.Io("truncate", e.message))
    }

    /** Reads this partial back for verification, in bounded positional reads. */
    public fun verificationSource(): VerificationSource =
        VerificationSource { offset, buffer, dataOffset, length ->
            val target = ByteBuffer.wrap(buffer, dataOffset, length)
            var total = 0
            while (target.hasRemaining()) {
                val count = readChannel.read(target, offset + total.toLong())
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
 * Creates, reopens, publishes and abandons pending MediaStore rows.
 *
 * Everything here is API 29+. Below that there is no pending column, and these
 * are refused rather than silently downgraded — a fallback that wrote a visible
 * partial under the final name would be worse than an honest refusal, because
 * the caller could then not tell what it was getting. The legacy and SAF paths
 * cover those levels.
 */
public object MediaStorePendingDestination {

    /** The first SDK with a pending mechanism. */
    public const val MIN_SDK: Int = 29

    public fun isAvailable(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= MIN_SDK

    /**
     * Inserts a pending row and returns its Uri.
     *
     * The Uri is the whole point of the return value: it is what makes the item
     * reopenable after the process that created it has gone.
     */
    @RequiresApi(29)
    public fun create(
        resolver: ContentResolver,
        collection: Uri,
        displayName: String,
        mimeType: String,
        relativePath: String? = null,
        sdk: Int = Build.VERSION.SDK_INT,
    ): PendingCreation {
        if (!isAvailable(sdk)) {
            return PendingCreation.Refused(
                TransferStorageError.Unsupported("media_store_pending"),
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            // Pending from the moment it exists: there is no window in which a
            // zero-byte file is visible under its final name.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (!relativePath.isNullOrBlank()) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
        }
        return try {
            val uri = resolver.insert(collection, values)
                ?: return PendingCreation.Refused(
                    TransferStorageError.ProviderFailure("media_store", "insert returned null"),
                )
            PendingCreation.Created(uri)
        } catch (e: SecurityException) {
            PendingCreation.Refused(TransferStorageError.PermissionRevoked("write"))
        } catch (e: IllegalArgumentException) {
            PendingCreation.Refused(
                TransferStorageError.ProviderFailure("media_store", e.message),
            )
        } catch (e: IOException) {
            PendingCreation.Refused(TransferStorageError.Io("open", e.message))
        }
    }

    /**
     * Reopens a pending row by the Uri recorded when it was created.
     *
     * This is the recovery path after a process death, and the reason the Uri is
     * persisted rather than held in memory.
     */
    public fun open(
        resolver: ContentResolver,
        uri: Uri,
        identity: PartialIdentity,
        sdk: Int = Build.VERSION.SDK_INT,
    ): PendingOpen {
        if (!isAvailable(sdk)) {
            return PendingOpen.Refused(
                TransferStorageError.Unsupported("media_store_pending"),
            )
        }
        val descriptor = try {
            // Read-write, not write-only: verification has to read the bytes back
            // through this same descriptor before anything is published.
            resolver.openFileDescriptor(uri, "rw")
                ?: return PendingOpen.Refused(
                    TransferStorageError.ProviderFailure(
                        "media_store",
                        "openFileDescriptor returned null",
                    ),
                )
        } catch (e: SecurityException) {
            return PendingOpen.Refused(TransferStorageError.PermissionRevoked("write"))
        } catch (e: java.io.FileNotFoundException) {
            // The row was deleted — by the user, or by an earlier cleanup pass.
            return PendingOpen.Refused(TransferStorageError.NotFound("partial"))
        } catch (e: IOException) {
            return PendingOpen.Refused(TransferStorageError.Io("open", e.message))
        } catch (e: IllegalArgumentException) {
            return PendingOpen.Refused(
                TransferStorageError.ProviderFailure("media_store", e.message),
            )
        }

        val resource = OwnedSourceResource(OwnedFileDescriptor(descriptor))
        return try {
            val writeChannel = resource.adopt(FileOutputStream(descriptor.fileDescriptor).channel)
            val readChannel = resource.adopt(FileInputStream(descriptor.fileDescriptor).channel)
            PendingOpen.Opened(
                MediaStorePendingPartial(identity, uri, resource, writeChannel, readChannel),
            )
        } catch (e: IOException) {
            resource.close()
            PendingOpen.Refused(TransferStorageError.Io("open", e.message))
        }
    }

    /** Whether the row is still flagged pending. */
    @RequiresApi(29)
    public fun isPending(
        resolver: ContentResolver,
        uri: Uri,
        sdk: Int = Build.VERSION.SDK_INT,
    ): Boolean {
        if (!isAvailable(sdk)) return false
        val cursor = try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)
        } catch (e: Exception) {
            null
        } ?: return false
        cursor.use {
            if (!it.moveToFirst()) return false
            val index = it.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
            if (index < 0 || it.isNull(index)) return false
            return it.getInt(index) != 0
        }
    }

    /**
     * Verifies the pending item and, only then, clears the pending flag.
     *
     * The order is the whole method: publish first and verify afterwards and
     * there is a window in which a corrupt file is visible in the user's gallery
     * under its final name.
     *
     * Idempotent. [Publication.AlreadyPublished] is returned when the flag is
     * already clear, which is what a restart that interrupted publication looks
     * like, so the caller records success instead of publishing twice.
     */
    @RequiresApi(29)
    public fun publish(
        resolver: ContentResolver,
        uri: Uri,
        identity: PartialIdentity,
        expectedBytes: Long,
        expected: Sha256Digest?,
        sdk: Int = Build.VERSION.SDK_INT,
        buffer: ByteArray = ByteArray(DestinationVerifier.VERIFY_BUFFER_BYTES),
        newDigester: () -> ChunkDigester = { Sha256Digester() },
    ): Publication {
        if (!isAvailable(sdk)) {
            return Publication.Refused(
                TransferStorageError.Unsupported("media_store_pending"),
            )
        }
        if (!isPending(resolver, uri, sdk)) {
            return Publication.AlreadyPublished
        }

        val partial = when (val opened = open(resolver, uri, identity, sdk)) {
            is PendingOpen.Opened -> opened.partial
            is PendingOpen.Refused -> return Publication.Refused(opened.error)
        }

        return try {
            val result = DestinationVerifier.verify(
                source = partial.verificationSource(),
                totalBytes = expectedBytes,
                expected = expected,
                buffer = buffer,
                newDigester = newDigester,
            )
            if (!result.allowsCommit) {
                // Left pending and therefore still hidden: a corrupt file is not
                // published in order to be deleted.
                return Publication.Blocked(result)
            }
            clearPending(resolver, uri)
        } finally {
            partial.close()
        }
    }

    /**
     * Deletes a pending row.
     *
     * The only deletion path, and reached only from the cleanup policy — never
     * from an interrupted transfer, because deleting a partial that a later pass
     * might still resume throws away work that cost the user bandwidth.
     */
    public fun abandon(
        resolver: ContentResolver,
        uri: Uri,
    ): Boolean = try {
        resolver.delete(uri, null, null) > 0
    } catch (e: SecurityException) {
        false
    } catch (e: Exception) {
        false
    }

    @RequiresApi(29)
    private fun clearPending(resolver: ContentResolver, uri: Uri): Publication = try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        if (resolver.update(uri, values, null, null) > 0) {
            Publication.Published
        } else {
            Publication.Refused(
                TransferStorageError.ProviderFailure("media_store", "update touched no rows"),
            )
        }
    } catch (e: SecurityException) {
        Publication.Refused(TransferStorageError.PermissionRevoked("write"))
    } catch (e: IllegalArgumentException) {
        Publication.Refused(
            TransferStorageError.ProviderFailure("media_store", e.message),
        )
    }

    /** Convenience for tests and for a caller that re-recorded only the string. */
    public fun uriOf(uriString: String): Uri = uriString.toUri()
}
