package app.morsecode.core.storage.transfer

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import app.morsecode.core.transfer.integrity.Sha256Digest
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/*
 * A partial held in a pending MediaStore row, API 29 and above.
 *
 * `IS_PENDING` is the only mechanism on the platform that genuinely hides an
 * incomplete file: while it is set the item is invisible to other apps and to the
 * user's gallery, and clearing the flag is what publishes it. So the flag is the
 * commit, and the rules around it are the rules around a commit.
 *
 * One owner per descriptor.
 *
 * The write phase and the verification phase each open their own descriptor and
 * each close it completely before the next begins. Nothing in this file holds two
 * wrappers — two channels, a stream and a channel — over one descriptor. Doing so
 * is unsafe in ways that are individually survivable and collectively a trap: the
 * wrappers may share one mutable file position, closing one may invalidate the
 * descriptor the other is using, ownership becomes ambiguous, and a provider is
 * under no obligation to support concurrent read and write wrappers over the same
 * descriptor. The cost of separate phases is one extra open per verification,
 * which happens once per transfer.
 *
 * Durability is the other thing that is easy to get wrong here.
 * `channel.force(true)` can be called and will usually return without error, and
 * it is tempting to report that as fsync-grade. Through a provider it is not: the
 * bytes have been handed to another process, which may have buffered them. So a
 * successful flush reports `FlushAttemptedGuaranteeUnknown` — never
 * `DurableFlushSupported` — and only the app-private strategies may claim durable.
 * See ADR-0003 §3.
 */

/**
 * Whether a pending row exists, and if so whether it is still hidden.
 *
 * [UNKNOWN] is the state that must exist and must not be collapsed into any of
 * the others. Failing to read `IS_PENDING` does not prove publication, and a
 * query that throws does not prove the row is gone. Collapsing "I could not tell"
 * into "published" would turn an unreadable row into a completed transfer;
 * collapsing it into "missing" would let a cleanup pass delete a file that is
 * still there.
 */
public enum class PendingState {
    /** The row exists and `IS_PENDING` is confirmed as 1. */
    PENDING,

    /** The row exists and `IS_PENDING` is confirmed as 0. The file is visible. */
    PUBLISHED,

    /** A query that completed successfully proves there is no row. */
    MISSING,

    /**
     * Row existence or the pending value cannot be determined reliably.
     *
     * The required column is absent, the query failed, a permission was
     * unavailable, or the provider returned something inconsistent.
     */
    UNKNOWN,
}

/** Outcome of creating a pending row. */
public sealed interface PendingCreation {
    /** The pending row exists; [uri] is what must be recorded to reopen it. */
    public data class Created(public val uri: Uri) : PendingCreation

    public data class Refused(public val error: TransferStorageError) : PendingCreation
}

/** Outcome of opening a phase owner over a pending row. */
public sealed interface PendingOpen {
    public data class Opened(public val partial: MediaStorePendingPartial) : PendingOpen
    public data class Refused(public val error: TransferStorageError) : PendingOpen
}

/** Outcome of reading a pending row back for verification. */
public sealed interface PendingReadOpen {
    public data class Opened(public val reader: PendingReader) : PendingReadOpen
    public data class Refused(public val error: TransferStorageError) : PendingReadOpen
}

/** Outcome of the verification phase. */
public sealed interface VerificationPhase {
    public data class Completed(public val result: VerifyResult) : VerificationPhase
    public data class Refused(public val error: TransferStorageError) : VerificationPhase
}

/** Outcome of publishing a pending row. */
public sealed interface Publication {

    /** Verification passed, the flag was cleared, and the final state confirms it. */
    public data object Published : Publication

    /** The durable record and the provider both say committed. */
    public data object AlreadyPublished : Publication

    /**
     * The provider says published but the durable record does not, and an
     * identity check — a matching digest, not merely a byte count — confirmed
     * that the bytes are the ones this transfer was writing.
     */
    public data object ExternallyCompleted : Publication

    /** Verification ran and did not pass. The item stays pending and hidden. */
    public data class Blocked(public val result: VerifyResult) : Publication

    /** Nothing was verified: the row could not be opened, read or found. */
    public data class Refused(public val error: TransferStorageError) : Publication

    /**
     * The state could not be determined, so nothing was decided.
     *
     * Never success, never a deletion, never a duplicate row. The caller must
     * surface it for reconciliation on a later pass.
     */
    public data class ReconciliationRequired(
        public val state: PendingState,
        public val detail: String,
    ) : Publication

