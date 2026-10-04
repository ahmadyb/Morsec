package app.morsecode.core.storage.transfer

import androidx.core.net.toUri
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.model.SafGrant
import app.morsecode.core.storage.saf.PathResolution
import app.morsecode.core.storage.saf.SafPaths
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Digest

/*
 * The SAF destination: identity, commit states, provider capabilities and tree
 * validation.
 *
 * The fact everything here is built around: SAF has no universal pending or
 * hidden mechanism. A document created inside a user-granted tree is visible to
 * every other app the moment it exists, whatever it is called. A `.part` suffix
 * makes a file unmistakably incomplete *to a human*; it does not make it hidden,
 * and nothing in this file — or in the documentation it feeds — will describe it
 * as hidden.
 *
 * So the default is app-private staging first: bytes land where no other app can
 * see them, a real fsync is available for the whole transfer, and only the
 * verified result is copied into the user's tree. That costs a second copy and
 * up to twice the free space, and it is worth it, because the alternative is a
 * truncated file under its final name in a folder the user has granted to other
 * apps.
 */

// ---------------------------------------------------------------------------
// Commit strategy
// ---------------------------------------------------------------------------

/**
 * How a verified staging file becomes a document in the user's tree.
 *
 * Neither strategy is atomic and neither hides the file. They differ in what a
 * crash leaves visible.
 */
public enum class SafCommitStrategy(public val id: String) {

    /**
     * Create a temporary document, copy, verify, then rename to the final name.
     *
     * The temporary name is *unmistakably incomplete to a human*, which is worth
     * a great deal and is not the same as hidden. The document is visible to
     * other apps from the moment it is created. Rename is not assumed atomic: a
     * process death either side of it is reconcilable from the recorded state.
     */
    TEMP_THEN_RENAME("saf_temp_then_rename"),

    /**
     * Create the document under its final name and copy straight into it.
     *
     * Not hidden and not atomic. While the copy is incomplete the user's folder
     * contains a short file with its final name. Selectable only when product
     * policy accepts that, and reported explicitly so the UI can warn.
     */
    VISIBLE_FINAL_COPY("saf_visible_final_copy"),
    ;

    /** True when an interrupted copy does not leave a file under the final name. */
    public val avoidsVisiblePartialUnderFinalName: Boolean get() = this == TEMP_THEN_RENAME

    public companion object {
        public fun fromId(id: String?): SafCommitStrategy =
            entries.firstOrNull { it.id == id } ?: TEMP_THEN_RENAME
    }
}

// ---------------------------------------------------------------------------
// Commit state
// ---------------------------------------------------------------------------

/**
 * Where a SAF commit has got to.
 *
 * The point of the list is that no state jumps straight to committed. Between
 * `COPY_STARTED` and `COMMITTED` there are flush, verify and rename steps, each
 * of which is a place a process can die, and each of which has to be
 * answerable on the next start.
 *
 * [restoration] is what the next pass does when it finds this state.
 */
public enum class SafCommitState(public val id: String) {

    /** The staged file's digest matches. Nothing has been created in the tree. */
    STAGING_VERIFIED("staging_verified"),

    /** The target directory is confirmed inside the grant and writable. */
    DESTINATION_RESOLVED("destination_resolved"),

    /** The temporary document exists and is empty. */
    TEMPORARY_CREATED("temporary_created"),

    /** The bounded copy is under way. */
    COPY_STARTED("copy_started"),

    /** All expected bytes were handed to the provider. Not yet flushed. */
    COPY_COMPLETED("copy_completed"),

    /** The strongest flush the provider offers has been attempted. */
    PROVIDER_FLUSH_COMPLETED("provider_flush_completed"),

    /** Re-reading the provider copy for length and digest. */
    PROVIDER_VERIFICATION_STARTED("provider_verification_started"),

    /** The provider copy matches the staged file. */
    PROVIDER_VERIFIED("provider_verified"),

    /** A rename has been requested. Not known to have happened. */
    RENAME_STARTED("rename_started"),

    /** The rename returned; the new URI is recorded. */
    RENAMED("renamed"),

    /** The final-name document exists (visible-copy strategy only). */
    FINAL_CREATED("final_created"),

    /** The document is present under its final name. Not yet cleaned up. */
    PUBLISHED_OR_VISIBLE("published_or_visible"),

