package app.morsecode.core.storage.transfer

import android.net.Uri
import android.provider.DocumentsContract

/*
 * Containment: proving that a write target is inside the tree the user granted.
 *
 * This replaces an earlier rule that rejected any raw document id containing an
 * encoded dot or slash. That rule was too broad: a standard SAF tree URI such as
 * `.../tree/primary%3ADownload/document/primary%3ADownload%2F2026` contains a
 * legitimate `%2F`, because a document id is one percent-encoded URI segment.
 * Rejecting it would have broken ordinary Downloads and DCIM destinations.
 *
 * The replacement is three separate layers, and the order matters.
 *
 *   A. Parse with the platform, never by hand. DocumentsContract knows how a
 *      tree URI splits into a root id and a document id; a regex over the raw
 *      string does not.
 *   B. Canonicalise exactly once, then validate. Decoding repeatedly until no
 *      percent signs remain is rejected here, because a second decode pass can
 *      turn a legitimate identifier into something else entirely.
 *   C. Ask the provider. A string prefix is a claim about naming; only the
 *      provider can say what is actually a descendant.
 *
 * The API-level fact that shapes layer C: `DocumentsContract.isChildDocument`
 * was added in **API 29**. On API 23-28 it does not exist, so the provider proof
 * falls back to `findDocumentPath` (API 26+) and then to canonical prefix
 * matching. A prefix match is recorded as the weaker basis it is, and anything
 * the provider declines to answer is returned as [SafContainmentProof.ContainmentUnknown]
 * rather than silently accepted.
 */

// ---------------------------------------------------------------------------
// Layer B — canonical validation
// ---------------------------------------------------------------------------

/** Why a document id was refused. */
public enum class SafDocumentIdViolation(public val id: String) {
    BLANK("blank"),
    NUL("nul"),
    BACKSLASH("backslash"),
    ABSOLUTE_FILESYSTEM("absolute_filesystem"),
    EMPTY_SEGMENT("empty_segment"),
    DOT_SEGMENT("dot_segment"),
    ENCODED_TRAVERSAL("encoded_traversal"),
    DOUBLE_ENCODED_TRAVERSAL("double_encoded_traversal"),
}

/** The outcome of canonicalising and checking one document id. */
public sealed interface SafDocumentIdCheck {
    public data class Valid(public val canonical: String) : SafDocumentIdCheck
    public data class Invalid(public val violation: SafDocumentIdViolation) : SafDocumentIdCheck
}

