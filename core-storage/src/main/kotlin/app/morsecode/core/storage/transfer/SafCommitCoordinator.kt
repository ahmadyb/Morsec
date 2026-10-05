package app.morsecode.core.storage.transfer

import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.storage.saf.SafPaths
import app.morsecode.core.transfer.integrity.Sha256Digest

/*
 * Committing a verified staging file into a user-granted SAF tree.
 *
 * Two facts drive the whole design:
 *
 * 1. SAF has no universal pending or hidden mechanism. A document created in a
 *    granted tree is visible to every other app from the moment it exists,
 *    whatever it is called. So nothing here creates a final document before the
 *    bytes are known good, and the temporary name is described as *unmistakably
 *    incomplete to a human* — never as hidden.
 *
 * 2. A provider is not a filesystem. It can claim a capability and then throw,
 *    return null instead of a URI, rename a document to something else because
 *    of a collision, and revoke the grant between two calls. So every provider
 *    interaction returns a typed result, and the interesting states are the ones
 *    where nothing is known.
 *
 * Every provider call goes through [SafDocumentGateway] so that the whole commit
 * sequence can be exercised against a deterministic fake. The production
 * implementation is the only place that touches a ContentResolver.
 */

// ---------------------------------------------------------------------------
// Provider results
// ---------------------------------------------------------------------------

/**
 * A document the provider described.
 *
 * [sizeBytes] is null when the provider omitted the column, which is a
 * different fact from "the file is zero bytes" and is never conflated with it.
 * [flags] is null when the provider omitted the flags column; that means "did
 * not say", not "supports nothing".
 */
public data class SafDocumentInfo(
    public val documentUri: String,
    public val documentId: String,
    public val displayName: String,
    public val sizeBytes: Long?,
    public val mimeType: String?,
    public val flags: Int?,
    public val isDirectory: Boolean,
)

/** The answer to "does this document exist, and what is it?". */
public sealed interface SafLookup {
    public data class Found(public val document: SafDocumentInfo) : SafLookup
    public data object Absent : SafLookup
    public data class Failed(public val error: TransferStorageError) : SafLookup
}

/** The answer to "create a document here". */
public sealed interface SafCreate {
    public data class Created(
        public val documentUri: String,
        public val documentId: String,
        /** The name the provider actually used, which may differ from the request. */
        public val displayName: String,
    ) : SafCreate

    public data class Failed(public val error: TransferStorageError) : SafCreate
}

/** The answer to "rename this document". */
public sealed interface SafRename {
    /**
     * The rename returned. [documentUri] may differ from the one passed in —
     * providers are free to hand back a new identity — and may be null, which
     * means the rename happened but the provider would not say where to.
     */
    public data class Renamed(public val documentUri: String?) : SafRename

    public data class Failed(public val error: TransferStorageError) : SafRename
}

/** The answer to "delete this document". */
public sealed interface SafDelete {
    public data object Deleted : SafDelete
    public data object Absent : SafDelete
    public data class Failed(public val error: TransferStorageError) : SafDelete
}

/*
 * Deletion, settled by observation rather than by the request.
 *
 * The rule this encodes is that a delete request is not proof of absence.
 * DocumentsContract.deleteDocument returns a boolean, and that boolean is not
 * the provider's answer to "is it gone" -- on the path this was measured on it
 * reports success whether or not the provider removed anything. Treating it as
 * proof closes cleanup against a fact nobody established, and the thing left
 * behind is an unverified partial under a name the user will see.
 *
 * So the request is issued and then the exact stored identity is queried, and
 * the observed state is what classifies the outcome. Only a query that ran and
 * returned no row proves absence. Everything else keeps the deletion open:
 * still-present, unknown, revoked and mismatched all leave cleanup pending
 * rather than complete, because each is a state in which the document may still
 * exist and therefore must not be reported as gone.
 *
 * [deleteReported] is kept on several cases purely as a diagnostic. It is never
 * read to decide an outcome, which is the point: it is the number that turned
 * out not to be trustworthy, retained so a log can say what the platform
 * claimed while the outcome says what was observed.
 */

/** What a delete request plus a follow-up query established. */
public sealed interface SafDeletion {

    /** Whether this outcome leaves cleanup still to do. */
    public val cleanupComplete: Boolean get() = this is ConfirmedAbsent

    /**
     * A follow-up query ran and returned no row for the exact stored identity.
     *
     * The only outcome that means the document is gone.
     */
    public data class ConfirmedAbsent(
        public val deleteReported: Boolean? = null,
    ) : SafDeletion

    /** The exact identity still resolves. Cleanup stays pending. */
    public data class StillPresent(
        public val document: SafDocumentInfo,
        public val deleteReported: Boolean? = null,
    ) : SafDeletion

    /** Presence and absence are both unproven. Reconciliation is required. */
    public data class QueryUnknown(
        public val diagnostic: String,
        public val deleteReported: Boolean? = null,
    ) : SafDeletion

    /** The grant went away before absence could be proved. */
    public data class PermissionRevoked(
        public val error: TransferStorageError.PermissionRevoked,
        public val deleteReported: Boolean? = null,
    ) : SafDeletion

    /**
     * The stored URI now resolves to a different document.
     *
     * Most often a same-name replacement: the name survived the delete and
     * something else now answers to the identity. Nothing is deleted and
     * nothing is assumed, because acting here would be acting against a
     * document this app did not create.
     */
    public data class IdentityMismatch(
        public val expectedDocumentId: String,
        public val observedDocumentId: String,
        public val observedDisplayName: String? = null,
    ) : SafDeletion

    /** The delete request itself threw or could not be issued. */
    public data class DeleteRequestFailed(
        public val error: TransferStorageError,
        public val deleteReported: Boolean? = null,
    ) : SafDeletion
}

/** The answer to "open this document". */
public sealed interface SafOpen {
    public data class Opened(public val handle: SafHandle) : SafOpen
    public data class Refused(public val error: TransferStorageError) : SafOpen
}

/**
 * The provider seam.
 *
 * Narrow on purpose: these are exactly the operations a commit needs, and no
 * caller of the coordinator has to know whether a `ContentResolver` or a fake is
 * behind them.
 */
public interface SafDocumentGateway {
    /**
     * Re-checks the persisted grant immediately before a provider operation.
     *
     * The grant object is a snapshot; it is not authority to assume a permission
     * still exists. Production checks ContentResolver.persistedUriPermissions
     * on each call. The coordinator asks before listing, creating, opening,
     * verifying, renaming and deleting, so a revocation is never mistaken for a
     * missing document or a successful operation.
     */
    public fun recheckPersistedGrant(
        grant: SafTreeGrant,
        operation: SafContainmentOperation,
    ): TransferStorageError?

    public fun findChild(parentUri: String, displayName: String): SafLookup
    public fun create(parentUri: String, mimeType: String, displayName: String): SafCreate
    public fun rename(documentUri: String, displayName: String): SafRename
    public fun delete(documentUri: String): SafDelete
    public fun query(documentUri: String): SafLookup
    public fun openWrite(documentUri: String): SafOpen
    public fun openRead(documentUri: String): SafOpen

    /**
     * Deletes [documentUri] and then asks what is actually there.
     *
     * The delete request's own answer is not the outcome: a provider can report
     * success and leave the row, or report failure and have removed it. Absence
     * is settled only by a completed query that finds nothing, which is what
     * [SafDeletion.ConfirmedAbsent] means.
     *
     * [expectedDocumentId] is the identity to act on. It is checked against what
     * the query finds, so that a document which happens to hold the same name
     * now is not mistaken for the one that was meant.
     */
    public fun deleteAndReconcile(
        documentUri: String,
        expectedDocumentId: String,
        grant: SafTreeGrant? = null,
    ): SafDeletion
}

// ---------------------------------------------------------------------------
// Handles — one owner per descriptor
// ---------------------------------------------------------------------------

/**
 * A read or write surface over an open document.
 *
 * The handle is the single owner. Closing it closes the stream it lent and the
 * descriptor underneath, exactly once. Callers must not close the stream; that
 * is how a descriptor gets closed twice, and the second close silently releases
 * a descriptor number the process has already reused.
 */
public sealed class SafHandle : Closeable {

    /**
     * How many times this handle attempted to close its primary resource.
     *
     * Incremented before the stream close: if it throws, the platform may still
     * have released the descriptor, so retrying can close a reused descriptor
     * number. A count of one means one close was attempted, not that release was
     * confirmed; the thrown close error is surfaced by the coordinator.
     */
    public var closeCount: Int = 0
        protected set

    /** True until a close attempt has begun; false does not prove release succeeded. */
    public val isOpen: Boolean get() = closeCount == 0
}

/**
 * The write side of one open document.
 *
 * The stream is a `ParcelFileDescriptor.AutoCloseOutputStream`, which closes the
 * descriptor when the stream is closed: one owner, not two. No `FileChannel` is
 * exposed, because a channel over a provider descriptor is a second owner with a
 * separate position, and the copy never needs one.
 */
public class SafWriteHandle internal constructor(
    private val descriptor: ParcelFileDescriptor?,
    private val stream: OutputStream,
    private val syncDescriptor: (ParcelFileDescriptor?) -> Unit = { fileDescriptor ->
        fileDescriptor?.fileDescriptor?.sync()
    },
) : SafHandle() {

    public fun write(buffer: ByteArray, offset: Int, length: Int) {
        stream.write(buffer, offset, length)
    }

    /**
     * The strongest flush the provider offers.
     *
     * Never [FlushDurability.DurableFlushSupported]: a provider flush is not
     * fsync, and a commit that treated it as one would acknowledge bytes that
     * are still in someone else's page cache.
     */
    public fun flush(): FlushDurability = try {
        stream.flush()
        // ParcelFileDescriptor has no sync of its own; the force belongs to the
        // java.io.FileDescriptor underneath it. This is still only an attempt:
        // a provider is free to hand back a socket, a pipe or an in-memory
        // handle, none of which have a durable medium to force to.
        syncDescriptor(descriptor)
        FlushDurability.FlushAttemptedGuaranteeUnknown
    } catch (e: Exception) {
        FlushDurability.FlushFailed(StorageFailureClassifier.classifyOrProviderFailure(e, "flush"))
    }

    override fun close() {
        if (closeCount > 0) return
        // Do not retry a failed close: the descriptor may have been released
        // before the stream reported its error, and a second close could act on
        // a reused descriptor number. The caller catches and surfaces this one.
        closeCount++
        stream.close()
    }
}

/**
 * The read side of one open document.
 *
 * Sequential by nature: a provider read descriptor cannot be relied on to seek,
 * so verification reads straight through from the start and the stored length is
 * the only thing that says where the end is.
 */
public class SafReadHandle internal constructor(
    private val stream: InputStream,
) : SafHandle() {

    private var position: Long = 0L

    public fun read(buffer: ByteArray, dataOffset: Int, length: Int): Int {
        val count = stream.read(buffer, dataOffset, length)
        if (count > 0) position += count
        return count
    }

    /**
     * The surface [DestinationVerifier] reads through.
     *
     * Verification opens a fresh descriptor of its own rather than reusing the
     * copy's, so the digest is taken from bytes the provider is holding, not
     * from bytes that were merely handed to its output stream.
     */
    public fun verificationSource(): VerificationSource =
        VerificationSource { offset, buffer, dataOffset, length ->
            if (offset != position) return@VerificationSource -1
            read(buffer, dataOffset, length)
        }

    override fun close() {
        if (closeCount > 0) return
        // Closing is attempted exactly once. If the stream reports failure the
        // coordinator must hear it and stop; silently swallowing it would let a
        // commit advance with an owner whose release was not confirmed.
        closeCount++
        stream.close()
    }
}

// ---------------------------------------------------------------------------
// Staging
// ---------------------------------------------------------------------------

/**
 * The app-private staging area, seen as the commit sees it.
 *
 * Staging is where bytes land first, because it is the only place on the device
 * where a partial is genuinely not visible to other apps and where a real fsync
 * is available. It is deleted only once the provider copy has been verified.
 */
public interface SafStaging {
    /** The staged length, or null when the staging file is gone. */
    public fun length(identity: PartialIdentity): Long?

    /** Opens a fresh read surface over the staged bytes. */
    public fun open(identity: PartialIdentity): SafOpen

    /** Deletes the staged bytes. Returns false when they could not be removed. */
    public fun delete(identity: PartialIdentity): Boolean
}

// ---------------------------------------------------------------------------
// Bounded copy
// ---------------------------------------------------------------------------

/** How a bounded copy finished. */
public sealed interface SafCopyOutcome {
    public data class Copied(public val bytes: Long) : SafCopyOutcome
    public data class Failed(
        public val error: TransferStorageError,
        public val bytes: Long,
    ) : SafCopyOutcome
}

/**
 * The copy phase and the flush that has to happen while the write handle is
 * still open.
 *
 * The two are returned together because they cannot be separated: a flush after
 * the descriptor is closed is not a flush at all, and a caller that only got the
 * copy outcome would have no way to tell whether the bytes ever left the
 * process.
 */
public data class SafCopyPhase(
    public val outcome: SafCopyOutcome,
    public val flush: FlushDurability,

    /**
     * Set when a handle would not close.
     *
     * Surfaced rather than swallowed, because a descriptor that did not close
     * is both a leak the caller has to hear about and a copy whose bytes are
     * not known to have left the process. `runCatching { close() }` that
     * discards the exception lets a copy be reported as flushed and verified on
     * the strength of a write that never finished.
     */
    public val closeError: TransferStorageError? = null,

    /** Journal failure at a flush boundary; no later phase may run. */
    public val journalError: TransferStorageError? = null,
)

/**
 * Copies exactly [totalBytes] bytes through a fixed buffer.
 *
 * Nothing here allocates anything proportional to the file: no whole-file byte
 * array, no `Int` conversion of the total, no memory mapping. The counts are
 * `Long` throughout, which is what lets the same code path serve a 3 KiB file
 * and a 5 GiB one.
 *
 * A read that returns zero is not end-of-file here — the file is known to be
 * [totalBytes] long — so it counts as no progress, and [maxZeroProgressSteps]
 * consecutive no-progress reads end the copy rather than spinning forever.
 */
public object SafCopyStreamer {

    public const val COPY_BUFFER_BYTES: Int = 65_536

    public fun copy(
        read: SafReadHandle,
        write: SafWriteHandle,
        totalBytes: Long,
        buffer: ByteArray = ByteArray(COPY_BUFFER_BYTES),
        maxZeroProgressSteps: Int = DestinationVerifier.MAX_ZERO_PROGRESS_STEPS,
        isCancelled: () -> Boolean = { false },
    ): SafCopyOutcome {
        require(totalBytes >= 0L) { "totalBytes must not be negative, was $totalBytes" }
        require(buffer.isNotEmpty()) { "buffer must not be empty" }

        var copied = 0L
        var zeroSteps = 0

        try {
            while (copied < totalBytes) {
                if (isCancelled()) {
                    return SafCopyOutcome.Failed(TransferStorageError.Cancelled, copied)
                }
                val remaining = totalBytes - copied
                val chunk = minOf(remaining, buffer.size.toLong()).toInt()
                val count = try {
                    read.read(buffer, 0, chunk)
                } catch (e: Exception) {
                    return SafCopyOutcome.Failed(mapCopyFailure(e, "read"), copied)
                }
                when {
                    count < 0 -> return SafCopyOutcome.Failed(
                        TransferStorageError.StateConflict("staging_shrank"),
                        copied,
                    )

                    count == 0 -> {
                        if (++zeroSteps >= maxZeroProgressSteps) {
                            return SafCopyOutcome.Failed(
                                TransferStorageError.ZeroProgress(zeroSteps, remaining),
                                copied,
                            )
                        }
                    }

                    else -> {
                        zeroSteps = 0
                        try {
                            write.write(buffer, 0, count)
                        } catch (e: Exception) {
                            return SafCopyOutcome.Failed(mapCopyFailure(e, "write"), copied)
                        }
                        copied += count
                    }
                }
            }
            return SafCopyOutcome.Copied(copied)
        } catch (e: Exception) {
            return SafCopyOutcome.Failed(mapCopyFailure(e, "write"), copied)
        }
    }

    private fun mapCopyFailure(error: Exception, access: String): TransferStorageError {
        if (error is java.io.FileNotFoundException) {
            return TransferStorageError.NotFound("staged_copy")
        }
        return StorageFailureClassifier.classify(error, "copy", access)
            ?: TransferStorageError.ProviderFailure("document_provider")
    }
}

// ---------------------------------------------------------------------------
// Duplicate naming
// ---------------------------------------------------------------------------

/**
 * Turns a wanted display name into one the destination does not already have.
 *
 * Deterministic, and bounded: [MAX_RENAME_ATTEMPTS] tries, then a stop rather
 * than a loop that could spin against a provider that always collides.
 *
 * The extension is preserved because `film.mp4` becoming `film (1)` is worse
 * than useless to whatever opens it next.
 */
public object SafDuplicateNaming {

    public const val MAX_RENAME_ATTEMPTS: Int = 100

    public fun withSuffix(displayName: String, index: Int): String {
        require(index > 0) { "duplicate suffix index must be positive" }
        return SafFilenamePolicy.withDuplicateSuffix(displayName, " ($index)")
    }

    /**
     * The first name in `name`, `name (1)`, `name (2)`, … that [isTaken] does not
     * claim. [isTaken] is consulted for every candidate, so a collision that
     * appears between two calls is caught rather than overwritten.
     */
    public fun firstFree(
        displayName: String,
        isTaken: (String) -> SafLookup,
    ): SafNameChoice {
        when (val first = isTaken(displayName)) {
            is SafLookup.Found -> Unit
            SafLookup.Absent -> return SafNameChoice.Chosen(displayName)
            is SafLookup.Failed -> return SafNameChoice.Unavailable(first.error)
        }
        for (index in 1..MAX_RENAME_ATTEMPTS) {
            val candidate = withSuffix(displayName, index)
            when (val looked = isTaken(candidate)) {
                is SafLookup.Found -> Unit
                SafLookup.Absent -> return SafNameChoice.Chosen(candidate)
                is SafLookup.Failed -> return SafNameChoice.Unavailable(looked.error)
            }
        }
        return SafNameChoice.Exhausted(displayName)
    }
}

/** What [SafDuplicateNaming.firstFree] decided. */
public sealed interface SafNameChoice {
    public data class Chosen(public val displayName: String) : SafNameChoice

    /** Every plausible name was taken. A rename cannot proceed. */
    public data class Exhausted(public val displayName: String) : SafNameChoice

    /** A collision query failed; failure is not evidence that the name is free. */
    public data class Unavailable(public val error: TransferStorageError) : SafNameChoice
}

// ---------------------------------------------------------------------------
// Commit outcomes
// ---------------------------------------------------------------------------

/** What a commit attempt concluded. */
public sealed interface SafCommitOutcome {
    /**
     * The file is at its final location.
     *
     * [stagingReleased] says whether staging was removed. It is reported
     * separately rather than assumed, because the delivery and the cleanup are
     * two facts: a staging file that would not delete does not un-deliver the
     * file, but it does leave something for the cleanup planner to finish.
     */
    public data class Committed(
        public val record: SafCommitRecord,
        public val finalUri: String,

        /**
         * What still has to be removed, by identity.
         *
         * Empty only when every cleanup step confirmed absence. Non-empty means
         * the file is delivered and the cleanup planner still has work: the two
         * are separate facts, and a staging file that would not delete does not
         * un-deliver a document that is already under its final name.
         */
        public val pendingCleanup: Set<SafCleanupPending> = emptySet(),
        public val checkpoint: SafCommitCheckpoint? = null,
    ) : SafCommitOutcome {

        /** True only when staging deletion was observed to complete. */
        public val stagingReleased: Boolean get() = record.stagingReleased

        /** True only when nothing at all is left to remove. */
        public val cleanupComplete: Boolean get() = pendingCleanup.isEmpty()

        override fun toString(): String =
            "SafCommitOutcome.Committed(state=${record.state.id}, pendingCleanup=${pendingCleanup.map { it.id }.sorted()}, " +
                "stagingReleased=$stagingReleased)"
    }

    /** The policy was skip, so no document was created. */
    public data class Skipped(
        public val record: SafCommitRecord,
        public val existingUri: String,
    ) : SafCommitOutcome {
        override fun toString(): String = "SafCommitOutcome.Skipped(state=${record.state.id})"
    }

    /** The policy was ask, so the caller must decide. Nothing was written. */
    public data class PendingUserDecision(
        public val record: SafCommitRecord,
        public val existingUri: String,
        public val finalName: String,
    ) : SafCommitOutcome {
        override fun toString(): String = "SafCommitOutcome.PendingUserDecision(state=${record.state.id})"
    }

    /**
     * The destination already holds a document and this commit cannot replace it
     * safely, so it did not try.
     *
     * Not a failure and not a success. The existing document is untouched and the
     * staged bytes are still there, so a caller can offer Rename or Skip and lose
     * nothing. The one thing it must never become is a delete-first overwrite:
     * that is the only route from here that can destroy the user's file.
     */
    public data class SafeOverwriteUnsupported(
        public val record: SafCommitRecord,
        public val existingUri: String,
        public val reason: String,
    ) : SafCommitOutcome {
        override fun toString(): String =
            "SafCommitOutcome.SafeOverwriteUnsupported(state=${record.state.id}, reason=$reason)"
    }

    /** The commit failed in a typed, explainable way. Staging is retained. */
    public data class Failed(
        public val record: SafCommitRecord,
        public val error: TransferStorageError,
        public val checkpoint: SafCommitCheckpoint? = null,
    ) : SafCommitOutcome {
        override fun toString(): String =
            "SafCommitOutcome.Failed(state=${record.state.id}, error=$error)"
    }

    /**
     * The provider's state could not be determined.
     *
     * Nothing was decided, nothing was deleted, and staging is retained. This is
     * never a success and never a failure that permits cleanup.
     */
    public data class ReconciliationRequired(
        public val record: SafCommitRecord,
        public val error: TransferStorageError,

        /**
         * Every identity known at the point the state stopped being decidable.
         *
         * The next pass needs all of them, because its first job is to ask the
         * provider which one actually exists. Keeping only the one that looked
         * most likely is how a rename that copied rather than moved loses the
         * spare document instead of cleaning it up.
         */
        public val knownUris: List<String> = emptyList(),
        public val checkpoint: SafCommitCheckpoint? = null,
    ) : SafCommitOutcome {
        override fun toString(): String =
            "SafCommitOutcome.ReconciliationRequired(state=${record.state.id}, error=$error, " +
                "knownIdentityCount=${knownUris.size})"
    }

    /** True only when the file is at its final location under its final name. */
    public val isDelivered: Boolean get() = this is Committed
}

// ---------------------------------------------------------------------------
// The coordinator
// ---------------------------------------------------------------------------

/**
 * Runs one commit, from a verified staging file to a document in a granted tree.
 *
 * The sequence is fixed, and the order is the safety property:
 *
 *   verify staging → resolve destination → apply duplicate policy → create →
 *   bounded copy → flush → close → verify the provider copy → rename or record →
 *   delete staging
 *
 * Staging is deleted last, on success only. A failure anywhere before that
 * leaves the staged bytes in place, so a retry has something to retry with and
 * the user still has the data.
 */