    /**
     * A verified replacement exists under a temporary identity, and the
     * existing final document has not been touched.
     *
     * The recoverable position: everything needed to finish an overwrite is
     * present, and nothing the user had has been destroyed yet.
     */
    REPLACEMENT_READY("replacement_ready"),

    /**
     * The existing final document has been moved aside to a backup identity.
     *
     * From here the only safe moves are forward (rename the replacement to the
     * final name) or back (rename the backup to the final name). Stopping here
     * is not an option the coordinator may choose silently.
     */
    BACKUP_CREATED("backup_created"),

    /** A backup identity may be removed; other cleanup can also be pending. */
    BACKUP_CLEANUP_PENDING("backup_cleanup_pending"),

    /** A provider temporary is no longer needed and may be removed. */
    PROVIDER_TEMPORARY_CLEANUP_PENDING("provider_temporary_cleanup_pending"),

    /** Staged bytes may now be removed. */
    STAGING_CLEANUP_PENDING("staging_cleanup_pending"),

    /** Terminal. The file is at its final location and staging is gone. */
    COMMITTED("committed"),

    /** The commit failed in a way that can be retried. */
    COMMIT_FAILED("commit_failed"),

    /** The provider's state cannot be determined. Nothing was decided. */
    RECONCILIATION_REQUIRED("reconciliation_required"),
    ;

    /** What the next pass does on finding this state. */
    public val restoration: String
        get() = when (this) {
            STAGING_VERIFIED -> "Resolve the destination again and continue."
            DESTINATION_RESOLVED -> "Apply the duplicate policy, then create the document."
            TEMPORARY_CREATED -> "Resume the copy from zero; the temporary is empty or discardable."
            COPY_STARTED -> "Discard the temporary and re-copy from zero; the copy is not resumable."
            COPY_COMPLETED -> "Attempt the flush again."
            PROVIDER_FLUSH_COMPLETED -> "Verify the provider copy; a failed flush is not a commit."
            PROVIDER_VERIFICATION_STARTED -> "Reopen a fresh read descriptor and verify again."
            PROVIDER_VERIFIED -> "Rename, or for the visible-copy strategy record the final URI."
            RENAME_STARTED -> "Query whether a final-name document exists before renaming again."
            RENAMED -> "Confirm the final document, then clean up staging."
            FINAL_CREATED -> "Re-copy from zero and verify."
            PUBLISHED_OR_VISIBLE -> "Clean up staging, then record committed."
            REPLACEMENT_READY ->
                "Move the existing final document to a backup identity, then promote the replacement."
            BACKUP_CREATED ->
                "Promote the replacement to the final name, or restore the backup. Never both."
            BACKUP_CLEANUP_PENDING ->
                "Retry every pending cleanup item by its recorded identity and confirm absence."
            PROVIDER_TEMPORARY_CLEANUP_PENDING ->
                "Retry every pending cleanup item by its recorded identity and confirm absence."
            STAGING_CLEANUP_PENDING -> "Delete the staged file, then record committed."
            COMMITTED -> "Nothing; the commit is finished."
            COMMIT_FAILED -> "Retry from the last durable state, or surface for reconciliation."
            RECONCILIATION_REQUIRED -> "Ask the provider again; never assume, never delete."
        }

    public val isTerminal: Boolean get() = this == COMMITTED

    /** True when a crash in this state needs work on the next start. */
    public val requiresRecovery: Boolean get() = this != COMMITTED

    /** Conservative state-only answer; a record's stagingReleased flag is authoritative. */
    public val ownsStaging: Boolean get() = this != COMMITTED