    /**
     * The durable record and the provider disagree in a way that no automatic
     * rule may resolve.
     */
    public data class Inconsistent(
        public val recorded: CommitState?,
        public val observed: PendingState,
        public val detail: String,
    ) : Publication

    public companion object {
        /**
         * Whether this outcome may be recorded as COMMITTED.
         *
         * The one rule the rest of the group depends on: [UNKNOWN] and [MISSING]
         * cannot reach it. Both are answerable by asking again, and neither is
         * evidence that the bytes are at their final location.
         */
        public fun isCommitted(outcome: Publication): Boolean = when (outcome) {
            is Publication.Published,
            is Publication.AlreadyPublished,
            is Publication.ExternallyCompleted,
            -> true

            is Publication.Blocked,
            is Publication.Refused,
            is Publication.ReconciliationRequired,
            is Publication.Inconsistent,
            -> false
        }
    }
}

/** Outcome of abandoning a pending row. */
public sealed interface AbandonOutcome {
    public data object Deleted : AbandonOutcome
    public data object AlreadyGone : AbandonOutcome

    /**
     * The row's state could not be determined, so it was not deleted.
     *
     * An unknown row is never reaped: if it turns out to hold a complete file,
     * deleting it destroys the user's data, and the whole point of the pending
     * mechanism is that a hidden row costs nothing while it waits.
     */
    public data class ReconciliationRequired(
        public val state: PendingState,
        public val detail: String,
    ) : AbandonOutcome
}

/**
 * The write phase of a pending row: one descriptor, one owner, one close.
 *
 * Writes are positional, because after a resume the first write is not at zero.
 * There is deliberately no read surface on this class — verification is a
 * separate phase with its own descriptor.
 */
public class MediaStorePendingPartial internal constructor(
    public val identity: PartialIdentity,
    public val uri: Uri,
    private val resource: OwnedResource,
    private val channel: FileChannel,
) : PartialSink, Closeable {

    /**
     * Times [close] has released the descriptor.
     *
     * Closing twice must be a no-op rather than a second close: once a descriptor
     * number is released the kernel may hand it to another component, so closing
     * it again would close someone else's file.
     */
    public val closeCount: Int get() = resource.closeCount

    override fun length(): Long = try {
        channel.size()
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
        channel.force(true)
        FlushDurability.FlushAttemptedGuaranteeUnknown
    } catch (e: IOException) {
        FlushDurability.FlushFailed(TransferStorageError.Io("sync", e.message))
    } catch (e: SecurityException) {
        FlushDurability.FlushFailed(TransferStorageError.PermissionRevoked("write"))
    }

    override fun truncateTo(offset: Long): TruncateOutcome = try {
        channel.truncate(offset)
        TruncateOutcome.Truncated(channel.size())
    } catch (e: IOException) {
        TruncateOutcome.Failed(TransferStorageError.Io("truncate", e.message))
    }

    override fun close() {
        resource.close()
    }
}

/**
 * The verification phase: one fresh read-only descriptor, one owner, one close.
 *
 * Reads are positional, so the phase never depends on the descriptor's own file
 * position — which is the property that makes it safe to run after the write
 * phase has been torn down completely.
 */
