package app.morsecode.core.storage.transfer

import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri

/*
 * Containment: proving that a write target is inside the tree the user granted.
 *
 * The result of a containment check is not a boolean. How the answer was
 * arrived at is part of the answer, because the commit policy downstream has to
 * be able to insist on provider-backed proof for a write, and has to refuse
 * entirely when the application cannot prove anything. Collapsing
 * "the provider confirmed this is a child", "the provider confirmed this path"
 * and "the id looks like it is under the root" into one `Inside` loses exactly
 * the distinction that decision needs.
 *
 * Three layers, and the order matters.
 *
 *   A. Parse with the platform, never by hand. DocumentsContract knows how a
 *      tree URI splits into a root id and a document id; a regex over the raw
 *      string does not.
 *   B. Canonicalise exactly once, then validate. Decoding repeatedly until no
 *      percent signs remain is refused here, because a second decode pass can
 *      turn a legitimate identifier into something else entirely.
 *   C. Ask the provider, at the strongest level the platform offers.
 *
 * The API-level fact that shapes layer C: `DocumentsContract.isChildDocument`
 * is API 29 and `findDocumentPath` is API 26. Below 26 the platform cannot ask
 * a provider anything about descent, so the only evidence available is that the
 * application built the destination itself out of the approved grant. That is
 * real evidence, and it is recorded as [SafContainmentEvidence.GrantScopedCanonical]
 * rather than being dressed up as provider proof. It is never available for an
 * arbitrary URI that arrived from outside.
 */

// ---------------------------------------------------------------------------
// Layer B — canonical validation
// ---------------------------------------------------------------------------

/** Why a document id or a relative segment was refused. */
public enum class SafDocumentIdViolation(public val id: String) {
    BLANK("blank"),
    NUL("nul"),
    BACKSLASH("backslash"),
    ABSOLUTE_FILESYSTEM("absolute_filesystem"),
    EMPTY_SEGMENT("empty_segment"),
    DOT_SEGMENT("dot_segment"),
    ENCODED_TRAVERSAL("encoded_traversal"),
    DOUBLE_ENCODED_TRAVERSAL("double_encoded_traversal"),

    /** A caller-supplied relative segment that is not one segment. */
    SEGMENT_SEPARATOR("segment_separator"),
}

/** The outcome of canonicalising and checking one document id. */
public sealed interface SafDocumentIdCheck {
    public data class Valid(public val canonical: String) : SafDocumentIdCheck
    public data class Invalid(public val violation: SafDocumentIdViolation) : SafDocumentIdCheck
}

/** The outcome of checking one caller-supplied relative segment. */
public sealed interface SafSegmentCheck {
    public data class Valid(public val canonical: String) : SafSegmentCheck
    public data class Invalid(public val violation: SafDocumentIdViolation) : SafSegmentCheck
}

/**
 * Canonical validation of SAF document ids and relative segments. Pure, and
 * deliberately free of `android.net`, so the rules are ordinary JVM tests.
 *
 * A document id uses `/` as its own separator — `primary:Download/2026/oct` is
 * one id naming a nested folder — so a slash is not suspicious in an id. What
 * is suspicious is a segment that is `.` or `..`, a backslash standing in for a
 * separator, an absolute filesystem path, or percent-encoding that hides any of
 * those.
 *
 * A caller-supplied *relative segment* is stricter: it must be one segment, so
 * even an encoded separator is refused there. The application builds
 * destinations segment by segment; a caller does not get to smuggle a path in.
 */
public object SafDocumentIdRules {

    private val HEX2 = Regex("[0-9A-Fa-f]{2}")

    /** Windows-style drive prefix. A single letter only: `primary:` is a volume. */
    private val DRIVE = Regex("^[A-Za-z]:[\\\\/]")