public class SafCommitCoordinator private constructor(
    /**
     * The gateway this coordinator acts through.
     *
     * Public so a test can prove the production factory installed the real
     * Android gateway rather than a stand-in. Everything above the gateway is
     * written against [SafDocumentGateway], so exposing the field costs no
     * coupling: a caller that wanted the concrete type would have to reach for
     * it deliberately.
     */
    public val gateway: SafDocumentGateway,
    private val staging: SafStaging,
    private val journal: SafCommitJournal,
    /**
     * Whether product policy permits the visible-copy fallback.
     *
     * False means a provider without rename support gets a typed refusal rather
     * than a silent downgrade to writing under the user's chosen name.
     */
    private val allowVisibleFinalCopy: Boolean,
    private val copyBufferBytes: Int,
    private val isCancelled: () -> Boolean,
    private val verificationDigesterFactory: () -> ChunkDigester,
) {
    /** Production constructor always uses incremental SHA-256. */
    public constructor(
        gateway: SafDocumentGateway,
        staging: SafStaging,
        journal: SafCommitJournal,
        allowVisibleFinalCopy: Boolean = false,
        copyBufferBytes: Int = SafCopyStreamer.COPY_BUFFER_BYTES,
        isCancelled: () -> Boolean = { false },
    ) : this(
        gateway,
        staging,
        journal,
        allowVisibleFinalCopy,
        copyBufferBytes,
        isCancelled,
        { Sha256Digester() },
    )

    /** Test seam for virtual large-file accounting; inaccessible outside this module. */
    internal constructor(
        gateway: SafDocumentGateway,
        staging: SafStaging,
        journal: SafCommitJournal,
        verificationDigesterFactory: () -> ChunkDigester,
        allowVisibleFinalCopy: Boolean = false,
        copyBufferBytes: Int = SafCopyStreamer.COPY_BUFFER_BYTES,
        isCancelled: () -> Boolean = { false },
    ) : this(
        gateway,
        staging,
        journal,
        allowVisibleFinalCopy,
        copyBufferBytes,
        isCancelled,
        verificationDigesterFactory,
    )

    /**
     * Commits [record] after hashing the staged bytes and checking their length.
     * If the record carries an expected digest, that is checked before creation;
     * the observed staged digest then verifies each provider copy against the
     * staged bytes (it does not establish sender authenticity by itself).
     *
     * The caller passes the record rather than letting the coordinator look it
     * up so that the state it writes back is the same record the caller
     * persists, and there is no second source of truth for "where are we".
     * [grant] is the approved tree grant; its persisted permission is re-read
     * before each provider operation rather than trusted from this snapshot.
     */
    public fun commit(record: SafCommitRecord, grant: SafTreeGrant): SafCommitOutcome {
        if (record.grantId != null && record.grantId != grant.grantId) {
            return fail(record, TransferStorageError.StateConflict("grant_context_mismatch"))
        }
        if (record.treeUri != grant.treeUri.toString() ||
            record.rootDocumentId != grant.rootDocumentId ||
            record.treeUri.toUri().authority != grant.authority
        ) {
            return fail(record, TransferStorageError.StateConflict("grant_context_mismatch"))
        }
        if (!renameHistoryMatchesCommit(
                history = record.renameHistory,
                scope = SafRenameScope.fromRecord(record, grant),
                strategy = record.strategy,
                duplicatePolicy = record.duplicatePolicy,
                existingIdentity = record.existingIdentity,
            )
        ) {
            return SafCommitOutcome.ReconciliationRequired(
                record = record.copy(state = SafCommitState.RECONCILIATION_REQUIRED),
                error = TransferStorageError.ContainmentUnknown("rename_history_malformed"),
            )
        }
        var scopedRecord = record.copy(grantId = grant.grantId, verifiedDigest = null)
        val previousCheckpoint = try {
            journal.load(scopedRecord.partialId)
        } catch (_: Exception) {
            return fail(scopedRecord, TransferStorageError.StateConflict("journal_load_failed"))
        }
        val requestedCheckpoint = checkpointFor(scopedRecord, grant, SafCommitCheckpointPhase.READY)
        val initialCheckpoint = if (previousCheckpoint == null) {
            requestedCheckpoint
        } else {
            if (!sameCheckpointCommit(requestedCheckpoint, previousCheckpoint)) {
                return SafCommitOutcome.ReconciliationRequired(
                    record = scopedRecord.copy(state = SafCommitState.RECONCILIATION_REQUIRED),
                    error = TransferStorageError.StateConflict("checkpoint_identity_mismatch"),
                    knownUris = listOfNotNull(
                        previousCheckpoint.temporaryIdentity?.documentUri,
                        previousCheckpoint.existingIdentity?.documentUri,
                        previousCheckpoint.backupIdentity?.documentUri,
                        previousCheckpoint.returnedRenameIdentity?.documentUri,
                        previousCheckpoint.finalIdentity?.documentUri,
                    ).distinct(),
                    checkpoint = previousCheckpoint,
                )
            }
            if (previousCheckpoint.phase == SafCommitCheckpointPhase.COMMITTED &&
                previousCheckpoint.finalIdentity != null && previousCheckpoint.pendingCleanup.isEmpty()
            ) {
                val committedRecord = recordFromCheckpoint(previousCheckpoint)
                val finalIdentity = previousCheckpoint.finalIdentity
                cleanupFinalIdentityError(committedRecord, grant, finalIdentity)?.let { error ->
                    return reconcileAndSave(committedRecord, grant, error, listOf(finalIdentity.documentUri))
                }
                SafCommitOutcome.Committed(
                    record = committedRecord.copy(state = SafCommitState.COMMITTED, stagingReleased = true),
                    finalUri = finalIdentity.documentUri,
                    checkpoint = previousCheckpoint,
                )
            }
            if (previousCheckpoint.phase != SafCommitCheckpointPhase.READY ||
                previousCheckpoint.temporaryIdentity != null ||
                previousCheckpoint.backupIdentity != null ||
                previousCheckpoint.finalIdentity != null ||
                previousCheckpoint.pendingCleanup.isNotEmpty() ||
                previousCheckpoint.copiedBytes != 0L
            ) {
                return SafCommitOutcome.ReconciliationRequired(
                    record = scopedRecord.copy(state = SafCommitState.RECONCILIATION_REQUIRED),
                    error = TransferStorageError.StateConflict("checkpoint_requires_recovery"),
                    knownUris = listOfNotNull(
                        previousCheckpoint.temporaryIdentity?.documentUri,
                        previousCheckpoint.existingIdentity?.documentUri,
                        previousCheckpoint.backupIdentity?.documentUri,
                        previousCheckpoint.returnedRenameIdentity?.documentUri,
                        previousCheckpoint.finalIdentity?.documentUri,
                    ).distinct(),
                    checkpoint = previousCheckpoint,
                )
            }
            previousCheckpoint
        }
        if (previousCheckpoint == null && !runCatching { journal.save(initialCheckpoint) }.getOrDefault(false)) {
            return SafCommitOutcome.Failed(
                record = scopedRecord.copy(state = SafCommitState.COMMIT_FAILED),
                error = TransferStorageError.StateConflict("journal_initial_save_failed"),
                checkpoint = initialCheckpoint,
            )
        }
        if (isCancelled()) return cancelBeforePublication(scopedRecord, grant)
        authorizationError(scopedRecord, grant, SafContainmentOperation.RECONCILE)?.let {
            return route(scopedRecord, it, created = null)
        }
        if (!saveCheckpoint(scopedRecord, grant, SafCommitCheckpointPhase.STAGING_VERIFICATION_INTENT)) {
            return journalFailure(
                scopedRecord,
                grant,
                SafCommitCheckpointPhase.STAGING_VERIFICATION_INTENT,
                afterMutation = false,
            )
        }
        if (isCancelled()) return cancelBeforePublication(scopedRecord, grant)

        val stagedLength = staging.length(scopedRecord.partialId)
            ?: return fail(scopedRecord, TransferStorageError.NotFound("staged_copy"))

        // The staged file is the authority on the size, not the record: if they
        // disagree then the record is describing bytes that no longer exist.
        if (stagedLength != scopedRecord.expectedSizeBytes) {
            return fail(
                scopedRecord,
                TransferStorageError.StateConflict("staging_length_disagrees"),
            )
        }

        val stagedVerification = verifyStagedBytes(scopedRecord)
        stagedVerification.closeError?.let {
            return if (it == TransferStorageError.Cancelled) cancelBeforePublication(scopedRecord, grant)
            else route(scopedRecord, it, created = null)
        }
        val stagedDigest = when (val result = stagedVerification.outcome) {
            is VerifyResult.Matched -> result.digest
            is VerifyResult.VerifiedWithoutExpected -> result.digest
            is VerifyResult.Mismatched -> return fail(
                scopedRecord,
                TransferStorageError.IntegrityMismatch("staging_digest"),
            )
            is VerifyResult.Failed -> return if (result.error == TransferStorageError.Cancelled) {
                cancelBeforePublication(scopedRecord, grant)
            } else {
                route(scopedRecord, result.error, created = null)
            }
        }
        scopedRecord = scopedRecord.copy(verifiedDigest = stagedDigest)
        if (!saveCheckpoint(scopedRecord, grant, SafCommitCheckpointPhase.STAGING_VERIFIED)) {
            return journalFailure(
                scopedRecord,
                grant,
                SafCommitCheckpointPhase.STAGING_VERIFIED,
                afterMutation = false,
            )
        }
        if (!saveCheckpoint(scopedRecord, grant, SafCommitCheckpointPhase.DESTINATION_RESOLUTION_INTENT)) {
            return journalFailure(
                scopedRecord,
                grant,
                SafCommitCheckpointPhase.DESTINATION_RESOLUTION_INTENT,
                afterMutation = false,
            )
        }
        if (isCancelled()) return cancelBeforePublication(scopedRecord, grant)

        authorizationError(scopedRecord, grant, SafContainmentOperation.RECONCILE)?.let {
            return route(scopedRecord, it, created = null)
        }
        val existing = gateway.findChild(
            scopedRecord.uriForTree(scopedRecord.parentDocumentId),
            scopedRecord.expectedFinalName,
        )
        return when (val choice = applyDuplicatePolicy(scopedRecord, existing, grant)) {
            is SafNameDecision.Use -> {
                val resolved = scopedRecord.copy(
                    state = SafCommitState.DESTINATION_RESOLVED,
                    expectedFinalName = choice.displayName,
                )
                if (!saveCheckpoint(resolved, grant, SafCommitCheckpointPhase.DESTINATION_RESOLVED)) {
                    return journalFailure(
                        resolved,
                        grant,
                        SafCommitCheckpointPhase.DESTINATION_RESOLVED,
                        afterMutation = false,
                    )
                }
                when (scopedRecord.strategy) {
                    SafCommitStrategy.TEMP_THEN_RENAME -> commitTempThenRename(resolved, grant, stagedDigest)
                    SafCommitStrategy.VISIBLE_FINAL_COPY -> commitVisibleFinalCopy(resolved, grant, stagedDigest)
                }
            }

            // Overwrite is not a naming decision. The final name is already
            // taken, and replacing is a different sequence rather than a
            // different name, so it leaves before anything is resolved.
            is SafNameDecision.Overwrite -> {
                val selected = scopedRecord.copy(
                    state = SafCommitState.DESTINATION_RESOLVED,
                    existingIdentity = SafStoredDocumentIdentity(
                        choice.existingUri,
                        choice.existingDocumentId,
                    ),
                )
                if (!saveCheckpoint(selected, grant, SafCommitCheckpointPhase.DESTINATION_RESOLVED)) {
                    return journalFailure(
                        selected,
                        grant,
                        SafCommitCheckpointPhase.DESTINATION_RESOLVED,
                        afterMutation = false,
                    )
                }
                commitOverwrite(
                    selected,
                    choice.existingUri,
                    choice.existingDocumentId,
                    grant,
                    stagedDigest,
                )
            }

            is SafNameDecision.Skip -> {
                if (!saveCheckpoint(scopedRecord, grant, SafCommitCheckpointPhase.DESTINATION_RESOLVED)) {
                    journalFailure(
                        scopedRecord,
                        grant,
                        SafCommitCheckpointPhase.DESTINATION_RESOLVED,
                        afterMutation = false,
                    )
                } else {
                    SafCommitOutcome.Skipped(scopedRecord, choice.existingUri)
                }
            }

            is SafNameDecision.Ask -> {
                if (!saveCheckpoint(scopedRecord, grant, SafCommitCheckpointPhase.DESTINATION_RESOLVED)) {
                    journalFailure(
                        scopedRecord,
                        grant,
                        SafCommitCheckpointPhase.DESTINATION_RESOLVED,
                        afterMutation = false,
                    )
                } else SafCommitOutcome.PendingUserDecision(
                    record = scopedRecord,
                    existingUri = choice.existingUri,
                    finalName = scopedRecord.expectedFinalName,
                )
            }

            is SafNameDecision.Unavailable -> reconcile(scopedRecord, choice.error)
            is SafNameDecision.Refused -> fail(scopedRecord, choice.error)
        }
    }

    /**
     * Retries only the exact cleanup identities recorded on a delivered commit.
     *
     * A query that cannot prove absence leaves the corresponding item pending;
     * this method never searches by name and never turns UNKNOWN into success.
     */
    /**
     * Loads the latest versioned checkpoint and executes only actions that can
     * be justified by its exact identities and fresh provider observations.
     *
     * Recovery is synchronous and side-effect bounded: it never starts a
     * service, timer, UI flow, or network operation. A checkpoint from another
     * commit, a revoked grant, or any unresolved provider observation stops at
     * reconciliation rather than being treated as success.
     */
    public fun resumeOrReconcile(
        checkpoint: SafCommitCheckpoint,
        grant: SafTreeGrant,
    ): SafCommitRecoveryOutcome {
        val latest = try {
            journal.load(checkpoint.commitId)
        } catch (_: Exception) {
            return SafCommitRecoveryOutcome.Failed(
                checkpoint,
                TransferStorageError.StateConflict("journal_load_failed"),
            )
        } ?: return SafCommitRecoveryOutcome.Failed(
            checkpoint,
            TransferStorageError.NotFound("commit_checkpoint"),
        )
        if (!sameCheckpointCommit(checkpoint, latest)) {
            return SafCommitRecoveryOutcome.ReconciliationRequired(
                latest,
                TransferStorageError.StateConflict("checkpoint_identity_mismatch"),
            )
        }
        if (latest.version != SafCommitCheckpoint.CURRENT_VERSION) {
            return SafCommitRecoveryOutcome.ReconciliationRequired(
                latest,
                TransferStorageError.Unsupported("saf_checkpoint_version"),
            )
        }
        if (!checkpointCleanupIdentitiesMatch(latest)) {
            return SafCommitRecoveryOutcome.ReconciliationRequired(
                latest,
                TransferStorageError.StateConflict("checkpoint_cleanup_identity_mismatch"),
            )
        }
        if (!checkpointIdentityShapeMatches(latest)) {
            return SafCommitRecoveryOutcome.ReconciliationRequired(
                latest,
                TransferStorageError.ContainmentUnknown("checkpoint_identity_malformed"),
            )
        }

        val record = recordFromCheckpoint(latest)
        if (grant.grantId != latest.approvedTree.grantId ||
            grant.treeUri.toString() != latest.approvedTree.treeUri ||
            grant.authority != latest.approvedTree.authority ||
            grant.rootDocumentId != latest.approvedTree.rootDocumentId
        ) {
            return SafCommitRecoveryOutcome.ReconciliationRequired(
                latest,
                TransferStorageError.StateConflict("grant_context_mismatch"),
                record.knownDocumentIdentities.map { it.documentUri },
            )
        }
        when (latest.phase) {
            SafCommitCheckpointPhase.CANCELLED -> return SafCommitRecoveryOutcome.Cancelled(latest)
            SafCommitCheckpointPhase.CANCELLED_TEMPORARY_DELETE_INTENT ->
                return retryCancelledTemporaryCleanup(latest, grant)
            SafCommitCheckpointPhase.CANCELLED_TEMPORARY_DELETE_OBSERVED ->
                return SafCommitRecoveryOutcome.Cancelled(latest)
            else -> Unit
        }
        if (latest.phase == SafCommitCheckpointPhase.RECONCILIATION_REQUIRED &&
            latest.lastFailure?.categoryId == TransferStorageError.Cancelled.category.id &&
            !checkpointAlreadyPublished(latest)
        ) {
            return SafCommitRecoveryOutcome.ReconciliationRequired(
                latest,
                TransferStorageError.Cancelled,
                record.knownDocumentIdentities.map { it.documentUri },
            )
        }
        if (isCancelled() && !checkpointAlreadyPublished(latest)) {
            val cancelled = cancelRecoveryAtBoundary(record, latest, grant)
            return toRecoveryOutcome(cancelled, latest)
        }
        authorizationError(record, grant, SafContainmentOperation.RECONCILE)?.let { error ->
            return recoveryReconciliation(
                record,
                grant,
                error,
                record.knownDocumentIdentities.map { it.documentUri },
            )
        }

        return when (latest.phase) {
            SafCommitCheckpointPhase.READY,
            SafCommitCheckpointPhase.STAGING_VERIFICATION_INTENT,
            SafCommitCheckpointPhase.STAGING_VERIFIED,
            SafCommitCheckpointPhase.DESTINATION_RESOLUTION_INTENT,
            SafCommitCheckpointPhase.DESTINATION_RESOLVED,
            -> resumeFromReady(record, grant)

            SafCommitCheckpointPhase.TEMPORARY_CREATE_INTENT,
            SafCommitCheckpointPhase.TEMPORARY_CREATED,
            SafCommitCheckpointPhase.COPY_STARTED,
            SafCommitCheckpointPhase.COPY_COMPLETED,
            SafCommitCheckpointPhase.FLUSH_INTENT,
            SafCommitCheckpointPhase.FLUSH_COMPLETED,
            SafCommitCheckpointPhase.PROVIDER_VERIFICATION_INTENT,
            SafCommitCheckpointPhase.PROVIDER_VERIFIED,
            SafCommitCheckpointPhase.INTERRUPTED_TEMPORARY_DELETE_INTENT,
            SafCommitCheckpointPhase.INTERRUPTED_TEMPORARY_DELETE_OBSERVED,
            -> resumeTemporary(record, latest, grant)

            SafCommitCheckpointPhase.VISIBLE_CREATE_INTENT,
            SafCommitCheckpointPhase.VISIBLE_CREATED,
            SafCommitCheckpointPhase.VISIBLE_DELETE_INTENT,
            SafCommitCheckpointPhase.VISIBLE_DELETE_OBSERVED,
            -> resumeVisibleCopy(record, latest, grant)

            SafCommitCheckpointPhase.FINAL_RENAME_INTENT,
            SafCommitCheckpointPhase.FINAL_RENAMED,
            SafCommitCheckpointPhase.RENAME_RECONCILIATION_INTENT,
            SafCommitCheckpointPhase.FINAL_VERIFICATION_INTENT,
            -> resumeFinalRename(record, latest, grant)

            SafCommitCheckpointPhase.FINAL_VERIFIED,
            SafCommitCheckpointPhase.PUBLICATION_INTENT,
            SafCommitCheckpointPhase.PUBLISHED,
            SafCommitCheckpointPhase.PROVIDER_TEMPORARY_DELETE_INTENT,
            SafCommitCheckpointPhase.PROVIDER_TEMPORARY_DELETE_OBSERVED,
            SafCommitCheckpointPhase.BACKUP_DELETE_INTENT,
            SafCommitCheckpointPhase.BACKUP_DELETE_OBSERVED,
            SafCommitCheckpointPhase.STAGING_DELETE_INTENT,
            SafCommitCheckpointPhase.STAGING_DELETE_OBSERVED,
            -> resumePublished(record, latest, grant)

            SafCommitCheckpointPhase.BACKUP_RENAME_INTENT,
            SafCommitCheckpointPhase.BACKUP_RENAMED,
            -> resumeBackupRename(record, latest, grant)

            SafCommitCheckpointPhase.CANCELLED,
            SafCommitCheckpointPhase.CANCELLED_TEMPORARY_DELETE_INTENT,
            SafCommitCheckpointPhase.CANCELLED_TEMPORARY_DELETE_OBSERVED,
            -> SafCommitRecoveryOutcome.Cancelled(latest)

            SafCommitCheckpointPhase.COMMITTED -> verifyCommittedCheckpoint(record, latest, grant)
            SafCommitCheckpointPhase.RECONCILIATION_REQUIRED -> when {
                record.finalIdentity != null &&
                    record.strategy == SafCommitStrategy.VISIBLE_FINAL_COPY &&
                    !checkpointAlreadyPublished(latest) -> resumeVisibleCopy(record, latest, grant)
                record.finalIdentity != null -> resumePublished(record, latest, grant)
                latest.unresolvedRenamePhase == SafRenamePhase.FINAL_PROMOTION ->
                    resumeFinalRename(record, latest, grant)
                latest.unresolvedRenamePhase == SafRenamePhase.BACKUP_RENAME ->
                    resumeBackupRename(record, latest, grant)
                record.renameHistory.lastOrNull()?.phase == SafRenamePhase.FINAL_PROMOTION ->
                    resumeFinalRename(record, latest, grant)
                record.renameHistory.lastOrNull()?.phase == SafRenamePhase.BACKUP_RENAME ->
                    resumeBackupRename(record, latest, grant)
                latest.returnedRenameUri != null && record.existingIdentity != null && record.backupIdentity == null ->
                    resumeBackupRename(record, latest, grant)
                latest.returnedRenameUri != null -> resumeFinalRename(record, latest, grant)
                record.temporaryIdentity != null -> resumeTemporary(record, latest, grant)
                record.strategy == SafCommitStrategy.TEMP_THEN_RENAME -> resumeTemporary(record, latest, grant)
                else -> SafCommitRecoveryOutcome.ReconciliationRequired(
                    latest,
                    TransferStorageError.StateConflict("checkpoint_requires_manual_reconciliation"),
                )
            }
        }
    }

    private fun resumeFromReady(
        record: SafCommitRecord,
        grant: SafTreeGrant,
    ): SafCommitRecoveryOutcome {
        val ready = record.copy(
            state = SafCommitState.STAGING_VERIFIED,
            temporaryUri = null,
            temporaryIdentity = null,
            finalUri = null,
            finalIdentity = null,
            backupIdentity = null,
            renameHistory = emptyList(),
            copiedBytes = 0L,
            pendingCleanup = emptySet(),
            stagingReleased = false,
        )
        if (!saveCheckpoint(ready, grant, SafCommitCheckpointPhase.READY)) {
            return SafCommitRecoveryOutcome.Failed(
                checkpointFor(record, grant, SafCommitCheckpointPhase.READY),
                TransferStorageError.StateConflict("journal_resume_ready_failed"),
            )
        }
        return toRecoveryOutcome(commit(ready, grant), checkpointFor(ready, grant, SafCommitCheckpointPhase.READY))
    }

    private fun resumeTemporary(
        record: SafCommitRecord,
        checkpoint: SafCommitCheckpoint,
        grant: SafTreeGrant,
    ): SafCommitRecoveryOutcome {
        if (isCancelled()) {
            return toRecoveryOutcome(cancelRecoveryAtBoundary(record, checkpoint, grant), checkpoint)
        }
        if (record.strategy != SafCommitStrategy.TEMP_THEN_RENAME) {
            return resumeVisibleCopy(record, checkpoint, grant)
        }
        val temporaryName = temporaryDocumentName(record.expectedFinalName, record.partialId)
        val persistedIdentity = record.temporaryIdentity
        val temporary = if (persistedIdentity != null) {
            authorizationError(
                record,
                grant,
                SafContainmentOperation.RECONCILE,
                persistedIdentity.documentUri,
                persistedIdentity.documentId,
            )?.let { return recoveryReconciliation(record, grant, it, listOf(persistedIdentity.documentUri)) }
            when (val lookup = gateway.query(persistedIdentity.documentUri)) {
                is SafLookup.Found -> {
                    if (lookup.document.documentId != persistedIdentity.documentId) {
                        return recoveryReconciliation(
                            record,
                            grant,
                            TransferStorageError.ContainmentUnknown("temporary_identity_changed"),
                            listOf(persistedIdentity.documentUri),
                        )
                    }
                    lookup.document
                }

                SafLookup.Absent -> {
                    if (checkpoint.phase == SafCommitCheckpointPhase.INTERRUPTED_TEMPORARY_DELETE_INTENT &&
                        !saveCheckpoint(
                            record,
                            grant,
                            SafCommitCheckpointPhase.INTERRUPTED_TEMPORARY_DELETE_OBSERVED,
                        )
                    ) {
                        return recoveryAtPhase(
                            record,
                            grant,
                            SafCommitCheckpointPhase.INTERRUPTED_TEMPORARY_DELETE_INTENT,
                            TransferStorageError.StateConflict("journal_temporary_delete_result_failed"),
                            listOf(persistedIdentity.documentUri),
                        )
                    }
                    return resumeFromReady(record, grant)
                }
                is SafLookup.Failed -> return recoveryReconciliation(record, grant, lookup.error, listOf(persistedIdentity.documentUri))
            }
        } else {
            if (checkpoint.phase != SafCommitCheckpointPhase.TEMPORARY_CREATE_INTENT &&
                checkpoint.phase != SafCommitCheckpointPhase.RECONCILIATION_REQUIRED
            ) {
                return recoveryReconciliation(
                    record,
                    grant,
                    TransferStorageError.ContainmentUnknown("temporary_identity_missing"),
                    emptyList(),
                )
            }
            when (val candidate = findRecoveryChild(record, grant, temporaryName)) {
                SafLookup.Absent -> return resumeFromReady(record, grant)
                is SafLookup.Failed -> return recoveryReconciliation(record, grant, candidate.error, emptyList())
                is SafLookup.Found -> {
                    if (candidate.document.displayName != temporaryName || candidate.document.isDirectory) {
                        return recoveryReconciliation(
                            record,
                            grant,
                            TransferStorageError.ContainmentUnknown("temporary_candidate_mismatch"),
                            listOf(candidate.document.documentUri),
                        )
                    }
                    candidate.document
                }
            }
        }

        val identity = SafStoredDocumentIdentity(temporary.documentUri, temporary.documentId)
        exactChildError(record, grant, identity, temporaryName, "temporary")?.let {
            return recoveryReconciliation(record, grant, it, listOf(identity.documentUri))
        }
        if (isCancelled()) {
            return toRecoveryOutcome(cancelRecoveryAtBoundary(record, checkpoint, grant), checkpoint)
        }
        val digest = recoveryStagedDigest(record)
        if (digest is RecoveryDigest.Failed) {
            return recoveryReconciliation(record, grant, digest.error, listOf(temporary.documentUri))
        }
        val stagedDigest = (digest as RecoveryDigest.Ready).digest
        val verified = verifyProviderCopy(
            identity.documentUri,
            identity.documentId,
            record,
            grant,
            stagedDigest,
        )
        verified.closeError?.let {
            return if (it == TransferStorageError.Cancelled) {
                toRecoveryOutcome(cancelBeforePublication(record, grant), checkpoint)
            } else {
                recoveryReconciliation(record, grant, it, listOf(identity.documentUri))
            }
        }
        when (val outcome = verified.outcome) {
            is VerifyResult.Failed -> return when {
                outcome.error == TransferStorageError.Cancelled ->
                    toRecoveryOutcome(cancelBeforePublication(record, grant), checkpoint)
                outcome.error is TransferStorageError.IntegrityMismatch && persistedIdentity != null ->
                    discardInterruptedTemporary(record, identity, grant)
                else -> recoveryReconciliation(record, grant, outcome.error, listOf(identity.documentUri))
            }
            is VerifyResult.Mismatched -> {
                if (persistedIdentity == null) {
                    return recoveryReconciliation(
                        record,
                        grant,
                        TransferStorageError.IntegrityMismatch("temporary_candidate"),
                        listOf(identity.documentUri),
                    )
                }
                return discardInterruptedTemporary(record, identity, grant)
            }

            is VerifyResult.VerifiedWithoutExpected,
            is VerifyResult.Matched,
            -> Unit
        }
        exactChildError(record, grant, identity, temporaryName, "temporary")?.let {
            return recoveryReconciliation(record, grant, it, listOf(identity.documentUri))
        }
        if (isCancelled()) return toRecoveryOutcome(cancelBeforePublication(record, grant), checkpoint)
        val recovered = record.copy(
            state = SafCommitState.PROVIDER_VERIFIED,
            temporaryUri = identity.documentUri,
            temporaryIdentity = identity,
            copiedBytes = record.expectedSizeBytes,
            verifiedDigest = stagedDigest,
        )
        if (!saveCheckpoint(recovered, grant, SafCommitCheckpointPhase.PROVIDER_VERIFIED)) {
            return recoveryReconciliation(
                recovered,
                grant,
                TransferStorageError.StateConflict("journal_provider_verified_failed"),
                listOf(identity.documentUri),
            )
        }
        val created = SafCreate.Created(identity.documentUri, identity.documentId, temporary.displayName)
        val outcome = if (recovered.duplicatePolicy == DuplicatePolicy.OVERWRITE &&
            recovered.existingIdentity != null
        ) {
            continueSafeOverwrite(
                recovered,
                created,
                recovered.existingIdentity.documentUri,
                recovered.existingIdentity.documentId,
                grant,
                stagedDigest,
            )
        } else {
            promoteVerifiedTemporary(recovered, created, grant, stagedDigest)
        }
        return toRecoveryOutcome(outcome, checkpointFor(recovered, grant, SafCommitCheckpointPhase.PROVIDER_VERIFIED))
    }

    private fun resumeVisibleCopy(
        record: SafCommitRecord,
        checkpoint: SafCommitCheckpoint,
        grant: SafTreeGrant,
    ): SafCommitRecoveryOutcome {
        if (isCancelled()) {
            return toRecoveryOutcome(cancelRecoveryAtBoundary(record, checkpoint, grant), checkpoint)
        }
        if (checkpoint.phase == SafCommitCheckpointPhase.VISIBLE_CREATE_INTENT && record.finalIdentity == null) {
            return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.ContainmentUnknown("visible_create_identity_unknown"),
                listOfNotNull(checkpoint.returnedRenameUri),
            )
        }
        val identity = record.finalIdentity
            ?: return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.ContainmentUnknown("visible_final_identity_missing"),
                emptyList(),
            )
        authorizationError(
            record,
            grant,
            SafContainmentOperation.RECONCILE,
            identity.documentUri,
            identity.documentId,
        )?.let { return recoveryReconciliation(record, grant, it, listOf(identity.documentUri)) }
        val visible = when (val lookup = gateway.query(identity.documentUri)) {
            is SafLookup.Found -> if (lookup.document.documentId == identity.documentId) {
                lookup.document
            } else {
                return recoveryReconciliation(
                    record,
                    grant,
                    TransferStorageError.ContainmentUnknown("visible_final_identity_changed"),
                    listOf(identity.documentUri),
                )
            }

            SafLookup.Absent -> {
                if (checkpoint.phase == SafCommitCheckpointPhase.VISIBLE_DELETE_INTENT &&
                    !saveCheckpoint(record, grant, SafCommitCheckpointPhase.VISIBLE_DELETE_OBSERVED)
                ) {
                    return recoveryAtPhase(
                        record,
                        grant,
                        SafCommitCheckpointPhase.VISIBLE_DELETE_INTENT,
                        TransferStorageError.StateConflict("journal_visible_delete_result_failed"),
                        listOf(identity.documentUri),
                    )
                }
                return resumeFromReady(record, grant)
            }
            is SafLookup.Failed -> return recoveryReconciliation(record, grant, lookup.error, listOf(identity.documentUri))
        }
        exactChildError(record, grant, identity, record.expectedFinalName, "visible_final")?.let {
            return recoveryReconciliation(record, grant, it, listOf(identity.documentUri))
        }
        if (isCancelled()) {
            return toRecoveryOutcome(cancelBeforePublication(record, grant), checkpoint)
        }
        val digest = recoveryStagedDigest(record)
        if (digest is RecoveryDigest.Failed) {
            return recoveryReconciliation(record, grant, digest.error, listOf(identity.documentUri))
        }
        val stagedDigest = (digest as RecoveryDigest.Ready).digest
        val verification = verifyProviderCopy(identity.documentUri, identity.documentId, record, grant, stagedDigest)
        verification.closeError?.let {
            return if (it == TransferStorageError.Cancelled) {
                toRecoveryOutcome(cancelBeforePublication(record, grant), checkpoint)
            } else {
                recoveryReconciliation(record, grant, it, listOf(identity.documentUri))
            }
        }
        when (val outcome = verification.outcome) {
            is VerifyResult.Failed -> return when {
                outcome.error == TransferStorageError.Cancelled ->
                    toRecoveryOutcome(cancelBeforePublication(record, grant), checkpoint)
                outcome.error is TransferStorageError.IntegrityMismatch ->
                    discardInterruptedVisibleFinal(record, identity, grant)
                else -> recoveryReconciliation(record, grant, outcome.error, listOf(identity.documentUri))
            }
            is VerifyResult.Mismatched -> return discardInterruptedVisibleFinal(record, identity, grant)


            is VerifyResult.VerifiedWithoutExpected,
            is VerifyResult.Matched,
            -> Unit
        }
        if (isCancelled()) return toRecoveryOutcome(cancelBeforePublication(record, grant), checkpoint)
        exactChildError(record, grant, identity, record.expectedFinalName, "visible_final")?.let {
            return recoveryReconciliation(record, grant, it, listOf(identity.documentUri))
        }
        if (isCancelled()) return toRecoveryOutcome(cancelBeforePublication(record, grant), checkpoint)
        val verified = record.copy(
            state = SafCommitState.PROVIDER_VERIFIED,
            copiedBytes = record.expectedSizeBytes,
            verifiedDigest = stagedDigest,
        )
        if (!saveCheckpoint(verified, grant, SafCommitCheckpointPhase.PROVIDER_VERIFIED)) {
            return recoveryReconciliation(
                verified,
                grant,
                TransferStorageError.StateConflict("journal_provider_verified_failed"),
                listOf(identity.documentUri),
            )
        }
        if (isCancelled()) {
            return toRecoveryOutcome(cancelBeforePublication(verified, grant), checkpoint)
        }
        val published = verified.copy(
            state = SafCommitState.PUBLISHED_OR_VISIBLE,
            pendingCleanup = setOf(SafCleanupPending.STAGING),
        )
        if (!saveCheckpoint(published, grant, SafCommitCheckpointPhase.PUBLISHED)) {
            return recoveryReconciliation(
                published,
                grant,
                TransferStorageError.StateConflict("journal_publication_failed"),
                listOf(identity.documentUri),
            )
        }
        return finishAndRetryCleanup(published, identity.documentUri, grant).let {
            toRecoveryOutcome(it, checkpointFor(published, grant, SafCommitCheckpointPhase.PUBLISHED))
        }
    }

    private fun discardInterruptedVisibleFinal(
        record: SafCommitRecord,
        identity: SafStoredDocumentIdentity,
        grant: SafTreeGrant,
    ): SafCommitRecoveryOutcome {
        exactChildError(record, grant, identity, record.expectedFinalName, "visible_interrupted")?.let {
            return recoveryReconciliation(record, grant, it, listOf(identity.documentUri))
        }
        if (isCancelled()) {
            return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.Cancelled,
                listOf(identity.documentUri),
            )
        }
        if (!saveCheckpoint(record, grant, SafCommitCheckpointPhase.VISIBLE_DELETE_INTENT)) {
            return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.StateConflict("journal_visible_delete_intent_failed"),
                listOf(identity.documentUri),
            )
        }
        if (isCancelled()) {
            return recoveryReconciliation(record, grant, TransferStorageError.Cancelled, listOf(identity.documentUri))
        }
        exactChildError(record, grant, identity, record.expectedFinalName, "visible_interrupted")?.let {
            return recoveryReconciliation(record, grant, it, listOf(identity.documentUri))
        }
        if (isCancelled()) {
            return recoveryReconciliation(record, grant, TransferStorageError.Cancelled, listOf(identity.documentUri))
        }
        val deletion = deleteExactIdentity(record, identity, grant, record.expectedFinalName)
        if (isCancelled()) {
            return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.Cancelled,
                listOf(identity.documentUri),
            )
        }
        val failure = when (deletion) {
            is SafDeletion.PermissionRevoked -> deletion.error
            is SafDeletion.DeleteRequestFailed -> deletion.error
            is SafDeletion.IdentityMismatch -> TransferStorageError.ContainmentUnknown("visible_identity_changed")
            is SafDeletion.QueryUnknown -> TransferStorageError.StateConflict("visible_delete_unsettled")
            else -> null
        }
        if (!saveCheckpoint(
                record,
                grant,
                SafCommitCheckpointPhase.VISIBLE_DELETE_OBSERVED,
                failure = failure,
            )
        ) {
            return recoveryAtPhase(
                record,
                grant,
                SafCommitCheckpointPhase.VISIBLE_DELETE_INTENT,
                TransferStorageError.StateConflict("journal_visible_delete_result_failed"),
                listOf(identity.documentUri),
            )
        }
        if (deletion !is SafDeletion.ConfirmedAbsent) {
            return recoveryAtPhase(
                record,
                grant,
                SafCommitCheckpointPhase.VISIBLE_DELETE_OBSERVED,
                failure ?: TransferStorageError.StateConflict("visible_partial_cleanup_unsettled"),
                listOf(identity.documentUri),
            )
        }
        return resumeFromReady(record, grant)
    }

    private fun resumeFinalRename(
        record: SafCommitRecord,
        checkpoint: SafCommitCheckpoint,
        grant: SafTreeGrant,
    ): SafCommitRecoveryOutcome {
        if (isCancelled()) {
            return toRecoveryOutcome(cancelRecoveryAtBoundary(record, checkpoint, grant), checkpoint)
        }
        val candidate = findRecoveryChild(record, grant, record.expectedFinalName)
        if (isCancelled()) {
            return toRecoveryOutcome(
                cancelAfterRename(
                    record,
                    grant,
                    record.knownDocumentIdentities.map { it.documentUri } + listOfNotNull(checkpoint.returnedRenameUri),
                    returnedRenameIdentity = checkpoint.returnedRenameIdentity,
                    returnedRenameUri = checkpoint.returnedRenameUri,
                    unresolvedRenamePhase = SafRenamePhase.FINAL_PROMOTION,
                ),
                checkpoint,
            )
        }
        when (candidate) {
            is SafLookup.Failed -> return recoveryReconciliation(record, grant, candidate.error, listOfNotNull(checkpoint.returnedRenameUri))
            SafLookup.Absent -> {
                if (checkpoint.returnedRenameUri != null ||
                    checkpoint.unresolvedRenamePhase == SafRenamePhase.FINAL_PROMOTION ||
                    checkpoint.phase == SafCommitCheckpointPhase.FINAL_RENAMED ||
                    checkpoint.phase == SafCommitCheckpointPhase.RENAME_RECONCILIATION_INTENT ||
                    checkpoint.phase == SafCommitCheckpointPhase.FINAL_VERIFICATION_INTENT ||
                    record.renameHistory.any { it.phase == SafRenamePhase.FINAL_PROMOTION }
                ) {
                    return recoveryReconciliation(
                        record,
                        grant,
                        TransferStorageError.StateConflict("final_rename_result_unobserved"),
                        record.knownDocumentIdentities.map { it.documentUri } +
                            listOfNotNull(checkpoint.returnedRenameUri),
                    )
                }
                val temporaryIdentity = record.temporaryIdentity
                    ?: return recoveryReconciliation(
                        record,
                        grant,
                        TransferStorageError.NotFound("renamed_final"),
                        listOfNotNull(checkpoint.returnedRenameUri),
                    )
                val digest = recoveryStagedDigest(record)
                if (digest is RecoveryDigest.Failed) {
                    return recoveryReconciliation(record, grant, digest.error, listOf(temporaryIdentity.documentUri))
                }
                val stagedDigest = (digest as RecoveryDigest.Ready).digest
                val existingIdentity = record.existingIdentity
                val backupIdentity = record.backupIdentity
                if (backupIdentity != null && existingIdentity == null) {
                    return recoveryReconciliation(
                        record,
                        grant,
                        TransferStorageError.ContainmentUnknown("overwrite_existing_identity_missing"),
                        listOf(backupIdentity.documentUri),
                    )
                }
                if (backupIdentity != null) {
                    val backupAuthorization = authorizationError(
                        record,
                        grant,
                        SafContainmentOperation.RECONCILE,
                        backupIdentity.documentUri,
                        backupIdentity.documentId,
                    )
                    if (backupAuthorization != null) {
                        return recoveryReconciliation(record, grant, backupAuthorization, listOf(backupIdentity.documentUri))
                    }
                    when (val backupLookup = gateway.query(backupIdentity.documentUri)) {
                        is SafLookup.Found -> if (backupLookup.document.documentId != backupIdentity.documentId) {
                            return recoveryReconciliation(
                                record,
                                grant,
                                TransferStorageError.ContainmentUnknown("backup_identity_changed"),
                                listOf(backupIdentity.documentUri),
                            )
                        }

                        SafLookup.Absent -> return recoveryReconciliation(
                            record,
                            grant,
                            TransferStorageError.NotFound("overwrite_backup"),
                            listOf(backupIdentity.documentUri),
                        )

                        is SafLookup.Failed -> return recoveryReconciliation(record, grant, backupLookup.error, listOf(backupIdentity.documentUri))
                    }
                    if (existingIdentity != null) {
                        when (val originalLookup = gateway.query(existingIdentity.documentUri)) {
                            SafLookup.Absent -> Unit
                            is SafLookup.Found -> return recoveryReconciliation(
                                record,
                                grant,
                                TransferStorageError.StateConflict("overwrite_original_still_present"),
                                listOf(existingIdentity.documentUri, backupIdentity.documentUri),
                            )

                            is SafLookup.Failed -> return recoveryReconciliation(
                                record,
                                grant,
                                originalLookup.error,
                                listOf(existingIdentity.documentUri),
                            )
                        }
                    }
                }
                authorizationError(
                    record,
                    grant,
                    SafContainmentOperation.RECONCILE,
                    temporaryIdentity.documentUri,
                    temporaryIdentity.documentId,
                )?.let {
                    return recoveryReconciliation(record, grant, it, listOf(temporaryIdentity.documentUri))
                }
                val temporary = when (val lookup = gateway.query(temporaryIdentity.documentUri)) {
                    is SafLookup.Found -> if (lookup.document.documentId == temporaryIdentity.documentId) {
                        lookup.document
                    } else {
                        return recoveryReconciliation(
                            record,
                            grant,
                            TransferStorageError.ContainmentUnknown("temporary_identity_changed"),
                            listOf(temporaryIdentity.documentUri),
                        )
                    }

                    SafLookup.Absent -> return recoveryReconciliation(
                        record,
                        grant,
                        TransferStorageError.NotFound("rename_source"),
                        listOf(temporaryIdentity.documentUri),
                    )

                    is SafLookup.Failed -> return recoveryReconciliation(record, grant, lookup.error, listOf(temporaryIdentity.documentUri))
                }
                val verified = verifyProviderCopy(
                    temporaryIdentity.documentUri,
                    temporaryIdentity.documentId,
                    record,
                    grant,
                    stagedDigest,
                )
                if (verified.closeError != null || verified.outcome !is VerifyResult.Matched) {
                    val error = verified.closeError ?: when (val result = verified.outcome) {
                        is VerifyResult.Failed -> result.error
                        is VerifyResult.Mismatched -> TransferStorageError.IntegrityMismatch("rename_source")
                        else -> TransferStorageError.StateConflict("rename_source_unverified")
                    }
                    return recoveryReconciliation(record, grant, error, listOf(temporaryIdentity.documentUri))
                }
                val created = SafCreate.Created(
                    temporaryIdentity.documentUri,
                    temporaryIdentity.documentId,
                    temporary.displayName,
                )
                val outcome = if (record.backupIdentity != null && record.existingIdentity != null) {
                    val backup = record.backupIdentity
                    promoteReplacementAfterBackup(
                        record.copy(state = SafCommitState.BACKUP_CREATED, verifiedDigest = stagedDigest),
                        created,
                        backup.documentUri,
                        backup.documentId,
                        record.existingIdentity.documentUri,
                        grant,
                        stagedDigest,
                    )
                } else {
                    promoteVerifiedTemporary(
                        record.copy(state = SafCommitState.PROVIDER_VERIFIED, verifiedDigest = stagedDigest),
                        created,
                        grant,
                        stagedDigest,
                    )
                }
                return toRecoveryOutcome(outcome, checkpoint)
            }

            is SafLookup.Found -> {
                val doc = candidate.document
                val candidateIdentity = SafStoredDocumentIdentity(doc.documentUri, doc.documentId)
                val returnedIdentityMatches = checkpoint.returnedRenameIdentity?.let { it == candidateIdentity }
                    ?: (checkpoint.returnedRenameUri == doc.documentUri)
                val known = listOfNotNull(
                    record.finalIdentity,
                    record.temporaryIdentity,
                ).any { it == candidateIdentity } ||
                    record.renameHistory.any { candidateIdentity in it.knownIdentities } ||
                    returnedIdentityMatches
                if (!known || doc.displayName != record.expectedFinalName || doc.isDirectory) {
                    return recoveryReconciliation(
                        record,
                        grant,
                        TransferStorageError.ContainmentUnknown("final_candidate_not_exact"),
                        listOf(doc.documentUri),
                    )
                }
                val identity = SafStoredDocumentIdentity(doc.documentUri, doc.documentId)
                val digest = recoveryStagedDigest(record)
                if (digest is RecoveryDigest.Failed) {
                    return recoveryReconciliation(record, grant, digest.error, listOf(doc.documentUri))
                }
                val stagedDigest = (digest as RecoveryDigest.Ready).digest
                val verification = verifyProviderCopy(doc.documentUri, doc.documentId, record, grant, stagedDigest)
                verification.closeError?.let { error ->
                    return if (error == TransferStorageError.Cancelled) {
                        toRecoveryOutcome(
                            cancelAfterRename(
                                record,
                                grant,
                                listOfNotNull(record.temporaryIdentity?.documentUri, doc.documentUri),
                                returnedRenameIdentity = identity,
                                returnedRenameUri = checkpoint.returnedRenameUri ?: doc.documentUri,
                                unresolvedRenamePhase = SafRenamePhase.FINAL_PROMOTION,
                            ),
                            checkpoint,
                        )
                    } else {
                        recoveryReconciliation(record, grant, error, listOf(doc.documentUri))
                    }
                }
                if (verification.outcome !is VerifyResult.Matched) {
                    val error = when (val result = verification.outcome) {
                        is VerifyResult.Failed -> result.error
                        is VerifyResult.Mismatched -> TransferStorageError.IntegrityMismatch("final_candidate")
                        else -> TransferStorageError.StateConflict("final_candidate_unverified")
                    }
                    return if (error == TransferStorageError.Cancelled) {
                        toRecoveryOutcome(
                            cancelAfterRename(
                                record,
                                grant,
                                listOfNotNull(record.temporaryIdentity?.documentUri, doc.documentUri),
                                returnedRenameIdentity = identity,
                                returnedRenameUri = checkpoint.returnedRenameUri ?: doc.documentUri,
                                unresolvedRenamePhase = SafRenamePhase.FINAL_PROMOTION,
                            ),
                            checkpoint,
                        )
                    } else {
                        recoveryReconciliation(record, grant, error, listOf(doc.documentUri))
                    }
                }
                if (isCancelled()) {
                    return toRecoveryOutcome(
                        cancelAfterRename(
                            record,
                            grant,
                            listOfNotNull(record.temporaryIdentity?.documentUri, doc.documentUri),
                            returnedRenameIdentity = identity,
                            returnedRenameUri = checkpoint.returnedRenameUri ?: doc.documentUri,
                            unresolvedRenamePhase = SafRenamePhase.FINAL_PROMOTION,
                        ),
                        checkpoint,
                    )
                }
                var retainedTemporaryIdentity: SafStoredDocumentIdentity? = null
                val sourceIdentity = record.temporaryIdentity
                if (sourceIdentity != null && sourceIdentity != identity) {
                    authorizationError(
                        record,
                        grant,
                        SafContainmentOperation.RECONCILE,
                        sourceIdentity.documentUri,
                        sourceIdentity.documentId,
                    )?.let { error ->
                        return recoveryReconciliation(record, grant, error, listOf(sourceIdentity.documentUri))
                    }
                    when (val source = gateway.query(sourceIdentity.documentUri)) {
                        SafLookup.Absent -> Unit
                        is SafLookup.Found -> {
                            val temporaryName = temporaryDocumentName(record.expectedFinalName, record.partialId)
                            val listedIdentity = when (val listed = findRecoveryChild(record, grant, temporaryName)) {
                                is SafLookup.Found -> listed.document
                                SafLookup.Absent -> null
                                is SafLookup.Failed -> return recoveryReconciliation(
                                    record,
                                    grant,
                                    listed.error,
                                    listOf(sourceIdentity.documentUri, identity.documentUri),
                                )
                            }
                            if (source.document.documentUri != sourceIdentity.documentUri ||
                                source.document.documentId != sourceIdentity.documentId ||
                                source.document.displayName != temporaryName ||
                                source.document.isDirectory ||
                                listedIdentity == null ||
                                listedIdentity.documentUri != sourceIdentity.documentUri ||
                                listedIdentity.documentId != sourceIdentity.documentId ||
                                listedIdentity.displayName != temporaryName ||
                                listedIdentity.isDirectory
                            ) {
                                return recoveryReconciliation(
                                    record,
                                    grant,
                                    TransferStorageError.StateConflict("final_rename_both_identities_resolve"),
                                    listOf(sourceIdentity.documentUri, identity.documentUri),
                                )
                            }
                            retainedTemporaryIdentity = sourceIdentity
                        }

                        is SafLookup.Failed -> return recoveryReconciliation(
                            record,
                            grant,
                            source.error,
                            listOf(sourceIdentity.documentUri, identity.documentUri),
                        )
                    }
                }
                val renameHistory = if (
                    record.renameHistory.any { it.phase == SafRenamePhase.FINAL_PROMOTION }
                ) {
                    record.renameHistory
                } else {
                    val sourceIdentity = record.temporaryIdentity
                        ?: return recoveryReconciliation(
                            record,
                            grant,
                            TransferStorageError.ContainmentUnknown("rename_source_identity_missing"),
                            listOf(doc.documentUri),
                        )
                    record.renameHistory + observedRenameEvidence(
                        record,
                        grant,
                        SafRenamePhase.FINAL_PROMOTION,
                        sourceIdentity,
                        identity,
                        reconciliation = if (retainedTemporaryIdentity != null) {
                            SafRenameReconciliation.AMBIGUOUS_BOTH_RESOLVE
                        } else {
                            null
                        },
                    )
                }
                if (!renameHistoryMatchesCommit(
                        history = renameHistory,
                        scope = SafRenameScope.fromRecord(record, grant),
                        strategy = record.strategy,
                        duplicatePolicy = record.duplicatePolicy,
                        existingIdentity = record.existingIdentity,
                    )
                ) {
                    return recoveryReconciliation(
                        record,
                        grant,
                        TransferStorageError.ContainmentUnknown("rename_history_malformed"),
                        listOf(doc.documentUri),
                    )
                }
                exactChildError(record, grant, identity, record.expectedFinalName, "final")?.let {
                    return recoveryReconciliation(record, grant, it, listOf(identity.documentUri))
                }
                if (isCancelled()) {
                    return toRecoveryOutcome(
                        cancelAfterRename(
                            record,
                            grant,
                            listOfNotNull(record.temporaryIdentity?.documentUri, identity.documentUri),
                            returnedRenameIdentity = identity,
                            returnedRenameUri = checkpoint.returnedRenameUri ?: identity.documentUri,
                        ),
                        checkpoint,
                    )
                }
                val verified = record.copy(
                    state = SafCommitState.PUBLISHED_OR_VISIBLE,
                    finalUri = identity.documentUri,
                    finalIdentity = identity,
                    renameHistory = renameHistory,
                    copiedBytes = record.expectedSizeBytes,
                    pendingCleanup = record.pendingCleanup + setOf(SafCleanupPending.STAGING) +
                        (if (record.backupIdentity == null) emptySet() else setOf(SafCleanupPending.BACKUP)) +
                        (if (retainedTemporaryIdentity == null || retainedTemporaryIdentity == identity) {
                            emptySet()
                        } else {
                            setOf(SafCleanupPending.PROVIDER_TEMPORARY)
                        }),
                    verifiedDigest = stagedDigest,
                )
                if (!saveCheckpoint(
                        verified,
                        grant,
                        SafCommitCheckpointPhase.FINAL_VERIFIED,
                        returnedRenameIdentity = identity,
                        returnedRenameUri = checkpoint.returnedRenameUri,
                    )
                ) {
                    return recoveryReconciliation(
                        verified,
                        grant,
                        TransferStorageError.StateConflict("journal_final_verification_failed"),
                        listOf(doc.documentUri),
                    )
                }
                if (!saveCheckpoint(
                        verified,
                        grant,
                        SafCommitCheckpointPhase.PUBLISHED,
                        returnedRenameIdentity = identity,
                        returnedRenameUri = checkpoint.returnedRenameUri,
                    )
                ) {
                    return recoveryReconciliation(
                        verified,
                        grant,
                        TransferStorageError.StateConflict("journal_publication_failed"),
                        listOf(doc.documentUri),
                    )
                }
                val outcome = finishAndRetryCleanup(verified, doc.documentUri, grant)
                return toRecoveryOutcome(outcome, checkpoint)
            }
        }
    }

    private fun resumeBackupRename(
        record: SafCommitRecord,
        checkpoint: SafCommitCheckpoint,
        grant: SafTreeGrant,
    ): SafCommitRecoveryOutcome {
        if (isCancelled()) {
            return toRecoveryOutcome(cancelRecoveryAtBoundary(record, checkpoint, grant), checkpoint)
        }
        val existing = record.existingIdentity
            ?: return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.ContainmentUnknown("overwrite_existing_identity_missing"),
                listOfNotNull(checkpoint.returnedRenameUri),
            )
        val replacement = record.temporaryIdentity
            ?: return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.ContainmentUnknown("overwrite_replacement_identity_missing"),
                listOfNotNull(existing.documentUri, checkpoint.returnedRenameUri),
            )
        authorizationError(
            record,
            grant,
            SafContainmentOperation.RECONCILE,
            existing.documentUri,
            existing.documentId,
        )?.let { return recoveryReconciliation(record, grant, it, listOf(existing.documentUri)) }
        authorizationError(
            record,
            grant,
            SafContainmentOperation.RECONCILE,
            replacement.documentUri,
            replacement.documentId,
        )?.let { return recoveryReconciliation(record, grant, it, listOf(replacement.documentUri)) }
        fun cancelledAtBoundary(knownUris: List<String>): SafCommitRecoveryOutcome? =
            if (!isCancelled()) {
                null
            } else {
                toRecoveryOutcome(
                    cancelAfterRename(
                        record,
                        grant,
                        listOfNotNull(existing.documentUri, replacement.documentUri, checkpoint.returnedRenameUri) + knownUris,
                        returnedRenameIdentity = checkpoint.returnedRenameIdentity,
                        returnedRenameUri = checkpoint.returnedRenameUri,
                        unresolvedRenamePhase = SafRenamePhase.BACKUP_RENAME,
                    ),
                    checkpoint,
                )
            }

        val backupName = backupDocumentName(record.expectedFinalName, record.partialId)
        val backupCandidate = findRecoveryChild(record, grant, backupName)
        cancelledAtBoundary(listOfNotNull((backupCandidate as? SafLookup.Found)?.document?.documentUri))?.let {
            return it
        }
        if (backupCandidate is SafLookup.Failed) {
            return recoveryReconciliation(record, grant, backupCandidate.error, listOfNotNull(checkpoint.returnedRenameUri))
        }
        val originalLookup = gateway.query(existing.documentUri)
        cancelledAtBoundary(listOf(existing.documentUri, replacement.documentUri))?.let { return it }
        if (originalLookup is SafLookup.Failed) {
            return recoveryReconciliation(record, grant, originalLookup.error, listOf(existing.documentUri))
        }
        val backupInfo = (backupCandidate as? SafLookup.Found)?.document
        val originalPresent = (originalLookup as? SafLookup.Found)?.document
        val returnedBackupMatches = backupInfo?.let { candidate ->
            val candidateIdentity = SafStoredDocumentIdentity(candidate.documentUri, candidate.documentId)
            checkpoint.returnedRenameIdentity?.let { it == candidateIdentity }
                ?: (checkpoint.returnedRenameUri == candidate.documentUri)
        } == true
        if (backupInfo != null &&
            ((backupInfo.documentId != existing.documentId && !returnedBackupMatches) ||
                backupInfo.displayName != backupName || backupInfo.isDirectory)
        ) {
            return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.ContainmentUnknown("backup_candidate_identity_mismatch"),
                listOf(backupInfo.documentUri, existing.documentUri),
            )
        }
        if (originalPresent != null && originalPresent.documentId != existing.documentId) {
            return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.ContainmentUnknown("overwrite_original_identity_changed"),
                listOf(existing.documentUri),
            )
        }
        val originalIsBackup = backupInfo != null && originalPresent != null &&
            backupInfo.documentUri == existing.documentUri &&
            backupInfo.documentId == existing.documentId &&
            originalPresent.documentUri == existing.documentUri &&
            originalPresent.documentId == existing.documentId
        if (backupInfo != null && originalPresent != null && !originalIsBackup) {
            return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.StateConflict("backup_rename_ambiguous"),
                listOf(backupInfo.documentUri, existing.documentUri),
            )
        }
        if (backupInfo == null && originalPresent == null) {
            return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.StateConflict("backup_identity_unresolved"),
                listOf(existing.documentUri),
            )
        }
        if (backupInfo == null &&
            (checkpoint.returnedRenameUri != null ||
                checkpoint.unresolvedRenamePhase == SafRenamePhase.BACKUP_RENAME)
        ) {
            return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.StateConflict("backup_rename_result_unobserved"),
                listOfNotNull(existing.documentUri, replacement.documentUri, checkpoint.returnedRenameUri),
            )
        }
        val digest = recoveryStagedDigest(record)
        if (digest is RecoveryDigest.Failed) {
            return recoveryReconciliation(record, grant, digest.error, listOf(replacement.documentUri))
        }
        val stagedDigest = (digest as RecoveryDigest.Ready).digest
        val replacementLookup = gateway.query(replacement.documentUri)
        cancelledAtBoundary(listOf(replacement.documentUri))?.let { return it }
        val replacementInfo = when (replacementLookup) {
            is SafLookup.Found -> if (replacementLookup.document.documentId == replacement.documentId) {
                replacementLookup.document
            } else {
                return recoveryReconciliation(
                    record,
                    grant,
                    TransferStorageError.ContainmentUnknown("replacement_identity_changed"),
                    listOf(replacement.documentUri),
                )
            }

            SafLookup.Absent -> {
                if (backupInfo != null || originalPresent == null) {
                    return recoveryReconciliation(
                        record,
                        grant,
                        TransferStorageError.NotFound("overwrite_replacement"),
                        listOfNotNull(backupInfo?.documentUri, existing.documentUri),
                    )
                }
                return resumeFromReady(record, grant)
            }
            is SafLookup.Failed -> return recoveryReconciliation(record, grant, replacementLookup.error, listOf(replacement.documentUri))
        }
        exactChildError(
            record,
            grant,
            replacement,
            temporaryDocumentName(record.expectedFinalName, record.partialId),
            "overwrite_replacement",
        )?.let { return recoveryReconciliation(record, grant, it, listOf(replacement.documentUri)) }
        cancelledAtBoundary(listOfNotNull(existing.documentUri, replacement.documentUri, backupInfo?.documentUri))?.let {
            return it
        }
        val verified = verifyProviderCopy(replacement.documentUri, replacement.documentId, record, grant, stagedDigest)
        if (verified.closeError != null || verified.outcome !is VerifyResult.Matched) {
            val error = verified.closeError ?: when (val result = verified.outcome) {
                is VerifyResult.Failed -> result.error
                is VerifyResult.Mismatched -> TransferStorageError.IntegrityMismatch("overwrite_replacement")
                else -> TransferStorageError.StateConflict("overwrite_replacement_unverified")
            }
            return if (error == TransferStorageError.Cancelled) {
                toRecoveryOutcome(
                    cancelAfterRename(
                        record,
                        grant,
                        listOfNotNull(existing.documentUri, replacement.documentUri, backupInfo?.documentUri),
                        returnedRenameIdentity = checkpoint.returnedRenameIdentity,
                        returnedRenameUri = checkpoint.returnedRenameUri,
                        unresolvedRenamePhase = SafRenamePhase.BACKUP_RENAME,
                    ),
                    checkpoint,
                )
            } else {
                recoveryReconciliation(record, grant, error, listOf(replacement.documentUri))
            }
        }
        if (isCancelled()) {
            return toRecoveryOutcome(
                cancelAfterRename(
                    record,
                    grant,
                    listOfNotNull(existing.documentUri, replacement.documentUri, backupInfo?.documentUri),
                    returnedRenameIdentity = checkpoint.returnedRenameIdentity,
                    returnedRenameUri = checkpoint.returnedRenameUri,
                    unresolvedRenamePhase = SafRenamePhase.BACKUP_RENAME,
                ),
                checkpoint,
            )
        }
        exactChildError(
            record,
            grant,
            replacement,
            temporaryDocumentName(record.expectedFinalName, record.partialId),
            "overwrite_replacement",
        )?.let { return recoveryReconciliation(record, grant, it, listOf(replacement.documentUri)) }
        val observedBackup = backupInfo?.let { SafStoredDocumentIdentity(it.documentUri, it.documentId) }
        val renameHistory = if (
            observedBackup != null && record.renameHistory.none { it.phase == SafRenamePhase.BACKUP_RENAME }
        ) {
            record.renameHistory + observedRenameEvidence(
                record,
                grant,
                SafRenamePhase.BACKUP_RENAME,
                existing,
                observedBackup,
            )
        } else {
            record.renameHistory
        }
        if (!renameHistoryMatchesCommit(
                history = renameHistory,
                scope = SafRenameScope.fromRecord(record, grant),
                strategy = record.strategy,
                duplicatePolicy = record.duplicatePolicy,
                existingIdentity = record.existingIdentity,
            )
        ) {
            return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.ContainmentUnknown("rename_history_malformed"),
                listOfNotNull(observedBackup?.documentUri, existing.documentUri),
            )
        }
        observedBackup?.let { backup ->
            exactChildError(
                record,
                grant,
                backup,
                backupDocumentName(record.expectedFinalName, record.partialId),
                "overwrite_backup",
            )?.let { return recoveryReconciliation(record, grant, it, listOf(backup.documentUri, replacement.documentUri)) }
        }
        cancelledAtBoundary(listOfNotNull(observedBackup?.documentUri, replacement.documentUri))?.let { return it }
        val ready = record.copy(
            state = if (backupInfo == null) SafCommitState.REPLACEMENT_READY else SafCommitState.BACKUP_CREATED,
            backupIdentity = observedBackup,
            temporaryIdentity = replacement,
            temporaryUri = replacement.documentUri,
            renameHistory = renameHistory,
            copiedBytes = record.expectedSizeBytes,
            verifiedDigest = stagedDigest,
        )
        val phase = if (backupInfo == null) {
            SafCommitCheckpointPhase.PROVIDER_VERIFIED
        } else {
            SafCommitCheckpointPhase.BACKUP_RENAMED
        }
        if (!saveCheckpoint(ready, grant, phase)) {
            return recoveryReconciliation(
                ready,
                grant,
                TransferStorageError.StateConflict("journal_overwrite_recovery_failed"),
                listOfNotNull(backupInfo?.documentUri, replacement.documentUri),
            )
        }
        val created = SafCreate.Created(replacement.documentUri, replacement.documentId, replacementInfo.displayName)
        val outcome = if (backupInfo == null) {
            continueSafeOverwrite(
                ready,
                created,
                existing.documentUri,
                existing.documentId,
                grant,
                stagedDigest,
            )
        } else {
            val backupIdentity = requireNotNull(ready.backupIdentity)
            promoteReplacementAfterBackup(
                ready,
                created,
                backupIdentity.documentUri,
                backupIdentity.documentId,
                existing.documentUri,
                grant,
                stagedDigest,
            )
        }
        return toRecoveryOutcome(outcome, checkpointFor(ready, grant, phase))
    }

    private fun resumePublished(
        record: SafCommitRecord,
        checkpoint: SafCommitCheckpoint,
        grant: SafTreeGrant,
    ): SafCommitRecoveryOutcome {
        val final = record.finalIdentity
            ?: return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.ContainmentUnknown("final_identity_missing"),
                record.knownDocumentIdentities.map { it.documentUri },
            )
        authorizationError(
            record,
            grant,
            SafContainmentOperation.RECONCILE,
            final.documentUri,
            final.documentId,
        )?.let {
            return recoveryReconciliation(record, grant, it, listOf(final.documentUri))
        }
        when (val lookup = gateway.query(final.documentUri)) {
            is SafLookup.Found -> if (lookup.document.documentId != final.documentId) {
                return recoveryReconciliation(
                    record,
                    grant,
                    TransferStorageError.ContainmentUnknown("final_identity_changed_after_publication"),
                    listOf(final.documentUri),
                )
            }

            SafLookup.Absent -> return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.NotFound("published_final"),
                listOf(final.documentUri),
            )

            is SafLookup.Failed -> return recoveryReconciliation(record, grant, lookup.error, listOf(final.documentUri))
        }
        cleanupFinalIdentityError(record, grant, final)?.let { error ->
            return recoveryReconciliation(record, grant, error, listOf(final.documentUri))
        }
        if (checkpoint.phase == SafCommitCheckpointPhase.FINAL_VERIFIED ||
            checkpoint.phase == SafCommitCheckpointPhase.PUBLICATION_INTENT ||
            checkpoint.phase == SafCommitCheckpointPhase.PUBLISHED
        ) {
            val pending = record.pendingCleanup + SafCleanupPending.STAGING +
                if (record.backupIdentity == null) emptySet() else setOf(SafCleanupPending.BACKUP)
            val published = record.copy(
                state = SafCommitState.PUBLISHED_OR_VISIBLE,
                pendingCleanup = pending,
            )
            if (!saveCheckpoint(published, grant, SafCommitCheckpointPhase.PUBLISHED)) {
                return recoveryReconciliation(
                    published,
                    grant,
                    TransferStorageError.StateConflict("journal_publication_failed"),
                    listOf(final.documentUri),
                )
            }
            val outcome = finishAndRetryCleanup(published, final.documentUri, grant)
            return toRecoveryOutcome(outcome, checkpointFor(published, grant, SafCommitCheckpointPhase.PUBLISHED))
        }
        if (record.pendingCleanup.isEmpty() && record.stagingReleased) {
            val committed = record.copy(state = SafCommitState.COMMITTED)
            if (!saveCheckpoint(committed, grant, SafCommitCheckpointPhase.COMMITTED)) {
                return recoveryReconciliation(
                    committed,
                    grant,
                    TransferStorageError.StateConflict("journal_committed_result_failed"),
                    listOf(final.documentUri),
                )
            }
            return SafCommitRecoveryOutcome.Committed(
                checkpointFor(committed, grant, SafCommitCheckpointPhase.COMMITTED),
            )
        }
        val outcome = retryPendingCleanup(record, grant)
        return toRecoveryOutcome(outcome, checkpoint)
    }

    private fun verifyCommittedCheckpoint(
        record: SafCommitRecord,
        checkpoint: SafCommitCheckpoint,
        grant: SafTreeGrant,
    ): SafCommitRecoveryOutcome {
        val final = record.finalIdentity
            ?: return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.ContainmentUnknown("committed_final_identity_missing"),
                emptyList(),
            )
        if (record.pendingCleanup.isNotEmpty()) {
            return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.StateConflict("committed_cleanup_not_empty"),
                listOf(final.documentUri),
            )
        }
        authorizationError(
            record,
            grant,
            SafContainmentOperation.RECONCILE,
            final.documentUri,
            final.documentId,
        )?.let {
            return recoveryReconciliation(record, grant, it, listOf(final.documentUri))
        }
        return when (val lookup = gateway.query(final.documentUri)) {
            is SafLookup.Found -> if (lookup.document.documentId == final.documentId) {
                cleanupFinalIdentityError(record, grant, final)?.let { error ->
                    return recoveryReconciliation(record, grant, error, listOf(final.documentUri))
                }
                SafCommitRecoveryOutcome.Committed(checkpoint)
            } else {
                recoveryReconciliation(
                    record,
                    grant,
                    TransferStorageError.ContainmentUnknown("committed_final_identity_changed"),
                    listOf(final.documentUri),
                )
            }

            SafLookup.Absent -> recoveryReconciliation(
                record,
                grant,
                TransferStorageError.NotFound("committed_final"),
                listOf(final.documentUri),
            )

            is SafLookup.Failed -> recoveryReconciliation(record, grant, lookup.error, listOf(final.documentUri))
        }
    }

    private fun findRecoveryChild(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        displayName: String,
    ): SafLookup {
        authorizationError(record, grant, SafContainmentOperation.RECONCILE)?.let {
            return SafLookup.Failed(it)
        }
        return try {
            gateway.findChild(record.uriForTree(record.parentDocumentId), displayName)
        } catch (error: Exception) {
            SafLookup.Failed(StorageFailureClassifier.classifyOrProviderFailure(error, "query"))
        }
    }

    /** A name lookup is only a reachability check; it must resolve to the stored URI/id. */
    private fun exactChildError(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        identity: SafStoredDocumentIdentity,
        expectedName: String,
        reason: String,
    ): TransferStorageError? = when (
        val listed = findRecoveryChild(record, grant, expectedName)
    ) {
        is SafLookup.Found -> {
            val document = listed.document
            if (document.documentUri == identity.documentUri &&
                document.documentId == identity.documentId &&
                document.displayName == expectedName &&
                !document.isDirectory
            ) {
                null
            } else {
                TransferStorageError.ContainmentUnknown("${reason}_child_identity_mismatch")
            }
        }
        SafLookup.Absent -> TransferStorageError.ContainmentUnknown("${reason}_child_not_found")
        is SafLookup.Failed -> listed.error
    }

    /** Allows exact-URI absence to settle a prior delete without repeating it. */
    private fun cleanupTargetIdentityError(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        identity: SafStoredDocumentIdentity,
        expectedName: String,
        reason: String,
    ): TransferStorageError? {
        authorizationError(
            record,
            grant,
            SafContainmentOperation.RECONCILE,
            identity.documentUri,
            identity.documentId,
        )?.let { return it }
        return when (val lookup = gateway.query(identity.documentUri)) {
            SafLookup.Absent -> null
            is SafLookup.Failed -> lookup.error
            is SafLookup.Found -> {
                if (lookup.document.documentUri != identity.documentUri ||
                    lookup.document.documentId != identity.documentId
                ) {
                    TransferStorageError.ContainmentUnknown("${reason}_identity_mismatch")
                } else {
                    exactChildError(record, grant, identity, expectedName, reason)
                }
            }
        }
    }

    private fun cleanupFinalIdentityError(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        identity: SafStoredDocumentIdentity,
    ): TransferStorageError? {
        val verifiedDigest = record.verifiedDigest
            ?: return TransferStorageError.StateConflict("cleanup_verified_digest_missing")
        if (record.expectedDigest != null && record.expectedDigest != verifiedDigest) {
            return TransferStorageError.IntegrityMismatch("cleanup_checkpoint_digest")
        }
        exactChildError(record, grant, identity, record.expectedFinalName, "cleanup_final")?.let { return it }
        when (val lookup = gateway.query(identity.documentUri)) {
            is SafLookup.Found -> if (
                lookup.document.documentUri != identity.documentUri ||
                lookup.document.documentId != identity.documentId ||
                lookup.document.isDirectory || lookup.document.sizeBytes?.let { it == record.expectedSizeBytes } == false
            ) {
                return TransferStorageError.ContainmentUnknown("cleanup_final_identity_mismatch")
            }

            SafLookup.Absent -> return TransferStorageError.NotFound("cleanup_final")
            is SafLookup.Failed -> return lookup.error
        }
        val verification = verifyProviderCopy(
            identity.documentUri,
            identity.documentId,
            record,
            grant,
            verifiedDigest,
        )
        verification.closeError?.let { return it }
        return when (val outcome = verification.outcome) {
            is VerifyResult.Matched -> null
            is VerifyResult.Mismatched -> TransferStorageError.IntegrityMismatch("cleanup_final_digest")
            is VerifyResult.Failed -> outcome.error
            is VerifyResult.VerifiedWithoutExpected ->
                TransferStorageError.StateConflict("cleanup_final_digest_unverified")
        }
    }

    private sealed interface RecoveryDigest {
        data class Ready(val digest: Sha256Digest) : RecoveryDigest
        data class Failed(val error: TransferStorageError) : RecoveryDigest
    }

    private fun recoveryStagedDigest(record: SafCommitRecord): RecoveryDigest {
        val stagedLength = try {
            staging.length(record.partialId)
        } catch (error: Exception) {
            return RecoveryDigest.Failed(
                StorageFailureClassifier.classifyOrProviderFailure(error, "verify", "read"),
            )
        } ?: return RecoveryDigest.Failed(TransferStorageError.NotFound("staged_copy"))
        if (stagedLength != record.expectedSizeBytes) {
            return RecoveryDigest.Failed(TransferStorageError.IntegrityMismatch("staging_length"))
        }
        val phase = verifyStagedBytes(record)
        phase.closeError?.let { return RecoveryDigest.Failed(it) }
        val digest = when (val outcome = phase.outcome) {
            is VerifyResult.Matched -> outcome.digest
            is VerifyResult.VerifiedWithoutExpected -> outcome.digest
            is VerifyResult.Mismatched -> return RecoveryDigest.Failed(TransferStorageError.IntegrityMismatch("staging_digest"))
            is VerifyResult.Failed -> return RecoveryDigest.Failed(outcome.error)
        }
        if (record.verifiedDigest != null && record.verifiedDigest != digest) {
            return RecoveryDigest.Failed(TransferStorageError.IntegrityMismatch("staging_content_changed"))
        }
        return RecoveryDigest.Ready(digest)
    }

    private fun discardInterruptedTemporary(
        record: SafCommitRecord,
        identity: SafStoredDocumentIdentity,
        grant: SafTreeGrant,
    ): SafCommitRecoveryOutcome {
        val temporaryName = temporaryDocumentName(record.expectedFinalName, record.partialId)
        exactChildError(record, grant, identity, temporaryName, "interrupted_temporary")?.let {
            return recoveryReconciliation(record, grant, it, listOf(identity.documentUri))
        }
        if (isCancelled()) {
            return recoveryReconciliation(record, grant, TransferStorageError.Cancelled, listOf(identity.documentUri))
        }
        if (!saveCheckpoint(record, grant, SafCommitCheckpointPhase.INTERRUPTED_TEMPORARY_DELETE_INTENT)) {
            return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.StateConflict("journal_temporary_delete_intent_failed"),
                listOf(identity.documentUri),
            )
        }
        if (isCancelled()) {
            return recoveryReconciliation(record, grant, TransferStorageError.Cancelled, listOf(identity.documentUri))
        }
        exactChildError(record, grant, identity, temporaryName, "interrupted_temporary")?.let {
            return recoveryReconciliation(record, grant, it, listOf(identity.documentUri))
        }
        if (isCancelled()) {
            return recoveryReconciliation(record, grant, TransferStorageError.Cancelled, listOf(identity.documentUri))
        }
        val deletion = deleteExactIdentity(
            record,
            identity,
            grant,
            temporaryDocumentName(record.expectedFinalName, record.partialId),
        )
        if (isCancelled()) {
            return recoveryReconciliation(
                record,
                grant,
                TransferStorageError.Cancelled,
                listOf(identity.documentUri),
            )
        }
        val failure = when (deletion) {
            is SafDeletion.PermissionRevoked -> deletion.error
            is SafDeletion.DeleteRequestFailed -> deletion.error
            is SafDeletion.IdentityMismatch -> TransferStorageError.ContainmentUnknown("temporary_identity_changed")
            is SafDeletion.QueryUnknown -> TransferStorageError.StateConflict("temporary_delete_unsettled")
            else -> null
        }
        if (!saveCheckpoint(
                record,
                grant,
                SafCommitCheckpointPhase.INTERRUPTED_TEMPORARY_DELETE_OBSERVED,
                failure = failure,
            )
        ) {
            return recoveryAtPhase(
                record,
                grant,
                SafCommitCheckpointPhase.INTERRUPTED_TEMPORARY_DELETE_INTENT,
                TransferStorageError.StateConflict("journal_temporary_delete_result_failed"),
                listOf(identity.documentUri),
            )
        }
        return if (deletion is SafDeletion.ConfirmedAbsent) {
            resumeFromReady(record, grant)
        } else {
            recoveryAtPhase(
                record,
                grant,
                SafCommitCheckpointPhase.INTERRUPTED_TEMPORARY_DELETE_OBSERVED,
                failure ?: TransferStorageError.StateConflict("temporary_cleanup_unsettled"),
                listOf(identity.documentUri),
            )
        }
    }

    private fun deleteExactIdentity(
        record: SafCommitRecord,
        identity: SafStoredDocumentIdentity,
        grant: SafTreeGrant,
        expectedName: String? = null,
    ): SafDeletion {
        val authorization = authorizationError(
            record,
            grant,
            SafContainmentOperation.DELETE_TEMPORARY,
            identity.documentUri,
            identity.documentId,
        )
        if (authorization is TransferStorageError.PermissionRevoked) {
            return SafDeletion.PermissionRevoked(authorization)
        }
        if (authorization != null) {
            return SafDeletion.DeleteRequestFailed(authorization)
        }
        return deleteExactChildIdentity(record, identity, grant, expectedName)
    }

    /**
     * Confirms that an exact stored identity is still a direct child of the
     * checkpoint's approved parent before allowing the gateway to delete it.
     * The parent listing is only a reachability check: deletion itself still
     * receives the exact URI/id pair and re-queries that pair before and after
     * the request.
     */
    private fun deleteExactChildIdentity(
        record: SafCommitRecord,
        identity: SafStoredDocumentIdentity,
        grant: SafTreeGrant,
        expectedName: String? = null,
    ): SafDeletion {
        val authorization = authorizationError(
            record,
            grant,
            SafContainmentOperation.DELETE_TEMPORARY,
            identity.documentUri,
            identity.documentId,
        )
        if (authorization is TransferStorageError.PermissionRevoked) {
            return SafDeletion.PermissionRevoked(authorization)
        }
        if (authorization != null) {
            return SafDeletion.DeleteRequestFailed(authorization)
        }
        val observed = try {
            gateway.query(identity.documentUri)
        } catch (_: Exception) {
            return SafDeletion.QueryUnknown("cleanup_identity_query_failed")
        }
        val document = when (observed) {
            SafLookup.Absent -> return SafDeletion.ConfirmedAbsent()
            is SafLookup.Failed -> return when (val error = observed.error) {
                is TransferStorageError.PermissionRevoked -> SafDeletion.PermissionRevoked(error)
                else -> SafDeletion.QueryUnknown("cleanup_identity_query_unresolved")
            }
            is SafLookup.Found -> observed.document
        }
        if (document.documentId != identity.documentId ||
            document.documentUri != identity.documentUri
        ) {
            return SafDeletion.IdentityMismatch(
                expectedDocumentId = identity.documentId,
                observedDocumentId = document.documentId,
                observedDisplayName = document.displayName,
            )
        }
        if (document.displayName.isBlank() ||
            (expectedName != null && document.displayName != expectedName) ||
            document.isDirectory
        ) {
            return SafDeletion.DeleteRequestFailed(
                TransferStorageError.ContainmentUnknown("cleanup_identity_not_expected_file"),
            )
        }
        val childName = expectedName ?: document.displayName
        val listed = try {
            gateway.findChild(record.uriForTree(record.parentDocumentId), childName)
        } catch (_: Exception) {
            return SafDeletion.QueryUnknown("cleanup_parent_query_failed")
        }
        when (listed) {
            SafLookup.Absent -> return SafDeletion.DeleteRequestFailed(
                TransferStorageError.ContainmentUnknown("cleanup_identity_not_reachable"),
            )
            is SafLookup.Failed -> return when (val error = listed.error) {
                is TransferStorageError.PermissionRevoked -> SafDeletion.PermissionRevoked(error)
                else -> SafDeletion.QueryUnknown("cleanup_parent_query_unresolved")
            }
            is SafLookup.Found -> {
                val child = listed.document
                if (child.documentUri != identity.documentUri ||
                    child.documentId != identity.documentId ||
                    child.displayName != document.displayName ||
                    child.displayName != childName ||
                    child.isDirectory
                ) {
                    return SafDeletion.IdentityMismatch(
                        expectedDocumentId = identity.documentId,
                        observedDocumentId = child.documentId,
                        observedDisplayName = child.displayName,
                    )
                }
            }
        }
        return try {
            gateway.deleteAndReconcile(identity.documentUri, identity.documentId, grant)
        } catch (error: Exception) {
            SafDeletion.DeleteRequestFailed(StorageFailureClassifier.classifyOrProviderFailure(error, "delete"))
        }
    }

    private fun recoveryAtPhase(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        phase: SafCommitCheckpointPhase,
        error: TransferStorageError,
        knownUris: List<String>,
    ): SafCommitRecoveryOutcome.ReconciliationRequired {
        val prior = runCatching { journal.load(record.partialId) }.getOrNull()
        val checkpoint = runCatching {
            checkpointFor(
                record,
                grant,
                phase,
                returnedRenameIdentity = prior?.returnedRenameIdentity,
                returnedRenameUri = prior?.returnedRenameUri,
                failure = error,
                unresolvedRenamePhase = prior?.unresolvedRenamePhase?.takeIf { unresolvedPhase ->
                    phase == SafCommitCheckpointPhase.RECONCILIATION_REQUIRED &&
                        record.renameHistory.none { it.phase == unresolvedPhase }
                },
            )
        }.getOrElse {
            prior ?: SafCommitCheckpoint.fromRecord(
                record.copy(grantId = grant.grantId),
                grant,
                phase,
            )
        }
        return SafCommitRecoveryOutcome.ReconciliationRequired(checkpoint, error, knownUris.distinct())
    }

    private fun recoveryReconciliation(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        error: TransferStorageError,
        knownUris: List<String>,
    ): SafCommitRecoveryOutcome.ReconciliationRequired {
        val unresolved = record.copy(state = SafCommitState.RECONCILIATION_REQUIRED)
        val prior = runCatching { journal.load(record.partialId) }.getOrNull()
        val returnedRenameIdentity = prior?.returnedRenameIdentity
        val returnedRenameUri = prior?.returnedRenameUri
        val checkpoint = runCatching {
            checkpointFor(
                unresolved,
                grant,
                SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
                returnedRenameIdentity = returnedRenameIdentity,
                returnedRenameUri = returnedRenameUri,
                failure = error,
                unresolvedRenamePhase = prior?.unresolvedRenamePhase?.takeIf { unresolvedPhase ->
                    unresolved.renameHistory.none { it.phase == unresolvedPhase }
                },
            )
        }.getOrNull() ?: SafCommitCheckpoint.fromRecord(
            unresolved.copy(grantId = grant.grantId),
            grant,
            SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
        )
        saveCheckpoint(
            unresolved,
            grant,
            SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
            returnedRenameIdentity = returnedRenameIdentity,
            returnedRenameUri = returnedRenameUri,
            failure = error,
            unresolvedRenamePhase = prior?.unresolvedRenamePhase?.takeIf { unresolvedPhase ->
                unresolved.renameHistory.none { it.phase == unresolvedPhase }
            },
        )
        return SafCommitRecoveryOutcome.ReconciliationRequired(checkpoint, error, knownUris.distinct())
    }

    private fun isCancellationCheckpoint(checkpoint: SafCommitCheckpoint): Boolean =
        checkpoint.phase == SafCommitCheckpointPhase.CANCELLED ||
            checkpoint.phase == SafCommitCheckpointPhase.CANCELLED_TEMPORARY_DELETE_INTENT ||
            checkpoint.phase == SafCommitCheckpointPhase.CANCELLED_TEMPORARY_DELETE_OBSERVED ||
            checkpoint.lastFailure?.categoryId == TransferStorageError.Cancelled.category.id

    private fun isTerminalCancellationCheckpoint(checkpoint: SafCommitCheckpoint): Boolean =
        checkpoint.phase == SafCommitCheckpointPhase.CANCELLED ||
            checkpoint.phase == SafCommitCheckpointPhase.CANCELLED_TEMPORARY_DELETE_OBSERVED

    /** Publication is not undone by a late cancellation; only cleanup remains. */
    private fun checkpointAlreadyPublished(checkpoint: SafCommitCheckpoint): Boolean =
        checkpoint.phase in setOf(
            SafCommitCheckpointPhase.PUBLISHED,
            SafCommitCheckpointPhase.PROVIDER_TEMPORARY_DELETE_INTENT,
            SafCommitCheckpointPhase.PROVIDER_TEMPORARY_DELETE_OBSERVED,
            SafCommitCheckpointPhase.BACKUP_DELETE_INTENT,
            SafCommitCheckpointPhase.BACKUP_DELETE_OBSERVED,
            SafCommitCheckpointPhase.STAGING_DELETE_INTENT,
            SafCommitCheckpointPhase.STAGING_DELETE_OBSERVED,
            SafCommitCheckpointPhase.COMMITTED,
        ) || (
            checkpoint.phase == SafCommitCheckpointPhase.RECONCILIATION_REQUIRED &&
                checkpoint.finalIdentity != null &&
                checkpoint.copiedBytes == checkpoint.expectedSizeBytes &&
                (checkpoint.stagingReleased || checkpoint.pendingCleanup.isNotEmpty())
            )

    /** Chooses cancellation only where it cannot erase an unresolved mutation. */
    private fun cancelRecoveryAtBoundary(
        record: SafCommitRecord,
        checkpoint: SafCommitCheckpoint,
        grant: SafTreeGrant,
    ): SafCommitOutcome {
        val potentialRename = when {
            checkpoint.unresolvedRenamePhase != null -> checkpoint.unresolvedRenamePhase
            checkpoint.phase == SafCommitCheckpointPhase.BACKUP_RENAME_INTENT -> SafRenamePhase.BACKUP_RENAME
            checkpoint.phase == SafCommitCheckpointPhase.BACKUP_RENAMED &&
                record.renameHistory.none { it.phase == SafRenamePhase.BACKUP_RENAME } -> SafRenamePhase.BACKUP_RENAME
            checkpoint.phase in setOf(
                SafCommitCheckpointPhase.FINAL_RENAME_INTENT,
                SafCommitCheckpointPhase.FINAL_RENAMED,
                SafCommitCheckpointPhase.RENAME_RECONCILIATION_INTENT,
                SafCommitCheckpointPhase.FINAL_VERIFICATION_INTENT,
                SafCommitCheckpointPhase.FINAL_VERIFIED,
            ) -> SafRenamePhase.FINAL_PROMOTION.takeIf {
                record.strategy == SafCommitStrategy.TEMP_THEN_RENAME
            }
            checkpoint.phase == SafCommitCheckpointPhase.RECONCILIATION_REQUIRED &&
                record.renameHistory.any { it.phase == SafRenamePhase.FINAL_PROMOTION } -> SafRenamePhase.FINAL_PROMOTION
            checkpoint.phase == SafCommitCheckpointPhase.RECONCILIATION_REQUIRED &&
                checkpoint.returnedRenameUri != null && record.existingIdentity != null &&
                record.backupIdentity == null && record.renameHistory.none { it.phase == SafRenamePhase.BACKUP_RENAME }
                -> SafRenamePhase.BACKUP_RENAME
            checkpoint.phase == SafCommitCheckpointPhase.RECONCILIATION_REQUIRED &&
                checkpoint.returnedRenameUri != null && record.temporaryIdentity != null -> SafRenamePhase.FINAL_PROMOTION
            else -> null
        }
        if (potentialRename != null) {
            return cancelAfterRename(
                record,
                grant,
                record.knownDocumentIdentities.map { it.documentUri } +
                    listOfNotNull(checkpoint.returnedRenameUri),
                returnedRenameIdentity = checkpoint.returnedRenameIdentity,
                returnedRenameUri = checkpoint.returnedRenameUri,
                unresolvedRenamePhase = potentialRename,
            )
        }
        val knownUris = record.knownDocumentIdentities.map { it.documentUri }
        return when (checkpoint.phase) {
            SafCommitCheckpointPhase.TEMPORARY_CREATE_INTENT,
            SafCommitCheckpointPhase.COPY_STARTED,
            SafCommitCheckpointPhase.FLUSH_INTENT,
            SafCommitCheckpointPhase.PROVIDER_VERIFICATION_INTENT,
            SafCommitCheckpointPhase.VISIBLE_CREATE_INTENT,
            SafCommitCheckpointPhase.VISIBLE_DELETE_INTENT,
            SafCommitCheckpointPhase.INTERRUPTED_TEMPORARY_DELETE_INTENT,
            -> reconcileAndSave(record, grant, TransferStorageError.Cancelled, knownUris)

            else -> cancelBeforePublication(record, grant)
        }
    }

    private fun toRecoveryOutcome(
        outcome: SafCommitOutcome,
        fallback: SafCommitCheckpoint,
    ): SafCommitRecoveryOutcome {
        val persisted = runCatching { journal.load(fallback.commitId) }.getOrNull() ?: fallback
        return when (outcome) {
            is SafCommitOutcome.Committed -> SafCommitRecoveryOutcome.Committed(outcome.checkpoint ?: persisted)
            is SafCommitOutcome.ReconciliationRequired -> {
                val checkpoint = outcome.checkpoint ?: persisted
                if (outcome.error == TransferStorageError.Cancelled &&
                    isTerminalCancellationCheckpoint(checkpoint) &&
                    !checkpointAlreadyPublished(checkpoint)
                ) {
                    SafCommitRecoveryOutcome.Cancelled(checkpoint)
                } else {
                    SafCommitRecoveryOutcome.ReconciliationRequired(checkpoint, outcome.error, outcome.knownUris)
                }
            }
            is SafCommitOutcome.Failed -> {
                val checkpoint = outcome.checkpoint ?: persisted
                if (outcome.error == TransferStorageError.Cancelled &&
                    isTerminalCancellationCheckpoint(checkpoint) &&
                    !checkpointAlreadyPublished(checkpoint)
                ) {
                    SafCommitRecoveryOutcome.Cancelled(checkpoint)
                } else {
                    SafCommitRecoveryOutcome.Failed(checkpoint, outcome.error)
                }
            }
            is SafCommitOutcome.PendingUserDecision -> SafCommitRecoveryOutcome.ReadyToResume(persisted)
            is SafCommitOutcome.Skipped -> SafCommitRecoveryOutcome.Skipped(persisted)
            is SafCommitOutcome.SafeOverwriteUnsupported -> SafCommitRecoveryOutcome.Failed(
                persisted,
                TransferStorageError.Unsupported("safe_overwrite"),
            )
        }
    }

    private fun sameCheckpointCommit(
        expected: SafCommitCheckpoint,
        actual: SafCommitCheckpoint,
    ): Boolean =
        expected.version == actual.version &&
            expected.sessionId == actual.sessionId &&
            expected.transferId == actual.transferId &&
            expected.commitId == actual.commitId &&
            expected.strategy == actual.strategy &&
            expected.duplicatePolicy == actual.duplicatePolicy &&
            expected.approvedTree == actual.approvedTree &&
            expected.parentDocumentId == actual.parentDocumentId &&
            expected.stagingIdentity == actual.stagingIdentity &&
            expected.expectedFinalName == actual.expectedFinalName &&
            expected.expectedSizeBytes == actual.expectedSizeBytes &&
            expected.expectedDigest == actual.expectedDigest

    private fun renameHistoryMatchesCommit(
        history: List<SafRenameEvidence>,
        scope: SafRenameScope,
        strategy: SafCommitStrategy,
        duplicatePolicy: DuplicatePolicy,
        existingIdentity: SafStoredDocumentIdentity?,
    ): Boolean =
        SafRenameHistoryPolicy.isWellFormed(history, scope) &&
            (history.none { it.phase == SafRenamePhase.BACKUP_RENAME } ||
                (duplicatePolicy == DuplicatePolicy.OVERWRITE && existingIdentity != null)) &&
            (history.none { it.phase == SafRenamePhase.FINAL_PROMOTION } ||
                strategy == SafCommitStrategy.TEMP_THEN_RENAME)

    private fun checkpointIdentityShapeMatches(checkpoint: SafCommitCheckpoint): Boolean {
        if (checkpoint.renameHistory.size > SafRenameHistoryPolicy.MAX_ENTRIES) return false
        if (checkpoint.unresolvedRenamePhase != null &&
            (checkpoint.phase != SafCommitCheckpointPhase.RECONCILIATION_REQUIRED ||
                checkpoint.renameHistory.any { it.phase == checkpoint.unresolvedRenamePhase })
        ) {
            return false
        }
        if (checkpoint.stagingIdentity != checkpoint.commitId) return false
        val treeUri = checkpoint.approvedTree.treeUri.toUri()
        if (treeUri.scheme != "content" ||
            treeUri.authority != checkpoint.approvedTree.authority ||
            SafContainment.treeDocumentIdOf(treeUri) != checkpoint.approvedTree.rootDocumentId ||
            SafDocumentIdRules.validate(checkpoint.parentDocumentId) !is SafDocumentIdCheck.Valid
        ) {
            return false
        }
        val renameScope = SafRenameScope(
            grantId = checkpoint.approvedTree.grantId,
            treeUri = checkpoint.approvedTree.treeUri,
            authority = checkpoint.approvedTree.authority,
            rootDocumentId = checkpoint.approvedTree.rootDocumentId,
            parentDocumentId = checkpoint.parentDocumentId,
            sessionId = checkpoint.sessionId,
            transferId = checkpoint.transferId,
            commitId = checkpoint.commitId,
        )
        if (!renameHistoryMatchesCommit(
                history = checkpoint.renameHistory,
                scope = renameScope,
                strategy = checkpoint.strategy,
                duplicatePolicy = checkpoint.duplicatePolicy,
                existingIdentity = checkpoint.existingIdentity,
            )
        ) return false
        // Parse both identities with DocumentsContract; document-id ancestry is never inferred by prefix.
        fun validDocumentIdentity(identity: SafStoredDocumentIdentity): Boolean {
            val strictUri = runCatching { java.net.URI(identity.documentUri) }.getOrNull() ?: return false
            val uri = identity.documentUri.toUri()
            val uriSegments = uri.pathSegments
            return strictUri.scheme == "content" &&
                strictUri.rawAuthority == checkpoint.approvedTree.authority &&
                strictUri.rawQuery == null &&
                strictUri.rawFragment == null &&
                uriSegments.size == 4 &&
                uriSegments[0] == "tree" &&
                uriSegments[2] == "document" &&
                uri.scheme == "content" &&
                uri.authority == checkpoint.approvedTree.authority &&
                SafContainment.treeDocumentIdOf(uri) == checkpoint.approvedTree.rootDocumentId &&
                SafContainment.documentIdOf(uri) == identity.documentId &&
                SafDocumentIdRules.validate(identity.documentId) is SafDocumentIdCheck.Valid
        }
        val identities = listOfNotNull(
            checkpoint.temporaryIdentity,
            checkpoint.existingIdentity,
            checkpoint.backupIdentity,
            checkpoint.returnedRenameIdentity,
            checkpoint.finalIdentity,
        ) + checkpoint.renameHistory.flatMap { evidence ->
            listOfNotNull(evidence.before, evidence.returned)
        }
        if (identities.any { !validDocumentIdentity(it) }) return false
        checkpoint.returnedRenameUri?.let { returnedUri ->
            val strictUri = runCatching { java.net.URI(returnedUri) }.getOrNull() ?: return false
            val uri = returnedUri.toUri()
            val uriSegments = uri.pathSegments
            val returnedId = SafContainment.documentIdOf(uri)
            if (strictUri.scheme != "content" ||
                strictUri.rawAuthority != checkpoint.approvedTree.authority ||
                strictUri.rawQuery != null ||
                strictUri.rawFragment != null ||
                uriSegments.size != 4 ||
                uriSegments[0] != "tree" ||
                uriSegments[2] != "document" ||
                uri.scheme != "content" ||
                uri.authority != checkpoint.approvedTree.authority ||
                SafContainment.treeDocumentIdOf(uri) != checkpoint.approvedTree.rootDocumentId ||
                returnedId == null ||
                SafDocumentIdRules.validate(returnedId) !is SafDocumentIdCheck.Valid ||
                checkpoint.returnedRenameIdentity?.let { identity ->
                    identity.documentUri != returnedUri || identity.documentId != returnedId
                } == true
            ) {
                return false
            }
        }
        return true
    }

    private fun checkpointCleanupIdentitiesMatch(checkpoint: SafCommitCheckpoint): Boolean =
        checkpoint.pendingCleanup.all { pending ->
            when (pending.type) {
                SafCleanupPending.STAGING ->
                    pending.stagingIdentity == checkpoint.stagingIdentity && pending.documentIdentity == null

                SafCleanupPending.PROVIDER_TEMPORARY ->
                    pending.documentIdentity == checkpoint.temporaryIdentity && pending.stagingIdentity == null

                SafCleanupPending.BACKUP ->
                    pending.documentIdentity == checkpoint.backupIdentity && pending.stagingIdentity == null
            }
        }

    private fun checkpointMatchesRecord(
        checkpoint: SafCommitCheckpoint,
        record: SafCommitRecord,
        grant: SafTreeGrant,
    ): Boolean =
        checkpointCleanupIdentitiesMatch(checkpoint) &&
            checkpoint.version == SafCommitCheckpoint.CURRENT_VERSION &&
            checkpoint.sessionId == record.sessionId &&
            checkpoint.transferId == record.transferId &&
            checkpoint.commitId == record.partialId &&
            checkpoint.approvedTree.grantId == grant.grantId &&
            checkpoint.approvedTree.treeUri == record.treeUri &&
            checkpoint.approvedTree.authority == grant.authority &&
            checkpoint.approvedTree.rootDocumentId == record.rootDocumentId &&
            checkpoint.parentDocumentId == record.parentDocumentId &&
            checkpoint.strategy == record.strategy &&
            checkpoint.duplicatePolicy == record.duplicatePolicy &&
            checkpoint.stagingIdentity == record.partialId &&
            checkpoint.expectedFinalName == record.expectedFinalName &&
            checkpoint.expectedSizeBytes == record.expectedSizeBytes &&
            checkpoint.expectedDigest == record.expectedDigest &&
            checkpoint.verifiedDigest == record.verifiedDigest &&
            checkpoint.temporaryIdentity == record.temporaryIdentity &&
            checkpoint.existingIdentity == record.existingIdentity &&
            checkpoint.backupIdentity == record.backupIdentity &&
            checkpoint.finalIdentity == record.finalIdentity &&
            checkpoint.renameHistory == record.renameHistory &&
            checkpoint.unresolvedRenamePhase == null &&
            checkpoint.pendingCleanup.map { it.type }.toSet() == record.pendingCleanup &&
            checkpoint.copiedBytes == record.copiedBytes &&
            checkpoint.stagingReleased == record.stagingReleased

    private fun recordFromCheckpoint(checkpoint: SafCommitCheckpoint): SafCommitRecord {
        val pending = checkpoint.pendingCleanup.map { it.type }.toSet()
        val phaseState = when (checkpoint.phase) {
            SafCommitCheckpointPhase.READY,
            SafCommitCheckpointPhase.STAGING_VERIFICATION_INTENT,
            SafCommitCheckpointPhase.STAGING_VERIFIED,
            -> SafCommitState.STAGING_VERIFIED

            SafCommitCheckpointPhase.DESTINATION_RESOLUTION_INTENT,
            SafCommitCheckpointPhase.DESTINATION_RESOLVED,
            SafCommitCheckpointPhase.TEMPORARY_CREATE_INTENT,
            SafCommitCheckpointPhase.VISIBLE_CREATE_INTENT,
            -> SafCommitState.DESTINATION_RESOLVED

            SafCommitCheckpointPhase.TEMPORARY_CREATED -> SafCommitState.TEMPORARY_CREATED
            SafCommitCheckpointPhase.VISIBLE_CREATED,
            SafCommitCheckpointPhase.VISIBLE_DELETE_INTENT,
            SafCommitCheckpointPhase.VISIBLE_DELETE_OBSERVED,
            SafCommitCheckpointPhase.COPY_STARTED,
            -> if (checkpoint.strategy == SafCommitStrategy.VISIBLE_FINAL_COPY) {
                SafCommitState.FINAL_CREATED
            } else {
                SafCommitState.COPY_STARTED
            }

            SafCommitCheckpointPhase.COPY_COMPLETED,
            SafCommitCheckpointPhase.FLUSH_INTENT,
            -> SafCommitState.COPY_COMPLETED

            SafCommitCheckpointPhase.FLUSH_COMPLETED -> SafCommitState.PROVIDER_FLUSH_COMPLETED
            SafCommitCheckpointPhase.PROVIDER_VERIFICATION_INTENT -> SafCommitState.PROVIDER_VERIFICATION_STARTED
            SafCommitCheckpointPhase.PROVIDER_VERIFIED -> SafCommitState.PROVIDER_VERIFIED
            SafCommitCheckpointPhase.BACKUP_RENAME_INTENT -> SafCommitState.REPLACEMENT_READY
            SafCommitCheckpointPhase.BACKUP_RENAMED -> SafCommitState.BACKUP_CREATED
            SafCommitCheckpointPhase.FINAL_RENAME_INTENT -> SafCommitState.RENAME_STARTED
            SafCommitCheckpointPhase.FINAL_RENAMED,
            SafCommitCheckpointPhase.RENAME_RECONCILIATION_INTENT,
            SafCommitCheckpointPhase.FINAL_VERIFICATION_INTENT,
            -> SafCommitState.RENAMED

            SafCommitCheckpointPhase.FINAL_VERIFIED,
            SafCommitCheckpointPhase.PUBLICATION_INTENT,
            SafCommitCheckpointPhase.PUBLISHED,
            -> SafCommitState.PUBLISHED_OR_VISIBLE

            SafCommitCheckpointPhase.INTERRUPTED_TEMPORARY_DELETE_INTENT,
            SafCommitCheckpointPhase.INTERRUPTED_TEMPORARY_DELETE_OBSERVED,
            -> SafCommitState.PROVIDER_TEMPORARY_CLEANUP_PENDING

            SafCommitCheckpointPhase.PROVIDER_TEMPORARY_DELETE_INTENT,
            SafCommitCheckpointPhase.PROVIDER_TEMPORARY_DELETE_OBSERVED,
            SafCommitCheckpointPhase.BACKUP_DELETE_INTENT,
            SafCommitCheckpointPhase.BACKUP_DELETE_OBSERVED,
            SafCommitCheckpointPhase.STAGING_DELETE_INTENT,
            SafCommitCheckpointPhase.STAGING_DELETE_OBSERVED,
            -> stateForCleanup(pending)

            SafCommitCheckpointPhase.CANCELLED,
            SafCommitCheckpointPhase.CANCELLED_TEMPORARY_DELETE_INTENT,
            SafCommitCheckpointPhase.CANCELLED_TEMPORARY_DELETE_OBSERVED,
            -> SafCommitState.COMMIT_FAILED

            SafCommitCheckpointPhase.RECONCILIATION_REQUIRED -> SafCommitState.RECONCILIATION_REQUIRED
            SafCommitCheckpointPhase.COMMITTED -> SafCommitState.COMMITTED
        }
        val stagingReleased = checkpoint.stagingReleased
        return SafCommitRecord(
            sessionId = checkpoint.sessionId,
            transferId = checkpoint.transferId,
            partialId = checkpoint.commitId,
            treeUri = checkpoint.approvedTree.treeUri,
            rootDocumentId = checkpoint.approvedTree.rootDocumentId,
            parentDocumentId = checkpoint.parentDocumentId,
            temporaryUri = checkpoint.temporaryIdentity?.documentUri,
            finalUri = checkpoint.finalIdentity?.documentUri,
            expectedFinalName = checkpoint.expectedFinalName,
            expectedSizeBytes = checkpoint.expectedSizeBytes,
            expectedDigest = checkpoint.expectedDigest,
            grantId = checkpoint.approvedTree.grantId,
            strategy = checkpoint.strategy,
            state = phaseState,
            duplicatePolicy = checkpoint.duplicatePolicy,
            copiedBytes = checkpoint.copiedBytes,
            temporaryIdentity = checkpoint.temporaryIdentity,
            finalIdentity = checkpoint.finalIdentity,
            existingIdentity = checkpoint.existingIdentity,
            backupIdentity = checkpoint.backupIdentity,
            renameHistory = checkpoint.renameHistory,
            pendingCleanup = pending,
            stagingReleased = stagingReleased,
            verifiedDigest = checkpoint.verifiedDigest,
        )
    }

    /**
     * Removes only an exact, incomplete provider document left by a cancelled
     * copy. This path never opens staging, copies bytes, renames, publishes, or
     * releases staging; normal recovery remains explicitly cancelled.
     */
    public fun retryCancelledTemporaryCleanup(
        checkpoint: SafCommitCheckpoint,
        grant: SafTreeGrant,
    ): SafCommitRecoveryOutcome {
        val latest = try {
            journal.load(checkpoint.commitId)
        } catch (_: Exception) {
            return SafCommitRecoveryOutcome.Failed(
                checkpoint,
                TransferStorageError.StateConflict("journal_load_failed"),
            )
        } ?: return SafCommitRecoveryOutcome.Failed(
            checkpoint,
            TransferStorageError.NotFound("commit_checkpoint"),
        )
        if (!sameCheckpointCommit(checkpoint, latest) ||
            latest.version != SafCommitCheckpoint.CURRENT_VERSION ||
            !checkpointCleanupIdentitiesMatch(latest) ||
            !checkpointIdentityShapeMatches(latest)
        ) {
            return SafCommitRecoveryOutcome.ReconciliationRequired(
                latest,
                TransferStorageError.StateConflict("cancelled_checkpoint_mismatch"),
            )
        }
        if (grant.grantId != latest.approvedTree.grantId ||
            grant.treeUri.toString() != latest.approvedTree.treeUri ||
            grant.authority != latest.approvedTree.authority ||
            grant.rootDocumentId != latest.approvedTree.rootDocumentId
        ) {
            return SafCommitRecoveryOutcome.ReconciliationRequired(
                latest,
                TransferStorageError.StateConflict("grant_context_mismatch"),
            )
        }
        if (!isCancellationCheckpoint(latest)) {
            return SafCommitRecoveryOutcome.Failed(
                latest,
                TransferStorageError.StateConflict("checkpoint_not_cancelled"),
            )
        }
        val record = recordFromCheckpoint(latest)
        if (checkpointAlreadyPublished(latest)) {
            return SafCommitRecoveryOutcome.ReconciliationRequired(
                latest,
                TransferStorageError.StateConflict("published_commit_not_cancelled_temporary"),
                record.knownDocumentIdentities.map { it.documentUri },
            )
        }
        if (latest.unresolvedRenamePhase != null ||
            record.renameHistory.any { it.phase == SafRenamePhase.FINAL_PROMOTION }
        ) {
            // A rename may have moved or duplicated the document. Never delete
            // either side while its final-vs-temporary role is unsettled.
            return SafCommitRecoveryOutcome.ReconciliationRequired(
                latest,
                TransferStorageError.Cancelled,
                record.knownDocumentIdentities.map { it.documentUri },
            )
        }
        val identity = when (record.strategy) {
            SafCommitStrategy.TEMP_THEN_RENAME -> record.temporaryIdentity
            SafCommitStrategy.VISIBLE_FINAL_COPY -> record.finalIdentity
        } ?: return if (latest.phase == SafCommitCheckpointPhase.CANCELLED) {
            SafCommitRecoveryOutcome.Cancelled(latest)
        } else {
            SafCommitRecoveryOutcome.ReconciliationRequired(
                latest,
                TransferStorageError.ContainmentUnknown("cancelled_temporary_identity_missing"),
                record.knownDocumentIdentities.map { it.documentUri },
            )
        }
        val cleanupName = when (record.strategy) {
            SafCommitStrategy.TEMP_THEN_RENAME -> temporaryDocumentName(record.expectedFinalName, record.partialId)
            SafCommitStrategy.VISIBLE_FINAL_COPY -> record.expectedFinalName
        }
        cleanupTargetIdentityError(record, grant, identity, cleanupName, "cancelled_temporary")?.let { error ->
            return SafCommitRecoveryOutcome.ReconciliationRequired(latest, error, listOf(identity.documentUri))
        }

        authorizationError(record, grant, SafContainmentOperation.RECONCILE)?.let { error ->
            return SafCommitRecoveryOutcome.ReconciliationRequired(latest, error, listOf(identity.documentUri))
        }
        val intent = checkpointFor(
            record,
            grant,
            SafCommitCheckpointPhase.CANCELLED_TEMPORARY_DELETE_INTENT,
            returnedRenameIdentity = latest.returnedRenameIdentity,
            returnedRenameUri = latest.returnedRenameUri,
            failure = TransferStorageError.Cancelled,
        )
        if (!runCatching { journal.save(intent) }.getOrDefault(false)) {
            return SafCommitRecoveryOutcome.ReconciliationRequired(
                latest,
                TransferStorageError.StateConflict("journal_cancel_delete_intent_failed"),
                listOf(identity.documentUri),
            )
        }
        cleanupTargetIdentityError(record, grant, identity, cleanupName, "cancelled_temporary")?.let { error ->
            return SafCommitRecoveryOutcome.ReconciliationRequired(intent, error, listOf(identity.documentUri))
        }

        // This is an explicit cleanup API. Once deletion is requested, always
        // persist its observation even if the transfer cancellation signal changes;
        // a failed result save is retried through exact-identity observation.
        val deletion = deleteExactIdentity(record, identity, grant, cleanupName)
        val error = when (deletion) {
            is SafDeletion.ConfirmedAbsent -> null
            is SafDeletion.PermissionRevoked -> deletion.error
            is SafDeletion.DeleteRequestFailed -> deletion.error
            is SafDeletion.IdentityMismatch -> TransferStorageError.ContainmentUnknown("cancelled_temporary_identity_changed")
            is SafDeletion.QueryUnknown -> TransferStorageError.StateConflict("cancelled_temporary_delete_unsettled")
            is SafDeletion.StillPresent -> TransferStorageError.StateConflict("cancelled_temporary_still_present")
        }
        val observed = checkpointFor(
            record,
            grant,
            SafCommitCheckpointPhase.CANCELLED_TEMPORARY_DELETE_OBSERVED,
            returnedRenameIdentity = latest.returnedRenameIdentity,
            returnedRenameUri = latest.returnedRenameUri,
            failure = error ?: TransferStorageError.Cancelled,
        )
        if (!runCatching { journal.save(observed) }.getOrDefault(false)) {
            return SafCommitRecoveryOutcome.ReconciliationRequired(
                intent,
                TransferStorageError.StateConflict("journal_cancel_delete_result_failed"),
                listOf(identity.documentUri),
            )
        }
        return if (error == null) {
            SafCommitRecoveryOutcome.Cancelled(observed)
        } else {
            SafCommitRecoveryOutcome.ReconciliationRequired(observed, error, listOf(identity.documentUri))
        }
    }

    public fun retryPendingCleanup(
        record: SafCommitRecord,
        grant: SafTreeGrant,
    ): SafCommitOutcome {
        if (!cleanupGrantContextMatches(record, grant)) {
            return reconcileKnown(
                record,
                TransferStorageError.StateConflict("cleanup_grant_context_mismatch"),
                record.knownDocumentIdentities.map { it.documentUri },
            )
        }
        val terminalizingCleanup = record.state == SafCommitState.STAGING_CLEANUP_PENDING &&
            record.pendingCleanup.isEmpty() && record.stagingReleased
        if (!cleanupStateAuthorizes(record) && !terminalizingCleanup) {
            return reconcileKnown(
                record,
                TransferStorageError.StateConflict("cleanup_state_not_authorized"),
                record.knownDocumentIdentities.map { it.documentUri },
            )
        }
        val final = record.finalIdentity
            ?: return reconcile(record, TransferStorageError.ContainmentUnknown("final_identity_missing"))
        if (!record.stagingReleased && SafCleanupPending.STAGING !in record.pendingCleanup) {
            return reconcile(
                record,
                TransferStorageError.StateConflict("cleanup_state_inconsistent"),
            )
        }
        val current = record.copy(grantId = grant.grantId)
        val stored = runCatching { journal.load(current.partialId) }.getOrNull()
            ?: return reconcileKnown(
                current,
                TransferStorageError.StateConflict("cleanup_checkpoint_missing"),
                current.knownDocumentIdentities.map { it.documentUri },
            )
        if (!checkpointMatchesRecord(stored, current, grant) ||
            !checkpointIdentityShapeMatches(stored) ||
            !cleanupCheckpointAuthorizesFinal(stored, current) ||
            stored.finalIdentity != final ||
            stored.copiedBytes != stored.expectedSizeBytes
        ) {
            return reconcileKnown(
                current,
                TransferStorageError.StateConflict("cleanup_checkpoint_mismatch"),
                current.knownDocumentIdentities.map { it.documentUri },
            )
        }
        cleanupFinalIdentityError(current, grant, final)?.let {
            return reconcileAndSave(current, grant, it, listOf(final.documentUri))
        }
        if (isCancelled()) {
            return reconcileKnown(current, TransferStorageError.Cancelled, listOf(final.documentUri))
        }

        var outstanding = current.pendingCleanup.toMutableSet()
        var settled = current
        var resultPhase = stored.phase
        for (item in listOf(SafCleanupPending.BACKUP, SafCleanupPending.PROVIDER_TEMPORARY)) {
            if (item !in outstanding) continue
            val identity = when (item) {
                SafCleanupPending.BACKUP -> current.backupIdentity
                SafCleanupPending.PROVIDER_TEMPORARY -> current.temporaryIdentity
                SafCleanupPending.STAGING -> null
            } ?: return reconcileKnown(
                current,
                TransferStorageError.ContainmentUnknown("cleanup_identity_missing"),
                current.knownDocumentIdentities.map { it.documentUri },
            )
            if (identity == final) {
                return reconcileKnown(
                    current,
                    TransferStorageError.ContainmentUnknown("cleanup_identity_is_authoritative_final"),
                    listOf(final.documentUri),
                )
            }
            val cleanupName = when (item) {
                SafCleanupPending.BACKUP -> backupDocumentName(current.expectedFinalName, current.partialId)
                SafCleanupPending.PROVIDER_TEMPORARY -> temporaryDocumentName(current.expectedFinalName, current.partialId)
                SafCleanupPending.STAGING -> error("staging is handled separately")
            }
            cleanupTargetIdentityError(current, grant, identity, cleanupName, "cleanup_${item.id}")?.let {
                return reconcileAndSave(current, grant, it, listOf(final.documentUri, identity.documentUri))
            }
            val intentPhase = when (item) {
                SafCleanupPending.BACKUP -> SafCommitCheckpointPhase.BACKUP_DELETE_INTENT
                SafCleanupPending.PROVIDER_TEMPORARY -> SafCommitCheckpointPhase.PROVIDER_TEMPORARY_DELETE_INTENT
                SafCleanupPending.STAGING -> error("staging is handled separately")
            }
            val observedPhase = when (item) {
                SafCleanupPending.BACKUP -> SafCommitCheckpointPhase.BACKUP_DELETE_OBSERVED
                SafCleanupPending.PROVIDER_TEMPORARY -> SafCommitCheckpointPhase.PROVIDER_TEMPORARY_DELETE_OBSERVED
                SafCleanupPending.STAGING -> error("staging is handled separately")
            }
            val authorization = authorizationError(
                current,
                grant,
                SafContainmentOperation.DELETE_TEMPORARY,
                identity.documentUri,
                identity.documentId,
            )
            if (authorization is TransferStorageError.PermissionRevoked) {
                if (!saveCheckpoint(
                        settled,
                        grant,
                        observedPhase,
                        failure = authorization,
                    )
                ) {
                    return journalFailure(
                        settled,
                        grant,
                        observedPhase,
                        afterMutation = true,
                        knownUris = current.knownDocumentIdentities.map { it.documentUri },
                    )
                }
                resultPhase = observedPhase
                continue
            }
            if (authorization != null) {
                val unresolved = settled.copy(state = SafCommitState.RECONCILIATION_REQUIRED)
                saveCheckpoint(
                    unresolved,
                    grant,
                    SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
                    failure = authorization,
                )
                return reconcileKnown(
                    unresolved,
                    authorization,
                    current.knownDocumentIdentities.map { it.documentUri },
                )
            }
            if (!saveCheckpoint(settled, grant, intentPhase)) {
                return journalFailure(
                    settled,
                    grant,
                    intentPhase,
                    afterMutation = true,
                    knownUris = current.knownDocumentIdentities.map { it.documentUri },
                )
            }
            if (isCancelled()) {
                return reconcileAndSave(settled, grant, TransferStorageError.Cancelled, listOf(final.documentUri, identity.documentUri))
            }
            cleanupFinalIdentityError(settled, grant, final)?.let {
                return reconcileAndSave(settled, grant, it, listOf(final.documentUri, identity.documentUri))
            }
            cleanupTargetIdentityError(settled, grant, identity, cleanupName, "cleanup_${item.id}")?.let {
                return reconcileAndSave(settled, grant, it, listOf(final.documentUri, identity.documentUri))
            }
            if (isCancelled()) {
                return reconcileKnown(settled, TransferStorageError.Cancelled, listOf(final.documentUri, identity.documentUri))
            }
            val deletion = deleteExactChildIdentity(current, identity, grant, cleanupName)
            if (isCancelled()) {
                val unresolvedCleanup = settled.copy(
                    state = stateForCleanup(outstanding + item),
                    pendingCleanup = (outstanding + item).toSet(),
                )
                return reconcileCancelledProviderMutation(
                    unresolvedCleanup,
                    grant,
                    listOf(identity.documentUri),
                )
            }
            var failure: TransferStorageError? = null
            when (deletion) {
                is SafDeletion.ConfirmedAbsent -> outstanding.remove(item)
                is SafDeletion.StillPresent -> Unit
                is SafDeletion.PermissionRevoked -> failure = deletion.error
                is SafDeletion.QueryUnknown,
                is SafDeletion.IdentityMismatch,
                is SafDeletion.DeleteRequestFailed,
                -> failure = when (deletion) {
                    is SafDeletion.DeleteRequestFailed -> deletion.error
                    is SafDeletion.IdentityMismatch -> TransferStorageError.ContainmentUnknown("cleanup_identity_changed")
                    else -> TransferStorageError.StateConflict("cleanup_unsettled")
                }
            }
            settled = settled.copy(
                state = stateForCleanup(outstanding),
                pendingCleanup = outstanding.toSet(),
            )
            if (!saveCheckpoint(settled, grant, observedPhase, failure = failure)) {
                // The durable intent still includes this identity. Preserve it in
                // the reconciliation snapshot so recovery performs a fresh exact
                // URI/id query before deciding whether another delete is needed.
                val unresolvedCleanup = settled.copy(
                    state = stateForCleanup(outstanding + item),
                    pendingCleanup = (outstanding + item).toSet(),
                )
                return journalFailure(
                    unresolvedCleanup,
                    grant,
                    observedPhase,
                    afterMutation = true,
                    knownUris = current.knownDocumentIdentities.map { it.documentUri },
                )
            }
            resultPhase = observedPhase
            if (failure != null && deletion !is SafDeletion.PermissionRevoked) {
                val unresolved = settled.copy(state = SafCommitState.RECONCILIATION_REQUIRED)
                saveCheckpoint(
                    unresolved,
                    grant,
                    SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
                    failure = failure,
                )
                return reconcileKnown(
                    unresolved,
                    failure,
                    current.knownDocumentIdentities.map { it.documentUri },
                )
            }
        }

        if (SafCleanupPending.STAGING in outstanding) {
            if (!saveCheckpoint(settled, grant, SafCommitCheckpointPhase.STAGING_DELETE_INTENT)) {
                return journalFailure(
                    settled,
                    grant,
                    SafCommitCheckpointPhase.STAGING_DELETE_INTENT,
                    afterMutation = true,
                    knownUris = current.knownDocumentIdentities.map { it.documentUri },
                )
            }
            if (isCancelled()) {
                return reconcileAndSave(settled, grant, TransferStorageError.Cancelled, listOf(final.documentUri))
            }
            cleanupFinalIdentityError(settled, grant, final)?.let {
                return reconcileAndSave(settled, grant, it, listOf(final.documentUri))
            }
            val observation = deleteStagingAndObserve(current.partialId)
            if (observation.confirmedAbsent) outstanding.remove(SafCleanupPending.STAGING)
            settled = settled.copy(
                state = stateForCleanup(outstanding),
                pendingCleanup = outstanding.toSet(),
                stagingReleased = observation.confirmedAbsent,
            )
            if (!saveCheckpoint(
                    settled,
                    grant,
                    SafCommitCheckpointPhase.STAGING_DELETE_OBSERVED,
                    failure = observation.error,
                )
            ) {
                return journalFailure(
                    settled,
                    grant,
                    SafCommitCheckpointPhase.STAGING_DELETE_OBSERVED,
                    afterMutation = true,
                    knownUris = current.knownDocumentIdentities.map { it.documentUri },
                )
            }
            resultPhase = SafCommitCheckpointPhase.STAGING_DELETE_OBSERVED
        }

        cleanupFinalIdentityError(settled, grant, final)?.let { error ->
            return reconcileAndSave(settled, grant, error, listOf(final.documentUri))
        }

        val remaining = outstanding.toSet()
        settled = settled.copy(
            state = stateForCleanup(remaining),
            pendingCleanup = remaining,
            stagingReleased = settled.stagingReleased || SafCleanupPending.STAGING !in remaining,
        )
        if (remaining.isEmpty()) {
            settled = settled.copy(state = SafCommitState.COMMITTED)
            if (!saveCheckpoint(settled, grant, SafCommitCheckpointPhase.COMMITTED)) {
                return journalFailure(
                    settled,
                    grant,
                    SafCommitCheckpointPhase.COMMITTED,
                    afterMutation = true,
                    knownUris = listOf(final.documentUri),
                )
            }
            resultPhase = SafCommitCheckpointPhase.COMMITTED
        } else {
            resultPhase = when {
                SafCleanupPending.BACKUP in remaining -> SafCommitCheckpointPhase.BACKUP_DELETE_OBSERVED
                SafCleanupPending.PROVIDER_TEMPORARY in remaining -> SafCommitCheckpointPhase.PROVIDER_TEMPORARY_DELETE_OBSERVED
                SafCleanupPending.STAGING in remaining -> SafCommitCheckpointPhase.STAGING_DELETE_OBSERVED
                else -> resultPhase
            }
            if (!saveCheckpoint(settled, grant, resultPhase)) {
                return journalFailure(
                    settled,
                    grant,
                    resultPhase,
                    afterMutation = true,
                    knownUris = listOf(final.documentUri),
                )
            }
        }
        val resultCheckpoint = runCatching { checkpointFor(settled, grant, resultPhase) }.getOrNull()
        return SafCommitOutcome.Committed(
            record = settled,
            finalUri = final.documentUri,
            pendingCleanup = remaining,
            checkpoint = resultCheckpoint,
        )
    }

    /**
     * The record and grant must describe the same selected tree. Then ask the
     * gateway for a live permission check; a snapshot's writable bit is not a
     * substitute for consulting the platform again.
     */
    private fun authorizationError(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        operation: SafContainmentOperation,
        documentUri: String? = null,
        expectedDocumentId: String? = null,
    ): TransferStorageError? {
        if (record.grantId != null && grant.grantId != record.grantId) {
            return TransferStorageError.StateConflict("grant_context_mismatch")
        }
        if (grant.treeUri.toString() != record.treeUri ||
            grant.rootDocumentId != record.rootDocumentId ||
            grant.authority != grant.treeUri.authority
        ) {
            return TransferStorageError.StateConflict("grant_context_mismatch")
        }
        val needsWrite = operation in setOf(
            SafContainmentOperation.CREATE_DESTINATION,
            SafContainmentOperation.OPEN_WRITE,
            SafContainmentOperation.RENAME,
            SafContainmentOperation.DELETE_TEMPORARY,
        )
        if (needsWrite && !grant.writable) {
            return TransferStorageError.PermissionRevoked("write")
        }
        gateway.recheckPersistedGrant(grant, operation)?.let { return it }

        // The parent URI is built from the grant, never accepted from a caller.
        // The exact root is already named by the persisted tree grant; every
        // other parent must be proven by the provider's tiered containment API.
        val parentUri = SafContainment.documentUriUsingTree(
            grant.treeUri,
            record.parentDocumentId,
        ) ?: return TransferStorageError.ContainmentUnknown("parent_uri_unavailable")
        if (record.parentDocumentId != grant.rootDocumentId) {
            val prover = gateway as? SafContainmentProver
                ?: return TransferStorageError.ContainmentUnknown("containment_prover_unavailable")
            when (
                val evidence = SafDestinationResolver.validateExistingUri(
                    grant = grant,
                    documentUri = parentUri,
                    prover = prover,
                    sdkInt = android.os.Build.VERSION.SDK_INT,
                )
            ) {
                is SafContainmentEvidence.ProviderConfirmedChild,
                is SafContainmentEvidence.ProviderConfirmedPath,
                -> Unit

                is SafContainmentEvidence.GrantScopedCanonical ->
                    if (operation != SafContainmentOperation.CREATE_DESTINATION) {
                        return TransferStorageError.ContainmentUnknown("parent_not_provider_confirmed")
                    }

                is SafContainmentEvidence.PermissionRevoked -> return evidence.error
                is SafContainmentEvidence.Unknown -> when (val reason = evidence.reason) {
                    is TransferStorageError.PermissionRevoked -> return reason
                    else -> return TransferStorageError.ContainmentUnknown("parent_unknown")
                }

                is SafContainmentEvidence.Outside ->
                    return TransferStorageError.ContainmentUnknown("parent_outside_grant")

                is SafContainmentEvidence.Malformed ->
                    return TransferStorageError.ContainmentUnknown("parent_malformed")
            }
        }

        if (documentUri != null) {
            val uri = documentUri.toUri()
            if (uri.scheme != "content" || uri.authority != grant.authority) {
                return TransferStorageError.ContainmentUnknown("document_authority_mismatch")
            }
            val uriDocumentId = SafContainment.documentIdOf(uri)
            if (expectedDocumentId == null || uriDocumentId != expectedDocumentId) {
                return TransferStorageError.ContainmentUnknown("document_identity_mismatch")
            }
            when (val looked = gateway.query(documentUri)) {
                SafLookup.Absent -> return if (
                    operation == SafContainmentOperation.DELETE_TEMPORARY ||
                    operation == SafContainmentOperation.RECONCILE
                ) {
                    null
                } else {
                    TransferStorageError.NotFound("saf_document")
                }
                is SafLookup.Failed -> return looked.error
                is SafLookup.Found -> {
                    val document = looked.document
                    if (document.documentUri != documentUri || document.documentId != expectedDocumentId) {
                        return TransferStorageError.ContainmentUnknown("document_identity_mismatch")
                    }
                    if (document.displayName.isBlank() || document.isDirectory) {
                        return TransferStorageError.ContainmentUnknown("document_not_file")
                    }
                    val child = when (
                        val listed = gateway.findChild(parentUri.toString(), document.displayName)
                    ) {
                        SafLookup.Absent -> return TransferStorageError.ContainmentUnknown("document_not_in_approved_parent")
                        is SafLookup.Failed -> return listed.error
                        is SafLookup.Found -> listed.document
                    }
                    if (child.documentUri != documentUri ||
                        child.documentId != expectedDocumentId ||
                        child.displayName != document.displayName ||
                        child.isDirectory
                    ) {
                        return TransferStorageError.ContainmentUnknown("document_parent_identity_mismatch")
                    }
                }
            }
        }
        return null
    }

    // -- Recoverable overwrite ----------------------------------------------

    /**
     * Replaces an existing document without ever deleting it first.
     *
     * The ordering is the whole content of "safe" here. The replacement is built
     * and verified under a temporary identity before the existing document is
     * moved, and the existing document is moved rather than deleted, so that
     * every intermediate state has a way back. Delete-first has one intermediate
     * state -- the bytes are gone and the replacement is not in place -- and no
     * way back from it.
     *
     * Ambiguity stops the commit rather than being resolved by guesswork, and
     * every identity involved is preserved on the way out so the next pass can
     * ask the provider what exists.
     */
    private fun commitSafeOverwrite(
        record: SafCommitRecord,
        existingUri: String,
        existingDocumentId: String,
        grant: SafTreeGrant,
        stagedDigest: Sha256Digest,
    ): SafCommitOutcome {
        val tempName = temporaryDocumentName(record.expectedFinalName, record.partialId)
        val provisional = when (val produced = produceVerifiedTemporary(record, tempName, grant, stagedDigest)) {
            is VerifiedTemporary.Stopped -> return produced.outcome
            is VerifiedTemporary.Ready -> produced
        }
        return continueSafeOverwrite(
            provisional.record,
            provisional.created,
            existingUri,
            existingDocumentId,
            grant,
            stagedDigest,
        )
    }

    private fun continueSafeOverwrite(
        record: SafCommitRecord,
        replacement: SafCreate.Created,
        existingUri: String,
        existingDocumentId: String,
        grant: SafTreeGrant,
        stagedDigest: Sha256Digest,
    ): SafCommitOutcome {
        if (isCancelled()) return cancelBeforePublication(record, grant)
        val replacementIdentity = record.temporaryIdentity
            ?: return reconcileKnown(record, TransferStorageError.ContainmentUnknown("overwrite_replacement_identity_missing"), listOf(replacement.documentUri))
        val replacementName = temporaryDocumentName(record.expectedFinalName, record.partialId)
        exactChildError(record, grant, replacementIdentity, replacementName, "overwrite_replacement")?.let {
            return reconcileAndSave(record, grant, it, listOf(replacement.documentUri))
        }
        // 4. Everything needed to finish is now present, and nothing the user
        // had has been touched yet.
        if (record.backupIdentity != null) {
            val backup = record.backupIdentity
            val backupAuthorization = authorizationError(
                record,
                grant,
                SafContainmentOperation.RECONCILE,
                backup.documentUri,
                backup.documentId,
            )
            if (backupAuthorization != null) {
                return reconcileKnown(record, backupAuthorization, listOf(backup.documentUri, replacement.documentUri))
            }
            exactChildError(
                record,
                grant,
                backup,
                backupDocumentName(record.expectedFinalName, record.partialId),
                "overwrite_backup",
            )?.let { return reconcileAndSave(record, grant, it, listOf(backup.documentUri, replacement.documentUri)) }
            when (val lookup = gateway.query(backup.documentUri)) {
                is SafLookup.Found -> if (lookup.document.documentId != backup.documentId) {
                    return reconcileKnown(
                        record,
                        TransferStorageError.ContainmentUnknown("backup_identity_changed"),
                        listOf(backup.documentUri, existingUri, replacement.documentUri),
                    )
                }

                SafLookup.Absent -> return reconcileKnown(
                    record,
                    TransferStorageError.NotFound("overwrite_backup"),
                    listOf(backup.documentUri, existingUri, replacement.documentUri),
                )

                is SafLookup.Failed -> return reconcileKnown(record, lookup.error, listOf(backup.documentUri))
            }
            when (val original = gateway.query(existingUri)) {
                SafLookup.Absent -> Unit
                is SafLookup.Found -> return reconcileKnown(
                    record,
                    TransferStorageError.StateConflict("overwrite_original_still_present"),
                    listOf(backup.documentUri, existingUri, replacement.documentUri),
                )

                is SafLookup.Failed -> return reconcileKnown(record, original.error, listOf(existingUri))
            }
            val readyReplacement = when (val looked = gateway.query(replacement.documentUri)) {
                is SafLookup.Found -> if (looked.document.documentId == replacement.documentId) {
                    looked.document
                } else {
                    return reconcileKnown(
                        record,
                        TransferStorageError.ContainmentUnknown("replacement_identity_changed"),
                        listOf(backup.documentUri, replacement.documentUri),
                    )
                }

                SafLookup.Absent -> return reconcileKnown(
                    record,
                    TransferStorageError.NotFound("overwrite_replacement"),
                    listOf(backup.documentUri, replacement.documentUri),
                )

                is SafLookup.Failed -> return reconcileKnown(record, looked.error, listOf(replacement.documentUri))
            }
            exactChildError(record, grant, replacementIdentity, replacementName, "overwrite_replacement")?.let {
                return reconcileAndSave(record, grant, it, listOf(backup.documentUri, replacement.documentUri))
            }
            return promoteReplacementAfterBackup(
                record.copy(state = SafCommitState.BACKUP_CREATED),
                SafCreate.Created(replacement.documentUri, replacement.documentId, readyReplacement.displayName),
                backup.documentUri,
                backup.documentId,
                existingUri,
                grant,
                stagedDigest,
            )
        }
        var current = record.copy(state = SafCommitState.REPLACEMENT_READY)

        // 5. Resolve the existing final again. The first lookup was before the
        // copy and the destination may have changed underneath it; acting on the
        // earlier answer is how an overwrite replaces the wrong document.
        authorizationError(
            current,
            grant,
            SafContainmentOperation.RECONCILE,
            existingUri,
            existingDocumentId,
        )?.let {
            return reconcileKnown(current, it, listOf(existingUri, replacement.documentUri))
        }
        val existing = when (val looked = gateway.query(existingUri)) {
            is SafLookup.Found -> {
                if (looked.document.documentId != existingDocumentId) {
                    return reconcileKnown(
                        current,
                        TransferStorageError.ContainmentUnknown("overwrite_identity_changed"),
                        listOf(existingUri, replacement.documentUri),
                    )
                }
                looked.document
            }
            SafLookup.Absent -> return reconcile(
                current,
                TransferStorageError.StateConflict("overwrite_target_absent"),
            )

            is SafLookup.Failed -> return reconcile(current, looked.error)
        }

        // 6. Move the existing document aside rather than deleting it. This is
        // the last point at which nothing has been given up.
        exactChildError(current, grant, replacementIdentity, replacementName, "overwrite_replacement")?.let {
            return reconcileAndSave(current, grant, it, listOf(existingUri, replacement.documentUri))
        }
        if (isCancelled()) return cancelBeforePublication(current, grant)
        val backupName = backupDocumentName(record.expectedFinalName, record.partialId)
        if (isCancelled()) return cancelBeforePublication(current, grant)
        renameHistoryAppendError(current, grant, SafRenamePhase.BACKUP_RENAME)?.let {
            return reconcileKnown(current, it, listOf(existing.documentUri, replacement.documentUri))
        }
        authorizationError(
            current,
            grant,
            SafContainmentOperation.RENAME,
            existing.documentUri,
            existing.documentId,
        )?.let {
            return route(current, it, replacement.documentUri)
        }
        if (isCancelled()) return cancelBeforePublication(current, grant)
        if (!saveCheckpoint(current, grant, SafCommitCheckpointPhase.BACKUP_RENAME_INTENT)) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.BACKUP_RENAME_INTENT,
                afterMutation = true,
                knownUris = listOf(existing.documentUri, replacement.documentUri),
            )
        }
        if (isCancelled()) return cancelBeforePublication(current, grant)
        exactChildError(current, grant, replacementIdentity, replacementName, "overwrite_replacement")?.let {
            return reconcileAndSave(current, grant, it, listOf(existing.documentUri, replacement.documentUri))
        }
        if (isCancelled()) return cancelBeforePublication(current, grant)
        val moved = when (val result = gateway.rename(existing.documentUri, backupName)) {
            is SafRename.Renamed -> result
            is SafRename.Failed -> {
                if (result.error is TransferStorageError.Unsupported) {
                    return onOverwriteUnsupported(
                        current,
                        existing.documentUri,
                        "backup_rename_unsupported",
                    )
                }
                return renameMutationFailed(
                    current,
                    grant,
                    SafRenamePhase.BACKUP_RENAME,
                    result.error,
                    listOf(existing.documentUri, replacement.documentUri),
                )
            }
        }
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOfNotNull(existing.documentUri, replacement.documentUri, moved.documentUri),
                returnedRenameUri = moved.documentUri,
                unresolvedRenamePhase = SafRenamePhase.BACKUP_RENAME,
            )
        }
        current = current.copy(state = SafCommitState.BACKUP_CREATED)
        if (!saveCheckpoint(
                current,
                grant,
                SafCommitCheckpointPhase.BACKUP_RENAMED,
                returnedRenameUri = moved.documentUri,
            )
        ) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.BACKUP_RENAMED,
                afterMutation = true,
                returnedRenameUri = moved.documentUri,
                knownUris = listOfNotNull(existing.documentUri, replacement.documentUri, moved.documentUri),
            )
        }
        if (!saveCheckpoint(
                current,
                grant,
                SafCommitCheckpointPhase.RENAME_RECONCILIATION_INTENT,
                returnedRenameUri = moved.documentUri,
            )
        ) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.RENAME_RECONCILIATION_INTENT,
                afterMutation = true,
                returnedRenameUri = moved.documentUri,
                knownUris = listOfNotNull(existing.documentUri, replacement.documentUri, moved.documentUri),
            )
        }
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOfNotNull(existing.documentUri, replacement.documentUri, moved.documentUri),
                returnedRenameUri = moved.documentUri,
                unresolvedRenamePhase = SafRenamePhase.BACKUP_RENAME,
            )
        }

        // 7. Settle what that rename left. Only a unique result is authoritative:
        // a provider that copies rather than moves has left two documents and
        // guessing which to delete next is how the wrong one goes.
        val backupSettlement = settleRename(
            existing.documentUri,
            existing.documentId,
            moved.documentUri,
            SafRenamePhase.BACKUP_RENAME,
            current,
            grant,
        )
        val backup = when (backupSettlement) {
            is RenameSettlement.Unknown -> return if (backupSettlement.error == TransferStorageError.Cancelled) {
                cancelAfterRename(
                    current,
                    grant,
                    backupSettlement.knownUris + replacement.documentUri + existing.documentUri,
                    returnedRenameIdentity = backupSettlement.evidence.returned,
                    returnedRenameUri = backupSettlement.evidence.returned?.documentUri,
                    unresolvedRenamePhase = SafRenamePhase.BACKUP_RENAME,
                )
            } else {
                reconcileKnown(
                    current.copy(renameHistory = current.renameHistory + backupSettlement.evidence),
                    backupSettlement.error,
                    backupSettlement.knownUris + replacement.documentUri + existing.documentUri,
                )
            }

            is RenameSettlement.Settled -> {
                current = current.copy(renameHistory = current.renameHistory + backupSettlement.evidence)
                backupSettlement.identity
            }
        }
        val backupUri = backup.authoritativeDocumentUri
            ?: return reconcileKnown(
                current,
                TransferStorageError.StateConflict("overwrite_backup_ambiguous"),
                backup.knownUris + replacement.documentUri,
            )
        val backupId = backup.authoritativeDocumentId
            ?: return reconcileKnown(
                current,
                TransferStorageError.StateConflict("overwrite_backup_ambiguous"),
                backup.knownUris + replacement.documentUri,
            )
        val backupIdentity = SafStoredDocumentIdentity(backupUri, backupId)
        exactChildError(current, grant, backupIdentity, backupName, "overwrite_backup")?.let {
            return reconcileAndSave(
                current,
                grant,
                it,
                listOf(backupUri, replacement.documentUri, existing.documentUri),
            )
        }
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOf(backupUri, replacement.documentUri, existing.documentUri),
                returnedRenameIdentity = backupIdentity,
                returnedRenameUri = moved.documentUri,
                unresolvedRenamePhase = SafRenamePhase.BACKUP_RENAME,
            )
        }
        current = current.copy(backupIdentity = backupIdentity)
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOf(backupUri, replacement.documentUri, existing.documentUri),
                returnedRenameIdentity = backupIdentity,
                returnedRenameUri = moved.documentUri,
            )
        }
        if (!saveCheckpoint(
                current,
                grant,
                SafCommitCheckpointPhase.BACKUP_RENAMED,
                returnedRenameIdentity = backupIdentity,
                returnedRenameUri = moved.documentUri,
            )
        ) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.BACKUP_RENAMED,
                afterMutation = true,
                returnedRenameIdentity = backupIdentity,
                returnedRenameUri = moved.documentUri,
                knownUris = listOf(backupUri, replacement.documentUri),
            )
        }

        return promoteReplacementAfterBackup(
            current,
            replacement,
            backupUri,
            backupId,
            existing.documentUri,
            grant,
            stagedDigest,
        )
    }

    private fun promoteReplacementAfterBackup(
        record: SafCommitRecord,
        replacement: SafCreate.Created,
        backupUri: String,
        backupId: String,
        existingUri: String,
        grant: SafTreeGrant,
        stagedDigest: Sha256Digest,
    ): SafCommitOutcome {
        var current = record
        if (isCancelled()) return cancelBeforePublication(current, grant)
        val replacementIdentity = current.temporaryIdentity
            ?: return reconcileAndSave(
                current,
                grant,
                TransferStorageError.ContainmentUnknown("overwrite_replacement_identity_missing"),
                listOf(backupUri, replacement.documentUri, existingUri),
            )
        val replacementName = temporaryDocumentName(current.expectedFinalName, current.partialId)
        exactChildError(current, grant, replacementIdentity, replacementName, "overwrite_replacement")?.let {
            return reconcileAndSave(current, grant, it, listOf(backupUri, replacement.documentUri, existingUri))
        }
        val backupIdentity = SafStoredDocumentIdentity(backupUri, backupId)
        if (current.backupIdentity != backupIdentity) {
            return reconcileAndSave(
                current,
                grant,
                TransferStorageError.ContainmentUnknown("overwrite_backup_identity_mismatch"),
                listOf(backupUri, replacement.documentUri, existingUri),
            )
        }
        exactChildError(
            current,
            grant,
            backupIdentity,
            backupDocumentName(current.expectedFinalName, current.partialId),
            "overwrite_backup",
        )?.let { return reconcileAndSave(current, grant, it, listOf(backupUri, replacement.documentUri, existingUri)) }
        renameHistoryAppendError(current, grant, SafRenamePhase.FINAL_PROMOTION)?.let {
            return reconcileAndSave(current, grant, it, listOf(backupUri, replacement.documentUri, existingUri))
        }
        // 8. Promote the replacement to the final name.
        authorizationError(
            current,
            grant,
            SafContainmentOperation.RENAME,
            replacement.documentUri,
            replacement.documentId,
        )?.let {
            return reconcileKnown(
                current,
                it,
                listOf(backupUri, replacement.documentUri, existingUri).distinct(),
            )
        }
        if (isCancelled()) return cancelBeforePublication(current, grant)
        current = current.copy(state = SafCommitState.RENAME_STARTED)
        if (!saveCheckpoint(current, grant, SafCommitCheckpointPhase.FINAL_RENAME_INTENT)) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.FINAL_RENAME_INTENT,
                afterMutation = true,
                knownUris = listOf(backupUri, replacement.documentUri, existingUri),
            )
        }
        if (isCancelled()) return cancelBeforePublication(current, grant)
        exactChildError(current, grant, replacementIdentity, replacementName, "overwrite_replacement")?.let {
            return reconcileAndSave(current, grant, it, listOf(backupUri, replacement.documentUri, existingUri))
        }
        exactChildError(
            current,
            grant,
            backupIdentity,
            backupDocumentName(current.expectedFinalName, current.partialId),
            "overwrite_backup",
        )?.let { return reconcileAndSave(current, grant, it, listOf(backupUri, replacement.documentUri, existingUri)) }
        if (isCancelled()) return cancelBeforePublication(current, grant)
        val promoted = when (val result = gateway.rename(replacement.documentUri, record.expectedFinalName)) {
            is SafRename.Renamed -> result
            is SafRename.Failed -> return renameMutationFailed(
                current,
                grant,
                SafRenamePhase.FINAL_PROMOTION,
                result.error,
                listOf(backupUri, replacement.documentUri, existingUri),
            )
        }
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOfNotNull(backupUri, replacement.documentUri, existingUri, promoted.documentUri),
                returnedRenameUri = promoted.documentUri,
                unresolvedRenamePhase = SafRenamePhase.FINAL_PROMOTION,
            )
        }
        current = current.copy(state = SafCommitState.RENAMED)
        if (!saveCheckpoint(
                current,
                grant,
                SafCommitCheckpointPhase.FINAL_RENAMED,
                returnedRenameUri = promoted.documentUri,
            )
        ) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.FINAL_RENAMED,
                afterMutation = true,
                returnedRenameUri = promoted.documentUri,
                knownUris = listOfNotNull(backupUri, replacement.documentUri, existingUri, promoted.documentUri),
            )
        }
        if (!saveCheckpoint(
                current,
                grant,
                SafCommitCheckpointPhase.RENAME_RECONCILIATION_INTENT,
                returnedRenameUri = promoted.documentUri,
            )
        ) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.RENAME_RECONCILIATION_INTENT,
                afterMutation = true,
                returnedRenameUri = promoted.documentUri,
                knownUris = listOfNotNull(backupUri, replacement.documentUri, existingUri, promoted.documentUri),
            )
        }
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOfNotNull(backupUri, replacement.documentUri, existingUri, promoted.documentUri),
                returnedRenameUri = promoted.documentUri,
                unresolvedRenamePhase = SafRenamePhase.FINAL_PROMOTION,
            )
        }

        // 9. Settle that rename too.
        val finalSettlement = settleRename(
            replacement.documentUri,
            replacement.documentId,
            promoted.documentUri,
            SafRenamePhase.FINAL_PROMOTION,
            current,
            grant,
        )
        val finalIdentity = when (finalSettlement) {
            is RenameSettlement.Unknown -> return if (finalSettlement.error == TransferStorageError.Cancelled) {
                cancelAfterRename(
                    current,
                    grant,
                    finalSettlement.knownUris + backupUri,
                    returnedRenameIdentity = finalSettlement.evidence.returned,
                    returnedRenameUri = promoted.documentUri,
                    unresolvedRenamePhase = SafRenamePhase.FINAL_PROMOTION,
                )
            } else {
                reconcileKnown(
                    current.copy(renameHistory = current.renameHistory + finalSettlement.evidence),
                    finalSettlement.error,
                    finalSettlement.knownUris + backupUri,
                )
            }

            is RenameSettlement.Settled -> {
                if (finalSettlement.identity.reconciliation == SafRenameReconciliation.AMBIGUOUS_BOTH_RESOLVE) {
                    return finishWithRetainedTemporary(
                        current,
                        grant,
                        stagedDigest,
                        finalSettlement.evidence,
                        finalSettlement.identity,
                    )
                }
                current = current.copy(renameHistory = current.renameHistory + finalSettlement.evidence)
                finalSettlement.identity
            }
        }
        val finalUri = finalIdentity.authoritativeDocumentUri
            ?: return reconcileKnown(
                current,
                TransferStorageError.StateConflict("overwrite_final_ambiguous"),
                finalIdentity.knownUris + backupUri,
            )
        val finalDocumentId = finalIdentity.authoritativeDocumentId
            ?: return reconcileKnown(
                current,
                TransferStorageError.StateConflict("overwrite_final_ambiguous"),
                finalIdentity.knownUris + backupUri,
            )
        val finalStoredIdentity = SafStoredDocumentIdentity(finalUri, finalDocumentId)
        exactChildError(current, grant, finalStoredIdentity, record.expectedFinalName, "overwrite_final")?.let {
            return reconcileAndSave(
                current,
                grant,
                it,
                listOf(backupUri, replacement.documentUri, finalUri),
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        }
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOf(backupUri, replacement.documentUri, finalUri),
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
                unresolvedRenamePhase = SafRenamePhase.FINAL_PROMOTION,
            )
        }
        current = current.copy(
            finalUri = finalUri,
            finalIdentity = finalStoredIdentity,
        )
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOf(backupUri, replacement.documentUri, finalUri),
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        }
        if (!saveCheckpoint(
                current,
                grant,
                SafCommitCheckpointPhase.FINAL_RENAMED,
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        ) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.FINAL_RENAMED,
                afterMutation = true,
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
                knownUris = listOf(backupUri, replacement.documentUri, finalUri),
            )
        }
        if (!saveCheckpoint(
                current,
                grant,
                SafCommitCheckpointPhase.FINAL_VERIFICATION_INTENT,
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        ) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.FINAL_VERIFICATION_INTENT,
                afterMutation = true,
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
                knownUris = listOf(backupUri, replacement.documentUri, finalUri),
            )
        }
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOf(backupUri, replacement.documentUri, finalUri),
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        }

        // 10. Verify the document under its final name, not the temporary's. A
        // rename is a provider operation; it has to be shown to have produced the
        // bytes that were verified, not assumed to have.
        val verified = verifyProviderCopy(finalUri, finalDocumentId, record, grant, stagedDigest)
        if (verified.outcome is VerifyResult.Failed &&
            verified.outcome.error == TransferStorageError.Cancelled
        ) {
            return cancelAfterRename(
                current,
                grant,
                listOf(backupUri, finalUri),
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        }
        val unverifiedFinal = current.copy(finalUri = null, finalIdentity = null)
        verified.closeError?.let {
            return reconcileAndSave(
                unverifiedFinal,
                grant,
                it,
                listOf(backupUri, finalUri),
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        }
        if (verified.outcome is VerifyResult.Failed) {
            return reconcileAndSave(
                unverifiedFinal,
                grant,
                verified.outcome.error,
                listOf(backupUri, finalUri),
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        }
        if (verified.outcome is VerifyResult.Mismatched) {
            return reconcileAndSave(
                unverifiedFinal,
                grant,
                TransferStorageError.IntegrityMismatch("digest"),
                listOf(backupUri, finalUri),
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        }
        if (verified.outcome !is VerifyResult.Matched) {
            return reconcileAndSave(
                unverifiedFinal,
                grant,
                TransferStorageError.StateConflict("verification_digest_missing"),
                listOf(backupUri, finalUri),
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        }
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOf(backupUri, replacement.documentUri, finalUri),
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        }
        exactChildError(current, grant, finalStoredIdentity, record.expectedFinalName, "overwrite_final")?.let {
            return reconcileAndSave(
                current.copy(finalUri = null, finalIdentity = null),
                grant,
                it,
                listOf(backupUri, replacement.documentUri, finalUri),
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        }
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOf(backupUri, replacement.documentUri, finalUri),
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        }
        current = current.copy(
            finalUri = finalUri,
            finalIdentity = finalStoredIdentity,
        )
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOf(backupUri, finalUri),
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        }
        if (!saveCheckpoint(
                current,
                grant,
                SafCommitCheckpointPhase.FINAL_VERIFIED,
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        ) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.FINAL_VERIFIED,
                afterMutation = true,
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
                knownUris = listOf(backupUri, finalUri),
            )
        }
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOf(backupUri, finalUri),
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        }
        current = current.copy(state = SafCommitState.PUBLISHED_OR_VISIBLE)
        if (!saveCheckpoint(
                current,
                grant,
                SafCommitCheckpointPhase.PUBLISHED,
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        ) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.PUBLICATION_INTENT,
                afterMutation = true,
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
                knownUris = listOf(backupUri, finalUri),
            )
        }

        // 11. Recorded before the deletion, so that a crash before it and a crash
        // after it are distinguishable by state alone.
        current = current.copy(
            state = SafCommitState.BACKUP_CLEANUP_PENDING,
            pendingCleanup = setOf(SafCleanupPending.BACKUP, SafCleanupPending.STAGING),
        )
        if (!saveCheckpoint(
                current,
                grant,
                SafCommitCheckpointPhase.BACKUP_DELETE_INTENT,
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        ) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.BACKUP_DELETE_INTENT,
                afterMutation = true,
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
                knownUris = listOf(backupUri, finalUri),
            )
        }

        // 12. Deleted by the identity the provider issued for the backup, never
        // by filename: a name resolves to whatever holds it now, which after two
        // renames is not something this commit should be guessing at.
        authorizationError(
            current,
            grant,
            SafContainmentOperation.DELETE_TEMPORARY,
            backupUri,
            backupId,
        )?.let {
            return when (it) {
                is TransferStorageError.PermissionRevoked -> finish(
                    current,
                    finalUri,
                    grant,
                    setOf(SafCleanupPending.BACKUP),
                )
                else -> reconcileAndSave(current, grant, it, listOf(backupUri, finalUri))
            }
        }
        if (isCancelled()) {
            return reconcileAndSave(current, grant, TransferStorageError.Cancelled, listOf(backupUri, finalUri))
        }
        cleanupFinalIdentityError(current, grant, finalStoredIdentity)?.let {
            return reconcileAndSave(current, grant, it, listOf(backupUri, finalUri))
        }
        exactChildError(
            current,
            grant,
            backupIdentity,
            backupDocumentName(current.expectedFinalName, current.partialId),
            "overwrite_backup_cleanup",
        )?.let { return reconcileAndSave(current, grant, it, listOf(backupUri, finalUri)) }
        if (isCancelled()) {
            return reconcileAndSave(current, grant, TransferStorageError.Cancelled, listOf(backupUri, finalUri))
        }
        val deletion = deleteExactChildIdentity(
            current,
            backupIdentity,
            grant,
            backupDocumentName(current.expectedFinalName, current.partialId),
        )
        if (isCancelled()) {
            return reconcileAndSave(
                current,
                grant,
                TransferStorageError.Cancelled,
                listOf(backupUri, finalUri),
                returnedRenameIdentity = finalStoredIdentity,
                returnedRenameUri = promoted.documentUri,
            )
        }

        // 13. Absence is settled by the follow-up query, never by the delete
        // request's own answer.
        return when (deletion) {
            is SafDeletion.ConfirmedAbsent -> {
                val observed = current.copy(pendingCleanup = setOf(SafCleanupPending.STAGING))
                if (!saveCheckpoint(
                        observed,
                        grant,
                        SafCommitCheckpointPhase.BACKUP_DELETE_OBSERVED,
                        returnedRenameIdentity = finalStoredIdentity,
                        returnedRenameUri = promoted.documentUri,
                    )
                ) {
                    val unresolvedCleanup = current.copy(
                        state = SafCommitState.BACKUP_CLEANUP_PENDING,
                        pendingCleanup = setOf(SafCleanupPending.BACKUP, SafCleanupPending.STAGING),
                    )
                    journalFailure(
                        unresolvedCleanup,
                        grant,
                        SafCommitCheckpointPhase.BACKUP_DELETE_OBSERVED,
                        afterMutation = true,
                        returnedRenameIdentity = finalStoredIdentity,
                        returnedRenameUri = promoted.documentUri,
                        knownUris = listOf(finalUri, backupUri),
                    )
                } else {
                    finish(observed, finalUri, grant)
                }
            }

            // The backup is still there, or the grant is gone: the document is
            // delivered either way, and the retry targets the stored identity.
            is SafDeletion.StillPresent,
            is SafDeletion.PermissionRevoked -> {
                val observed = current.copy(
                    state = SafCommitState.BACKUP_CLEANUP_PENDING,
                    pendingCleanup = setOf(SafCleanupPending.BACKUP, SafCleanupPending.STAGING),
                )
                val failure = (deletion as? SafDeletion.PermissionRevoked)?.error
                if (!saveCheckpoint(
                        observed,
                        grant,
                        SafCommitCheckpointPhase.BACKUP_DELETE_OBSERVED,
                        returnedRenameIdentity = finalStoredIdentity,
                        returnedRenameUri = promoted.documentUri,
                        failure = failure,
                    )
                ) {
                    journalFailure(
                        observed,
                        grant,
                        SafCommitCheckpointPhase.BACKUP_DELETE_OBSERVED,
                        afterMutation = true,
                        returnedRenameIdentity = finalStoredIdentity,
                        returnedRenameUri = promoted.documentUri,
                        knownUris = listOf(finalUri, backupUri),
                    )
                } else {
                    finish(observed, finalUri, grant, setOf(SafCleanupPending.BACKUP))
                }
            }

            // What is there is not known, or is not the backup. Deleting on that
            // basis is how a cleanup removes a document it did not create.
            is SafDeletion.QueryUnknown,
            is SafDeletion.IdentityMismatch,
            is SafDeletion.DeleteRequestFailed -> {
                val error = TransferStorageError.StateConflict("backup_cleanup_unsettled")
                if (!saveCheckpoint(
                        current,
                        grant,
                        SafCommitCheckpointPhase.BACKUP_DELETE_OBSERVED,
                        returnedRenameIdentity = finalStoredIdentity,
                        returnedRenameUri = promoted.documentUri,
                        failure = error,
                    )
                ) {
                    journalFailure(
                        current,
                        grant,
                        SafCommitCheckpointPhase.BACKUP_DELETE_OBSERVED,
                        afterMutation = true,
                        returnedRenameIdentity = finalStoredIdentity,
                        returnedRenameUri = promoted.documentUri,
                        knownUris = listOf(finalUri, backupUri),
                    )
                } else {
                    reconcileKnown(current, error, listOf(backupUri, finalUri).distinct())
                }
            }
        }
    }

    private fun onOverwriteUnsupported(
        record: SafCommitRecord,
        existingUri: String,
        reason: String,
    ): SafCommitOutcome = SafCommitOutcome.SafeOverwriteUnsupported(
        record = record.copy(state = SafCommitState.DESTINATION_RESOLVED),
        existingUri = existingUri,
        reason = reason,
    )

    private fun reconcileKnown(
        record: SafCommitRecord,
        error: TransferStorageError,
        knownUris: List<String>,
    ): SafCommitOutcome = SafCommitOutcome.ReconciliationRequired(
        record = record.copy(state = SafCommitState.RECONCILIATION_REQUIRED),
        error = error,
        knownUris = knownUris.distinct(),
    )

    private fun reconcileAndSave(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        error: TransferStorageError,
        knownUris: List<String>,
        returnedRenameIdentity: SafStoredDocumentIdentity? = null,
        returnedRenameUri: String? = null,
    ): SafCommitOutcome.ReconciliationRequired {
        val unresolved = record.copy(state = SafCommitState.RECONCILIATION_REQUIRED)
        val prior = runCatching { journal.load(record.partialId) }.getOrNull()
        val returnedIdentity = returnedRenameIdentity ?: prior?.returnedRenameIdentity
        val returnedUri = returnedRenameUri ?: prior?.returnedRenameUri
        val unresolvedPhase = prior?.unresolvedRenamePhase?.takeIf { phase ->
            unresolved.renameHistory.none { it.phase == phase }
        }
        val checkpoint = checkpointFor(
            unresolved,
            grant,
            SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
            returnedRenameIdentity = returnedIdentity,
            returnedRenameUri = returnedUri,
            failure = error,
            unresolvedRenamePhase = unresolvedPhase,
        )
        val saved = runCatching { journal.save(checkpoint) }.getOrDefault(false)
        return SafCommitOutcome.ReconciliationRequired(
            record = unresolved,
            error = if (saved) error else TransferStorageError.StateConflict("journal_reconciliation_save_failed"),
            knownUris = knownUris.distinct(),
            checkpoint = checkpoint,
        )
    }

    private fun renameMutationFailed(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        phase: SafRenamePhase,
        error: TransferStorageError,
        knownUris: List<String>,
    ): SafCommitOutcome.ReconciliationRequired {
        val unresolved = record.copy(state = SafCommitState.RECONCILIATION_REQUIRED)
        val checkpoint = checkpointFor(
            unresolved,
            grant,
            SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
            failure = error,
            unresolvedRenamePhase = phase,
        )
        val persisted = saveCheckpoint(
            unresolved,
            grant,
            SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
            failure = error,
            unresolvedRenamePhase = phase,
        )
        return SafCommitOutcome.ReconciliationRequired(
            record = unresolved,
            error = if (persisted) error else TransferStorageError.StateConflict("journal_rename_failure_save_failed"),
            knownUris = knownUris.distinct(),
            checkpoint = checkpoint,
        )
    }

    // -- Strategy A ---------------------------------------------------------

    /**
     * Where a commit got to in producing a verified temporary.
     *
     * Both strategies and the overwrite path share the same prologue, because the
     * order of operations is not a per-strategy choice: create, copy, flush,
     * close, reopen, verify is the only order in which the digest means anything.
     * Duplicating it would let the copies drift apart, and the interesting bugs
     * live in the differences.
     */
    private sealed interface VerifiedTemporary {
        data class Ready(
            val record: SafCommitRecord,
            val created: SafCreate.Created,
        ) : VerifiedTemporary

        data class Stopped(val outcome: SafCommitOutcome) : VerifiedTemporary
    }

    private fun produceVerifiedTemporary(
        record: SafCommitRecord,
        tempName: String,
        grant: SafTreeGrant,
        stagedDigest: Sha256Digest,
    ): VerifiedTemporary {
        if (isCancelled()) return VerifiedTemporary.Stopped(cancelBeforePublication(record, grant))
        val parentUri = record.uriForTree(record.parentDocumentId)
        authorizationError(record, grant, SafContainmentOperation.CREATE_DESTINATION)?.let {
            return VerifiedTemporary.Stopped(route(record, it, created = null))
        }

        if (!saveCheckpoint(record, grant, SafCommitCheckpointPhase.TEMPORARY_CREATE_INTENT)) {
            return VerifiedTemporary.Stopped(
                journalFailure(
                    record,
                    grant,
                    SafCommitCheckpointPhase.TEMPORARY_CREATE_INTENT,
                    afterMutation = false,
                ),
            )
        }
        if (isCancelled()) return VerifiedTemporary.Stopped(cancelBeforePublication(record, grant))
        val created = when (val result = gateway.create(parentUri, "application/octet-stream", tempName)) {
            is SafCreate.Created -> result
            is SafCreate.Failed -> {
                if (!saveCheckpoint(
                        record,
                        grant,
                        SafCommitCheckpointPhase.TEMPORARY_CREATE_INTENT,
                        failure = result.error,
                    )
                ) {
                    return VerifiedTemporary.Stopped(
                        journalFailure(
                            record,
                            grant,
                            SafCommitCheckpointPhase.TEMPORARY_CREATE_INTENT,
                            afterMutation = true,
                        ),
                    )
                }
                if (isCancelled()) {
                    return VerifiedTemporary.Stopped(
                        reconcileCancelledProviderMutation(record, grant, emptyList()),
                    )
                }
                return VerifiedTemporary.Stopped(onCreateFailed(record, result.error))
            }
        }

        var current = record.copy(
            state = SafCommitState.TEMPORARY_CREATED,
            temporaryUri = created.documentUri,
            temporaryIdentity = SafStoredDocumentIdentity(created.documentUri, created.documentId),
        )
        if (isCancelled()) {
            return VerifiedTemporary.Stopped(
                reconcileCancelledProviderMutation(current, grant, listOf(created.documentUri)),
            )
        }
        if (!saveCheckpoint(current, grant, SafCommitCheckpointPhase.TEMPORARY_CREATED)) {
            return VerifiedTemporary.Stopped(
                journalFailure(
                    current,
                    grant,
                    SafCommitCheckpointPhase.TEMPORARY_CREATED,
                    afterMutation = true,
                ),
            )
        }
        if (isCancelled()) return VerifiedTemporary.Stopped(cancelBeforePublication(current, grant))
        val temporaryIdentity = requireNotNull(current.temporaryIdentity)
        exactChildError(current, grant, temporaryIdentity, tempName, "temporary")?.let {
            return VerifiedTemporary.Stopped(reconcileAndSave(current, grant, it, listOf(created.documentUri)))
        }
        if (isCancelled()) return VerifiedTemporary.Stopped(cancelBeforePublication(current, grant))

        // Copy, flush and close before anything is verified: the digest has to
        // come from a descriptor the provider is holding, not from the stream
        // that was written to.
        current = current.copy(state = SafCommitState.COPY_STARTED)
        if (!saveCheckpoint(current, grant, SafCommitCheckpointPhase.COPY_STARTED)) {
            return VerifiedTemporary.Stopped(
                journalFailure(
                    current,
                    grant,
                    SafCommitCheckpointPhase.COPY_STARTED,
                    afterMutation = true,
                ),
            )
        }
        val phase = runCopy(current, created.documentUri, created.documentId, grant)
        val copiedBytes = when (val outcome = phase.outcome) {
            is SafCopyOutcome.Copied -> outcome.bytes
            is SafCopyOutcome.Failed -> outcome.bytes
        }
        current = current.copy(copiedBytes = copiedBytes)
        phase.journalError?.let {
            return VerifiedTemporary.Stopped(
                if (it == TransferStorageError.Cancelled) {
                    reconcileCancelledProviderMutation(current, grant, listOf(created.documentUri))
                } else {
                    journalFailure(
                        current,
                        grant,
                        SafCommitCheckpointPhase.COPY_STARTED,
                        afterMutation = true,
                        knownUris = listOf(created.documentUri),
                    )
                },
            )
        }
        phase.closeError?.let {
            return VerifiedTemporary.Stopped(onCopyFailed(current, it, created.documentUri))
        }
        if (phase.outcome is SafCopyOutcome.Failed &&
            phase.outcome.error == TransferStorageError.Cancelled
        ) {
            return VerifiedTemporary.Stopped(cancelBeforePublication(current, grant))
        }
        if (phase.outcome is SafCopyOutcome.Failed) {
            return VerifiedTemporary.Stopped(
                onCopyFailed(current, phase.outcome.error, created.documentUri),
            )
        }
        current = current.copy(state = SafCommitState.COPY_COMPLETED)

        if (phase.flush is FlushDurability.FlushFailed) {
            return VerifiedTemporary.Stopped(
                onCopyFailed(current, phase.flush.error, created.documentUri),
            )
        }
        // A copy the provider would not flush is not a copy that can be
        // verified, let alone committed.
        if (phase.flush is FlushDurability.FlushUnsupported) {
            return VerifiedTemporary.Stopped(
                onCopyFailed(
                    current,
                    TransferStorageError.Unsupported("saf_flush"),
                    created.documentUri,
                ),
            )
        }
        current = current.copy(state = SafCommitState.PROVIDER_FLUSH_COMPLETED)

        current = current.copy(state = SafCommitState.PROVIDER_VERIFICATION_STARTED)
        if (isCancelled()) return VerifiedTemporary.Stopped(cancelBeforePublication(current, grant))
        if (!saveCheckpoint(current, grant, SafCommitCheckpointPhase.PROVIDER_VERIFICATION_INTENT)) {
            return VerifiedTemporary.Stopped(
                journalFailure(
                    current,
                    grant,
                    SafCommitCheckpointPhase.PROVIDER_VERIFICATION_INTENT,
                    afterMutation = true,
                ),
            )
        }
        if (isCancelled()) return VerifiedTemporary.Stopped(cancelBeforePublication(current, grant))
        val verified = verifyProviderCopy(
            created.documentUri,
            created.documentId,
            record,
            grant,
            stagedDigest,
        )
        verified.closeError?.let {
            return VerifiedTemporary.Stopped(onCopyFailed(current, it, created.documentUri))
        }
        if (verified.outcome is VerifyResult.Failed) {
            return VerifiedTemporary.Stopped(
                if (verified.outcome.error == TransferStorageError.Cancelled) {
                    cancelBeforePublication(current, grant)
                } else {
                    onCopyFailed(current, verified.outcome.error, created.documentUri)
                },
            )
        }
        if (verified.outcome is VerifyResult.Mismatched) {
            return VerifiedTemporary.Stopped(
                onCopyFailed(
                    current,
                    TransferStorageError.IntegrityMismatch("digest"),
                    created.documentUri,
                ),
            )
        }
        if (verified.outcome !is VerifyResult.Matched) {
            return VerifiedTemporary.Stopped(
                onCopyFailed(
                    current,
                    TransferStorageError.StateConflict("verification_digest_missing"),
                    created.documentUri,
                ),
            )
        }
        exactChildError(current, grant, temporaryIdentity, tempName, "temporary")?.let {
            return VerifiedTemporary.Stopped(reconcileAndSave(current, grant, it, listOf(created.documentUri)))
        }
        if (isCancelled()) return VerifiedTemporary.Stopped(cancelBeforePublication(current, grant))
        current = current.copy(state = SafCommitState.PROVIDER_VERIFIED)
        if (!saveCheckpoint(current, grant, SafCommitCheckpointPhase.PROVIDER_VERIFIED)) {
            return VerifiedTemporary.Stopped(
                journalFailure(
                    current,
                    grant,
                    SafCommitCheckpointPhase.PROVIDER_VERIFIED,
                    afterMutation = true,
                ),
            )
        }

        return VerifiedTemporary.Ready(current, created)
    }

    private fun commitTempThenRename(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        stagedDigest: Sha256Digest,
    ): SafCommitOutcome {
        val tempName = temporaryDocumentName(record.expectedFinalName, record.partialId)
        val provisional = when (val produced = produceVerifiedTemporary(record, tempName, grant, stagedDigest)) {
            is VerifiedTemporary.Stopped -> return produced.outcome
            is VerifiedTemporary.Ready -> produced
        }
        return promoteVerifiedTemporary(provisional.record, provisional.created, grant, stagedDigest)
    }

    private fun promoteVerifiedTemporary(
        record: SafCommitRecord,
        created: SafCreate.Created,
        grant: SafTreeGrant,
        stagedDigest: Sha256Digest,
    ): SafCommitOutcome {
        var current = record
        if (isCancelled()) return cancelBeforePublication(current, grant)
        val temporaryIdentity = current.temporaryIdentity
            ?: return reconcileKnown(current, TransferStorageError.ContainmentUnknown("temporary_identity_missing"), listOf(created.documentUri))
        val temporaryName = temporaryDocumentName(current.expectedFinalName, current.partialId)
        exactChildError(current, grant, temporaryIdentity, temporaryName, "temporary")?.let {
            return reconcileAndSave(current, grant, it, listOf(created.documentUri))
        }
        renameHistoryAppendError(current, grant, SafRenamePhase.FINAL_PROMOTION)?.let {
            return reconcileKnown(current, it, listOf(created.documentUri))
        }
        // Recorded before the call so that a death either side of it is
        // answerable: RENAMED with no final document means the rename did not
        // happen, whatever the provider claimed.
        current = current.copy(state = SafCommitState.RENAME_STARTED)
        authorizationError(
            current,
            grant,
            SafContainmentOperation.RENAME,
            created.documentUri,
            created.documentId,
        )?.let {
            return route(current, it, created.documentUri)
        }
        if (isCancelled()) return cancelBeforePublication(current, grant)
        if (!saveCheckpoint(current, grant, SafCommitCheckpointPhase.FINAL_RENAME_INTENT)) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.FINAL_RENAME_INTENT,
                afterMutation = true,
                knownUris = listOf(created.documentUri),
            )
        }
        if (isCancelled()) return cancelBeforePublication(current, grant)
        exactChildError(current, grant, temporaryIdentity, temporaryName, "temporary")?.let {
            return reconcileAndSave(current, grant, it, listOf(created.documentUri))
        }
        if (isCancelled()) return cancelBeforePublication(current, grant)
        val renamed = when (
            val result = gateway.rename(created.documentUri, record.expectedFinalName)
        ) {
            is SafRename.Renamed -> result
            is SafRename.Failed -> return onRenameFailed(current, grant, result.error, created.documentUri)
        }
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOfNotNull(created.documentUri, renamed.documentUri),
                returnedRenameUri = renamed.documentUri,
                unresolvedRenamePhase = SafRenamePhase.FINAL_PROMOTION,
            )
        }
        current = current.copy(state = SafCommitState.RENAMED)
        if (!saveCheckpoint(
                current,
                grant,
                SafCommitCheckpointPhase.FINAL_RENAMED,
                returnedRenameUri = renamed.documentUri,
            )
        ) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.FINAL_RENAMED,
                afterMutation = true,
                returnedRenameUri = renamed.documentUri,
                knownUris = listOfNotNull(created.documentUri, renamed.documentUri),
            )
        }
        if (!saveCheckpoint(
                current,
                grant,
                SafCommitCheckpointPhase.RENAME_RECONCILIATION_INTENT,
                returnedRenameUri = renamed.documentUri,
            )
        ) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.RENAME_RECONCILIATION_INTENT,
                afterMutation = true,
                returnedRenameUri = renamed.documentUri,
                knownUris = listOfNotNull(created.documentUri, renamed.documentUri),
            )
        }
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOfNotNull(created.documentUri, renamed.documentUri),
                returnedRenameUri = renamed.documentUri,
                unresolvedRenamePhase = SafRenamePhase.FINAL_PROMOTION,
            )
        }

        // The provider may have returned the same identity, a new one, or
        // nothing. Which of those it was decides what exists now, so it is
        // settled by querying both -- never by preferring the old URI because
        // it is the one already to hand.
        when (val settled = settleRename(
            created.documentUri,
            created.documentId,
            renamed.documentUri,
            SafRenamePhase.FINAL_PROMOTION,
            current,
            grant,
        )) {
            is RenameSettlement.Unknown -> return if (settled.error == TransferStorageError.Cancelled) {
                cancelAfterRename(
                    current.copy(renameHistory = current.renameHistory + settled.evidence),
                    grant,
                    settled.knownUris,
                    returnedRenameIdentity = settled.evidence.returned,
                    returnedRenameUri = renamed.documentUri,
                )
            } else {
                onRenameUnresolved(
                    current,
                    grant,
                    settled,
                    created.documentUri,
                    renamed.documentUri,
                )
            }

            is RenameSettlement.Settled -> {
                val identity = settled.identity
                if (identity.reconciliation == SafRenameReconciliation.AMBIGUOUS_BOTH_RESOLVE) {
                    return finishWithRetainedTemporary(
                        current,
                        grant,
                        stagedDigest,
                        settled.evidence,
                        identity,
                    )
                }
                if (identity.requiresReconciliation) {
                    return onRenameAmbiguous(current, grant, identity, settled.evidence, created.documentUri)
                }
                val finalUri = identity.authoritativeDocumentUri
                    ?: return onRenameAmbiguous(current, grant, identity, settled.evidence, created.documentUri)
                val finalDocumentId = identity.authoritativeDocumentId
                    ?: return onRenameAmbiguous(current, grant, identity, settled.evidence, created.documentUri)
                current = current.copy(renameHistory = current.renameHistory + settled.evidence)
                val settledIdentity = SafStoredDocumentIdentity(finalUri, finalDocumentId)
                if (isCancelled()) {
                    return cancelAfterRename(
                        current,
                        grant,
                        listOf(created.documentUri, finalUri),
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                }
                if (!saveCheckpoint(
                        current,
                        grant,
                        SafCommitCheckpointPhase.FINAL_RENAMED,
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                ) {
                    return journalFailure(
                        current,
                        grant,
                        SafCommitCheckpointPhase.FINAL_RENAMED,
                        afterMutation = true,
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                        knownUris = listOf(created.documentUri, finalUri),
                    )
                }
                if (!saveCheckpoint(
                        current,
                        grant,
                        SafCommitCheckpointPhase.FINAL_VERIFICATION_INTENT,
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                ) {
                    return journalFailure(
                        current,
                        grant,
                        SafCommitCheckpointPhase.FINAL_VERIFICATION_INTENT,
                        afterMutation = true,
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                        knownUris = listOf(created.documentUri, finalUri),
                    )
                }
                if (isCancelled()) {
                    return cancelAfterRename(
                        current,
                        grant,
                        listOf(created.documentUri, finalUri),
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                }
                exactChildError(current, grant, settledIdentity, record.expectedFinalName, "final")?.let {
                    return reconcileAndSave(
                        current,
                        grant,
                        it,
                        listOf(created.documentUri, finalUri),
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                }
                if (isCancelled()) {
                    return cancelAfterRename(
                        current,
                        grant,
                        listOf(created.documentUri, finalUri),
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                }

                // Both identities surviving is caught above as ambiguous, so
                // reaching here means exactly one of them resolves and the
                // authoritative URI is the one to publish.
                val finalVerification = verifyProviderCopy(
                    finalUri,
                    finalDocumentId,
                    current,
                    grant,
                    stagedDigest,
                )
                val knownFinal = current.copy(finalUri = finalUri, finalIdentity = settledIdentity)
                if (finalVerification.outcome is VerifyResult.Failed &&
                    finalVerification.outcome.error == TransferStorageError.Cancelled
                ) {
                    return cancelAfterRename(
                        knownFinal,
                        grant,
                        listOf(created.documentUri, finalUri),
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                }
                finalVerification.closeError?.let {
                    return reconcileAndSave(
                        current,
                        grant,
                        it,
                        listOf(created.documentUri, finalUri),
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                }
                if (finalVerification.outcome is VerifyResult.Failed) {
                    return reconcileAndSave(
                        current,
                        grant,
                        finalVerification.outcome.error,
                        listOf(created.documentUri, finalUri),
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                }
                if (finalVerification.outcome is VerifyResult.Mismatched) {
                    return reconcileAndSave(
                        current,
                        grant,
                        TransferStorageError.IntegrityMismatch("digest"),
                        listOf(created.documentUri, finalUri),
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                }
                if (finalVerification.outcome !is VerifyResult.Matched) {
                    return reconcileAndSave(
                        current,
                        grant,
                        TransferStorageError.StateConflict("verification_digest_missing"),
                        listOf(created.documentUri, finalUri),
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                }
                if (isCancelled()) {
                    return cancelAfterRename(
                        knownFinal,
                        grant,
                        listOf(created.documentUri, finalUri),
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                }
                exactChildError(current, grant, settledIdentity, record.expectedFinalName, "final")?.let {
                    return reconcileAndSave(
                        current,
                        grant,
                        it,
                        listOf(created.documentUri, finalUri),
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                }
                if (isCancelled()) {
                    return cancelAfterRename(
                        knownFinal,
                        grant,
                        listOf(created.documentUri, finalUri),
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                }
                current = current.copy(
                    finalUri = finalUri,
                    finalIdentity = settledIdentity,
                )
                if (isCancelled()) {
                    return cancelAfterRename(
                        current,
                        grant,
                        listOf(created.documentUri, finalUri),
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                }
                if (!saveCheckpoint(
                        current,
                        grant,
                        SafCommitCheckpointPhase.FINAL_VERIFIED,
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                ) {
                    return journalFailure(
                        current,
                        grant,
                        SafCommitCheckpointPhase.FINAL_VERIFIED,
                        afterMutation = true,
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                        knownUris = listOf(created.documentUri, finalUri),
                    )
                }
                if (isCancelled()) {
                    return cancelAfterRename(
                        current,
                        grant,
                        listOf(created.documentUri, finalUri),
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                }
                current = current.copy(
                    state = SafCommitState.PUBLISHED_OR_VISIBLE,
                    pendingCleanup = setOf(SafCleanupPending.STAGING),
                )
                if (!saveCheckpoint(
                        current,
                        grant,
                        SafCommitCheckpointPhase.PUBLISHED,
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                ) {
                    return journalFailure(
                        current,
                        grant,
                        SafCommitCheckpointPhase.PUBLICATION_INTENT,
                        afterMutation = true,
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                        knownUris = listOf(created.documentUri, finalUri),
                    )
                }
                if (isCancelled()) {
                    return cancelAfterRename(
                        current,
                        grant,
                        listOf(created.documentUri, finalUri),
                        returnedRenameIdentity = settledIdentity,
                        returnedRenameUri = renamed.documentUri,
                    )
                }
                return finish(current, finalUri, grant)
            }
        }
    }

    /**
     * Safely accepts providers that implement rename as a copy and leave the
     * exact, conspicuously named temporary behind. The returned final must be
     * the exact child at the final name and pass a fresh size/digest read before
     * the old identity can become a cleanup target.
     */
    private fun finishWithRetainedTemporary(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        stagedDigest: Sha256Digest,
        evidence: SafRenameEvidence,
        renameIdentity: SafRenameIdentity,
    ): SafCommitOutcome {
        val temporary = evidence.before
        val final = evidence.returned ?: return onRenameAmbiguous(
            record,
            grant,
            renameIdentity,
            evidence,
            evidence.before.documentUri,
        )
        val temporaryName = temporaryDocumentName(record.expectedFinalName, record.partialId)
        fun unresolved(error: TransferStorageError, uris: List<String>) = onRenameUnresolved(
            record.copy(temporaryIdentity = temporary, temporaryUri = temporary.documentUri),
            grant,
            RenameSettlement.Unknown(error, uris.distinct(), evidence),
            temporary.documentUri,
            final.documentUri,
        )

        val temporaryCandidate = when (val lookup = findRecoveryChild(record, grant, temporaryName)) {
            is SafLookup.Found -> lookup.document
            SafLookup.Absent -> return unresolved(
                TransferStorageError.ContainmentUnknown("retained_temporary_not_reachable"),
                listOf(temporary.documentUri, final.documentUri),
            )
            is SafLookup.Failed -> return unresolved(lookup.error, listOf(temporary.documentUri, final.documentUri))
        }
        val finalCandidate = when (val lookup = findRecoveryChild(record, grant, record.expectedFinalName)) {
            is SafLookup.Found -> lookup.document
            SafLookup.Absent -> return unresolved(
                TransferStorageError.ContainmentUnknown("renamed_final_not_reachable"),
                listOf(temporary.documentUri, final.documentUri),
            )
            is SafLookup.Failed -> return unresolved(lookup.error, listOf(temporary.documentUri, final.documentUri))
        }
        if (temporaryCandidate.documentUri != temporary.documentUri ||
            temporaryCandidate.documentId != temporary.documentId ||
            temporaryCandidate.displayName != temporaryName ||
            temporaryCandidate.isDirectory ||
            finalCandidate.documentUri != final.documentUri ||
            finalCandidate.documentId != final.documentId ||
            finalCandidate.displayName != record.expectedFinalName ||
            finalCandidate.isDirectory
        ) {
            return unresolved(
                TransferStorageError.ContainmentUnknown("rename_candidates_not_exact"),
                listOf(temporaryCandidate.documentUri, finalCandidate.documentUri),
            )
        }
        val renameHistory = if (record.renameHistory.any { it.phase == SafRenamePhase.FINAL_PROMOTION }) {
            record.renameHistory
        } else {
            record.renameHistory + evidence
        }
        if (!renameHistoryMatchesCommit(
                history = renameHistory,
                scope = SafRenameScope.fromRecord(record, grant),
                strategy = record.strategy,
                duplicatePolicy = record.duplicatePolicy,
                existingIdentity = record.existingIdentity,
            )
        ) {
            return unresolved(
                TransferStorageError.ContainmentUnknown("rename_history_malformed"),
                listOf(temporary.documentUri, final.documentUri),
            )
        }
        var current = record.copy(
            renameHistory = renameHistory,
            temporaryUri = temporary.documentUri,
            temporaryIdentity = temporary,
            verifiedDigest = stagedDigest,
        )
        if (!saveCheckpoint(
                current,
                grant,
                SafCommitCheckpointPhase.FINAL_RENAMED,
                returnedRenameIdentity = final,
                returnedRenameUri = final.documentUri,
            )
        ) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.FINAL_RENAMED,
                afterMutation = true,
                returnedRenameIdentity = final,
                returnedRenameUri = final.documentUri,
                knownUris = listOf(temporary.documentUri, final.documentUri),
            )
        }
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOf(temporary.documentUri, final.documentUri),
                returnedRenameIdentity = final,
                returnedRenameUri = final.documentUri,
            )
        }
        if (!saveCheckpoint(
                current,
                grant,
                SafCommitCheckpointPhase.FINAL_VERIFICATION_INTENT,
                returnedRenameIdentity = final,
                returnedRenameUri = final.documentUri,
            )
        ) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.FINAL_VERIFICATION_INTENT,
                afterMutation = true,
                returnedRenameIdentity = final,
                returnedRenameUri = final.documentUri,
                knownUris = listOf(temporary.documentUri, final.documentUri),
            )
        }
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOf(temporary.documentUri, final.documentUri),
                returnedRenameIdentity = final,
                returnedRenameUri = final.documentUri,
            )
        }
        val verification = verifyProviderCopy(final.documentUri, final.documentId, current, grant, stagedDigest)
        verification.closeError?.let { error ->
            return unresolved(error, listOf(temporary.documentUri, final.documentUri))
        }
        if (verification.outcome !is VerifyResult.Matched) {
            val error = when (val outcome = verification.outcome) {
                is VerifyResult.Failed -> outcome.error
                is VerifyResult.Mismatched -> TransferStorageError.IntegrityMismatch("retained_rename_final")
                else -> TransferStorageError.StateConflict("retained_rename_final_unverified")
            }
            if (error == TransferStorageError.Cancelled) {
                return cancelAfterRename(
                    current,
                    grant,
                    listOf(temporary.documentUri, final.documentUri),
                    returnedRenameIdentity = final,
                    returnedRenameUri = final.documentUri,
                )
            }
            return unresolved(error, listOf(temporary.documentUri, final.documentUri))
        }
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOf(temporary.documentUri, final.documentUri),
                returnedRenameIdentity = final,
                returnedRenameUri = final.documentUri,
            )
        }
        exactChildError(current, grant, temporary, temporaryName, "retained_temporary")?.let {
            return unresolved(it, listOf(temporary.documentUri, final.documentUri))
        }
        exactChildError(current, grant, final, record.expectedFinalName, "retained_final")?.let {
            return unresolved(it, listOf(temporary.documentUri, final.documentUri))
        }
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOf(temporary.documentUri, final.documentUri),
                returnedRenameIdentity = final,
                returnedRenameUri = final.documentUri,
            )
        }
        val cleanup = buildSet {
            add(SafCleanupPending.STAGING)
            if (temporary != final) add(SafCleanupPending.PROVIDER_TEMPORARY)
            if (record.backupIdentity != null) add(SafCleanupPending.BACKUP)
        }
        current = current.copy(
            state = SafCommitState.PUBLISHED_OR_VISIBLE,
            finalUri = final.documentUri,
            finalIdentity = final,
            copiedBytes = record.expectedSizeBytes,
            pendingCleanup = cleanup,
        )
        if (!saveCheckpoint(
                current,
                grant,
                SafCommitCheckpointPhase.FINAL_VERIFIED,
                returnedRenameIdentity = final,
                returnedRenameUri = final.documentUri,
            )
        ) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.FINAL_VERIFIED,
                afterMutation = true,
                returnedRenameIdentity = final,
                returnedRenameUri = final.documentUri,
                knownUris = listOf(temporary.documentUri, final.documentUri),
            )
        }
        if (isCancelled()) {
            return cancelAfterRename(
                current,
                grant,
                listOf(temporary.documentUri, final.documentUri),
                returnedRenameIdentity = final,
                returnedRenameUri = final.documentUri,
            )
        }
        if (!saveCheckpoint(
                current,
                grant,
                SafCommitCheckpointPhase.PUBLISHED,
                returnedRenameIdentity = final,
                returnedRenameUri = final.documentUri,
            )
        ) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.PUBLISHED,
                afterMutation = true,
                returnedRenameIdentity = final,
                returnedRenameUri = final.documentUri,
                knownUris = listOf(temporary.documentUri, final.documentUri),
            )
        }
        return finishAndRetryCleanup(current, final.documentUri, grant)
    }

    // -- Strategy B ---------------------------------------------------------

    /**
     * Writes straight to the final name.
     *
     * Not atomic and not hidden: from the moment the document is created the
     * user's folder contains a short file under the name they chose. That is
     * accepted only when [allowVisibleFinalCopy] is set, and the outcome is
     * reported so the caller can say so.
     */
    private fun commitVisibleFinalCopy(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        stagedDigest: Sha256Digest,
    ): SafCommitOutcome {
        if (!allowVisibleFinalCopy) {
            return fail(
                record,
                TransferStorageError.Unsupported("saf_visible_final_copy"),
            )
        }
        if (isCancelled()) return cancelBeforePublication(record, grant)

        val parentUri = record.uriForTree(record.parentDocumentId)
        authorizationError(record, grant, SafContainmentOperation.CREATE_DESTINATION)?.let {
            return route(record, it, created = null)
        }
        if (!saveCheckpoint(record, grant, SafCommitCheckpointPhase.VISIBLE_CREATE_INTENT)) {
            return journalFailure(
                record,
                grant,
                SafCommitCheckpointPhase.VISIBLE_CREATE_INTENT,
                afterMutation = false,
            )
        }
        if (isCancelled()) return cancelBeforePublication(record, grant)
        val created = when (
            val result = gateway.create(
                parentUri,
                "application/octet-stream",
                record.expectedFinalName,
            )
        ) {
            is SafCreate.Created -> result
            is SafCreate.Failed -> {
                if (!saveCheckpoint(
                        record,
                        grant,
                        SafCommitCheckpointPhase.VISIBLE_CREATE_INTENT,
                        failure = result.error,
                    )
                ) {
                    return journalFailure(
                        record,
                        grant,
                        SafCommitCheckpointPhase.VISIBLE_CREATE_INTENT,
                        afterMutation = true,
                    )
                }
                if (isCancelled()) {
                    return reconcileCancelledProviderMutation(record, grant, emptyList())
                }
                return onCreateFailed(record, result.error)
            }
        }

        var current = record.copy(
            state = SafCommitState.FINAL_CREATED,
            finalUri = created.documentUri,
            finalIdentity = SafStoredDocumentIdentity(created.documentUri, created.documentId),
        )
        if (isCancelled()) {
            return reconcileCancelledProviderMutation(current, grant, listOf(created.documentUri))
        }
        if (!saveCheckpoint(current, grant, SafCommitCheckpointPhase.VISIBLE_CREATED)) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.VISIBLE_CREATED,
                afterMutation = true,
                knownUris = listOf(created.documentUri),
            )
        }
        if (isCancelled()) return cancelBeforePublication(current, grant)
        val visibleIdentity = requireNotNull(current.finalIdentity)
        exactChildError(current, grant, visibleIdentity, record.expectedFinalName, "visible_final")?.let {
            return reconcileAndSave(current, grant, it, listOf(created.documentUri))
        }
        if (isCancelled()) return cancelBeforePublication(current, grant)

        current = current.copy(state = SafCommitState.COPY_STARTED)
        if (!saveCheckpoint(current, grant, SafCommitCheckpointPhase.COPY_STARTED)) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.COPY_STARTED,
                afterMutation = true,
                knownUris = listOf(created.documentUri),
            )
        }
        val phase = runCopy(current, created.documentUri, created.documentId, grant)
        val copiedBytes = when (val copy = phase.outcome) {
            is SafCopyOutcome.Copied -> copy.bytes
            is SafCopyOutcome.Failed -> copy.bytes
        }
        current = current.copy(copiedBytes = copiedBytes)
        phase.journalError?.let {
            return if (it == TransferStorageError.Cancelled) {
                reconcileCancelledProviderMutation(current, grant, listOf(created.documentUri))
            } else {
                journalFailure(
                    current,
                    grant,
                    SafCommitCheckpointPhase.COPY_STARTED,
                    afterMutation = true,
                    knownUris = listOf(created.documentUri),
                )
            }
        }
        phase.closeError?.let { return onCopyFailed(current, it, created.documentUri) }
        if (phase.outcome is SafCopyOutcome.Failed &&
            phase.outcome.error == TransferStorageError.Cancelled
        ) {
            return cancelBeforePublication(current, grant)
        }
        if (phase.outcome is SafCopyOutcome.Failed) {
            return onCopyFailed(current, phase.outcome.error, created.documentUri)
        }
        current = current.copy(state = SafCommitState.COPY_COMPLETED)

        if (phase.flush is FlushDurability.FlushFailed) {
            return onCopyFailed(current, phase.flush.error, created.documentUri)
        }
        // A copy the provider would not flush is not a copy that can be
        // verified, let alone committed.
        if (phase.flush is FlushDurability.FlushUnsupported) {
            return onCopyFailed(
                current,
                TransferStorageError.Unsupported("saf_flush"),
                created.documentUri,
            )
        }
        current = current.copy(state = SafCommitState.PROVIDER_FLUSH_COMPLETED)

        current = current.copy(state = SafCommitState.PROVIDER_VERIFICATION_STARTED)
        if (isCancelled()) return cancelBeforePublication(current, grant)
        if (!saveCheckpoint(current, grant, SafCommitCheckpointPhase.PROVIDER_VERIFICATION_INTENT)) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.PROVIDER_VERIFICATION_INTENT,
                afterMutation = true,
                knownUris = listOf(created.documentUri),
            )
        }
        if (isCancelled()) return cancelBeforePublication(current, grant)
        val verified = verifyProviderCopy(
            created.documentUri,
            created.documentId,
            record,
            grant,
            stagedDigest,
        )
        verified.closeError?.let { return onCopyFailed(current, it, created.documentUri) }
        if (verified.outcome is VerifyResult.Failed) {
            return if (verified.outcome.error == TransferStorageError.Cancelled) {
                cancelBeforePublication(current, grant)
            } else {
                onCopyFailed(current, verified.outcome.error, created.documentUri)
            }
        }
        if (verified.outcome is VerifyResult.Mismatched) {
            return onCopyFailed(
                current,
                TransferStorageError.IntegrityMismatch("digest"),
                created.documentUri,
            )
        }
        if (verified.outcome !is VerifyResult.Matched) {
            return onCopyFailed(
                current,
                TransferStorageError.StateConflict("verification_digest_missing"),
                created.documentUri,
            )
        }
        if (isCancelled()) return cancelBeforePublication(current, grant)
        exactChildError(current, grant, visibleIdentity, record.expectedFinalName, "visible_final")?.let {
            return reconcileAndSave(current, grant, it, listOf(created.documentUri))
        }
        if (isCancelled()) return cancelBeforePublication(current, grant)
        current = current.copy(state = SafCommitState.PROVIDER_VERIFIED)
        if (!saveCheckpoint(current, grant, SafCommitCheckpointPhase.PROVIDER_VERIFIED)) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.PROVIDER_VERIFIED,
                afterMutation = true,
                knownUris = listOf(created.documentUri),
            )
        }
        if (isCancelled()) return cancelBeforePublication(current, grant)

        current = current.copy(
            state = SafCommitState.PUBLISHED_OR_VISIBLE,
            pendingCleanup = setOf(SafCleanupPending.STAGING),
        )
        if (!saveCheckpoint(current, grant, SafCommitCheckpointPhase.PUBLISHED)) {
            return journalFailure(
                current,
                grant,
                SafCommitCheckpointPhase.PUBLICATION_INTENT,
                afterMutation = true,
                knownUris = listOf(created.documentUri),
            )
        }
        // Publication is now durably recorded after full verification. A late
        // cancellation may stop a retry, but it must not relabel a complete
        // visible file as an interrupted copy or delete it as one.
        return finish(current, created.documentUri, grant)
    }

    // -- Shared steps -------------------------------------------------------

    /**
     * Copies the staged bytes into [targetUri] and flushes, while the write
     * handle is still open.
     *
     * Both handles are owned here and closed here, exactly once, on every
     * outcome -- including the one where the target refuses to open.
     */
    private fun runCopy(
        record: SafCommitRecord,
        targetUri: String,
        targetDocumentId: String,
        grant: SafTreeGrant,
    ): SafCopyPhase {
        if (isCancelled()) {
            return unflushed(SafCopyOutcome.Failed(TransferStorageError.Cancelled, 0L))
        }
        val staged = when (val opened = staging.open(record.partialId)) {
            is SafOpen.Opened -> opened.handle as? SafReadHandle ?: run {
                val closeError = closeHandle(opened.handle, "read")
                return unflushed(
                    SafCopyOutcome.Failed(
                        TransferStorageError.ProviderFailure("document_provider"),
                        0L,
                    ),
                    closeError,
                )
            }

            is SafOpen.Refused -> return unflushed(SafCopyOutcome.Failed(opened.error, 0L))
        }

        if (isCancelled()) {
            val closeError = closeHandle(staged, "read")
            return unflushed(SafCopyOutcome.Failed(TransferStorageError.Cancelled, 0L), closeError)
        }
        authorizationError(
            record,
            grant,
            SafContainmentOperation.OPEN_WRITE,
            targetUri,
            targetDocumentId,
        )?.let {
            val closeError = closeHandle(staged, "read")
            return unflushed(SafCopyOutcome.Failed(it, 0L), closeError)
        }
        if (isCancelled()) {
            val closeError = closeHandle(staged, "read")
            return unflushed(SafCopyOutcome.Failed(TransferStorageError.Cancelled, 0L), closeError)
        }
        val target = when (val opened = gateway.openWrite(targetUri)) {
            is SafOpen.Opened -> opened.handle as? SafWriteHandle ?: run {
                val unexpectedClose = closeHandle(opened.handle, "write")
                val stagedClose = closeHandle(staged, "read")
                return unflushed(
                    SafCopyOutcome.Failed(
                        TransferStorageError.ProviderFailure("document_provider"),
                        0L,
                    ),
                    unexpectedClose ?: stagedClose,
                )
            }

            is SafOpen.Refused -> {
                val closeError = closeHandle(staged, "read")
                val journalError = if (saveCheckpoint(
                        record,
                        grant,
                        SafCommitCheckpointPhase.COPY_STARTED,
                        failure = opened.error,
                    )
                ) {
                    null
                } else {
                    TransferStorageError.StateConflict("journal_copy_result_failed")
                }
                return SafCopyPhase(
                    outcome = SafCopyOutcome.Failed(opened.error, 0L),
                    flush = FlushDurability.FlushUnsupported,
                    closeError = closeError,
                    journalError = journalError,
                )
            }
        }
        if (isCancelled()) {
            val closeError = closeBoth(target, staged)
            return SafCopyPhase(
                outcome = SafCopyOutcome.Failed(TransferStorageError.Cancelled, 0L),
                flush = FlushDurability.FlushUnsupported,
                closeError = closeError,
                journalError = TransferStorageError.Cancelled,
            )
        }

        var closeError: TransferStorageError? = null
        val phase = try {
            var outcome = SafCopyStreamer.copy(
                read = staged,
                write = target,
                totalBytes = record.expectedSizeBytes,
                buffer = ByteArray(copyBufferBytes),
                isCancelled = isCancelled,
            )
            if (outcome is SafCopyOutcome.Copied && isCancelled()) {
                outcome = SafCopyOutcome.Failed(TransferStorageError.Cancelled, outcome.bytes)
            }
            // Flushed before the handle is closed, or not at all. The intent is
            // durable before asking the provider to flush.
            var journalError: TransferStorageError? = null
            val copiedBytes = when (outcome) {
                is SafCopyOutcome.Copied -> outcome.bytes
                is SafCopyOutcome.Failed -> outcome.bytes
            }
            val copiedRecord = record.copy(copiedBytes = copiedBytes)
            val flush = if (outcome is SafCopyOutcome.Copied) {
                if (!saveCheckpoint(copiedRecord, grant, SafCommitCheckpointPhase.COPY_COMPLETED)) {
                    journalError = TransferStorageError.StateConflict("journal_copy_result_failed")
                    FlushDurability.FlushUnsupported
                } else if (!saveCheckpoint(copiedRecord, grant, SafCommitCheckpointPhase.FLUSH_INTENT)) {
                    journalError = TransferStorageError.StateConflict("journal_flush_intent_failed")
                    FlushDurability.FlushUnsupported
                } else if (isCancelled()) {
                    if (!saveCheckpoint(
                            copiedRecord,
                            grant,
                            SafCommitCheckpointPhase.CANCELLED,
                            failure = TransferStorageError.Cancelled,
                        )
                    ) {
                        journalError = TransferStorageError.StateConflict("journal_cancel_save_failed")
                    }
                    return SafCopyPhase(
                        SafCopyOutcome.Failed(TransferStorageError.Cancelled, copiedBytes),
                        FlushDurability.FlushUnsupported,
                        journalError = journalError,
                    )
                } else {
                    val result = target.flush()
                    if (isCancelled()) {
                        // Flush may have changed provider state. The caller records
                        // reconciliation rather than replacing this unresolved
                        // mutation with a terminal cancellation checkpoint.
                        return SafCopyPhase(
                            SafCopyOutcome.Failed(TransferStorageError.Cancelled, copiedBytes),
                            result,
                            journalError = TransferStorageError.Cancelled,
                        )
                    }
                    val flushPhase = if (result == FlushDurability.FlushAttemptedGuaranteeUnknown) {
                        SafCommitCheckpointPhase.FLUSH_COMPLETED
                    } else {
                        SafCommitCheckpointPhase.FLUSH_INTENT
                    }
                    val flushFailure = when (result) {
                        is FlushDurability.FlushFailed -> result.error
                        FlushDurability.FlushUnsupported -> TransferStorageError.Unsupported("saf_flush")
                        else -> null
                    }
                    if (!saveCheckpoint(
                            copiedRecord,
                            grant,
                            flushPhase,
                            failure = flushFailure,
                        )
                    ) {
                        journalError = TransferStorageError.StateConflict("journal_flush_result_failed")
                    }
                    result
                }
            } else {
                val copyFailure = (outcome as SafCopyOutcome.Failed).error
                if (!saveCheckpoint(
                        copiedRecord,
                        grant,
                        SafCommitCheckpointPhase.COPY_STARTED,
                        failure = copyFailure,
                    )
                ) {
                    journalError = TransferStorageError.StateConflict("journal_copy_result_failed")
                }
                FlushDurability.FlushUnsupported
            }
            SafCopyPhase(outcome, flush, journalError = journalError)
        } catch (e: Exception) {
            // A throw out of the copy is a failed copy, not an exception for the
            // caller to catch: the commit has a typed way to say this.
            SafCopyPhase(
                SafCopyOutcome.Failed(
                    StorageFailureClassifier.classifyOrProviderFailure(e, "copy"),
                    0L,
                ),
                FlushDurability.FlushUnsupported,
            )
        } finally {
            // Both owners are released here on every path. A failure to release
            // them is recorded rather than thrown, so that it cannot displace
            // the copy outcome -- but it is recorded, and the caller must not
            // advance past it.
            closeError = closeBoth(target, staged)
        }
        return phase.copy(closeError = closeError)
    }

    /**
     * Releases both owners and reports the first failure, or null.
     *
     * Both are always attempted: abandoning the second because the first failed
     * would turn one leak into two.
     */
    private fun closeBoth(
        target: SafWriteHandle,
        staged: SafReadHandle,
    ): TransferStorageError? {
        val targetFailure = runCatching { target.close() }.exceptionOrNull()
        val stagedFailure = runCatching { staged.close() }.exceptionOrNull()
        val targetError = targetFailure?.let {
            StorageFailureClassifier.classifyOrProviderFailure(it, "close", "write")
        }
        val stagedError = stagedFailure?.let {
            StorageFailureClassifier.classifyOrProviderFailure(it, "close", "read")
        }
        return targetError ?: stagedError
    }

    private fun closeHandle(handle: SafHandle, access: String): TransferStorageError? =
        runCatching { handle.close() }.exceptionOrNull()?.let {
            StorageFailureClassifier.classifyOrProviderFailure(it, "close", access)
        }

    /** A copy that never got as far as writing, so nothing was flushed. */
    private fun unflushed(
        outcome: SafCopyOutcome,
        closeError: TransferStorageError? = null,
    ): SafCopyPhase = SafCopyPhase(outcome, FlushDurability.FlushUnsupported, closeError)

    /**
     * The outcome of a verification pass, and whether the read owner let go.
     *
     * The close is reported rather than swallowed for the same reason the copy's
     * is: `runCatching { handle.close() }` hides both a leak and the fact that
     * the last thing to touch the descriptor did not succeed. A verification
     * whose reader would not close has not finished cleanly, and the commit must
     * not advance as though it had.
     */
    private data class VerifyPhase(
        val outcome: VerifyResult,
        val closeError: TransferStorageError? = null,
    )

    /**
     * The digest pass reads exactly the expected number of bytes. Probe one more
     * byte before accepting it so missing provider length metadata cannot make a
     * matching prefix stand in for an exact-length file.
     */
    private fun verifyExactLength(
        handle: SafReadHandle,
        verification: VerifyResult,
        buffer: ByteArray,
    ): VerifyResult {
        if (isCancelled()) return VerifyResult.Failed(TransferStorageError.Cancelled)
        if (verification !is VerifyResult.Matched &&
            verification !is VerifyResult.VerifiedWithoutExpected
        ) {
            return verification
        }

        var zeroProgressSteps = 0
        while (zeroProgressSteps < DestinationVerifier.MAX_ZERO_PROGRESS_STEPS) {
            when (val count = handle.read(buffer, 0, 1)) {
                -1 -> return verification
                0 -> zeroProgressSteps++
                1 -> return VerifyResult.Failed(TransferStorageError.IntegrityMismatch("length"))
                else -> return VerifyResult.Failed(
                    TransferStorageError.ProviderFailure(
                        provider = "verification_source",
                        diagnostic = "end probe returned $count for a request of 1",
                    ),
                )
            }
        }
        return VerifyResult.Failed(
            TransferStorageError.ZeroProgress(
                steps = zeroProgressSteps,
                bytesRemaining = 1L,
            ),
        )
    }

    /** Hashes the staging bytes in a fresh bounded pass and closes their sole owner. */
    private fun verifyStagedBytes(record: SafCommitRecord): VerifyPhase {
        if (isCancelled()) return VerifyPhase(VerifyResult.Failed(TransferStorageError.Cancelled))
        val handle = when (val opened = staging.open(record.partialId)) {
            is SafOpen.Opened -> opened.handle as? SafReadHandle ?: run {
                val closeError = closeHandle(opened.handle, "read")
                return VerifyPhase(
                    VerifyResult.Failed(TransferStorageError.ProviderFailure("staging")),
                    closeError,
                )
            }

            is SafOpen.Refused -> return VerifyPhase(VerifyResult.Failed(opened.error))
        }

        var outcome: VerifyResult? = null
        var closeError: TransferStorageError? = null
        try {
            val buffer = ByteArray(copyBufferBytes)
            val verification = DestinationVerifier.verify(
                source = handle.verificationSource(),
                totalBytes = record.expectedSizeBytes,
                expected = record.expectedDigest,
                buffer = buffer,
                newDigester = verificationDigesterFactory,
                isCancelled = isCancelled,
            )
            outcome = verifyExactLength(handle, verification, buffer)
        } catch (error: Exception) {
            outcome = VerifyResult.Failed(
                StorageFailureClassifier.classifyOrProviderFailure(error, "verify", "read"),
            )
        } finally {
            closeError = closeHandle(handle, "read")
        }
        return VerifyPhase(
            outcome ?: VerifyResult.Failed(TransferStorageError.ProviderFailure("verification")),
            closeError,
        )
    }

    /**
     * Verifies what the provider is actually holding.
     *
     * Length first, from a query, because that is the cheap check and it catches
     * a truncated copy before any bytes are read. Then a fresh read descriptor,
     * because a digest taken from the stream that was written to would only
     * prove what was handed over, not what landed.
     */
    private fun verifyProviderCopy(
        documentUri: String,
        expectedDocumentId: String,
        record: SafCommitRecord,
        grant: SafTreeGrant,
        stagedDigest: Sha256Digest,
    ): VerifyPhase {
        if (isCancelled()) return VerifyPhase(VerifyResult.Failed(TransferStorageError.Cancelled))
        authorizationError(
            record,
            grant,
            SafContainmentOperation.VERIFY,
            documentUri,
            expectedDocumentId,
        )?.let {
            return VerifyPhase(VerifyResult.Failed(it))
        }
        if (isCancelled()) return VerifyPhase(VerifyResult.Failed(TransferStorageError.Cancelled))
        when (val looked = gateway.query(documentUri)) {
            is SafLookup.Absent -> return VerifyPhase(
                VerifyResult.Failed(TransferStorageError.NotFound("staged_copy")),
            )

            is SafLookup.Failed -> return VerifyPhase(VerifyResult.Failed(looked.error))
            is SafLookup.Found -> {
                if (looked.document.documentId != expectedDocumentId) {
                    return VerifyPhase(
                        VerifyResult.Failed(
                            TransferStorageError.ContainmentUnknown("verify_identity_changed"),
                        ),
                    )
                }
                val size = looked.document.sizeBytes
                // A null size means the provider did not say, which is not the
                // same as agreeing; the digest pass below is what settles it.
                if (size != null && size != record.expectedSizeBytes) {
                    return VerifyPhase(
                        VerifyResult.Failed(TransferStorageError.IntegrityMismatch("length")),
                    )
                }
            }
        }

        if (isCancelled()) return VerifyPhase(VerifyResult.Failed(TransferStorageError.Cancelled))
        authorizationError(
            record,
            grant,
            SafContainmentOperation.VERIFY,
            documentUri,
            expectedDocumentId,
        )?.let {
            return VerifyPhase(VerifyResult.Failed(it))
        }
        if (isCancelled()) return VerifyPhase(VerifyResult.Failed(TransferStorageError.Cancelled))
        val handle = when (val opened = gateway.openRead(documentUri)) {
            is SafOpen.Opened -> opened.handle as? SafReadHandle ?: run {
                val closeError = closeHandle(opened.handle, "read")
                return VerifyPhase(
                    VerifyResult.Failed(
                        TransferStorageError.ProviderFailure("document_provider"),
                    ),
                    closeError,
                )
            }

            is SafOpen.Refused -> return VerifyPhase(VerifyResult.Failed(opened.error))
        }

        var outcome: VerifyResult? = null
        var closeError: TransferStorageError? = null
        try {
            val buffer = ByteArray(copyBufferBytes)
            val verification = DestinationVerifier.verify(
                source = handle.verificationSource(),
                totalBytes = record.expectedSizeBytes,
                expected = stagedDigest,
                buffer = buffer,
                newDigester = verificationDigesterFactory,
                isCancelled = isCancelled,
            )
            outcome = verifyExactLength(handle, verification, buffer)
        } catch (error: Exception) {
            outcome = VerifyResult.Failed(
                StorageFailureClassifier.classifyOrProviderFailure(error, "verify", "read"),
            )
        } finally {
            closeError = closeHandle(handle, "read")
        }
        return VerifyPhase(
            outcome ?: VerifyResult.Failed(TransferStorageError.ProviderFailure("verification")),
            closeError,
        )
    }

    /**
     * Releases staging and records the commit.
     *
     * Staging is removed only here, only after the document is under its final
     * name. A staging file that cannot be removed is not a failure of the
     * delivery — the file is where the user asked for it — so it is reported as
     * committed with cleanup still pending.
     */
    private fun checkpointFor(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        phase: SafCommitCheckpointPhase,
        returnedRenameIdentity: SafStoredDocumentIdentity? = null,
        returnedRenameUri: String? = null,
        failure: TransferStorageError? = null,
        unresolvedRenamePhase: SafRenamePhase? = null,
    ): SafCommitCheckpoint = SafCommitCheckpoint.fromRecord(
        record = record.copy(grantId = grant.grantId),
        grant = grant,
        phase = phase,
    ).copy(
        returnedRenameUri = returnedRenameUri,
        returnedRenameIdentity = returnedRenameIdentity,
        unresolvedRenamePhase = unresolvedRenamePhase,
        lastFailure = failure?.let {
            SafCommitCheckpointFailure(
                categoryId = it.category.id,
                code = when (it) {
                    is TransferStorageError.StateConflict -> it.reason.take(64).replace(Regex("[^a-z0-9_]"), "_")
                    else -> it.category.id
                },
            )
        },
    )

    private fun saveCheckpoint(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        phase: SafCommitCheckpointPhase,
        returnedRenameIdentity: SafStoredDocumentIdentity? = null,
        returnedRenameUri: String? = null,
        failure: TransferStorageError? = null,
        unresolvedRenamePhase: SafRenamePhase? = null,
    ): Boolean = runCatching {
        journal.save(
            checkpointFor(
                record,
                grant,
                phase,
                returnedRenameIdentity,
                returnedRenameUri,
                failure,
                unresolvedRenamePhase,
            ),
        )
    }.getOrDefault(false)

    private fun unresolvedRenamePhaseAfterSaveFailure(
        record: SafCommitRecord,
        phase: SafCommitCheckpointPhase,
    ): SafRenamePhase? = when (phase) {
        SafCommitCheckpointPhase.BACKUP_RENAMED ->
            SafRenamePhase.BACKUP_RENAME.takeIf { record.renameHistory.none { item -> item.phase == it } }

        SafCommitCheckpointPhase.FINAL_RENAMED ->
            SafRenamePhase.FINAL_PROMOTION.takeIf { record.renameHistory.none { item -> item.phase == it } }

        SafCommitCheckpointPhase.RENAME_RECONCILIATION_INTENT -> when {
            record.existingIdentity != null && record.backupIdentity == null &&
                record.renameHistory.none { it.phase == SafRenamePhase.BACKUP_RENAME } -> SafRenamePhase.BACKUP_RENAME

            record.renameHistory.none { it.phase == SafRenamePhase.FINAL_PROMOTION } -> SafRenamePhase.FINAL_PROMOTION
            else -> null
        }

        else -> null
    }

    private fun journalFailure(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        phase: SafCommitCheckpointPhase,
        afterMutation: Boolean,
        returnedRenameIdentity: SafStoredDocumentIdentity? = null,
        returnedRenameUri: String? = null,
        knownUris: List<String> = record.knownDocumentIdentities.map { it.documentUri },
    ): SafCommitOutcome {
        val error = TransferStorageError.StateConflict(
            if (afterMutation) "journal_result_save_failed" else "journal_intent_save_failed",
        )
        val checkpoint = runCatching {
            checkpointFor(
                record.copy(state = SafCommitState.RECONCILIATION_REQUIRED),
                grant,
                SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
                returnedRenameIdentity = returnedRenameIdentity,
                returnedRenameUri = returnedRenameUri,
                failure = error,
                unresolvedRenamePhase = if (afterMutation) {
                    unresolvedRenamePhaseAfterSaveFailure(record, phase)
                } else {
                    null
                },
            )
        }.getOrNull()
        if (afterMutation && checkpoint != null) {
            runCatching { journal.save(checkpoint) }
        }
        return if (afterMutation) {
            SafCommitOutcome.ReconciliationRequired(
                record = record.copy(state = SafCommitState.RECONCILIATION_REQUIRED),
                error = error,
                knownUris = (knownUris + returnedRenameIdentity?.documentUri + returnedRenameUri)
                    .filterNotNull()
                    .distinct(),
                checkpoint = checkpoint,
            )
        } else {
            SafCommitOutcome.Failed(
                record = record.copy(state = SafCommitState.COMMIT_FAILED),
                error = error,
                checkpoint = checkpoint,
            )
        }
    }

    private fun finish(
        record: SafCommitRecord,
        finalUri: String,
        grant: SafTreeGrant,
        pendingCleanup: Set<SafCleanupPending> = emptySet(),
    ): SafCommitOutcome {
        val finalIdentity = record.finalIdentity
            ?: record.temporaryIdentity?.takeIf { it.documentUri == finalUri }
            ?: return reconcile(record, TransferStorageError.ContainmentUnknown("final_identity_missing"))
        val possibleProviderTemporary = record.temporaryIdentity?.takeIf { it != finalIdentity }
        val pendingBefore = record.pendingCleanup + pendingCleanup + SafCleanupPending.STAGING +
            if (possibleProviderTemporary == null) emptySet() else setOf(SafCleanupPending.PROVIDER_TEMPORARY)
        // Every call site enters after an exact final-identity size/digest pass (or
        // recovery has just repeated that pass). `finish` releases only private
        // staging; provider-temporary and backup deletion is deferred to
        // retryPendingCleanup, which revalidates the final immediately before delete.
        val beforeDelete = record.copy(
            state = stateForCleanup(pendingBefore),
            finalUri = finalUri,
            finalIdentity = finalIdentity,
            pendingCleanup = pendingBefore,
        )
        if (!saveCheckpoint(beforeDelete, grant, SafCommitCheckpointPhase.PUBLISHED)) {
            return journalFailure(
                beforeDelete,
                grant,
                SafCommitCheckpointPhase.PUBLISHED,
                afterMutation = true,
                knownUris = listOf(finalUri),
            )
        }
        if (isCancelled()) {
            return reconcileKnown(beforeDelete, TransferStorageError.Cancelled, listOf(finalUri))
        }
        if (!beforeDelete.stagingReleased && SafCleanupPending.STAGING in pendingBefore &&
            !saveCheckpoint(beforeDelete, grant, SafCommitCheckpointPhase.STAGING_DELETE_INTENT)
        ) {
            return journalFailure(
                beforeDelete,
                grant,
                SafCommitCheckpointPhase.STAGING_DELETE_INTENT,
                afterMutation = true,
                knownUris = listOf(finalUri),
            )
        }
        if (!beforeDelete.stagingReleased && SafCleanupPending.STAGING in pendingBefore) {
            if (isCancelled()) {
                return reconcileAndSave(beforeDelete, grant, TransferStorageError.Cancelled, listOf(finalUri))
            }
        }
        val stagingObservation = if (beforeDelete.stagingReleased) {
            StagingCleanupObservation(confirmedAbsent = true)
        } else {
            deleteStagingAndObserve(beforeDelete.partialId)
        }
        val removed = stagingObservation.confirmedAbsent
        val outstanding = pendingBefore.toMutableSet().apply {
            if (removed) remove(SafCleanupPending.STAGING) else add(SafCleanupPending.STAGING)
        }.toSet()
        val completed = beforeDelete.copy(
            state = stateForCleanup(outstanding),
            copiedBytes = beforeDelete.expectedSizeBytes,
            pendingCleanup = outstanding,
            stagingReleased = removed,
        )
        if (!saveCheckpoint(
                completed,
                grant,
                SafCommitCheckpointPhase.STAGING_DELETE_OBSERVED,
                failure = stagingObservation.error,
            )
        ) {
            return journalFailure(
                completed,
                grant,
                SafCommitCheckpointPhase.STAGING_DELETE_OBSERVED,
                afterMutation = true,
                knownUris = listOf(finalUri),
            )
        }
        val phase = if (outstanding.isEmpty()) {
            SafCommitCheckpointPhase.COMMITTED
        } else {
            SafCommitCheckpointPhase.STAGING_DELETE_OBSERVED
        }
        if (phase == SafCommitCheckpointPhase.COMMITTED &&
            !saveCheckpoint(completed, grant, SafCommitCheckpointPhase.COMMITTED)
        ) {
            return journalFailure(
                completed,
                grant,
                SafCommitCheckpointPhase.COMMITTED,
                afterMutation = true,
                knownUris = listOf(finalUri),
            )
        }
        val checkpoint = runCatching { checkpointFor(completed, grant, phase) }.getOrNull()
        return SafCommitOutcome.Committed(
            record = completed,
            finalUri = finalUri,
            pendingCleanup = outstanding,
            checkpoint = checkpoint,
        )
    }

    private fun finishAndRetryCleanup(
        record: SafCommitRecord,
        finalUri: String,
        grant: SafTreeGrant,
    ): SafCommitOutcome = when (val outcome = finish(record, finalUri, grant)) {
        is SafCommitOutcome.Committed -> if (outcome.pendingCleanup.isEmpty()) {
            outcome
        } else {
            retryPendingCleanup(outcome.record, grant)
        }

        else -> outcome
    }

    private data class StagingCleanupObservation(
        val confirmedAbsent: Boolean,
        val error: TransferStorageError? = null,
    )

    /** Delete staging, then observe its row rather than treating the request result as proof. */
    private fun deleteStagingAndObserve(identity: PartialIdentity): StagingCleanupObservation {
        val deletionFailure = runCatching { staging.delete(identity) }.exceptionOrNull()
        val length = runCatching { staging.length(identity) }
        if (length.isFailure) {
            return StagingCleanupObservation(
                confirmedAbsent = false,
                error = StorageFailureClassifier.classifyOrProviderFailure(
                    requireNotNull(length.exceptionOrNull()),
                    "staging_delete",
                ),
            )
        }
        if (length.getOrNull() == null) return StagingCleanupObservation(confirmedAbsent = true)
        return StagingCleanupObservation(
            confirmedAbsent = false,
            error = deletionFailure?.let {
                StorageFailureClassifier.classifyOrProviderFailure(it, "staging_delete")
            },
        )
    }

    private fun cleanupGrantContextMatches(
        record: SafCommitRecord,
        grant: SafTreeGrant,
    ): Boolean =
        record.grantId != null &&
            record.grantId == grant.grantId &&
            grant.treeUri.toString() == record.treeUri &&
            grant.rootDocumentId == record.rootDocumentId &&
            grant.authority == grant.treeUri.authority

    private fun cleanupCheckpointAuthorizesFinal(
        checkpoint: SafCommitCheckpoint,
        record: SafCommitRecord,
    ): Boolean {
        val final = record.finalIdentity ?: return false
        val finalContextMatches = when (record.strategy) {
            SafCommitStrategy.VISIBLE_FINAL_COPY ->
                record.renameHistory.none { it.phase == SafRenamePhase.FINAL_PROMOTION }

            SafCommitStrategy.TEMP_THEN_RENAME ->
                record.renameHistory.lastOrNull { it.phase == SafRenamePhase.FINAL_PROMOTION }
                    ?.knownIdentities?.contains(final) == true
        }
        val publicationPhase = checkpoint.phase in setOf(
            SafCommitCheckpointPhase.FINAL_VERIFIED,
            SafCommitCheckpointPhase.PUBLICATION_INTENT,
            SafCommitCheckpointPhase.PUBLISHED,
            SafCommitCheckpointPhase.BACKUP_DELETE_INTENT,
            SafCommitCheckpointPhase.BACKUP_DELETE_OBSERVED,
            SafCommitCheckpointPhase.PROVIDER_TEMPORARY_DELETE_INTENT,
            SafCommitCheckpointPhase.PROVIDER_TEMPORARY_DELETE_OBSERVED,
            SafCommitCheckpointPhase.STAGING_DELETE_INTENT,
            SafCommitCheckpointPhase.STAGING_DELETE_OBSERVED,
            SafCommitCheckpointPhase.COMMITTED,
        ) || (
            checkpoint.phase == SafCommitCheckpointPhase.RECONCILIATION_REQUIRED &&
                checkpointAlreadyPublished(checkpoint)
            )
        return publicationPhase &&
            finalContextMatches &&
            checkpoint.finalIdentity == final &&
            checkpoint.copiedBytes == checkpoint.expectedSizeBytes &&
            checkpoint.verifiedDigest != null &&
            (checkpoint.expectedDigest == null || checkpoint.expectedDigest == checkpoint.verifiedDigest) &&
            checkpoint.verifiedDigest == record.verifiedDigest
    }

    private fun cleanupStateAuthorizes(record: SafCommitRecord): Boolean = when (record.state) {
        SafCommitState.BACKUP_CLEANUP_PENDING ->
            record.finalIdentity != null && SafCleanupPending.BACKUP in record.pendingCleanup

        SafCommitState.PROVIDER_TEMPORARY_CLEANUP_PENDING ->
            record.finalIdentity != null && SafCleanupPending.PROVIDER_TEMPORARY in record.pendingCleanup

        SafCommitState.STAGING_CLEANUP_PENDING ->
            record.finalIdentity != null && SafCleanupPending.STAGING in record.pendingCleanup

        SafCommitState.RECONCILIATION_REQUIRED ->
            record.finalIdentity != null && record.pendingCleanup.isNotEmpty()

        SafCommitState.COMMITTED ->
            record.finalIdentity != null && record.pendingCleanup.isEmpty() && record.stagingReleased

        else -> false
    }

    private fun stateForCleanup(pending: Set<SafCleanupPending>): SafCommitState = when {
        SafCleanupPending.BACKUP in pending -> SafCommitState.BACKUP_CLEANUP_PENDING
        SafCleanupPending.PROVIDER_TEMPORARY in pending -> SafCommitState.PROVIDER_TEMPORARY_CLEANUP_PENDING
        SafCleanupPending.STAGING in pending -> SafCommitState.STAGING_CLEANUP_PENDING
        else -> SafCommitState.COMMITTED
    }

    // -- Rename reconciliation ----------------------------------------------

    private sealed interface RenameSettlement {
        data class Settled(
            val identity: SafRenameIdentity,
            val evidence: SafRenameEvidence,
        ) : RenameSettlement

        /** The provider would not say what exists, so nothing is decided. */
        data class Unknown(
            val error: TransferStorageError,
            val knownUris: List<String>,
            val evidence: SafRenameEvidence,
        ) : RenameSettlement
    }

    private fun observedRenameEvidence(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        phase: SafRenamePhase,
        before: SafStoredDocumentIdentity,
        returned: SafStoredDocumentIdentity,
        reconciliation: SafRenameReconciliation? = null,
    ): SafRenameEvidence = SafRenameEvidence(
        before = before,
        returned = returned,
        reconciliation = reconciliation ?: if (before == returned) {
            SafRenameReconciliation.RESOLVED_TO_ORIGINAL
        } else {
            SafRenameReconciliation.RESOLVED_TO_RETURNED
        },
        scope = SafRenameScope.fromRecord(record, grant),
        phase = phase,
        sequence = record.renameHistory.size,
    )

    private fun renameHistoryAppendError(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        phase: SafRenamePhase,
    ): TransferStorageError? {
        if (!renameHistoryMatchesCommit(
                history = record.renameHistory,
                scope = SafRenameScope.fromRecord(record, grant),
                strategy = record.strategy,
                duplicatePolicy = record.duplicatePolicy,
                existingIdentity = record.existingIdentity,
            )
        ) {
            return TransferStorageError.ContainmentUnknown("rename_history_malformed")
        }
        if (record.renameHistory.size >= SafRenameHistoryPolicy.MAX_ENTRIES) {
            return TransferStorageError.StateConflict("rename_history_limit")
        }
        if (record.renameHistory.any { it.phase == phase }) {
            return TransferStorageError.StateConflict("rename_history_phase_already_used")
        }
        return null
    }

    /**
     * Works out what a rename left behind, from what the provider returned and
     * what still resolves.
     *
     * Both queries happen here rather than in [SafRenameIdentityResolver],
     * which is pure and takes the answers as arguments. The split is the point:
     * the decision is testable without a provider, while the asking is not
     * confused with the deciding.
     */
    private fun settleRename(
        beforeUri: String,
        beforeId: String,
        returnedUri: String?,
        phase: SafRenamePhase,
        record: SafCommitRecord,
        grant: SafTreeGrant,
    ): RenameSettlement {
        val known = listOfNotNull(beforeUri, returnedUri).distinct()
        val beforeIdentity = SafStoredDocumentIdentity(beforeUri, beforeId)
        val returnedIdentity = returnedUri?.let { uri ->
            SafContainment.documentIdOf(uri.toUri())
                ?.takeIf { it.isNotBlank() }
                ?.let { id -> SafStoredDocumentIdentity(uri, id) }
        }
        val scope = SafRenameScope.fromRecord(record, grant)
        val sequence = record.renameHistory.size
        val attemptEvidence = SafRenameEvidence(
            before = beforeIdentity,
            returned = returnedIdentity,
            scope = scope,
            phase = phase,
            sequence = sequence,
        )
        fun unknown(error: TransferStorageError) = RenameSettlement.Unknown(
            error = error,
            knownUris = known,
            evidence = attemptEvidence,
        )

        // An identity under a different authority is not something this rename
        // produced. It is not adopted in either direction.
        if (returnedUri != null &&
            beforeUri.toUri().authority != returnedUri.toUri().authority
        ) {
            return unknown(TransferStorageError.StateConflict("rename_authority_changed"))
        }

        if (isCancelled()) return unknown(TransferStorageError.Cancelled)
        authorizationError(
            record,
            grant,
            SafContainmentOperation.RECONCILE,
            beforeUri,
            beforeId,
        )?.let { return unknown(it) }
        if (isCancelled()) return unknown(TransferStorageError.Cancelled)

        val originalResolves = when (val looked = gateway.query(beforeUri)) {
            is SafLookup.Found -> {
                if (looked.document.documentId != beforeId) {
                    return unknown(
                        TransferStorageError.ContainmentUnknown("rename_before_identity_changed"),
                    )
                }
                true
            }

            SafLookup.Absent -> false
            is SafLookup.Failed -> return unknown(looked.error)
        }
        if (isCancelled()) return unknown(TransferStorageError.Cancelled)

        val returnedResolves = when {
            returnedUri == null -> false
            returnedUri == beforeUri -> originalResolves
            else -> {
                val returnedId = returnedIdentity?.documentId
                    ?: return unknown(
                        TransferStorageError.ContainmentUnknown("rename_returned_identity_missing"),
                    )
                if (isCancelled()) return unknown(TransferStorageError.Cancelled)
                authorizationError(
                    record,
                    grant,
                    SafContainmentOperation.RECONCILE,
                    returnedUri,
                    returnedId,
                )?.let { return unknown(it) }
                if (isCancelled()) return unknown(TransferStorageError.Cancelled)
                when (val looked = gateway.query(returnedUri)) {
                    is SafLookup.Found -> {
                        if (looked.document.documentId != returnedId) {
                            return unknown(
                                TransferStorageError.ContainmentUnknown("rename_returned_identity_changed"),
                            )
                        }
                        true
                    }

                    SafLookup.Absent -> false
                    is SafLookup.Failed -> return unknown(looked.error)
                }
            }
        }
        if (isCancelled()) return unknown(TransferStorageError.Cancelled)

        val identity = SafRenameIdentityResolver.resolve(
            beforeDocumentUri = beforeUri,
            beforeDocumentId = beforeId,
            returnedDocumentUri = returnedUri,
            returnedDocumentId = returnedIdentity?.documentId,
            originalStillResolves = originalResolves,
            returnedResolves = returnedResolves,
        )
        val evidence = identity.evidence.copy(
            scope = scope,
            phase = phase,
            sequence = sequence,
        )
        return RenameSettlement.Settled(identity, evidence)
    }

    private fun onRenameUnresolved(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        settled: RenameSettlement.Unknown,
        temporaryUri: String,
        returnedRenameUri: String?,
    ): SafCommitOutcome.ReconciliationRequired {
        val unresolved = record.copy(
            state = SafCommitState.RECONCILIATION_REQUIRED,
            temporaryUri = temporaryUri,
            renameHistory = record.renameHistory + settled.evidence,
        )
        val checkpoint = checkpointFor(
            unresolved,
            grant,
            SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
            returnedRenameIdentity = settled.evidence.returned,
            returnedRenameUri = returnedRenameUri,
            failure = settled.error,
        )
        saveCheckpoint(
            unresolved,
            grant,
            SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
            returnedRenameIdentity = settled.evidence.returned,
            returnedRenameUri = returnedRenameUri,
            failure = settled.error,
        )
        return SafCommitOutcome.ReconciliationRequired(
            record = unresolved,
            error = settled.error,
            knownUris = settled.knownUris,
            checkpoint = checkpoint,
        )
    }

    private fun onRenameAmbiguous(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        identity: SafRenameIdentity,
        evidence: SafRenameEvidence,
        temporaryUri: String,
    ): SafCommitOutcome.ReconciliationRequired {
        val error = TransferStorageError.StateConflict("rename_ambiguous")
        val unresolved = record.copy(
            state = SafCommitState.RECONCILIATION_REQUIRED,
            temporaryUri = temporaryUri,
            renameHistory = record.renameHistory + evidence,
        )
        val checkpoint = checkpointFor(
            unresolved,
            grant,
            SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
            returnedRenameIdentity = evidence.returned,
            returnedRenameUri = identity.returnedDocumentUri,
            failure = error,
        )
        saveCheckpoint(
            unresolved,
            grant,
            SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
            returnedRenameIdentity = evidence.returned,
            returnedRenameUri = identity.returnedDocumentUri,
            failure = error,
        )
        return SafCommitOutcome.ReconciliationRequired(
            record = unresolved,
            error = error,
            knownUris = identity.knownUris,
            checkpoint = checkpoint,
        )
    }

    // -- Failure routing ----------------------------------------------------

    private fun onCreateFailed(record: SafCommitRecord, error: TransferStorageError): SafCommitOutcome =
        route(record, error, created = null)

    private fun onCopyFailed(
        record: SafCommitRecord,
        error: TransferStorageError,
        temporaryUri: String,
    ): SafCommitOutcome = route(record, error, created = temporaryUri)

    private fun onRenameFailed(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        error: TransferStorageError,
        temporaryUri: String,
    ): SafCommitOutcome.ReconciliationRequired = renameMutationFailed(
        record,
        grant,
        SafRenamePhase.FINAL_PROMOTION,
        error,
        listOf(temporaryUri),
    )

    /**
     * Decides whether a failure is a typed failure or an unresolvable unknown.
     *
     * A revoked grant, a missing document and an unreadable provider state get
     * three different answers, because they need three different recoveries.
     * Collapsing them into one "it failed" is what loses data.
     */
    private fun route(
        record: SafCommitRecord,
        error: TransferStorageError,
        created: String?,
    ): SafCommitOutcome {
        // A revoked grant and an unreadable provider state both leave the
        // provider's contents unknown, so they keep the temporary identity and
        // ask for reconciliation. Everything else is a typed failure, retryable
        // from the last durable state.
        val held = record.copy(
            state = if (error is TransferStorageError.PermissionRevoked ||
                error is TransferStorageError.ProviderFailure ||
                error is TransferStorageError.ContainmentUnknown
            ) {
                SafCommitState.RECONCILIATION_REQUIRED
            } else {
                SafCommitState.COMMIT_FAILED
            },
            temporaryUri = created ?: record.temporaryUri,
        )
        return when (error) {
            is TransferStorageError.PermissionRevoked -> SafCommitOutcome.ReconciliationRequired(held, error)
            is TransferStorageError.ProviderFailure -> SafCommitOutcome.ReconciliationRequired(held, error)
            is TransferStorageError.ContainmentUnknown -> SafCommitOutcome.ReconciliationRequired(held, error)
            else -> SafCommitOutcome.Failed(held, error)
        }
    }

    /**
     * Replaces the document at [existingUri], or refuses to.
     *
     * The refusal matters as much as the replacement: under the visible-copy
     * fallback there is no rename with which to move the existing document
     * aside, so an overwrite there would mean writing new bytes under the final
     * name while the old ones are still there. That is not an overwrite, it is a
     * collision, and it is refused rather than attempted.
     */
    /**
     * A provider mutation completed, but cancellation arrived before its ordinary
     * result checkpoint. Persist exact returned identities in reconciliation;
     * never call that state an ordinary cancellation with no provider evidence.
     */
    private fun reconcileCancelledProviderMutation(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        knownUris: List<String>,
    ): SafCommitOutcome.ReconciliationRequired {
        val unresolved = record.copy(state = SafCommitState.RECONCILIATION_REQUIRED)
        val checkpoint = checkpointFor(
            unresolved,
            grant,
            SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
            failure = TransferStorageError.Cancelled,
        )
        val saved = runCatching { journal.save(checkpoint) }.getOrDefault(false)
        return SafCommitOutcome.ReconciliationRequired(
            record = unresolved,
            error = if (saved) TransferStorageError.Cancelled
            else TransferStorageError.StateConflict("journal_cancel_after_mutation_failed"),
            knownUris = knownUris.distinct(),
            checkpoint = checkpoint,
        )
    }

    /** Records a user cancellation without releasing staging or restarting work. */
    private fun cancelBeforePublication(
        record: SafCommitRecord,
        grant: SafTreeGrant,
    ): SafCommitOutcome {
        val cancelled = record.copy(state = SafCommitState.COMMIT_FAILED)
        val checkpoint = checkpointFor(
            cancelled,
            grant,
            SafCommitCheckpointPhase.CANCELLED,
            failure = TransferStorageError.Cancelled,
        )
        val saved = runCatching { journal.save(checkpoint) }.getOrDefault(false)
        return if (saved) {
            SafCommitOutcome.Failed(cancelled, TransferStorageError.Cancelled, checkpoint)
        } else {
            SafCommitOutcome.ReconciliationRequired(
                record = cancelled.copy(state = SafCommitState.RECONCILIATION_REQUIRED),
                error = TransferStorageError.StateConflict("journal_cancel_save_failed"),
                knownUris = cancelled.knownDocumentIdentities.map { it.documentUri },
                checkpoint = checkpoint,
            )
        }
    }

    /** A rename already ran; retain all identities and stop before verification/publication. */
    private fun cancelAfterRename(
        record: SafCommitRecord,
        grant: SafTreeGrant,
        knownUris: List<String>,
        returnedRenameIdentity: SafStoredDocumentIdentity? = null,
        returnedRenameUri: String? = null,
        unresolvedRenamePhase: SafRenamePhase? = null,
    ): SafCommitOutcome.ReconciliationRequired {
        val unresolved = record.copy(state = SafCommitState.RECONCILIATION_REQUIRED)
        val checkpoint = checkpointFor(
            unresolved,
            grant,
            SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
            returnedRenameIdentity = returnedRenameIdentity,
            returnedRenameUri = returnedRenameUri,
            failure = TransferStorageError.Cancelled,
            unresolvedRenamePhase = unresolvedRenamePhase?.takeIf { phase ->
                unresolved.renameHistory.none { it.phase == phase }
            },
        )
        val saved = runCatching { journal.save(checkpoint) }.getOrDefault(false)
        return SafCommitOutcome.ReconciliationRequired(
            record = unresolved,
            error = if (saved) TransferStorageError.Cancelled
            else TransferStorageError.StateConflict("journal_cancel_save_failed"),
            knownUris = knownUris.distinct(),
            checkpoint = checkpoint,
        )
    }

    private fun commitOverwrite(
        record: SafCommitRecord,
        existingUri: String,
        existingDocumentId: String,
        grant: SafTreeGrant,
        stagedDigest: Sha256Digest,
    ): SafCommitOutcome {
        val resolved = record.copy(
            state = SafCommitState.DESTINATION_RESOLVED,
            existingIdentity = SafStoredDocumentIdentity(existingUri, existingDocumentId),
        )
        return when (record.strategy) {
            SafCommitStrategy.TEMP_THEN_RENAME ->
                commitSafeOverwrite(resolved, existingUri, existingDocumentId, grant, stagedDigest)

            SafCommitStrategy.VISIBLE_FINAL_COPY -> SafCommitOutcome.SafeOverwriteUnsupported(
                record = resolved,
                existingUri = existingUri,
                reason = "visible_final_copy_cannot_replace",
            )
        }
    }

    private fun fail(record: SafCommitRecord, error: TransferStorageError): SafCommitOutcome =
        SafCommitOutcome.Failed(record.copy(state = SafCommitState.COMMIT_FAILED), error)

    private fun reconcile(record: SafCommitRecord, error: TransferStorageError): SafCommitOutcome =
        SafCommitOutcome.ReconciliationRequired(
            record.copy(state = SafCommitState.RECONCILIATION_REQUIRED),
            error,
        )

    // -- Duplicate policy ---------------------------------------------------

    private sealed interface SafNameDecision {
        data class Use(val displayName: String) : SafNameDecision
        data class Skip(val existingUri: String) : SafNameDecision
        data class Ask(val existingUri: String) : SafNameDecision

        /**
         * Replace [existingUri], but only by the recoverable route.
         *
         * Carries the existing identity rather than a name: the overwrite has to
         * act on the document the lookup found, and by the time it is acted on
         * another lookup has to confirm it is still the same one.
         */
        data class Overwrite(
            val existingUri: String,
            val existingDocumentId: String,
        ) : SafNameDecision

        data class Unavailable(val error: TransferStorageError) : SafNameDecision
        data class Refused(val error: TransferStorageError) : SafNameDecision
    }

    private fun applyDuplicatePolicy(
        record: SafCommitRecord,
        existing: SafLookup,
        grant: SafTreeGrant,
    ): SafNameDecision = when (existing) {
        is SafLookup.Failed -> SafNameDecision.Unavailable(existing.error)

        SafLookup.Absent -> SafNameDecision.Use(record.expectedFinalName)

        is SafLookup.Found -> when (record.duplicatePolicy) {
            DuplicatePolicy.RENAME -> {
                val choice = SafDuplicateNaming.firstFree(record.expectedFinalName) { name ->
                    val authError = authorizationError(record, grant, SafContainmentOperation.RECONCILE)
                    if (authError != null) {
                        SafLookup.Failed(authError)
                    } else {
                        gateway.findChild(record.uriForTree(record.parentDocumentId), name)
                    }
                }
                when (choice) {
                    is SafNameChoice.Chosen -> SafNameDecision.Use(choice.displayName)
                    is SafNameChoice.Exhausted -> SafNameDecision.Refused(
                        TransferStorageError.StateConflict("duplicate_name_exhausted"),
                    )
                    is SafNameChoice.Unavailable -> SafNameDecision.Unavailable(choice.error)
                }
            }

            // The old destination is never deleted first. The replacement is
            // created, copied and verified under a temporary identity, and only
            // then is the existing document moved aside. Doing it the other way
            // round means a crash mid-copy destroys the file the user had.
            DuplicatePolicy.OVERWRITE -> SafNameDecision.Overwrite(
                existingUri = existing.document.documentUri,
                existingDocumentId = existing.document.documentId,
            )

            DuplicatePolicy.SKIP -> SafNameDecision.Skip(existing.document.documentUri)

            DuplicatePolicy.ASK -> SafNameDecision.Ask(existing.document.documentUri)
        }
    }
}

/** The document uri for a document id inside the record's tree. */
private fun SafCommitRecord.uriForTree(documentId: String): String =
    SafPaths.uriFor(treeUri, documentId)
        ?: throw IllegalArgumentException("document id is not addressable in this tree")