    public companion object {
        public fun fromId(id: String?): SafCommitState =
            entries.firstOrNull { it.id == id } ?: STAGING_VERIFIED

        private val ALLOWED: Map<SafCommitState, Set<SafCommitState>> = mapOf(
            STAGING_VERIFIED to setOf(DESTINATION_RESOLVED, COMMIT_FAILED),
            DESTINATION_RESOLVED to setOf(
                TEMPORARY_CREATED, FINAL_CREATED, COMMIT_FAILED, RECONCILIATION_REQUIRED,
            ),
            TEMPORARY_CREATED to setOf(COPY_STARTED, COMMIT_FAILED, RECONCILIATION_REQUIRED),
            COPY_STARTED to setOf(COPY_COMPLETED, COMMIT_FAILED, RECONCILIATION_REQUIRED),
            COPY_COMPLETED to setOf(PROVIDER_FLUSH_COMPLETED, COMMIT_FAILED),
            PROVIDER_FLUSH_COMPLETED to setOf(
                PROVIDER_VERIFICATION_STARTED, COMMIT_FAILED,
            ),
            PROVIDER_VERIFICATION_STARTED to setOf(
                PROVIDER_VERIFIED, COMMIT_FAILED, RECONCILIATION_REQUIRED,
            ),
            PROVIDER_VERIFIED to setOf(
                RENAME_STARTED, PUBLISHED_OR_VISIBLE, REPLACEMENT_READY, COMMIT_FAILED,
            ),
            RENAME_STARTED to setOf(RENAMED, COMMIT_FAILED, RECONCILIATION_REQUIRED),
            RENAMED to setOf(
                PUBLISHED_OR_VISIBLE, PROVIDER_TEMPORARY_CLEANUP_PENDING,
                COMMIT_FAILED, RECONCILIATION_REQUIRED,
            ),
            REPLACEMENT_READY to setOf(
                BACKUP_CREATED, COMMIT_FAILED, RECONCILIATION_REQUIRED,
            ),
            BACKUP_CREATED to setOf(
                RENAME_STARTED, COMMIT_FAILED, RECONCILIATION_REQUIRED,
            ),
            FINAL_CREATED to setOf(COPY_STARTED, COMMIT_FAILED, RECONCILIATION_REQUIRED),
            PUBLISHED_OR_VISIBLE to setOf(
                STAGING_CLEANUP_PENDING, PROVIDER_TEMPORARY_CLEANUP_PENDING,
                BACKUP_CLEANUP_PENDING, COMMIT_FAILED,
            ),
            PROVIDER_TEMPORARY_CLEANUP_PENDING to setOf(
                PROVIDER_TEMPORARY_CLEANUP_PENDING, STAGING_CLEANUP_PENDING,
                COMMITTED, RECONCILIATION_REQUIRED,
            ),
            STAGING_CLEANUP_PENDING to setOf(
                STAGING_CLEANUP_PENDING, COMMITTED, BACKUP_CLEANUP_PENDING,
                RECONCILIATION_REQUIRED,
            ),
            BACKUP_CLEANUP_PENDING to setOf(
                BACKUP_CLEANUP_PENDING, PROVIDER_TEMPORARY_CLEANUP_PENDING,
                STAGING_CLEANUP_PENDING, COMMITTED, RECONCILIATION_REQUIRED,
            ),
            COMMITTED to emptySet(),
            COMMIT_FAILED to setOf(DESTINATION_RESOLVED, COMMIT_FAILED, RECONCILIATION_REQUIRED),
            RECONCILIATION_REQUIRED to entries.toSet(),
        )

        /**
         * States a particular strategy must not enter from [from].
         *
         * Without this the machine would happily let a temporary-then-rename
         * commit jump from verified straight to published, which is exactly the
         * question `RENAME_STARTED` exists to make answerable after a crash.
         */
        private val DISALLOWED: Map<SafCommitStrategy, Map<SafCommitState, Set<SafCommitState>>> =
            mapOf(
                SafCommitStrategy.TEMP_THEN_RENAME to mapOf(
                    DESTINATION_RESOLVED to setOf(FINAL_CREATED),
                    PROVIDER_VERIFIED to setOf(PUBLISHED_OR_VISIBLE),
                ),
                SafCommitStrategy.VISIBLE_FINAL_COPY to mapOf(
                    DESTINATION_RESOLVED to setOf(TEMPORARY_CREATED),
                    PROVIDER_VERIFIED to setOf(RENAME_STARTED),
                ),
            )

        /**
         * The states [from] may move to.
         *
         * With [strategy] null this is the union across both strategies, which
         * is what a storage-layer check wants when it does not know which
         * strategy a record used. With a strategy it is the exact set, which is
         * what a coordinator wants before it records a step.
         */
        public fun next(
            from: SafCommitState,
            strategy: SafCommitStrategy? = null,
        ): Set<SafCommitState> {
            val allowed = ALLOWED[from] ?: emptySet()
            val forbidden = strategy?.let { DISALLOWED[it]?.get(from) } ?: emptySet()
            return allowed - forbidden
        }

        public fun allows(
            from: SafCommitState,
            to: SafCommitState,
            strategy: SafCommitStrategy? = null,
        ): Boolean = to in next(from, strategy)
    }
}