    /**
     * Decodes percent-escapes **once**.
     *
     * Anything that is not a well-formed `%XX` escape is left exactly as it was,
     * including a bare `%`. That is what makes a file genuinely named `100%.txt`
     * survive: `100%.txt` decodes to itself, and `100%25.txt` decodes to
     * `100%.txt`. Neither is treated as an escape attempt.
     */
    public fun decodeOnce(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val hex = value.substring(index + 1, index + 3)
                if (HEX2.matches(hex)) {
                    out.append(hex.toInt(16).toChar())
                    index += 3
                    continue
                }
            }
            out.append(char)
            index++
        }
        return out.toString()
    }

    /**
     * Canonicalises [documentId] once and checks it.
     *
     * The returned canonical form is what every later comparison must use, so
     * that `primary:Download%2F2026` and `primary:Download/2026` are recognised
     * as naming the same place rather than one being rejected for having a
     * slash the other lacks.
     */
    public fun validate(documentId: String): SafDocumentIdCheck {
        if (documentId.isEmpty()) {
            return SafDocumentIdCheck.Invalid(SafDocumentIdViolation.BLANK)
        }
        if (documentId.indexOf('\u0000') >= 0) {
            return SafDocumentIdCheck.Invalid(SafDocumentIdViolation.NUL)
        }
        // A backslash is a separator to Windows and to some providers; refusing
        // it outright is cheaper than reasoning about every reading of it.
        if (documentId.indexOf('\\') >= 0) {
            return SafDocumentIdCheck.Invalid(SafDocumentIdViolation.BACKSLASH)
        }
        if (documentId.startsWith('/') || DRIVE.containsMatchIn(documentId)) {
            return SafDocumentIdCheck.Invalid(SafDocumentIdViolation.ABSOLUTE_FILESYSTEM)
        }

        for (segment in documentId.split('/')) {
            if (segment.isEmpty()) {
                return SafDocumentIdCheck.Invalid(SafDocumentIdViolation.EMPTY_SEGMENT)
            }
            if (segment == "." || segment == "..") {
                return SafDocumentIdCheck.Invalid(SafDocumentIdViolation.DOT_SEGMENT)
            }
        }

        val canonical = decodeOnce(documentId)

        // One decode. If that reveals a traversal, the id was hiding one.
        if (canonical != documentId && hasTraversal(canonical)) {
            return SafDocumentIdCheck.Invalid(SafDocumentIdViolation.ENCODED_TRAVERSAL)
        }

        // A second decode is never performed on the value used for comparison,
        // but it is inspected: if a downstream consumer decodes again and lands
        // on a traversal, the id is refused here instead.
        val twice = decodeOnce(canonical)
        if (twice != canonical && hasTraversal(twice)) {
            return SafDocumentIdCheck.Invalid(SafDocumentIdViolation.DOUBLE_ENCODED_TRAVERSAL)
        }

        return SafDocumentIdCheck.Valid(canonical)
    }

    /**
     * Checks one caller-supplied relative segment.
     *
     * Stricter than [validate]: a relative segment is one segment, so a
     * separator is refused whether it arrives literally or encoded. This is
     * what stops a caller passing `a/b` or `a%2Fb` where one name was asked for.
     */
    public fun validateSegment(segment: String): SafSegmentCheck {
        if (segment.isEmpty()) {
            return SafSegmentCheck.Invalid(SafDocumentIdViolation.BLANK)
        }
        if (segment.indexOf('\u0000') >= 0) {
            return SafSegmentCheck.Invalid(SafDocumentIdViolation.NUL)
        }
        if (segment.indexOf('/') >= 0) {
            return SafSegmentCheck.Invalid(SafDocumentIdViolation.SEGMENT_SEPARATOR)
        }
        if (segment.indexOf('\\') >= 0) {
            return SafSegmentCheck.Invalid(SafDocumentIdViolation.BACKSLASH)
        }
        if (segment == "." || segment == "..") {
            return SafSegmentCheck.Invalid(SafDocumentIdViolation.DOT_SEGMENT)
        }

        val canonical = decodeOnce(segment)
        if (canonical != segment && (hasTraversal(canonical) || canonical.indexOf('/') >= 0)) {
            return SafSegmentCheck.Invalid(SafDocumentIdViolation.ENCODED_TRAVERSAL)
        }
        val twice = decodeOnce(canonical)
        if (twice != canonical && hasTraversal(twice)) {
            return SafSegmentCheck.Invalid(SafDocumentIdViolation.DOUBLE_ENCODED_TRAVERSAL)
        }
        return SafSegmentCheck.Valid(canonical)
    }

    /**
     * Whether [root] contains [target], compared segment by segment.
     *
     * Segment comparison, not an unbounded string prefix: `primary:Download` is
     * a prefix of `primary:Downloads` and of `primary:DownloadBackup`, and
     * neither is inside it.
     */
    public fun isWithinOnSegmentBoundary(root: String, target: String): Boolean {
        if (root.isEmpty() || target.isEmpty()) return false
        val rootSegments = root.split('/')
        val targetSegments = target.split('/')
        if (targetSegments.size < rootSegments.size) return false
        for (index in rootSegments.indices) {
            if (rootSegments[index] != targetSegments[index]) return false
        }
        return true
    }

    /** Convenience for callers holding raw strings; validates both first. */
    public fun contains(rootDocumentId: String, targetDocumentId: String): Boolean {
        val root = validate(rootDocumentId) as? SafDocumentIdCheck.Valid ?: return false
        val target = validate(targetDocumentId) as? SafDocumentIdCheck.Valid ?: return false
        return isWithinOnSegmentBoundary(root.canonical, target.canonical)
    }

    private fun hasTraversal(value: String): Boolean {
        if (value.indexOf('\\') >= 0) return true
        if (value.indexOf('\u0000') >= 0) return true
        for (segment in value.split('/')) {
            if (segment == "." || segment == "..") return true
        }
        return false
    }
}

