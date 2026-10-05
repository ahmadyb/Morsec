package app.morsecode.core.storage.transfer

import androidx.core.net.toUri
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId

/*
 * Identity: not "is this place inside the grant" but "is this the document I
 * made".
 *
 * Containment and identity are different questions, and the second one cannot
 * be answered by looking at a name. A provider is free to hand back a different
 * name because of a collision, a user can rename a file, a cloud-sync client can
 * append " (1)", and a second transfer of a file with the same name would
 * collide with the first. Locating a document again by searching for its name is
 * how one transfer ends up deleting another's work.
 *
 * So once a document has been created, the SAF layer records exactly what came
 * back — the grant, the tree, the parent, the document URI, the document id,
 * and the transfer and commit it belongs to — and every later operation is
 * checked against that record. Nothing is located by name, ever.
 *
 * This matters most on API 23-25, where GrantScopedCanonical is enough to
 * construct and create a new child but can say nothing about a document that
 * already exists. After creation the application has a stronger fact available:
 * it made this document, and it knows exactly which one.
 */

/** A provider-issued URI and document id that were recorded together. */
public data class SafStoredDocumentIdentity(
    public val documentUri: String,
    public val documentId: String,
) {
    init {
        require(documentUri.isNotBlank()) { "documentUri must not be blank" }
        require(documentId.isNotBlank()) { "documentId must not be blank" }
    }

    /** Keep provider identities out of accidental diagnostic strings. */
    override fun toString(): String = "SafStoredDocumentIdentity([redacted])"
}

/** Rename operations performed by a SAF commit, in their only legal order. */
public enum class SafRenamePhase(public val id: String, public val order: Int) {
    BACKUP_RENAME("backup_rename", 0),
    FINAL_PROMOTION("final_promotion", 1),
}

/** Immutable commit scope attached to each persisted rename-evidence entry. */
public data class SafRenameScope(
    public val grantId: String,
    public val treeUri: String,
    public val authority: String,
    public val rootDocumentId: String,
    public val parentDocumentId: String,
    public val sessionId: SessionId,
    public val transferId: TransferId,
    public val commitId: PartialIdentity,
) {
    init {
        require(grantId.isNotBlank()) { "grant id must not be blank" }
        require(treeUri.isNotBlank()) { "tree uri must not be blank" }
        require(authority.isNotBlank()) { "authority must not be blank" }
        require(rootDocumentId.isNotBlank()) { "root document id must not be blank" }
        require(parentDocumentId.isNotBlank()) { "parent document id must not be blank" }
    }

    override fun toString(): String = "SafRenameScope([redacted])"

    public companion object {
        public fun fromRecord(record: SafCommitRecord, grant: SafTreeGrant): SafRenameScope = SafRenameScope(
            grantId = grant.grantId,
            treeUri = record.treeUri,
            authority = grant.authority,
            rootDocumentId = record.rootDocumentId,
            parentDocumentId = record.parentDocumentId,
            sessionId = record.sessionId,
            transferId = record.transferId,
            commitId = record.partialId,
        )
    }
}

/** Identity evidence retained when a rename is reconciled after a restart. */
public data class SafRenameEvidence(
    public val before: SafStoredDocumentIdentity,
    public val returned: SafStoredDocumentIdentity?,
    /** Null means the provider state was not fully observed. */
    public val reconciliation: SafRenameReconciliation? = null,
    /** Required for durable history; absent only on the pure resolver's transient value. */
    public val scope: SafRenameScope? = null,
    /** Required for durable history; records which mutation this evidence settles. */
    public val phase: SafRenamePhase? = null,
    /** Zero-based append order in the commit's bounded history. */
    public val sequence: Int? = null,
) {
    init {
        require((scope == null) == (phase == null) && (phase == null) == (sequence == null)) {
            "rename scope, phase and sequence must be present together"
        }
        require(sequence == null || sequence >= 0) { "rename sequence must not be negative" }
    }

    public val knownIdentities: List<SafStoredDocumentIdentity>
        get() = listOfNotNull(before, returned).distinct()

    override fun toString(): String =
        "SafRenameEvidence(phase=${phase?.id}, sequence=$sequence, [redacted])"
}

/** Bounds and validates persisted rename evidence without consulting a provider. */
public object SafRenameHistoryPolicy {
    /** One optional backup move followed by one final promotion. */
    public const val MAX_ENTRIES: Int = 2