/**
 * Something that outlived the commit and still has to be removed.
 *
 * Kept as a set on the outcome rather than one flag, because more than one can
 * be outstanding at once -- staging and a leftover provider temporary, say --
 * and collapsing them into "cleanup failed" leaves the cleanup planner unable
 * to tell what to retry.
 *
 * Each entry names an identity to act on, never a name to search for. A cleanup
 * that resolved its target by filename would eventually delete a document it
 * did not create.
 */
public enum class SafCleanupPending(public val id: String) {

    /** The app-private staging file. Safe to retry; nothing else depends on it. */
    STAGING("staging"),

    /**
     * A provider temporary that is no longer the authoritative identity.
     *
     * Arises when a provider renames by copying rather than moving, so both
     * identities survive the rename.
     */
    PROVIDER_TEMPORARY("provider_temporary"),

    /** The pre-overwrite document, moved aside to a backup identity. */
    BACKUP("backup"),
    ;

    public companion object {
        public fun fromIds(ids: Collection<String>): Set<SafCleanupPending> =
            ids.mapNotNull { id -> entries.firstOrNull { it.id == id } }.toSet()
    }
}

// ---------------------------------------------------------------------------
// Temporary-document identity
// ---------------------------------------------------------------------------

/**
 * Everything needed to finish, retry or clean up a SAF commit after a restart.
 *
 * A temporary SAF document must never be identified only by its visible name:
 * the provider can rename it, a user can rename it, and a second transfer of a
 * file with the same name would collide. So the record carries the document
 * *identity* the provider issued — the URI it returned — and cleanup uses that,
 * never a filename search.
 */
public data class SafCommitRecord(
    /** The transfer this commit belongs to. */
    public val transferId: TransferId,

    /** The staged partial this commit is copying out of. */
    public val partialId: PartialIdentity,

    /** The granted tree this commit is writing into. */
    public val treeUri: String,

    /** Document id of the granted root, the ceiling for containment. */
    public val rootDocumentId: String,

    /** Document id of the directory that receives the file. */
    public val parentDocumentId: String,

    /** URI the provider returned for the temporary document, if one exists. */
    public val temporaryUri: String? = null,

    /** URI of the document under its final name, once it exists. */
    public val finalUri: String? = null,

    /** The name the document must end up with. */
    public val expectedFinalName: String,

    /** Exactly how many bytes the copy must contain. */
    public val expectedSizeBytes: Long,

    /** The digest the staged file was verified against, when one was supplied. */
    public val expectedDigest: Sha256Digest? = null,

    /** Persisted grant-row identity authorizing every provider operation. */
    public val grantId: String? = null,

    /** Which strategy was selected, and why. */
    public val strategy: SafCommitStrategy = SafCommitStrategy.TEMP_THEN_RENAME,

    /** Where the commit has got to. */
    public val state: SafCommitState = SafCommitState.STAGING_VERIFIED,

    /** How an existing destination is handled. */
    public val duplicatePolicy: DuplicatePolicy = DuplicatePolicy.RENAME,

    /** Bytes believed written so far. Diagnostic only; never the source of truth. */
    public val copiedBytes: Long = 0L,

    /** Exact identities retained for recovery; URI and id are never reconstructed from a name. */
    public val temporaryIdentity: SafStoredDocumentIdentity? = null,
    public val finalIdentity: SafStoredDocumentIdentity? = null,
    public val existingIdentity: SafStoredDocumentIdentity? = null,
    public val backupIdentity: SafStoredDocumentIdentity? = null,
    public val renameHistory: List<SafRenameEvidence> = emptyList(),

    /** The exact cleanup work still outstanding when the commit record is persisted. */
    public val pendingCleanup: Set<SafCleanupPending> = emptySet(),

    /** True only after app-private staging deletion returned successfully. */
    public val stagingReleased: Boolean = false,
) {
    init {
        require(expectedSizeBytes >= 0L) {
            "expectedSizeBytes must not be negative, was $expectedSizeBytes"
        }
        require(expectedFinalName.isNotBlank()) { "expectedFinalName must not be blank" }
        require(grantId == null || grantId.isNotBlank()) { "grantId must not be blank when present" }
        require(temporaryIdentity == null || temporaryIdentity.documentUri == temporaryUri) {
            "temporary identity uri must match temporaryUri"
        }
        require(finalIdentity == null || finalIdentity.documentUri == finalUri) {
            "final identity uri must match finalUri"
        }
    }

    /** All known URI/id pairs, retained for reconciliation, never authorization by name. */
    public val knownDocumentIdentities: List<SafStoredDocumentIdentity>
        get() = buildList {
            temporaryIdentity?.let { add(it) }
            finalIdentity?.let { add(it) }
            existingIdentity?.let { add(it) }
            backupIdentity?.let { add(it) }
            renameHistory.forEach { addAll(it.knownIdentities) }
        }.distinct()

    /** Exact staging ownership; unlike the state alone, this survives mixed cleanup states. */
    public val ownsStaging: Boolean get() = !stagingReleased

    /** Legacy URI summary only; never sufficient authorization for provider deletion. */
    public val cleanupUri: String? get() = temporaryUri ?: finalUri

    public fun withState(next: SafCommitState): SafCommitRecord = copy(state = next)

    /** Do not leak tree/document URIs, names, ids or digest bytes to diagnostics. */
    override fun toString(): String =
        "SafCommitRecord(state=${state.id}, strategy=${strategy.id}, expectedSizeBytes=$expectedSizeBytes, " +
            "pendingCleanup=${pendingCleanup.map { it.id }.sorted()})"
}

