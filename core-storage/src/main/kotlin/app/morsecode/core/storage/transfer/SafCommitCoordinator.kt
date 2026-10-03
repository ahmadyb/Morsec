package app.morsecode.core.storage.transfer

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.storage.saf.SafPaths

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
    public fun findChild(parentUri: String, displayName: String): SafLookup
    public fun create(parentUri: String, mimeType: String, displayName: String): SafCreate
    public fun rename(documentUri: String, displayName: String): SafRename
    public fun delete(documentUri: String): SafDelete
    public fun query(documentUri: String): SafLookup
    public fun openWrite(documentUri: String): SafOpen
    public fun openRead(documentUri: String): SafOpen
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

    /** How many times this handle has actually closed its primary resource. */
    public var closeCount: Int = 0
        protected set

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
        FlushDurability.FlushFailed(TransferStorageError.Io("sync"))
    }

    override fun close() {
        if (closeCount++ > 0) return
        runCatching { stream.close() }
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
        if (closeCount++ > 0) return
        runCatching { stream.close() }
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
                val count = read.read(buffer, 0, chunk)
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
                        write.write(buffer, 0, count)
                        copied += count
                    }
                }
            }
            return SafCopyOutcome.Copied(copied)
        } catch (e: Exception) {
            return SafCopyOutcome.Failed(mapCopyFailure(e), copied)
        }
    }

    private fun mapCopyFailure(error: Exception): TransferStorageError = when (error) {
        is SecurityException -> TransferStorageError.PermissionRevoked("write")
        is java.io.FileNotFoundException -> TransferStorageError.NotFound("staged_copy")
        is java.io.IOException -> TransferStorageError.Io("copy")
        else -> TransferStorageError.ProviderFailure("document_provider")
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
        if (!isTaken(displayName).isTaken()) return SafNameChoice.Chosen(displayName)
        for (index in 1..MAX_RENAME_ATTEMPTS) {
            val candidate = withSuffix(displayName, index)
            if (!isTaken(candidate).isTaken()) return SafNameChoice.Chosen(candidate)
        }
        return SafNameChoice.Exhausted(displayName)
    }

    private fun SafLookup.isTaken(): Boolean = this is SafLookup.Found
}

/** What [SafDuplicateNaming.firstFree] decided. */
public sealed interface SafNameChoice {
    public data class Chosen(public val displayName: String) : SafNameChoice

