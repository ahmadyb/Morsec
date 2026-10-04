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
        descriptor?.fileDescriptor?.sync()
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
        val dot = displayName.lastIndexOf('.')
        return if (dot > 0) {
            val base = displayName.substring(0, dot)
            val extension = displayName.substring(dot)
            "$base ($index)$extension"
        } else {
            "$displayName ($index)"
        }
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
public class SafCommitCoordinator(
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
    /**
     * Whether product policy permits the visible-copy fallback.
     *
     * False means a provider without rename support gets a typed refusal rather
     * than a silent downgrade to writing under the user's chosen name.
     */
    private val allowVisibleFinalCopy: Boolean = false,
    private val copyBufferBytes: Int = SafCopyStreamer.COPY_BUFFER_BYTES,
    private val isCancelled: () -> Boolean = { false },
) {

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
        val scopedRecord = record.copy(grantId = grant.grantId)
        authorizationError(scopedRecord, grant, SafContainmentOperation.RECONCILE)?.let {
            return route(scopedRecord, it, created = null)
        }

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
        stagedVerification.closeError?.let { return route(scopedRecord, it, created = null) }
        val stagedDigest = when (val result = stagedVerification.outcome) {
            is VerifyResult.Matched -> result.digest
            is VerifyResult.VerifiedWithoutExpected -> result.digest
            is VerifyResult.Mismatched -> return fail(
                scopedRecord,
                TransferStorageError.IntegrityMismatch("staging_digest"),
            )
            is VerifyResult.Failed -> return route(scopedRecord, result.error, created = null)
        }

        authorizationError(scopedRecord, grant, SafContainmentOperation.RECONCILE)?.let {
            return route(scopedRecord, it, created = null)
        }
        val existing = gateway.findChild(
            scopedRecord.uriForTree(scopedRecord.parentDocumentId),
            scopedRecord.expectedFinalName,
        )
        val finalName = when (
            val choice = applyDuplicatePolicy(scopedRecord, existing, grant)
        ) {
            is SafNameDecision.Use -> choice.displayName

            // Overwrite is not a naming decision. The final name is already
            // taken, and replacing is a different sequence rather than a
            // different name, so it leaves before anything is resolved.
            is SafNameDecision.Overwrite -> return commitOverwrite(
                scopedRecord,
                choice.existingUri,
                choice.existingDocumentId,
                grant,
                stagedDigest,
            )

            is SafNameDecision.Skip -> return SafCommitOutcome.Skipped(scopedRecord, choice.existingUri)
            is SafNameDecision.Ask -> return SafCommitOutcome.PendingUserDecision(
                record = scopedRecord,
                existingUri = choice.existingUri,
                finalName = scopedRecord.expectedFinalName,
            )

            is SafNameDecision.Unavailable -> return reconcile(scopedRecord, choice.error)
            is SafNameDecision.Refused -> return fail(scopedRecord, choice.error)
        }

        val resolved = scopedRecord.copy(
            state = SafCommitState.DESTINATION_RESOLVED,
            expectedFinalName = finalName,
        )

        return when (scopedRecord.strategy) {
            SafCommitStrategy.TEMP_THEN_RENAME -> commitTempThenRename(resolved, grant, stagedDigest)
            SafCommitStrategy.VISIBLE_FINAL_COPY -> commitVisibleFinalCopy(resolved, grant, stagedDigest)
        }
    }

    /**
     * Retries only the exact cleanup identities recorded on a delivered commit.
     *
     * A query that cannot prove absence leaves the corresponding item pending;
     * this method never searches by name and never turns UNKNOWN into success.
     */
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
        if (!cleanupStateAuthorizes(record)) {
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
        if (record.pendingCleanup.isEmpty()) {
            return SafCommitOutcome.Committed(
                record = record.copy(state = SafCommitState.COMMITTED),
                finalUri = final.documentUri,
            )
        }

        val outstanding = record.pendingCleanup.toMutableSet()
        for (item in listOf(SafCleanupPending.BACKUP, SafCleanupPending.PROVIDER_TEMPORARY)) {
            if (item !in outstanding) continue
            val identity = when (item) {
                SafCleanupPending.BACKUP -> record.backupIdentity
                SafCleanupPending.PROVIDER_TEMPORARY -> record.temporaryIdentity
                SafCleanupPending.STAGING -> null
            } ?: return reconcileKnown(
                record,
                TransferStorageError.ContainmentUnknown("cleanup_identity_missing"),
                record.knownDocumentIdentities.map { it.documentUri },
            )

            val authorization = authorizationError(
                record,
                grant,
                SafContainmentOperation.DELETE_TEMPORARY,
                identity.documentUri,
                identity.documentId,
            )
            if (authorization is TransferStorageError.PermissionRevoked) continue
            if (authorization != null) {
                return reconcileKnown(
                    record,
                    authorization,
                    record.knownDocumentIdentities.map { it.documentUri },
                )
            }

            when (gateway.deleteAndReconcile(identity.documentUri, identity.documentId, grant)) {
                is SafDeletion.ConfirmedAbsent -> outstanding.remove(item)
                is SafDeletion.StillPresent,
                is SafDeletion.PermissionRevoked,
                -> Unit

                is SafDeletion.QueryUnknown,
                is SafDeletion.IdentityMismatch,
                is SafDeletion.DeleteRequestFailed,
                -> return reconcileKnown(
                    record,
                    TransferStorageError.StateConflict("cleanup_unsettled"),
                    record.knownDocumentIdentities.map { it.documentUri },
                )
            }
        }

        var stagingReleased = record.stagingReleased
        if (SafCleanupPending.STAGING in outstanding) {
            stagingReleased = stagingReleased || staging.delete(record.partialId)
            if (stagingReleased) outstanding.remove(SafCleanupPending.STAGING)
        }

        val remaining = outstanding.toSet()
        val settled = record.copy(
            state = stateForCleanup(remaining),
            pendingCleanup = remaining,
            stagingReleased = stagingReleased,
        )
        return SafCommitOutcome.Committed(
            record = settled,
            finalUri = final.documentUri,
            pendingCleanup = remaining,
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

        if (documentUri != null) {
            val uri = documentUri.toUri()
            if (uri.authority != grant.authority) {
                return TransferStorageError.ContainmentUnknown("document_authority_mismatch")
            }
            val uriDocumentId = SafContainment.documentIdOf(uri)
            if (expectedDocumentId == null || uriDocumentId != expectedDocumentId) {
                return TransferStorageError.ContainmentUnknown("document_identity_mismatch")
            }
        }

        // The parent URI is built from the grant, never accepted from a caller.
        // The exact root is already named by the persisted tree grant; every
        // other parent must be proven by the provider's tiered containment API.
        val parentUri = SafContainment.documentUriUsingTree(
            grant.treeUri,
            record.parentDocumentId,
        ) ?: return TransferStorageError.ContainmentUnknown("parent_uri_unavailable")
        if (record.parentDocumentId == grant.rootDocumentId) return null

        val prover = gateway as? SafContainmentProver
            ?: return TransferStorageError.ContainmentUnknown("containment_prover_unavailable")
        return when (
            val evidence = SafDestinationResolver.validateExistingUri(
                grant = grant,
                documentUri = parentUri,
                prover = prover,
                sdkInt = android.os.Build.VERSION.SDK_INT,
            )
        ) {
            is SafContainmentEvidence.ProviderConfirmedChild,
            is SafContainmentEvidence.ProviderConfirmedPath,
            -> null

            is SafContainmentEvidence.GrantScopedCanonical ->
                if (operation == SafContainmentOperation.CREATE_DESTINATION) {
                    null
                } else {
                    TransferStorageError.ContainmentUnknown("parent_not_provider_confirmed")
                }

            is SafContainmentEvidence.PermissionRevoked -> evidence.error
            is SafContainmentEvidence.Unknown -> when (val reason = evidence.reason) {
                is TransferStorageError.PermissionRevoked -> reason
                else -> TransferStorageError.ContainmentUnknown("parent_unknown")
            }

            is SafContainmentEvidence.Outside ->
                TransferStorageError.ContainmentUnknown("parent_outside_grant")

            is SafContainmentEvidence.Malformed ->
                TransferStorageError.ContainmentUnknown("parent_malformed")
        }
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
        val replacement = provisional.created

        // 4. Everything needed to finish is now present, and nothing the user
        // had has been touched yet.
        var current = provisional.record.copy(state = SafCommitState.REPLACEMENT_READY)

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
        val backupName = backupDocumentName(record.expectedFinalName, record.partialId)
        authorizationError(
            current,
            grant,
            SafContainmentOperation.RENAME,
            existing.documentUri,
            existing.documentId,
        )?.let {
            return route(current, it, replacement.documentUri)
        }
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
                return reconcileKnown(
                    current,
                    result.error,
                    listOf(existing.documentUri, replacement.documentUri).distinct(),
                )
            }
        }
        current = current.copy(state = SafCommitState.BACKUP_CREATED)

        // 7. Settle what that rename left. Only a unique result is authoritative:
        // a provider that copies rather than moves has left two documents and
        // guessing which to delete next is how the wrong one goes.
        val backupSettlement = settleRename(
            existing.documentUri,
            existing.documentId,
            moved.documentUri,
            current,
            grant,
        )
        val backup = when (backupSettlement) {
            is RenameSettlement.Unknown -> return reconcileKnown(
                current.copy(renameHistory = current.renameHistory + backupSettlement.evidence),
                backupSettlement.error,
                backupSettlement.knownUris + replacement.documentUri + existing.documentUri,
            )

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
        current = current.copy(backupIdentity = SafStoredDocumentIdentity(backupUri, backupId))

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
                listOf(backupUri, replacement.documentUri, existing.documentUri).distinct(),
            )
        }
        val promoted = when (val result = gateway.rename(replacement.documentUri, record.expectedFinalName)) {
            is SafRename.Renamed -> result
            is SafRename.Failed -> return reconcileKnown(
                current,
                result.error,
                listOf(backupUri, replacement.documentUri, existing.documentUri).distinct(),
            )
        }
        current = current.copy(state = SafCommitState.RENAME_STARTED)

        // 9. Settle that rename too.
        val finalSettlement = settleRename(
            replacement.documentUri,
            replacement.documentId,
            promoted.documentUri,
            current,
            grant,
        )
        val finalIdentity = when (finalSettlement) {
            is RenameSettlement.Unknown -> return reconcileKnown(
                current.copy(renameHistory = current.renameHistory + finalSettlement.evidence),
                finalSettlement.error,
                finalSettlement.knownUris + backupUri,
            )

            is RenameSettlement.Settled -> {
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
        current = current.copy(
            finalUri = finalUri,
            finalIdentity = SafStoredDocumentIdentity(finalUri, finalDocumentId),
        )

        // 10. Verify the document under its final name, not the temporary's. A
        // rename is a provider operation; it has to be shown to have produced the
        // bytes that were verified, not assumed to have.
        val verified = verifyProviderCopy(finalUri, finalDocumentId, record, grant, stagedDigest)
        verified.closeError?.let {
            return reconcileKnown(current, it, listOf(backupUri, finalUri).distinct())
        }
        if (verified.outcome is VerifyResult.Failed) {
            return reconcileKnown(
                current,
                verified.outcome.error,
                listOf(backupUri, finalUri).distinct(),
            )
        }
        if (verified.outcome is VerifyResult.Mismatched) {
            return reconcileKnown(
                current,
                TransferStorageError.IntegrityMismatch("digest"),
                listOf(backupUri, finalUri).distinct(),
            )
        }
        if (verified.outcome !is VerifyResult.Matched) {
            return reconcileKnown(
                current,
                TransferStorageError.StateConflict("verification_digest_missing"),
                listOf(backupUri, finalUri).distinct(),
            )
        }
        current = current.copy(
            state = SafCommitState.PUBLISHED_OR_VISIBLE,
            finalUri = finalUri,
            finalIdentity = SafStoredDocumentIdentity(finalUri, finalDocumentId),
        )

        // 11. Recorded before the deletion, so that a crash before it and a crash
        // after it are distinguishable by state alone.
        current = current.copy(
            state = SafCommitState.BACKUP_CLEANUP_PENDING,
            pendingCleanup = setOf(SafCleanupPending.BACKUP, SafCleanupPending.STAGING),
        )

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
                    setOf(SafCleanupPending.BACKUP),
                )
                else -> reconcileKnown(current, it, listOf(backupUri, finalUri).distinct())
            }
        }
        val deletion = gateway.deleteAndReconcile(backupUri, backupId, grant)

        // 13. Absence is settled by the follow-up query, never by the delete
        // request's own answer.
        return when (deletion) {
            is SafDeletion.ConfirmedAbsent -> finish(
                current.copy(pendingCleanup = setOf(SafCleanupPending.STAGING)),
                finalUri,
            )

            // The backup is still there, or the grant is gone: the document is
            // delivered either way, and the retry targets the stored identity.
            is SafDeletion.StillPresent,
            is SafDeletion.PermissionRevoked ->
                finish(current, finalUri, setOf(SafCleanupPending.BACKUP))

            // What is there is not known, or is not the backup. Deleting on that
            // basis is how a cleanup removes a document it did not create.
            is SafDeletion.QueryUnknown,
            is SafDeletion.IdentityMismatch,
            is SafDeletion.DeleteRequestFailed -> reconcileKnown(
                current,
                TransferStorageError.StateConflict("backup_cleanup_unsettled"),
                listOf(backupUri, finalUri).distinct(),
            )
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
        val parentUri = record.uriForTree(record.parentDocumentId)
        authorizationError(record, grant, SafContainmentOperation.CREATE_DESTINATION)?.let {
            return VerifiedTemporary.Stopped(route(record, it, created = null))
        }

        val created = when (val result = gateway.create(parentUri, "application/octet-stream", tempName)) {
            is SafCreate.Created -> result
            is SafCreate.Failed -> return VerifiedTemporary.Stopped(
                onCreateFailed(record, result.error),
            )
        }

        var current = record.copy(
            state = SafCommitState.TEMPORARY_CREATED,
            temporaryUri = created.documentUri,
            temporaryIdentity = SafStoredDocumentIdentity(created.documentUri, created.documentId),
        )

        // Copy, flush and close before anything is verified: the digest has to
        // come from a descriptor the provider is holding, not from the stream
        // that was written to.
        current = current.copy(state = SafCommitState.COPY_STARTED)
        val phase = runCopy(current, created.documentUri, created.documentId, grant)
        phase.closeError?.let {
            return VerifiedTemporary.Stopped(onCopyFailed(current, it, created.documentUri))
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
                onCopyFailed(current, verified.outcome.error, created.documentUri),
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
        current = current.copy(state = SafCommitState.PROVIDER_VERIFIED)

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
        var current = provisional.record
        val created = provisional.created

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
        val renamed = when (
            val result = gateway.rename(created.documentUri, record.expectedFinalName)
        ) {
            is SafRename.Renamed -> result
            is SafRename.Failed -> return onRenameFailed(current, result.error, created.documentUri)
        }
        current = current.copy(state = SafCommitState.RENAMED)

        // The provider may have returned the same identity, a new one, or
        // nothing. Which of those it was decides what exists now, so it is
        // settled by querying both -- never by preferring the old URI because
        // it is the one already to hand.
        when (val settled = settleRename(
            created.documentUri,
            created.documentId,
            renamed.documentUri,
            current,
            grant,
        )) {
            is RenameSettlement.Unknown -> return onRenameUnresolved(current, settled, created.documentUri)

            is RenameSettlement.Settled -> {
                val identity = settled.identity
                if (identity.requiresReconciliation) {
                    return onRenameAmbiguous(current, identity, created.documentUri)
                }
                val finalUri = identity.authoritativeDocumentUri
                    ?: return onRenameAmbiguous(current, identity, created.documentUri)
                val finalDocumentId = identity.authoritativeDocumentId
                    ?: return onRenameAmbiguous(current, identity, created.documentUri)
                current = current.copy(renameHistory = current.renameHistory + identity.evidence)

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
                finalVerification.closeError?.let {
                    return reconcileKnown(current, it, listOf(created.documentUri, finalUri).distinct())
                }
                if (finalVerification.outcome is VerifyResult.Failed) {
                    return reconcileKnown(
                        current,
                        finalVerification.outcome.error,
                        listOf(created.documentUri, finalUri).distinct(),
                    )
                }
                if (finalVerification.outcome is VerifyResult.Mismatched) {
                    return reconcileKnown(
                        current,
                        TransferStorageError.IntegrityMismatch("digest"),
                        listOf(created.documentUri, finalUri).distinct(),
                    )
                }
                if (finalVerification.outcome !is VerifyResult.Matched) {
                    return reconcileKnown(
                        current,
                        TransferStorageError.StateConflict("verification_digest_missing"),
                        listOf(created.documentUri, finalUri).distinct(),
                    )
                }
                current = current.copy(
                    state = SafCommitState.PUBLISHED_OR_VISIBLE,
                    finalUri = finalUri,
                    finalIdentity = SafStoredDocumentIdentity(finalUri, finalDocumentId),
                    pendingCleanup = setOf(SafCleanupPending.STAGING),
                )
                return finish(current, finalUri)
            }
        }
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

        val parentUri = record.uriForTree(record.parentDocumentId)
        authorizationError(record, grant, SafContainmentOperation.CREATE_DESTINATION)?.let {
            return route(record, it, created = null)
        }
        val created = when (
            val result = gateway.create(
                parentUri,
                "application/octet-stream",
                record.expectedFinalName,
            )
        ) {
            is SafCreate.Created -> result
            is SafCreate.Failed -> return onCreateFailed(record, result.error)
        }

        var current = record.copy(
            state = SafCommitState.FINAL_CREATED,
            finalUri = created.documentUri,
            finalIdentity = SafStoredDocumentIdentity(created.documentUri, created.documentId),
        )

        current = current.copy(state = SafCommitState.COPY_STARTED)
        val phase = runCopy(current, created.documentUri, created.documentId, grant)
        phase.closeError?.let { return onCopyFailed(current, it, created.documentUri) }
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
        val verified = verifyProviderCopy(
            created.documentUri,
            created.documentId,
            record,
            grant,
            stagedDigest,
        )
        verified.closeError?.let { return onCopyFailed(current, it, created.documentUri) }
        if (verified.outcome is VerifyResult.Failed) {
            return onCopyFailed(current, verified.outcome.error, created.documentUri)
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
        current = current.copy(state = SafCommitState.PROVIDER_VERIFIED)

        current = current.copy(
            state = SafCommitState.PUBLISHED_OR_VISIBLE,
            pendingCleanup = setOf(SafCleanupPending.STAGING),
        )
        return finish(current, created.documentUri)
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
                return unflushed(SafCopyOutcome.Failed(opened.error, 0L), closeError)
            }
        }

        var closeError: TransferStorageError? = null
        val phase = try {
            val outcome = SafCopyStreamer.copy(
                read = staged,
                write = target,
                totalBytes = record.expectedSizeBytes,
                buffer = ByteArray(copyBufferBytes),
                isCancelled = isCancelled,
            )
            // Flushed before the handle is closed, or not at all.
            val flush = if (outcome is SafCopyOutcome.Copied) {
                target.flush()
            } else {
                FlushDurability.FlushUnsupported
            }
            SafCopyPhase(outcome, flush)
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
        authorizationError(
            record,
            grant,
            SafContainmentOperation.VERIFY,
            documentUri,
            expectedDocumentId,
        )?.let {
            return VerifyPhase(VerifyResult.Failed(it))
        }
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

        authorizationError(
            record,
            grant,
            SafContainmentOperation.VERIFY,
            documentUri,
            expectedDocumentId,
        )?.let {
            return VerifyPhase(VerifyResult.Failed(it))
        }
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
    private fun finish(
        record: SafCommitRecord,
        finalUri: String,
        pendingCleanup: Set<SafCleanupPending> = emptySet(),
    ): SafCommitOutcome {
        val finalIdentity = record.finalIdentity
            ?: record.temporaryIdentity?.takeIf { it.documentUri == finalUri }
        val beforeDelete = record.copy(
            state = SafCommitState.STAGING_CLEANUP_PENDING,
            finalUri = finalUri,
            finalIdentity = finalIdentity,
        )
        val removed = beforeDelete.stagingReleased || staging.delete(beforeDelete.partialId)
        val outstanding = (beforeDelete.pendingCleanup + pendingCleanup).toMutableSet().apply {
            if (removed) remove(SafCleanupPending.STAGING) else add(SafCleanupPending.STAGING)
        }.toSet()
        val completed = beforeDelete.copy(
            state = stateForCleanup(outstanding),
            copiedBytes = beforeDelete.expectedSizeBytes,
            pendingCleanup = outstanding,
            stagingReleased = removed,
        )
        return SafCommitOutcome.Committed(
            record = completed,
            finalUri = finalUri,
            pendingCleanup = outstanding,
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
        val attemptEvidence = SafRenameEvidence(beforeIdentity, returnedIdentity)
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

        authorizationError(
            record,
            grant,
            SafContainmentOperation.RECONCILE,
            beforeUri,
            beforeId,
        )?.let { return unknown(it) }

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

        val returnedResolves = when {
            returnedUri == null -> false
            returnedUri == beforeUri -> originalResolves
            else -> {
                val returnedId = returnedIdentity?.documentId
                    ?: return unknown(
                        TransferStorageError.ContainmentUnknown("rename_returned_identity_missing"),
                    )
                authorizationError(
                    record,
                    grant,
                    SafContainmentOperation.RECONCILE,
                    returnedUri,
                    returnedId,
                )?.let { return unknown(it) }
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

        val identity = SafRenameIdentityResolver.resolve(
            beforeDocumentUri = beforeUri,
            beforeDocumentId = beforeId,
            returnedDocumentUri = returnedUri,
            returnedDocumentId = returnedIdentity?.documentId,
            originalStillResolves = originalResolves,
            returnedResolves = returnedResolves,
        )
        return RenameSettlement.Settled(identity, identity.evidence)
    }

    private fun onRenameUnresolved(
        record: SafCommitRecord,
        settled: RenameSettlement.Unknown,
        temporaryUri: String,
    ): SafCommitOutcome = SafCommitOutcome.ReconciliationRequired(
        record = record.copy(
            temporaryUri = temporaryUri,
            renameHistory = record.renameHistory + settled.evidence,
        ),
        error = settled.error,
        knownUris = settled.knownUris,
    )

    private fun onRenameAmbiguous(
        record: SafCommitRecord,
        identity: SafRenameIdentity,
        temporaryUri: String,
    ): SafCommitOutcome = SafCommitOutcome.ReconciliationRequired(
        record = record.copy(
            temporaryUri = temporaryUri,
            renameHistory = record.renameHistory + identity.evidence,
        ),
        error = TransferStorageError.StateConflict("rename_ambiguous"),
        knownUris = identity.knownUris,
    )

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
        error: TransferStorageError,
        temporaryUri: String,
    ): SafCommitOutcome = route(record, error, created = temporaryUri)

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