// ---------------------------------------------------------------------------
// Layer C — what the provider can be asked, per platform level
// ---------------------------------------------------------------------------

/** Which descendant API the platform offers. */
public enum class SafContainmentTier(public val id: String) {
    /** `DocumentsContract.isChildDocument`, API 29 and above. */
    CHILD_DOCUMENT("child_document"),

    /** `DocumentsContract.findDocumentPath`, API 26 to 28. */
    DOCUMENT_PATH("document_path"),

    /** Nothing. API 23 to 25. */
    CANONICAL_ONLY("canonical_only"),
    ;

    public companion object {
        public fun forSdk(sdkInt: Int): SafContainmentTier = when {
            sdkInt >= 29 -> CHILD_DOCUMENT
            sdkInt >= 26 -> DOCUMENT_PATH
            else -> CANONICAL_ONLY
        }
    }
}

/** What the provider said when asked whether one document contains another. */
public sealed interface SafChildAnswer {
    public data class Answered(public val isChild: Boolean) : SafChildAnswer

    /** The provider declined to answer, or the call failed. */
    public data object Indeterminate : SafChildAnswer

    /** The grant is gone. Not the same as "not a child". */
    public data object Revoked : SafChildAnswer
}

/** What the provider said when asked for the path down to a document. */
public sealed interface SafPathAnswer {
    /**
     * The provider described a path. [segments] runs root-first and the last
     * entry is the document itself; [rootId] is the provider's own idea of the
     * root, which may be null when it did not say.
     */
    public data class Resolved(
        public val rootId: String?,
        public val segments: List<String>,
    ) : SafPathAnswer

    public data object Indeterminate : SafPathAnswer

    public data object Revoked : SafPathAnswer
}

/**
 * The two provider calls layer C needs.
 *
 * Narrow enough to fake, and the only place the API-level split between
 * `isChildDocument` (29+) and `findDocumentPath` (26+) is allowed to live.
 */
public interface SafContainmentProver {
    public fun isChildDocument(parentDocumentUri: Uri, childDocumentUri: Uri): SafChildAnswer
    public fun documentPath(documentUri: Uri): SafPathAnswer
}

// ---------------------------------------------------------------------------
// Evidence
// ---------------------------------------------------------------------------

/**
 * The result of a containment check, including how it was established.
 *
 * The three positive levels are deliberately not collapsed into one `Inside`:
 * commit policy treats them differently, and a caller that cannot tell them
 * apart cannot make a sound decision about whether to write.
 */
public sealed interface SafContainmentEvidence {