    /** Every plausible name was taken. A rename cannot proceed. */
    public data class Exhausted(public val displayName: String) : SafNameChoice
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
        public val stagingReleased: Boolean = true,
    ) : SafCommitOutcome

    /** The policy was skip, so no document was created. */
    public data class Skipped(
        public val record: SafCommitRecord,
        public val existingUri: String,
    ) : SafCommitOutcome

    /** The policy was ask, so the caller must decide. Nothing was written. */
    public data class PendingUserDecision(
        public val record: SafCommitRecord,
        public val existingUri: String,
        public val finalName: String,
    ) : SafCommitOutcome

    /** The commit failed in a typed, explainable way. Staging is retained. */
    public data class Failed(
        public val record: SafCommitRecord,
        public val error: TransferStorageError,
    ) : SafCommitOutcome

    /**
     * The provider's state could not be determined.
     *
     * Nothing was decided, nothing was deleted, and staging is retained. This is
     * never a success and never a failure that permits cleanup.
     */
    public data class ReconciliationRequired(
        public val record: SafCommitRecord,
        public val error: TransferStorageError,
    ) : SafCommitOutcome

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
    private val gateway: SafDocumentGateway,
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
     * Commits [record], whose staging file has already been verified.
     *
     * The caller passes the record rather than letting the coordinator look it
     * up so that the state it writes back is the same record the caller
     * persists, and there is no second source of truth for "where are we".
     */
    public fun commit(record: SafCommitRecord): SafCommitOutcome {
        val stagedLength = staging.length(record.partialId)
            ?: return fail(record, TransferStorageError.NotFound("staged_copy"))

        // The staged file is the authority on the size, not the record: if they
        // disagree then the record is describing bytes that no longer exist.
        if (stagedLength != record.expectedSizeBytes) {
            return fail(
                record,
                TransferStorageError.StateConflict("staging_length_disagrees"),
            )
        }

        val existing = gateway.findChild(record.uriForTree(record.parentDocumentId), record.expectedFinalName)
        val finalName = when (val choice = applyDuplicatePolicy(record, existing)) {
            is SafNameDecision.Use -> choice.displayName
            is SafNameDecision.Skip -> return SafCommitOutcome.Skipped(record, choice.existingUri)
            is SafNameDecision.Ask -> return SafCommitOutcome.PendingUserDecision(
                record = record,
                existingUri = choice.existingUri,
                finalName = record.expectedFinalName,
            )

            is SafNameDecision.Unavailable -> return reconcile(record, choice.error)
            is SafNameDecision.Refused -> return fail(record, choice.error)
        }

        val resolved = record.copy(
            state = SafCommitState.DESTINATION_RESOLVED,
            expectedFinalName = finalName,
        )

        return when (record.strategy) {
            SafCommitStrategy.TEMP_THEN_RENAME -> commitTempThenRename(resolved)
            SafCommitStrategy.VISIBLE_FINAL_COPY -> commitVisibleFinalCopy(resolved)
        }
    }

    // -- Strategy A ---------------------------------------------------------

    private fun commitTempThenRename(record: SafCommitRecord): SafCommitOutcome {
        val parentUri = record.uriForTree(record.parentDocumentId)
        val tempName = temporaryDocumentName(record.expectedFinalName, record.partialId)

        val created = when (val result = gateway.create(parentUri, "application/octet-stream", tempName)) {
            is SafCreate.Created -> result
            is SafCreate.Failed -> return onCreateFailed(record, result.error)
        }

        var current = record.copy(
            state = SafCommitState.TEMPORARY_CREATED,
            temporaryUri = created.documentUri,
        )

        // Copy, flush and close before anything is verified: the digest has to
        // come from a descriptor the provider is holding, not from the stream
        // that was written to.
        current = current.copy(state = SafCommitState.COPY_STARTED)
        val phase = runCopy(current, created.documentUri)
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
        val verified = verifyProviderCopy(created.documentUri, record)
        if (verified is VerifyResult.Failed) {
            return onCopyFailed(current, verified.error, created.documentUri)
        }
        if (verified is VerifyResult.Mismatched) {
            return onCopyFailed(
                current,
                TransferStorageError.IntegrityMismatch("digest"),
                created.documentUri,
            )
        }
        current = current.copy(state = SafCommitState.PROVIDER_VERIFIED)

        // Recorded before the call so that a death either side of it is
        // answerable: RENAMED with no final document means the rename did not
        // happen, whatever the provider claimed.
        current = current.copy(state = SafCommitState.RENAME_STARTED)
        val renamed = when (
            val result = gateway.rename(created.documentUri, record.expectedFinalName)
        ) {
            is SafRename.Renamed -> result
            is SafRename.Failed -> return onRenameFailed(current, result.error, created.documentUri)
        }
        current = current.copy(state = SafCommitState.RENAMED)

        val finalUri = renamed.documentUri ?: created.documentUri
        current = current.copy(state = SafCommitState.PUBLISHED_OR_VISIBLE, finalUri = finalUri)

        return finish(current, finalUri)
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
    private fun commitVisibleFinalCopy(record: SafCommitRecord): SafCommitOutcome {
        if (!allowVisibleFinalCopy) {
            return fail(
                record,
                TransferStorageError.Unsupported("saf_visible_final_copy"),
            )
        }

        val parentUri = record.uriForTree(record.parentDocumentId)
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
        )

        current = current.copy(state = SafCommitState.COPY_STARTED)
        val phase = runCopy(current, created.documentUri)
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
        val verified = verifyProviderCopy(created.documentUri, record)
        if (verified is VerifyResult.Failed) {
            return onCopyFailed(current, verified.error, created.documentUri)
        }
        if (verified is VerifyResult.Mismatched) {
            return onCopyFailed(
                current,
                TransferStorageError.IntegrityMismatch("digest"),
                created.documentUri,
            )
        }
        current = current.copy(state = SafCommitState.PROVIDER_VERIFIED)

        current = current.copy(state = SafCommitState.PUBLISHED_OR_VISIBLE)
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
    private fun runCopy(record: SafCommitRecord, targetUri: String): SafCopyPhase {
        val staged = when (val opened = staging.open(record.partialId)) {
            is SafOpen.Opened -> opened.handle as? SafReadHandle
                ?: return unflushed(
                    SafCopyOutcome.Failed(
                        TransferStorageError.ProviderFailure("document_provider"),
                        0L,
                    ),
                )

            is SafOpen.Refused -> return unflushed(SafCopyOutcome.Failed(opened.error, 0L))
        }

        val target = when (val opened = gateway.openWrite(targetUri)) {
            is SafOpen.Opened -> opened.handle as? SafWriteHandle
                ?: run {
                    staged.close()
                    return unflushed(
                        SafCopyOutcome.Failed(
                            TransferStorageError.ProviderFailure("document_provider"),
                            0L,
                        ),
                    )
                }

            is SafOpen.Refused -> {
                staged.close()
                return unflushed(SafCopyOutcome.Failed(opened.error, 0L))
            }
        }

        return try {
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
        } finally {
            runCatching { target.close() }
            runCatching { staged.close() }
        }
    }

    /** A copy that never got as far as writing, so nothing was flushed. */
    private fun unflushed(outcome: SafCopyOutcome): SafCopyPhase =
        SafCopyPhase(outcome, FlushDurability.FlushUnsupported)

    /**
     * Verifies what the provider is actually holding.
     *
     * Length first, from a query, because that is the cheap check and it catches
     * a truncated copy before any bytes are read. Then a fresh read descriptor,
     * because a digest taken from the stream that was written to would only
     * prove what was handed over, not what landed.
     */
    private fun verifyProviderCopy(documentUri: String, record: SafCommitRecord): VerifyResult {
        when (val looked = gateway.query(documentUri)) {
            is SafLookup.Absent -> return VerifyResult.Failed(
                TransferStorageError.NotFound("staged_copy"),
            )

            is SafLookup.Failed -> return VerifyResult.Failed(looked.error)
            is SafLookup.Found -> {
                val size = looked.document.sizeBytes
                // A null size means the provider did not say, which is not the
                // same as agreeing; the digest pass below is what settles it.
                if (size != null && size != record.expectedSizeBytes) {
                    return VerifyResult.Failed(
                        TransferStorageError.IntegrityMismatch("length"),
                    )
                }
            }
        }

        val handle = when (val opened = gateway.openRead(documentUri)) {
            is SafOpen.Opened -> opened.handle as? SafReadHandle
                ?: return VerifyResult.Failed(
                    TransferStorageError.ProviderFailure("document_provider"),
                )

            is SafOpen.Refused -> return VerifyResult.Failed(opened.error)
        }

        return try {
            DestinationVerifier.verify(
                source = handle.verificationSource(),
                totalBytes = record.expectedSizeBytes,
                expected = record.expectedDigest,
                buffer = ByteArray(copyBufferBytes),
            )
        } finally {
            runCatching { handle.close() }
        }
    }

    /**
     * Releases staging and records the commit.
     *
     * Staging is removed only here, only after the document is under its final
     * name. A staging file that cannot be removed is not a failure of the
     * delivery — the file is where the user asked for it — so it is reported as
     * committed with cleanup still pending.
     */
    private fun finish(record: SafCommitRecord, finalUri: String): SafCommitOutcome {
        val pending = record.copy(
            state = SafCommitState.STAGING_CLEANUP_PENDING,
            finalUri = finalUri,
        )
        val removed = staging.delete(pending.partialId)
        return if (removed) {
            SafCommitOutcome.Committed(
                record = pending.copy(state = SafCommitState.COMMITTED),
                finalUri = finalUri,
                stagingReleased = true,
            )
        } else {
            // Delivered, but the record must not claim the staging is gone:
            // the cleanup planner needs to see that it is still there.
            SafCommitOutcome.Committed(
                record = pending.copy(copiedBytes = pending.expectedSizeBytes),
                finalUri = finalUri,
                stagingReleased = false,
            )
        }
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
                error is TransferStorageError.ProviderFailure
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
            else -> SafCommitOutcome.Failed(held, error)
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
        data class Unavailable(val error: TransferStorageError) : SafNameDecision
        data class Refused(val error: TransferStorageError) : SafNameDecision
    }

    private fun applyDuplicatePolicy(
        record: SafCommitRecord,
        existing: SafLookup,
    ): SafNameDecision = when (existing) {
        is SafLookup.Failed -> SafNameDecision.Unavailable(existing.error)

        SafLookup.Absent -> SafNameDecision.Use(record.expectedFinalName)

        is SafLookup.Found -> when (record.duplicatePolicy) {
            DuplicatePolicy.RENAME -> {
                val choice = SafDuplicateNaming.firstFree(record.expectedFinalName) { name ->
                    gateway.findChild(record.uriForTree(record.parentDocumentId), name)
                }
                when (choice) {
                    is SafNameChoice.Chosen -> SafNameDecision.Use(choice.displayName)
                    is SafNameChoice.Exhausted -> SafNameDecision.Refused(
                        TransferStorageError.StateConflict("duplicate_name_exhausted"),
                    )
                }
            }

            // The old destination is never deleted first. The replacement is
            // created, copied and verified under a temporary identity, and only
            // then is the existing document reconciled. Doing it the other way
            // round means a crash mid-copy destroys the file the user had.
            DuplicatePolicy.OVERWRITE -> SafNameDecision.Refused(
                TransferStorageError.Unsupported("saf_overwrite"),
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