    public fun isWellFormed(
        history: List<SafRenameEvidence>,
        expectedScope: SafRenameScope,
    ): Boolean {
        if (history.size > MAX_ENTRIES || !scopeIsWellFormed(expectedScope)) return false
        val phases = mutableSetOf<SafRenamePhase>()
        val entries = mutableSetOf<Triple<SafRenamePhase, SafStoredDocumentIdentity, SafStoredDocumentIdentity?>>()
        val edges = mutableMapOf<SafStoredDocumentIdentity, SafStoredDocumentIdentity>()
        var priorPhaseOrder = -1

        history.forEachIndexed { index, evidence ->
            val phase = evidence.phase ?: return false
            if (evidence.scope != expectedScope || evidence.sequence != index) return false
            if (phase.order < priorPhaseOrder || !phases.add(phase)) return false
            priorPhaseOrder = phase.order
            if (!identityIsWellFormed(evidence.before, expectedScope) ||
                evidence.returned?.let { !identityIsWellFormed(it, expectedScope) } == true
            ) {
                return false
            }
            if (!entries.add(Triple(phase, evidence.before, evidence.returned))) return false
            when (evidence.reconciliation) {
                SafRenameReconciliation.RESOLVED_TO_RETURNED -> if (evidence.returned == null) return false
                SafRenameReconciliation.RESOLVED_TO_ORIGINAL -> if (evidence.returned != evidence.before) return false
                SafRenameReconciliation.NULL_RETURN -> if (evidence.returned != null) return false
                else -> Unit
            }

            val returned = evidence.returned
            if (returned != null && returned != evidence.before) {
                if (edges.containsKey(evidence.before)) return false
                var cursor: SafStoredDocumentIdentity? = returned
                val visited = mutableSetOf<SafStoredDocumentIdentity>()
                while (cursor != null && visited.add(cursor)) {
                    if (cursor == evidence.before) return false
                    cursor = edges[cursor]
                }
                edges[evidence.before] = returned
            }
        }
        return true
    }

    private fun scopeIsWellFormed(scope: SafRenameScope): Boolean {
        val strictTreeUri = runCatching { java.net.URI(scope.treeUri) }.getOrNull() ?: return false
        val treeUri = scope.treeUri.toUri()
        return strictTreeUri.scheme == "content" &&
            strictTreeUri.rawAuthority == scope.authority &&
            strictTreeUri.rawQuery == null &&
            strictTreeUri.rawFragment == null &&
            treeUri.scheme == "content" &&
            treeUri.authority == scope.authority &&
            SafContainment.treeDocumentIdOf(treeUri) == scope.rootDocumentId &&
            SafDocumentIdRules.validate(scope.rootDocumentId) is SafDocumentIdCheck.Valid &&
            SafDocumentIdRules.validate(scope.parentDocumentId) is SafDocumentIdCheck.Valid
    }

    private fun identityIsWellFormed(
        identity: SafStoredDocumentIdentity,
        scope: SafRenameScope,
    ): Boolean {
        val strictUri = runCatching { java.net.URI(identity.documentUri) }.getOrNull() ?: return false
        val uri = identity.documentUri.toUri()
        val parsedId = SafContainment.documentIdOf(uri) ?: return false
        return strictUri.scheme == "content" &&
            strictUri.rawAuthority == scope.authority &&
            strictUri.rawQuery == null &&
            strictUri.rawFragment == null &&
            uri.scheme == "content" &&
            uri.authority == scope.authority &&
            SafContainment.treeDocumentIdOf(uri) == scope.rootDocumentId &&
            parsedId == identity.documentId &&
            SafDocumentIdRules.validate(identity.documentId) is SafDocumentIdCheck.Valid
    }
}

/**
 * A document this application created, recorded exactly as the provider
 * reported it. Room persistence is a later layer; SAF makes the identity
 * decision here without treating containment as identity.
 */
public data class InternallyCreatedDocument(
    public val grantId: String,
    public val treeUri: String,
    public val authority: String,
    public val parentDocumentId: String,
    public val documentUri: String,
    public val documentId: String,
    public val transferId: TransferId,
    public val commitId: PartialIdentity,
) {
    init {
        require(grantId.isNotBlank()) { "grantId must not be blank" }
        require(treeUri.isNotBlank()) { "treeUri must not be blank" }
        require(authority.isNotBlank()) { "authority must not be blank" }
        require(documentUri.isNotBlank()) { "documentUri must not be blank" }
        require(documentId.isNotBlank()) { "documentId must not be blank" }
    }
}