/**
 * The name a temporary document is created under.
 *
 * Unmistakably incomplete to a human — which is the most a name can do — and
 * carrying the opaque partial id so two concurrent transfers of identically
 * named files cannot share a temporary.
 */
public fun temporaryDocumentName(finalName: String, partialId: PartialIdentity): String {
    val body = partialId.value.map { char ->
        if (char.isLetterOrDigit() || char == '-' || char == '_') char else '-'
    }.joinToString("")
    return "$finalName.$body.morsec-part"
}

/**
 * The name the existing document is moved to during a recoverable overwrite.
 *
 * Parameterised by the partial for the same reason the temporary name is: two
 * concurrent transfers of the same filename must not share a backup slot. It is
 * a different suffix from the temporary's so that a directory containing both is
 * readable to whoever has to look at it, and so a cleanup that is handed one
 * cannot be confused about which it holds.
 */
public fun backupDocumentName(finalName: String, partialId: PartialIdentity): String {
    val body = partialId.value.map { char ->
        if (char.isLetterOrDigit() || char == '-' || char == '_') char else '-'
    }.joinToString("")
    return "$finalName.$body.morsec-backup"
}

// ---------------------------------------------------------------------------
// Provider capabilities
// ---------------------------------------------------------------------------

/**
 * What a document provider appears to support.
 *
 * Advisory. Every field may be false on a provider that actually works, and
 * every field may be true on one that throws the moment it is asked — so no
 * operation here is allowed to skip its typed failure handling because a flag
 * said it was supported.
 *
 * Absent flags produce *false* for the capability but set [flagsPresent] to
 * false, which is what lets a caller distinguish "this provider cannot rename"
 * from "this provider did not tell us".
 */