public class PendingReader internal constructor(
    public val uri: Uri,
    private val resource: OwnedResource,
    private val channel: FileChannel,
) : Closeable {

    public val closeCount: Int get() = resource.closeCount

    /** A bounded positional read surface over this descriptor. */
    public fun source(): VerificationSource =
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

/** What opening a phase descriptor produced, keeping the reason for a failure. */
private sealed interface DescriptorOpen {
    public data class Opened(public val descriptor: ParcelFileDescriptor) : DescriptorOpen
    public data class Refused(public val error: TransferStorageError) : DescriptorOpen
}

/**
 * Creates, writes, verifies, publishes and abandons pending MediaStore rows.
 *
 * Everything that touches `IS_PENDING` is API 29+. Below that there is no pending
 * column, and those operations are refused rather than silently downgraded — a
 * fallback that wrote a visible partial under the final name would be worse than
 * an honest refusal, because the caller could then not tell what it was getting.
 */
public object MediaStorePendingDestination {

    /** The first SDK with a pending mechanism. */
    public const val MIN_SDK: Int = 29

    private const val WRITE_MODE = "rw"
    private const val READ_MODE = "r"

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
     * Opens the write phase: one descriptor, opened read-write, owned by one
     * object that closes it once.
     */
    public fun openForWrite(
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
        val descriptor = when (val opened = openDescriptor(resolver, uri, WRITE_MODE)) {
            is DescriptorOpen.Opened -> opened.descriptor
            is DescriptorOpen.Refused -> return PendingOpen.Refused(opened.error)
        }
        return try {
            // One owner. AutoCloseOutputStream closes the descriptor when the
            // stream is closed, and OwnedResource closes the stream exactly once.
            // The channel is a view over the same descriptor and is never closed
            // separately, because that would close the same file a second time.
            val stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            val resource = OwnedResource(stream)
            PendingOpen.Opened(
                MediaStorePendingPartial(identity, uri, resource, stream.channel),
            )
        } catch (e: IOException) {
            descriptor.close()
            PendingOpen.Refused(TransferStorageError.Io("open", e.message))
        } catch (e: SecurityException) {
            descriptor.close()
            PendingOpen.Refused(TransferStorageError.PermissionRevoked("write"))
        }
    }

    /**
     * Opens the verification phase: a fresh read-only descriptor of its own.
     *
     * Separate on purpose. It is opened only after the write descriptor has been
     * closed, so the two phases can share nothing — not a descriptor, not a file
     * position, not an owner.
     */
    public fun openForRead(
        resolver: ContentResolver,
        uri: Uri,
        sdk: Int = Build.VERSION.SDK_INT,
    ): PendingReadOpen {
        if (!isAvailable(sdk)) {
            return PendingReadOpen.Refused(
                TransferStorageError.Unsupported("media_store_pending"),
            )
        }
        val descriptor = when (val opened = openDescriptor(resolver, uri, READ_MODE)) {
            is DescriptorOpen.Opened -> opened.descriptor
            is DescriptorOpen.Refused -> return PendingReadOpen.Refused(opened.error)
        }
        return try {
            val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            val resource = OwnedResource(stream)
            PendingReadOpen.Opened(PendingReader(uri, resource, stream.channel))
        } catch (e: IOException) {
            descriptor.close()
            PendingReadOpen.Refused(TransferStorageError.Io("read", e.message))
        } catch (e: SecurityException) {
            descriptor.close()
            PendingReadOpen.Refused(TransferStorageError.PermissionRevoked("read"))
        }
    }

    /**
     * Runs the whole verification phase: open, read from zero, digest, close.
     *
     * Owns the descriptor for the duration and closes it on every outcome,
     * including a verification failure.
     */
    public fun verify(
        resolver: ContentResolver,
        uri: Uri,
        expectedBytes: Long,
        expected: Sha256Digest?,
        sdk: Int = Build.VERSION.SDK_INT,
        buffer: ByteArray = ByteArray(DestinationVerifier.VERIFY_BUFFER_BYTES),
        newDigester: () -> ChunkDigester = { Sha256Digester() },
    ): VerificationPhase {
        val reader = when (val opened = openForRead(resolver, uri, sdk)) {
            is PendingReadOpen.Opened -> opened.reader
            is PendingReadOpen.Refused -> return VerificationPhase.Refused(opened.error)
        }
        return try {
            VerificationPhase.Completed(
                DestinationVerifier.verify(
                    source = reader.source(),
                    totalBytes = expectedBytes,
                    expected = expected,
                    buffer = buffer,
                    newDigester = newDigester,
                ),
            )
        } finally {
            reader.close()
        }
    }

    /** Whether the row is confirmed still pending. */
    @RequiresApi(29)
    public fun isPending(
        resolver: ContentResolver,
        uri: Uri,
        sdk: Int = Build.VERSION.SDK_INT,
    ): Boolean = stateOf(resolver, uri, sdk) == PendingState.PENDING

    /**
     * The row's state, answering three questions in order rather than inferring
     * one answer from another:
     *
     *  1. Does the row exist? Only a query that *completed* may answer MISSING.
     *  2. If it exists, can `IS_PENDING` be read? An absent column is UNKNOWN.
     *  3. If it can be read, is it 1 or 0?
     *
     * A thrown query is UNKNOWN, not MISSING: an exception is evidence that
     * nothing was determined, and treating it as a conclusive "gone" would let
     * the next pass re-create a row that is still there.
     */
    @RequiresApi(29)
    public fun stateOf(
        resolver: ContentResolver,
        uri: Uri,
        sdk: Int = Build.VERSION.SDK_INT,
    ): PendingState {
        if (!isAvailable(sdk)) return PendingState.UNKNOWN
        val cursor = try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)
        } catch (e: SecurityException) {
            return PendingState.UNKNOWN
        } catch (e: Exception) {
            return PendingState.UNKNOWN
        }
        // A null cursor is a query that did not run, not a row that is absent.
        if (cursor == null) return PendingState.UNKNOWN

        cursor.use {
            val exists = try {
                it.moveToFirst()
            } catch (e: Exception) {
                return PendingState.UNKNOWN
            }
            if (!exists) return PendingState.MISSING

            val index = try {
                it.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
            } catch (e: Exception) {
                -1
            }
            if (index < 0) return PendingState.UNKNOWN

            return try {
                if (it.isNull(index)) {
                    PendingState.UNKNOWN
                } else if (it.getInt(index) == 0) {
                    PendingState.PUBLISHED
                } else {
                    PendingState.PENDING
                }
            } catch (e: Exception) {
                PendingState.UNKNOWN
            }
        }
    }

    /**
     * Verifies the pending item and, only then, clears the pending flag.
     *
     * The order is the whole method: publish first and verify afterwards and
     * there is a window in which a corrupt file is visible in the user's gallery
     * under its final name.
     *
     * The flag is cleared and then the state is *re-read and reconciled*. The
     * update's own return value is never taken as proof, because a row can be
     * deleted or mutated between the query and the update, and because a
     * zero-row update says nothing about why.
     */
    @RequiresApi(29)
    public fun publish(
        resolver: ContentResolver,
        uri: Uri,
        identity: PartialIdentity,
        expectedBytes: Long,
        expected: Sha256Digest?,
        recorded: CommitState? = null,
        sdk: Int = Build.VERSION.SDK_INT,
        buffer: ByteArray = ByteArray(DestinationVerifier.VERIFY_BUFFER_BYTES),
        newDigester: () -> ChunkDigester = { Sha256Digester() },
    ): Publication {
        if (!isAvailable(sdk)) {
            return Publication.Refused(
                TransferStorageError.Unsupported("media_store_pending"),
            )
        }

        return when (val before = stateOf(resolver, uri, sdk)) {
            PendingState.UNKNOWN ->
                Publication.ReconciliationRequired(before, "state unreadable before publication")

            PendingState.MISSING ->
                Publication.Refused(TransferStorageError.NotFound("partial"))

            PendingState.PUBLISHED ->
                reconcileAlreadyClear(resolver, uri, expectedBytes, expected, recorded, sdk, buffer, newDigester)

            PendingState.PENDING -> {
                if (recorded == CommitState.COMMITTED) {
                    // The record claims the commit finished and the provider says
                    // it did not. No automatic rule resolves that.
                    return Publication.Inconsistent(
                        recorded = recorded,
                        observed = before,
                        detail = "record says committed, provider still reports pending",
                    )
                }
                val verification = verify(
                    resolver = resolver,
                    uri = uri,
                    expectedBytes = expectedBytes,
                    expected = expected,
                    sdk = sdk,
                    buffer = buffer,
                    newDigester = newDigester,
                )
                when (verification) {
                    is VerificationPhase.Refused ->
                        Publication.Refused(verification.error)

                    is VerificationPhase.Completed ->
                        if (!verification.result.allowsCommit) {
                            // Left pending and therefore still hidden: a corrupt
                            // file is not published in order to be deleted.
                            Publication.Blocked(verification.result)
                        } else {
                            clearPendingAndReconcile(resolver, uri, sdk)
                        }
                }
            }
        }
    }

    /**
     * Deletes a pending row — but only when it is known to be there.
     *
     * Reached only from the cleanup policy, never from an interrupted transfer:
     * deleting a partial that a later pass might resume throws away work that
     * cost the user bandwidth. An [PendingState.UNKNOWN] row is never deleted.
     */
    @RequiresApi(29)
    public fun abandon(
        resolver: ContentResolver,
        uri: Uri,
        sdk: Int = Build.VERSION.SDK_INT,
    ): AbandonOutcome = when (val state = stateOf(resolver, uri, sdk)) {
        PendingState.MISSING -> AbandonOutcome.AlreadyGone

        PendingState.UNKNOWN -> AbandonOutcome.ReconciliationRequired(
            state,
            "row state unreadable; not deleted",
        )

        PendingState.PENDING, PendingState.PUBLISHED -> try {
            if (resolver.delete(uri, null, null) > 0) {
                AbandonOutcome.Deleted
            } else {
                AbandonOutcome.ReconciliationRequired(
                    state,
                    "delete reported no rows removed",
                )
            }
        } catch (e: SecurityException) {
            AbandonOutcome.ReconciliationRequired(state, "delete refused by permission")
        } catch (e: Exception) {
            AbandonOutcome.ReconciliationRequired(state, "delete failed: ${e.message}")
        }
    }

    /**
     * Opens a descriptor, keeping the reason it failed.
     *
     * Returning null for every failure would collapse a revoked grant, a deleted
     * row and a provider crash into one "the provider failed" answer — and those
     * three have three different recoveries: ask the user, restart, and retry.
     */
    private fun openDescriptor(
        resolver: ContentResolver,
        uri: Uri,
        mode: String,
    ): DescriptorOpen = try {
        val descriptor = resolver.openFileDescriptor(uri, mode)
            ?: return DescriptorOpen.Refused(
                TransferStorageError.ProviderFailure(
                    "media_store",
                    "openFileDescriptor returned null",
                ),
            )
        DescriptorOpen.Opened(descriptor)
    } catch (e: SecurityException) {
        DescriptorOpen.Refused(
            TransferStorageError.PermissionRevoked(if (mode == READ_MODE) "read" else "write"),
        )
    } catch (e: FileNotFoundException) {
        DescriptorOpen.Refused(TransferStorageError.NotFound("partial"))
    } catch (e: IOException) {
        DescriptorOpen.Refused(TransferStorageError.Io("open", e.message))
    } catch (e: IllegalArgumentException) {
        DescriptorOpen.Refused(
            TransferStorageError.ProviderFailure("media_store", e.message),
        )
    }

    /**
     * Clears the flag and then re-reads the state, because the row can be deleted
     * or mutated between the two calls and an update count is not evidence.
     */
    @RequiresApi(29)
    private fun clearPendingAndReconcile(
        resolver: ContentResolver,
        uri: Uri,
        sdk: Int,
    ): Publication {
        val cleared = try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, values, null, null)
        } catch (e: SecurityException) {
            return Publication.Refused(TransferStorageError.PermissionRevoked("write"))
        } catch (e: Exception) {
            return Publication.ReconciliationRequired(
                PendingState.UNKNOWN,
                "clearing the pending flag failed: ${e.message}",
            )
        }

        return when (val after = stateOf(resolver, uri, sdk)) {
            PendingState.PUBLISHED -> Publication.Published

            PendingState.MISSING -> Publication.Refused(
                TransferStorageError.NotFound("partial"),
            )

            PendingState.PENDING -> Publication.Refused(
                TransferStorageError.StateConflict(
                    reason = "publication_not_effective",
                    diagnostic = "update removed $cleared rows; the flag is still set",
                ),
            )

            PendingState.UNKNOWN -> Publication.ReconciliationRequired(
                after,
                "state unreadable after the pending flag was cleared",
            )
        }
    }

    /**
     * The row is already visible but the durable record does not say committed.
     *
     * Accepted as externally completed only after an identity check. A byte count
     * is not an identity — a different file of the same length would satisfy it —
     * and a name is not an identity either, because a provider is free to rename
     * a file ("clip (1).mp4") without asking. So this requires a digest match,
     * and anything else is reported as a disagreement for a human pass.
     */
    private fun reconcileAlreadyClear(
        resolver: ContentResolver,
        uri: Uri,
        expectedBytes: Long,
        expected: Sha256Digest?,
        recorded: CommitState?,
        sdk: Int,
        buffer: ByteArray,
        newDigester: () -> ChunkDigester,
    ): Publication {
        if (recorded == CommitState.COMMITTED) return Publication.AlreadyPublished

        if (expected == null) {
            return Publication.Inconsistent(
                recorded = recorded,
                observed = PendingState.PUBLISHED,
                detail = "provider reports published but no digest is available to confirm identity",
            )
        }

        val verification = verify(
            resolver = resolver,
            uri = uri,
            expectedBytes = expectedBytes,
            expected = expected,
            sdk = sdk,
            buffer = buffer,
            newDigester = newDigester,
        )
        return when (verification) {
            is VerificationPhase.Refused -> Publication.Refused(verification.error)

            is VerificationPhase.Completed -> when (verification.result) {
                is VerifyResult.Matched -> Publication.ExternallyCompleted

                else -> Publication.Inconsistent(
                    recorded = recorded,
                    observed = PendingState.PUBLISHED,
                    detail = "provider reports published but the bytes do not match",
                )
            }
        }
    }
}