    public val rootDocumentId: String?
    public val targetDocumentId: String?
    public val targetUri: String?

    /** True only when the target is proven or constructed to be in the tree. */
    public val isConfined: Boolean

    /** True when the provider itself, not the application, established this. */
    public val isProviderBacked: Boolean

    /**
     * The provider confirmed the target is a descendant of the granted root.
     *
     * `DocumentsContract.isChildDocument` returned true. The strongest
     * evidence available.
     */
    public data class ProviderConfirmedChild(
        override val rootDocumentId: String,
        override val targetDocumentId: String,
        override val targetUri: String,
    ) : SafContainmentEvidence {
        override val isConfined: Boolean get() = true
        override val isProviderBacked: Boolean get() = true
    }

    /**
     * The provider returned a path from the approved root to the target, and
     * every step of it was checked.
     *
     * `DocumentsContract.findDocumentPath` answered, and: the path was
     * non-empty; its first entry is the approved root; its last entry is the
     * target; each entry is a well-formed document id; and each entry is
     * within its predecessor on a segment boundary. A provider that reports a
     * root id and it disagrees with the approved root is treated as
     * inconsistent, not as a contradiction to be silently overridden.
     */
    public data class ProviderConfirmedPath(
        override val rootDocumentId: String,
        override val targetDocumentId: String,
        override val targetUri: String,
        public val segments: List<String>,
    ) : SafContainmentEvidence {
        override val isConfined: Boolean get() = true
        override val isProviderBacked: Boolean get() = true
    }

    /**
     * The application built the destination itself from the persisted grant,
     * on a platform too old to ask the provider anything.
     *
     * Not provider-backed. Available only when every one of these holds: the
     * persisted grant is valid and writable; the target was constructed from
     * that grant rather than supplied as a URI; each relative segment passed
     * canonical validation; the resulting id matches the root on a segment
     * boundary; the built URI carries the same authority and tree identity; and
     * no provider result contradicted containment.
     *
     * An arbitrary URI can never earn this, on any platform level.
     */
    public data class GrantScopedCanonical(
        override val rootDocumentId: String,
        override val targetDocumentId: String,
        override val targetUri: String,
    ) : SafContainmentEvidence {
        // Confined: the application built it under the grant and every
        // condition above was checked. Not provider-backed: the platform on
        // this level cannot ask.
        override val isConfined: Boolean get() = true
        override val isProviderBacked: Boolean get() = false
    }

    /** Well-formed, but the provider or the naming says it is not in the tree. */
    public data class Outside(
        override val rootDocumentId: String?,
        override val targetDocumentId: String?,
        override val targetUri: String?,
    ) : SafContainmentEvidence {
        override val isConfined: Boolean get() = false
        override val isProviderBacked: Boolean get() = false
    }

    /**
     * The application could not prove where the target is.
     *
     * Never treated as confined, and never a licence to try the write anyway.
     * This is the answer when a provider declines, when a provider fails, and
     * when an arbitrary URI arrives on a platform that cannot ask.
     */
    public data class Unknown(
        override val rootDocumentId: String?,
        override val targetDocumentId: String?,
        override val targetUri: String?,
        public val reason: TransferStorageError,
    ) : SafContainmentEvidence {
        override val isConfined: Boolean get() = false
        override val isProviderBacked: Boolean get() = false
    }

    /** The grant was revoked. Its own outcome, not a generic failure. */
    public data class PermissionRevoked(
        override val rootDocumentId: String?,
        override val targetDocumentId: String?,
        override val targetUri: String?,
        public val error: TransferStorageError,
    ) : SafContainmentEvidence {
        override val isConfined: Boolean get() = false
        override val isProviderBacked: Boolean get() = false
    }

    /** Not something this application can interpret. */
    public data class Malformed(
        override val rootDocumentId: String?,
        override val targetDocumentId: String?,
        override val targetUri: String?,
        public val violation: SafDocumentIdViolation,
    ) : SafContainmentEvidence {
        override val isConfined: Boolean get() = false
        override val isProviderBacked: Boolean get() = false
    }
}