public data class SafProviderCapabilities(
    public val canCreateDocument: Boolean,
    public val canWriteDocument: Boolean,
    public val canDeleteDocument: Boolean,
    public val canRenameDocument: Boolean,
    public val canMoveDocument: Boolean,
    public val supportsSeekableWrites: Boolean,
    public val supportsTruncate: Boolean,
    public val durabilityKnown: Boolean,
    public val canQuery: Boolean,
    /** False when the provider omitted the flags column entirely. */
    public val flagsPresent: Boolean,
) {

    /** Whether the temporary-then-rename strategy can be attempted at all. */
    public val supportsTempThenRename: Boolean get() = canCreateDocument && canRenameDocument

    /** Whether the visible-copy fallback can be attempted at all. */
    public val supportsVisibleFinalCopy: Boolean get() = canCreateDocument && canWriteDocument

    /**
     * The strategy to use, or null when neither is available.
     *
     * Prefers the temporary name, because an interrupted copy then does not
     * leave a short file under the name the user chose.
     */
    public fun preferredStrategy(): SafCommitStrategy? = when {
        supportsTempThenRename -> SafCommitStrategy.TEMP_THEN_RENAME
        supportsVisibleFinalCopy -> SafCommitStrategy.VISIBLE_FINAL_COPY
        else -> null
    }

    public companion object {

        /** Nothing is known. Nothing may be assumed supported. */
        public val UNKNOWN: SafProviderCapabilities = SafProviderCapabilities(
            canCreateDocument = false,
            canWriteDocument = false,
            canDeleteDocument = false,
            canRenameDocument = false,
            canMoveDocument = false,
            supportsSeekableWrites = false,
            supportsTruncate = false,
            durabilityKnown = false,
            canQuery = false,
            flagsPresent = false,
        )

        /**
         * Derives capabilities from `DocumentsContract.Document.COLUMN_FLAGS`.
         *
         * [parentFlags] are the containing directory's (for create) and
         * [documentFlags] an existing document's (for write, rename, delete).
         * Either may be null when the provider omitted the column, in which case
         * the corresponding capabilities are false and [flagsPresent] records
         * that the answer was "did not say" rather than "no".
         */
        public fun fromFlags(parentFlags: Int?, documentFlags: Int?): SafProviderCapabilities {
            val present = parentFlags != null || documentFlags != null
            return SafProviderCapabilities(
                canCreateDocument = parentFlags
                    ?.let { it and DocumentsContractFlags.DIR_SUPPORTS_CREATE != 0 } ?: false,
                canWriteDocument = documentFlags
                    ?.let { it and DocumentsContractFlags.SUPPORTS_WRITE != 0 } ?: false,
                canDeleteDocument = documentFlags
                    ?.let { it and DocumentsContractFlags.SUPPORTS_DELETE != 0 } ?: false,
                canRenameDocument = documentFlags
                    ?.let { it and DocumentsContractFlags.SUPPORTS_RENAME != 0 } ?: false,
                canMoveDocument = documentFlags
                    ?.let { it and DocumentsContractFlags.SUPPORTS_MOVE != 0 } ?: false,
                // Write descriptors come from the provider; whether one can be
                // positioned is a property of the descriptor and is measured, not
                // declared, by the seekability probe.
                supportsSeekableWrites = false,
                supportsTruncate = false,
                // A provider flush is never fsync-grade, so the guarantee is
                // never known regardless of flags. See ADR-0003 §3.
                durabilityKnown = false,
                canQuery = present,
                flagsPresent = present,
            )
        }
    }
}

/**
 * `DocumentsContract.Document` flag values, copied rather than referenced.
 *
 * They are compile-time constants inlined from the platform, so referencing them
 * directly is safe on API 23; naming them once here keeps the capability
 * derivation readable and keeps the Android import out of the rest of this file.
 */
private object DocumentsContractFlags {
    const val SUPPORTS_WRITE = 0x00000002
    const val SUPPORTS_DELETE = 0x00000004
    const val SUPPORTS_RENAME = 0x00000040
    const val SUPPORTS_MOVE = 0x00000100
    const val DIR_SUPPORTS_CREATE = 0x00000008
}

// ---------------------------------------------------------------------------
// Tree validation — the pure half
// ---------------------------------------------------------------------------

/** What resolving a SAF target directory produced. */
public sealed interface SafTargetResolution {
    public data class Resolved(
        public val rootDocumentId: String,
        public val parentDocumentId: String,
        /** The parent's document URI, as a string, ready to be persisted. */
        public val parentUri: String,
    ) : SafTargetResolution

    public data class Rejected(public val error: TransferStorageError) : SafTargetResolution
}

/**
 * Whether a grant for a tree is still held.
 *
 * [SafGrantChecker] is the accepted production contract, but it takes an
 * `android.net.Uri`, which would drag `android.net` into every test of the
 * containment rules. This narrow `String` form keeps those tests ordinary JVM
 * tests, and [fromChecker] adapts the platform-backed checker for production.
 */
public fun interface SafGrantProbe {
    public fun isGranted(treeUri: String): Boolean

    public companion object {
        public fun fromChecker(checker: SafGrantChecker): SafGrantProbe =
            SafGrantProbe { treeUri -> checker.isReadGranted(treeUri.toUri()) }
    }
}