/** What a caller must present to act on a recorded document. */
public data class SafOperationContext(
    public val grant: SafTreeGrant,
    public val transferId: TransferId,
    public val commitId: PartialIdentity,
)

/**
 * Whether a recorded identity authorises an operation.
 *
 * Every field is checked, because each one rules out a different accident:
 * the grant and tree rule out acting under a different grant, the authority
 * rules out a different provider, and the transfer and commit ids rule out one
 * transfer touching another's document.
 */
public object SafDocumentIdentityPolicy {

    public fun decide(
        identity: InternallyCreatedDocument,
        context: SafOperationContext,
        operation: SafContainmentOperation,
        /** What the caller wants to act on. Null means the stored identity itself. */
        presentedDocumentUri: String? = null,
        /** Whether the recorded commit state authorises the operation. */
        commitStateAllows: Boolean = true,
    ): SafContainmentDecision {
        // A grant that is no longer held authorises nothing, and that is not
        // the same thing as a mismatch.
        if (!context.grant.writable) {
            return SafContainmentDecision.FORBIDDEN_NO_WRITE
        }

        if (identity.grantId != context.grant.grantId) return SafContainmentDecision.REJECTED
        if (identity.authority != context.grant.authority) return SafContainmentDecision.REJECTED
        if (identity.treeUri != context.grant.treeUri.toString()) {
            return SafContainmentDecision.REJECTED
        }
        if (identity.transferId != context.transferId) return SafContainmentDecision.REJECTED
        if (identity.commitId != context.commitId) return SafContainmentDecision.REJECTED
        if (!commitStateAllows) return SafContainmentDecision.REJECTED

        if (presentedDocumentUri != null) {
            // Exact match or nothing. A document found by searching for the
            // same name is a different document until the id says otherwise.
            if (presentedDocumentUri != identity.documentUri) {
                return SafContainmentDecision.REJECTED
            }
            if (presentedDocumentUri.toUri().authority != identity.authority) {
                return SafContainmentDecision.REJECTED
            }
            // The id inside the presented uri must be the recorded one. A uri
            // that looks right but names a different document is a different
            // document: this is what stops a stale record matching a
            // replacement that happens to sit at a similar-looking uri.
            val presentedId = SafContainment.documentIdOf(presentedDocumentUri.toUri())
            if (presentedId == null || presentedId != identity.documentId) {
                return SafContainmentDecision.REJECTED
            }
        }

        // Deletion demands the exact stored identity, named explicitly. There
        // is no deletion by containment, by name, or by search result.
        if (operation == SafContainmentOperation.DELETE_TEMPORARY && presentedDocumentUri == null) {
            return SafContainmentDecision.REJECTED
        }

        return SafContainmentDecision.ALLOWED
    }

    /**
     * Re-records an identity after the provider returned a new identity.
     *
     * Returns null while the rename is unresolved, because there is no honest
     * identity to record yet.
     */
    public fun afterRename(
        identity: InternallyCreatedDocument,
        resolved: SafRenameIdentity,
    ): InternallyCreatedDocument? {
        val uri = resolved.authoritativeDocumentUri ?: return null
        val id = resolved.authoritativeDocumentId ?: return null
        return identity.copy(documentUri = uri, documentId = id)
    }
}

// ---------------------------------------------------------------------------
// Rename: the provider may hand back a different identity
// ---------------------------------------------------------------------------

/**
 * What a rename left behind.
 *
 * A rename is not atomic and not guaranteed to preserve identity: a provider
 * may return a new URI, the same URI, or nothing at all. Each of those is a
 * different fact and needs a different next step.
 */
public enum class SafRenameReconciliation(public val id: String) {

    /** Only the returned identity resolves. It becomes authoritative. */
    RESOLVED_TO_RETURNED("resolved_to_returned"),

    /** The rename returned the same identity, and it resolves. */
    RESOLVED_TO_ORIGINAL("resolved_to_original"),

    /** Both identities resolve. Nothing is known about what the rename did. */
    AMBIGUOUS_BOTH_RESOLVE("ambiguous_both_resolve"),

    /** The returned identity does not resolve, but the original still does. */
    RETURNED_UNRESOLVED("returned_unresolved"),

    /** Neither identity resolves. */
    NEITHER_RESOLVES("neither_resolves"),