// ---------------------------------------------------------------------------
// The approved grant
// ---------------------------------------------------------------------------

/**
 * A persisted, currently-held tree grant that containment measures against.
 *
 * [authority] and [treeUri] are carried because the same document id under a
 * different authority, or under a different tree grant, is not the same
 * destination and must not be treated as one.
 */
public data class SafTreeGrant(
    public val treeUri: Uri,
    public val rootDocumentId: String,
    public val authority: String,
    public val writable: Boolean,
)

// ---------------------------------------------------------------------------
// Layer A + the three layers together
// ---------------------------------------------------------------------------

/** Platform parsing helpers. */
public object SafContainment {

    /** The granted root's document id, parsed by the platform. API 21+. */
    public fun treeDocumentIdOf(treeUri: Uri): String? = runCatching {
        DocumentsContract.getTreeDocumentId(treeUri)
    }.getOrNull()

    /** A document's id, parsed by the platform. API 19+. */
    public fun documentIdOf(documentUri: Uri): String? = runCatching {
        DocumentsContract.getDocumentId(documentUri)
    }.getOrNull()

    /** The document URI for [documentId] inside [treeUri]. API 21+. */
    public fun documentUriUsingTree(treeUri: Uri, documentId: String): Uri? = runCatching {
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    }.getOrNull()
}

// ---------------------------------------------------------------------------
// Trusted construction, then validation
// ---------------------------------------------------------------------------

/**
 * Resolves and validates SAF destinations.
 *
 * The write path is [resolveDestination]: the application supplies the approved
 * grant and a list of relative segments, and the destination is *constructed*
 * under the grant. There is no `acceptDestinationUri` for writes, because
 * deciding whether an arbitrary URI is inside a grant by inspecting it is the
 * operation that goes wrong.
 *
 * [validateExistingUri] exists for reconciliation — checking a URI a previous
 * session recorded — and is deliberately weaker: on API 23-25 it returns
 * [SafContainmentEvidence.Unknown] rather than accepting a string prefix,
 * because on those levels the platform cannot ask the provider anything.
 */
public object SafDestinationResolver {

    /**
     * Constructs a destination under [grant] from [relativeSegments].
     *
     * Each segment is validated as one segment, so a caller cannot pass `a/b`
     * or `a%2Fb` where one name was asked for. An empty list means the granted
     * root itself.
     */
    public fun resolveDestination(
        grant: SafTreeGrant,
        relativeSegments: List<String>,
        prover: SafContainmentProver,
        sdkInt: Int,
    ): SafContainmentEvidence {
        if (!grant.writable) {
            return SafContainmentEvidence.PermissionRevoked(
                rootDocumentId = grant.rootDocumentId,
                targetDocumentId = null,
                targetUri = null,
                error = TransferStorageError.PermissionRevoked("write"),
            )
        }

        val root = when (val check = SafDocumentIdRules.validate(grant.rootDocumentId)) {
            is SafDocumentIdCheck.Valid -> check
            is SafDocumentIdCheck.Invalid -> return SafContainmentEvidence.Malformed(
                rootDocumentId = grant.rootDocumentId,
                targetDocumentId = null,
                targetUri = null,
                violation = check.violation,
            )
        }

        val canonicalSegments = ArrayList<String>(relativeSegments.size)
        for (segment in relativeSegments) {
            when (val check = SafDocumentIdRules.validateSegment(segment)) {
                is SafSegmentCheck.Valid -> canonicalSegments += check.canonical
                is SafSegmentCheck.Invalid -> return SafContainmentEvidence.Malformed(
                    rootDocumentId = root.canonical,
                    targetDocumentId = null,
                    targetUri = null,
                    violation = check.violation,
                )
            }
        }

        val targetId = if (canonicalSegments.isEmpty()) {
            root.canonical
        } else {
            buildList {
                add(root.canonical)
                addAll(canonicalSegments)
            }.joinToString("/")
        }

        if (!SafDocumentIdRules.isWithinOnSegmentBoundary(root.canonical, targetId)) {
            return SafContainmentEvidence.Outside(root.canonical, targetId, null)
        }

        val targetUri = SafContainment.documentUriUsingTree(grant.treeUri, targetId)?.toString()
            ?: return SafContainmentEvidence.Unknown(
                rootDocumentId = root.canonical,
                targetDocumentId = targetId,
                targetUri = null,
                reason = TransferStorageError.Unsupported("saf_document_uri"),
            )

        // The URI was built from the grant, so this is a self-check rather
        // than a test of caller input: if the authority drifted, something is
        // wrong with the grant and the destination must not be used.
        if (targetUri.toUri().authority != grant.authority) {
            return SafContainmentEvidence.Outside(root.canonical, targetId, targetUri)
        }

        return confirm(
            grant = grant,
            rootId = root.canonical,
            targetId = targetId,
            targetUri = targetUri,
            prover = prover,
            sdkInt = sdkInt,
            internallyConstructed = true,
        )
    }