/**
 * Canonical validation of a SAF document id. Pure, and deliberately free of
 * `android.net`, so the rules are ordinary JVM tests.
 *
 * A document id uses `/` as its own separator — `primary:Download/2026/oct` is
 * one id naming a nested folder — so a slash is not suspicious in itself. What
 * is suspicious is a segment that is `.` or `..`, a backslash standing in for a
 * separator, an absolute filesystem path, or percent-encoding that hides any of
 * those.
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
     * The returned [SafDocumentIdCheck.Valid.canonical] is the single decoded
     * form every later comparison must use, so that `primary:Download%2F2026`
     * and `primary:Download/2026` are recognised as naming the same place
     * rather than one being rejected for having a slash the other lacks.
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
     * Whether [root] contains [target], compared on canonical forms.
     *
     * Both must already have passed [validate]; this is the naming check that
     * layer C then corroborates with the provider.
     */
    public fun contains(root: SafDocumentIdCheck.Valid, target: SafDocumentIdCheck.Valid): Boolean {
        val rootId = root.canonical
        val targetId = target.canonical
        return targetId == rootId || targetId.startsWith("$rootId/")
    }

    /** Convenience for callers holding raw strings; validates both first. */
    public fun contains(rootDocumentId: String, targetDocumentId: String): Boolean {
        val root = validate(rootDocumentId) as? SafDocumentIdCheck.Valid ?: return false
        val target = validate(targetDocumentId) as? SafDocumentIdCheck.Valid ?: return false
        return contains(root, target)
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
// Layer C — provider-backed descendant proof
// ---------------------------------------------------------------------------

/**
 * How containment was established, weakest last.
 *
 * Recorded on every [SafContainmentProof.Inside] so a caller can insist on
 * provider-backed proof for a write and still proceed on a weaker basis where
 * the platform offers nothing better.
 */
public enum class SafContainmentMethod(public val id: String) {
    /** The document id is the granted root itself. No proof needed. */
    IDENTITY("identity"),

    /** `DocumentsContract.isChildDocument`, API 29 and above. */
    PROVIDER_CHILD_DOCUMENT("provider_child_document"),

    /** `DocumentsContract.findDocumentPath`, API 26 and above. */
    PROVIDER_DOCUMENT_PATH("provider_document_path"),

    /** Canonical prefix containment after validation. No provider involved. */
    CANONICAL_PREFIX("canonical_prefix"),
    ;

    /** True when the provider itself confirmed the relationship. */
    public val isProviderBacked: Boolean
        get() = this == PROVIDER_CHILD_DOCUMENT || this == PROVIDER_DOCUMENT_PATH

    public companion object {
        /** The strongest proof available on [sdkInt], which the gateway reports. */
        public fun availableOn(sdkInt: Int): SafContainmentMethod = when {
            sdkInt >= 29 -> PROVIDER_CHILD_DOCUMENT
            sdkInt >= 26 -> PROVIDER_DOCUMENT_PATH
            else -> CANONICAL_PREFIX
        }
    }
}

/** What the provider said when asked whether one document contains another. */
public sealed interface SafChildProof {
    public data class Answered(
        public val isChild: Boolean,
        public val method: SafContainmentMethod,
    ) : SafChildProof

    /** The provider declined to answer, or the platform cannot ask. */
    public data object Indeterminate : SafChildProof

    /** The grant is gone. Not the same as "not a child". */
    public data class Revoked(public val error: TransferStorageError) : SafChildProof

    public data class Failed(public val error: TransferStorageError) : SafChildProof
}

/**
 * The provider call layer C needs.
 *
 * Narrow enough to fake, and the only place the API-level split between
 * `isChildDocument` (29+) and `findDocumentPath` (26+) is allowed to live.
 */
public fun interface SafChildProver {
    public fun prove(parentDocumentUri: Uri, childDocumentUri: Uri): SafChildProof
}

/** The result of a containment check. */
public sealed interface SafContainmentProof {

    public data class Inside(
        public val rootDocumentId: String,
        public val targetDocumentId: String,
        public val method: SafContainmentMethod,
    ) : SafContainmentProof

    /** Well-formed, but naming somewhere other than the granted tree. */
    public data class Outside(
        public val rootDocumentId: String,
        public val targetDocumentId: String,
    ) : SafContainmentProof

    /** Not a document id this app can interpret. */
    public data class Malformed(
        public val rootDocumentId: String?,
        public val targetDocumentId: String?,
        public val violation: SafDocumentIdViolation,
    ) : SafContainmentProof

    /**
     * The application could not prove the write stays under the grant.
     *
     * Never treated as inside, and never a failure that permits a retry to
     * assume otherwise. This is the honest answer when a provider will not say.
     */
    public data class ContainmentUnknown(
        public val rootDocumentId: String,
        public val targetDocumentId: String,
        public val reason: TransferStorageError,
    ) : SafContainmentProof

    /** The grant was revoked. Preserved as its own outcome, not a generic failure. */
    public data class Revoked(
        public val rootDocumentId: String,
        public val targetDocumentId: String,
        public val error: TransferStorageError,
    ) : SafContainmentProof

    public companion object {
        public fun isInside(proof: SafContainmentProof): Boolean = proof is Inside
    }
}

// ---------------------------------------------------------------------------
// Layer A + the three layers together
// ---------------------------------------------------------------------------

/**
 * Parses with the platform, validates canonically, then asks the provider.
 */
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

    /**
     * Proves that [targetDocumentId] is inside the tree [treeUri].
     *
     * A blank or null target means the granted root itself, which needs no
     * provider call: it is the root by identity.
     *
     * [prover] may be null, which is how the API 23-25 path reads — there is no
     * provider descendant API that low — and which yields
     * [SafContainmentMethod.CANONICAL_PREFIX] rather than pretending otherwise.
     */
    public fun prove(
        treeUri: Uri,
        targetDocumentId: String?,
        prover: SafChildProver? = null,
    ): SafContainmentProof {
        // --- Layer A: let the platform parse, never a regex over the raw URI.
        val rootRaw = treeDocumentIdOf(treeUri)
            ?: return SafContainmentProof.Malformed(
                rootDocumentId = null,
                targetDocumentId = targetDocumentId,
                violation = SafDocumentIdViolation.BLANK,
            )

        // --- Layer B: canonicalise once, then validate.
        val root = when (val check = SafDocumentIdRules.validate(rootRaw)) {
            is SafDocumentIdCheck.Valid -> check
            is SafDocumentIdCheck.Invalid -> return SafContainmentProof.Malformed(
                rootDocumentId = rootRaw,
                targetDocumentId = targetDocumentId,
                violation = check.violation,
            )
        }

        val targetRaw = targetDocumentId?.takeIf { it.isNotEmpty() } ?: rootRaw
        val target = when (val check = SafDocumentIdRules.validate(targetRaw)) {
            is SafDocumentIdCheck.Valid -> check
            is SafDocumentIdCheck.Invalid -> return SafContainmentProof.Malformed(
                rootDocumentId = rootRaw,
                targetDocumentId = targetRaw,
                violation = check.violation,
            )
        }

        if (!SafDocumentIdRules.contains(root, target)) {
            return SafContainmentProof.Outside(rootRaw, targetRaw)
        }

        if (targetRaw == rootRaw) {
            return SafContainmentProof.Inside(
                rootDocumentId = rootRaw,
                targetDocumentId = rootRaw,
                method = SafContainmentMethod.IDENTITY,
            )
        }

        // --- Layer C: corroborate with the provider where one is available.
        val targetUri = documentUriUsingTree(treeUri, targetRaw)
            ?: return SafContainmentProof.ContainmentUnknown(
                rootDocumentId = rootRaw,
                targetDocumentId = targetRaw,
                reason = TransferStorageError.Unsupported("saf_document_uri"),
            )

        if (prover == null) {
            // No provider descendant API on this platform level. The canonical
            // check above is the strongest safe validation available, and it is
            // reported as such rather than dressed up as provider proof.
            return SafContainmentProof.Inside(
                rootDocumentId = rootRaw,
                targetDocumentId = targetRaw,
                method = SafContainmentMethod.CANONICAL_PREFIX,
            )
        }

        val rootUri = documentUriUsingTree(treeUri, rootRaw)
            ?: return SafContainmentProof.ContainmentUnknown(
                rootDocumentId = rootRaw,
                targetDocumentId = targetRaw,
                reason = TransferStorageError.Unsupported("saf_document_uri"),
            )

        val proof = try {
            prover.prove(rootUri, targetUri)
        } catch (e: SecurityException) {
            // A revoked grant is not "not a child", and not "the provider
            // broke". It needs its own outcome so the caller can preserve the
            // identity and state for later reconciliation.
            SafChildProof.Revoked(TransferStorageError.PermissionRevoked("write"))
        } catch (e: Exception) {
            SafChildProof.Indeterminate
        }

        return when (proof) {
            is SafChildProof.Answered -> if (proof.isChild) {
                SafContainmentProof.Inside(rootRaw, targetRaw, proof.method)
            } else {
                // The provider is the final authority: it says the document is
                // not a descendant, so prefix agreement does not save it.
                SafContainmentProof.Outside(rootRaw, targetRaw)
            }

            SafChildProof.Indeterminate -> SafContainmentProof.ContainmentUnknown(
                rootDocumentId = rootRaw,
                targetDocumentId = targetRaw,
                reason = TransferStorageError.Unsupported("saf_containment_unknown"),
            )

            is SafChildProof.Revoked -> SafContainmentProof.Revoked(rootRaw, targetRaw, proof.error)

            is SafChildProof.Failed -> SafContainmentProof.ContainmentUnknown(
                rootDocumentId = rootRaw,
                targetDocumentId = targetRaw,
                reason = proof.error,
            )
        }
    }
}