    /** The provider returned no identity at all. */
    NULL_RETURN("null_return"),
    ;

    public val isResolved: Boolean
        get() = this == RESOLVED_TO_RETURNED || this == RESOLVED_TO_ORIGINAL

    public val requiresReconciliation: Boolean get() = !isResolved
}

/**
 * The identities a rename involves, and which one is authoritative.
 *
 * Both are preserved while unresolved, because the next pass will need to ask
 * the provider which of them actually exists rather than guessing.
 */
public data class SafRenameIdentity(
    public val beforeDocumentUri: String,
    public val beforeDocumentId: String,
    public val renameRequested: Boolean,
    public val returnedDocumentUri: String?,
    public val returnedDocumentId: String?,
    public val reconciliation: SafRenameReconciliation,
) {

    /**
     * The identity to use from now on.
     *
     * Null while reconciliation is required. Callers must not fall back to the
     * old identity when this is null: that is precisely the case where the old
     * identity is no longer known to mean anything.
     */
    public val authoritativeDocumentUri: String?
        get() = when (reconciliation) {
            SafRenameReconciliation.RESOLVED_TO_RETURNED -> returnedDocumentUri
            SafRenameReconciliation.RESOLVED_TO_ORIGINAL -> beforeDocumentUri
            else -> null
        }

    public val authoritativeDocumentId: String?
        get() = when (reconciliation) {
            SafRenameReconciliation.RESOLVED_TO_RETURNED -> returnedDocumentId
            SafRenameReconciliation.RESOLVED_TO_ORIGINAL -> beforeDocumentId
            else -> null
        }

    /** Every identity known, so a later pass can disambiguate. */
    public val knownUris: List<String>
        get() = listOfNotNull(beforeDocumentUri, returnedDocumentUri).distinct()

    /** URI and id pairs retained without substituting one identity for another. */
    public val evidence: SafRenameEvidence
        get() = SafRenameEvidence(
            before = SafStoredDocumentIdentity(beforeDocumentUri, beforeDocumentId),
            returned = returnedDocumentUri
                ?.takeIf { it.isNotBlank() }
                ?.let { uri ->
                    returnedDocumentId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { id -> SafStoredDocumentIdentity(uri, id) }
                },
            reconciliation = reconciliation,
        )

    public val requiresReconciliation: Boolean get() = reconciliation.requiresReconciliation

    override fun toString(): String =
        "SafRenameIdentity(reconciliation=${reconciliation.id}, knownIdentityCount=${knownUris.size})"
}

/**
 * Decides what a rename left behind, from what the provider returned and what
 * still resolves.
 *
 * Pure: the two `resolves` flags come from provider queries the caller has
 * already made, so this is testable without a provider at all.
 */
public object SafRenameIdentityResolver {

    public fun resolve(
        beforeDocumentUri: String,
        beforeDocumentId: String,
        returnedDocumentUri: String?,
        returnedDocumentId: String?,
        originalStillResolves: Boolean,
        returnedResolves: Boolean,
    ): SafRenameIdentity {
        val outcome = when {
            // The provider gave nothing back. Nothing is known.
            returnedDocumentUri == null -> SafRenameReconciliation.NULL_RETURN

            // The rename reported the same identity back.
            returnedDocumentUri == beforeDocumentUri -> if (originalStillResolves) {
                SafRenameReconciliation.RESOLVED_TO_ORIGINAL
            } else {
                SafRenameReconciliation.NEITHER_RESOLVES
            }

            // A genuinely new identity, and the old one is gone.
            returnedResolves && !originalStillResolves -> SafRenameReconciliation.RESOLVED_TO_RETURNED

            // Both exist. The rename may have copied rather than moved, or the
            // provider may be mid-operation. Not ours to guess.
            returnedResolves && originalStillResolves -> SafRenameReconciliation.AMBIGUOUS_BOTH_RESOLVE

            // The new identity does not exist but the old one does, so the
            // rename probably did not happen. Still not a certainty.
            originalStillResolves -> SafRenameReconciliation.RETURNED_UNRESOLVED

            else -> SafRenameReconciliation.NEITHER_RESOLVES
        }

        return SafRenameIdentity(
            beforeDocumentUri = beforeDocumentUri,
            beforeDocumentId = beforeDocumentId,
            renameRequested = true,
            returnedDocumentUri = returnedDocumentUri,
            returnedDocumentId = returnedDocumentId,
            reconciliation = outcome,
        )
    }
}