    /**
     * Validates an arbitrary existing document URI against [grant].
     *
     * For reconciliation only. Weaker than [resolveDestination] by design: on
     * API 23-25 the platform cannot ask the provider about descent, so a
     * string prefix is the only evidence available and it is not accepted.
     */
    public fun validateExistingUri(
        grant: SafTreeGrant,
        documentUri: Uri,
        prover: SafContainmentProver,
        sdkInt: Int,
    ): SafContainmentEvidence {
        if (documentUri.authority != grant.authority) {
            return SafContainmentEvidence.Outside(
                rootDocumentId = grant.rootDocumentId,
                targetDocumentId = null,
                targetUri = documentUri.toString(),
            )
        }

        val root = when (val check = SafDocumentIdRules.validate(grant.rootDocumentId)) {
            is SafDocumentIdCheck.Valid -> check
            is SafDocumentIdCheck.Invalid -> return SafContainmentEvidence.Malformed(
                rootDocumentId = grant.rootDocumentId,
                targetDocumentId = null,
                targetUri = documentUri.toString(),
                violation = check.violation,
            )
        }

        val rawId = SafContainment.documentIdOf(documentUri)
            ?: return SafContainmentEvidence.Malformed(
                rootDocumentId = root.canonical,
                targetDocumentId = null,
                targetUri = documentUri.toString(),
                violation = SafDocumentIdViolation.BLANK,
            )

        val target = when (val check = SafDocumentIdRules.validate(rawId)) {
            is SafDocumentIdCheck.Valid -> check
            is SafDocumentIdCheck.Invalid -> return SafContainmentEvidence.Malformed(
                rootDocumentId = root.canonical,
                targetDocumentId = rawId,
                targetUri = documentUri.toString(),
                violation = check.violation,
            )
        }

        if (!SafDocumentIdRules.isWithinOnSegmentBoundary(root.canonical, target.canonical)) {
            return SafContainmentEvidence.Outside(
                rootDocumentId = root.canonical,
                targetDocumentId = target.canonical,
                targetUri = documentUri.toString(),
            )
        }

        // Below API 26 there is no provider descendant API at all. An arbitrary
        // URI is therefore never accepted on a prefix, however well it matches.
        if (sdkInt < 26) {
            return SafContainmentEvidence.Unknown(
                rootDocumentId = root.canonical,
                targetDocumentId = target.canonical,
                targetUri = documentUri.toString(),
                reason = TransferStorageError.Unsupported("saf_containment_unknown"),
            )
        }

        return confirm(
            grant = grant,
            rootId = root.canonical,
            targetId = target.canonical,
            targetUri = documentUri.toString(),
            prover = prover,
            sdkInt = sdkInt,
            internallyConstructed = false,
        )
    }