/**
 * Validates a SAF target directory against the persisted grants, using nothing
 * but the accepted `SafPaths` arithmetic.
 *
 * Pure on purpose. Every rejection below has to be testable without a provider,
 * because the containment rules are the part that must not differ between
 * providers, and a `content://` URI proves nothing: it can arrive from an
 * intent, from a row an older version wrote, or from a peer.
 */
public object SafDestinationTree {

    /**
     * Resolves [targetDocumentId] inside the granted tree [treeUri].
     *
     * A null or blank target means the granted root itself, which is a valid
     * destination and is the common case.
     */
    public fun resolve(
        grants: List<SafGrant>,
        grantProbe: SafGrantProbe,
        treeUri: String,
        targetDocumentId: String? = null,
        requireWrite: Boolean = true,
    ): SafTargetResolution {
        val grant = grants.firstOrNull { it.treeUri == treeUri }
            ?: return SafTargetResolution.Rejected(
                TransferStorageError.Unsupported("saf_outside_tree"),
            )

        // A grant row records what was granted; the probe records what is still
        // held. Both are needed: a stale row without a live grant is a revocation.
        if (requireWrite) {
            if (!grant.readWrite) {
                return SafTargetResolution.Rejected(
                    TransferStorageError.PermissionRevoked("write"),
                )
            }
            if (!grantProbe.isGranted(treeUri)) {
                return SafTargetResolution.Rejected(
                    TransferStorageError.PermissionRevoked("write"),
                )
            }
        }

        val rootDocumentId = SafPaths.rootDocumentIdOf(treeUri)
            ?: return SafTargetResolution.Rejected(
                TransferStorageError.Unsupported("saf_document_uri"),
            )

        val target = targetDocumentId?.takeIf { it.isNotBlank() } ?: rootDocumentId

        // Canonicalise once, then validate. An earlier rule here rejected any id
        // containing an encoded dot or slash, which was too broad: a document id
        // is one percent-encoded URI segment, so primary:Download%2F2026 carries
        // a legitimate %2F and ordinary Downloads destinations broke on it.
        // SafDocumentIdRules now owns this, and the canonical form is what gets
        // resolved below, so an encoded slash and a literal slash name the same
        // place.
        val canonical = when (val check = SafDocumentIdRules.validate(target)) {
            is SafDocumentIdCheck.Valid -> check.canonical
            is SafDocumentIdCheck.Invalid -> return SafTargetResolution.Rejected(
                TransferStorageError.Unsupported(
                    "saf_document_uri",
                    diagnostic = check.violation.id,
                ),
            )
        }

        // SafPaths rejects blank ids, NULs, '.' and '..' segments, and anything
        // that is not under the granted root.

        val resolved = when (val resolution = SafPaths.resolve(target, treeUri)) {
            is PathResolution.Inside -> resolution.documentId
            PathResolution.Outside -> return SafTargetResolution.Rejected(
                TransferStorageError.Unsupported(
                    "saf_outside_tree",
                    diagnostic = "target resolves outside the granted tree",
                ),
            )

            PathResolution.Malformed -> return SafTargetResolution.Rejected(
                TransferStorageError.Unsupported(
                    "saf_document_uri",
                    diagnostic = "target is malformed or contains a traversal",
                ),
            )
        }

        val parentUri = SafPaths.uriFor(treeUri, resolved)
            ?: return SafTargetResolution.Rejected(
                TransferStorageError.Unsupported("saf_document_uri"),
            )

        return SafTargetResolution.Resolved(
            rootDocumentId = rootDocumentId,
            parentDocumentId = resolved,
            parentUri = parentUri,
        )
    }

    /**
     * Whether [documentId] is a well-formed descendant of the granted root.
     *
     * Used to check a URI a previous session recorded before trusting it again,
     * which is the same rule as for a URI that has just arrived.
     */
    public fun isInsideGrantedTree(treeUri: String, documentId: String?): Boolean {
        if (documentId.isNullOrBlank()) return true
        val root = SafPaths.rootDocumentIdOf(treeUri) ?: return false
        val canonical = when (val check = SafDocumentIdRules.validate(documentId)) {
            is SafDocumentIdCheck.Valid -> check.canonical
            is SafDocumentIdCheck.Invalid -> return false
        }
        return SafPaths.resolve(canonical, treeUri) is PathResolution.Inside &&
            SafDocumentIdRules.contains(root, canonical)
    }
}