    private fun confirm(
        grant: SafTreeGrant,
        rootId: String,
        targetId: String,
        targetUri: String,
        prover: SafContainmentProver,
        sdkInt: Int,
        internallyConstructed: Boolean,
    ): SafContainmentEvidence {
        val rootUri = SafContainment.documentUriUsingTree(grant.treeUri, rootId)
            ?: return SafContainmentEvidence.Unknown(
                rootDocumentId = rootId,
                targetDocumentId = targetId,
                targetUri = targetUri,
                reason = TransferStorageError.Unsupported("saf_document_uri"),
            )

        return try {
            when (SafContainmentTier.forSdk(sdkInt)) {
                SafContainmentTier.CHILD_DOCUMENT -> when (
                    val answer = prover.isChildDocument(rootUri, targetUri.toUri())
                ) {
                    is SafChildAnswer.Answered -> if (answer.isChild) {
                        SafContainmentEvidence.ProviderConfirmedChild(rootId, targetId, targetUri)
                    } else {
                        // An explicit provider "no" is final. There is no
                        // falling back to a string comparison after this.
                        SafContainmentEvidence.Outside(rootId, targetId, targetUri)
                    }

                    SafChildAnswer.Revoked -> SafContainmentEvidence.PermissionRevoked(
                        rootId, targetId, targetUri,
                        TransferStorageError.PermissionRevoked("write"),
                    )

                    SafChildAnswer.Indeterminate -> SafContainmentEvidence.Unknown(
                        rootId, targetId, targetUri,
                        TransferStorageError.Unsupported("saf_containment_unknown"),
                    )
                }

                SafContainmentTier.DOCUMENT_PATH -> when (
                    val answer = prover.documentPath(targetUri.toUri())
                ) {
                    is SafPathAnswer.Resolved -> evaluatePath(rootId, targetId, targetUri, answer)
                    SafPathAnswer.Revoked -> SafContainmentEvidence.PermissionRevoked(
                        rootId, targetId, targetUri,
                        TransferStorageError.PermissionRevoked("write"),
                    )

                    SafPathAnswer.Indeterminate -> SafContainmentEvidence.Unknown(
                        rootId, targetId, targetUri,
                        TransferStorageError.Unsupported("saf_containment_unknown"),
                    )
                }

                SafContainmentTier.CANONICAL_ONLY -> if (internallyConstructed) {
                    // Every condition for GrantScopedCanonical has already been
                    // checked by the time this is reached: a writable persisted
                    // grant, segments validated one by one, a segment-boundary
                    // match against the root, and the same authority and tree
                    // identity in the constructed URI.
                    SafContainmentEvidence.GrantScopedCanonical(rootId, targetId, targetUri)
                } else {
                    // Unreachable: validateExistingUri returns Unknown before
                    // reaching here on this tier. Kept as a refusal so that a
                    // future caller cannot buy a prefix acceptance by accident.
                    SafContainmentEvidence.Unknown(
                        rootId, targetId, targetUri,
                        TransferStorageError.Unsupported("saf_containment_unknown"),
                    )
                }
            }
        } catch (e: SecurityException) {
            // A revoked grant is not "not a child" and not "the provider broke".
            SafContainmentEvidence.PermissionRevoked(
                rootId, targetId, targetUri,
                TransferStorageError.PermissionRevoked("write"),
            )
        } catch (e: Exception) {
            // A provider exception is not a licence to accept the string.
            SafContainmentEvidence.Unknown(
                rootId, targetId, targetUri,
                TransferStorageError.ProviderFailure("document_provider"),
            )
        }
    }

    private fun evaluatePath(
        rootId: String,
        targetId: String,
        targetUri: String,
        answer: SafPathAnswer.Resolved,
    ): SafContainmentEvidence {
        val segments = answer.segments

        if (segments.isEmpty()) {
            return SafContainmentEvidence.Unknown(
                rootId, targetId, targetUri,
                TransferStorageError.Unsupported("saf_containment_unknown"),
            )
        }

        // A provider that names a different root is not contradicting us so
        // much as describing somewhere else; that is unknown, not outside.
        if (answer.rootId != null && answer.rootId != rootId) {
            return SafContainmentEvidence.Unknown(
                rootId, targetId, targetUri,
                TransferStorageError.Unsupported("saf_containment_unknown"),
            )
        }

        if (segments.first() != rootId) {
            return SafContainmentEvidence.Outside(rootId, targetId, targetUri)
        }

        if (segments.last() != targetId) {
            return SafContainmentEvidence.Unknown(
                rootId, targetId, targetUri,
                TransferStorageError.Unsupported("saf_containment_unknown"),
            )
        }

        for (segment in segments) {
            if (SafDocumentIdRules.validate(segment) !is SafDocumentIdCheck.Valid) {
                return SafContainmentEvidence.Unknown(
                    rootId, targetId, targetUri,
                    TransferStorageError.Unsupported("saf_containment_unknown"),
                )
            }
        }

        for (index in 0 until segments.lastIndex) {
            if (!SafDocumentIdRules.isWithinOnSegmentBoundary(segments[index], segments[index + 1])) {
                return SafContainmentEvidence.Unknown(
                    rootId, targetId, targetUri,
                    TransferStorageError.Unsupported("saf_containment_unknown"),
                )
            }
        }

        return SafContainmentEvidence.ProviderConfirmedPath(rootId, targetId, targetUri, segments)
    }
}

// ---------------------------------------------------------------------------
// Commit policy
// ---------------------------------------------------------------------------

/** The operation containment is being asked to authorise. */
public enum class SafContainmentOperation(public val id: String) {
    /** Create a new destination document under the grant. */
    CREATE_DESTINATION("create_destination"),

    /** Reopen a temporary document this application created earlier. */
    REOPEN_TEMPORARY("reopen_temporary"),

    /** Rename a temporary document to its final name. */
    RENAME_TEMPORARY("rename_temporary"),

    /** Delete a temporary document this application created earlier. */
    DELETE_TEMPORARY("delete_temporary"),

    /** Inspect an existing final document during reconciliation. */
    RECONCILE_FINAL("reconcile_final"),
}

/** What containment policy decided. */
public enum class SafContainmentDecision(public val id: String) {
    ALLOWED("allowed"),

    /** Definitively not permitted. */
    REJECTED("rejected"),

    /** Not enough evidence to act. No write, rename or delete. */
    FORBIDDEN_NO_WRITE("forbidden_no_write"),
}

/**
 * Which evidence levels authorise which operations.
 *
 * Containment alone is never sufficient for deletion: deleting by containment
 * would let one transfer remove a document another transfer created, or a
 * document the user made. Deletion additionally requires that the stored opaque
 * temporary identity matches, which is checked by the caller and passed in as
 * [storedIdentityMatches].
 */
public object SafContainmentPolicy {

    public fun decide(
        evidence: SafContainmentEvidence,
        operation: SafContainmentOperation,
        storedIdentityMatches: Boolean = false,
    ): SafContainmentDecision = when (evidence) {
        is SafContainmentEvidence.ProviderConfirmedChild ->
            allow(operation, storedIdentityMatches)

        is SafContainmentEvidence.ProviderConfirmedPath ->
            allow(operation, storedIdentityMatches)

        // Constructed by the application itself under the exact persisted
        // grant. Enough to create a fresh destination; not enough to act on a
        // document that already exists, because only the provider can say what
        // is actually there.
        is SafContainmentEvidence.GrantScopedCanonical ->
            if (operation == SafContainmentOperation.CREATE_DESTINATION) {
                SafContainmentDecision.ALLOWED
            } else {
                SafContainmentDecision.REJECTED
            }

        is SafContainmentEvidence.Outside -> SafContainmentDecision.REJECTED
        is SafContainmentEvidence.Malformed -> SafContainmentDecision.REJECTED

        // Unknown forbids writing, renaming and deleting. Reconciliation is a
        // read, but an ambiguous one cannot be decided either.
        is SafContainmentEvidence.Unknown -> SafContainmentDecision.FORBIDDEN_NO_WRITE

        is SafContainmentEvidence.PermissionRevoked -> SafContainmentDecision.FORBIDDEN_NO_WRITE
    }

    private fun allow(
        operation: SafContainmentOperation,
        storedIdentityMatches: Boolean,
    ): SafContainmentDecision = if (
        operation == SafContainmentOperation.DELETE_TEMPORARY && !storedIdentityMatches
    ) {
        SafContainmentDecision.REJECTED
    } else {
        SafContainmentDecision.ALLOWED
    }
}
