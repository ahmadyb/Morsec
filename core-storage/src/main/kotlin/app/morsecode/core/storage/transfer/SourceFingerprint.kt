package app.morsecode.core.storage.transfer

/*
 * Source fingerprinting: deciding whether a stored resume offset still applies.
 *
 * The question this answers is narrow and unforgiving: I have a partial file at
 * offset N, and the user has asked to resume the same transfer. Is the source
 * still the same file?
 *
 * Getting it wrong in one direction re-downloads something that had not
 * changed, which is wasteful. Getting it wrong in the other direction splices
 * the head of one file onto the tail of another and publishes it as complete.
 * The policy below is written so that the second mistake is impossible and the
 * first is merely possible.
 *
 * There is deliberately no attempt to hash the source to answer this. Hashing
 * the whole file is exactly the multi-gigabyte read this group is forbidden from
 * doing before a transfer even starts, and a partial hash of the head would be
 * cheaper but would prove nothing about the tail — which is where the resume
 * would begin. Metadata, plus a strong identity where the provider offers one,
 * is what is affordable and what the six outcomes below are graded on.
 */

/** How much a fingerprint can actually prove. */
public enum class FingerprintStrength(public val id: String) {
    /**
     * A provider-supplied identity is present alongside the size, so two
     * fingerprints agreeing is real evidence that the content is the same file.
     */
    STRONG("strong"),

    /**
     * Only size (and possibly last-modified) is available. Agreement is
     * suggestive, not proof: a different file of the same length scores a match.
     */
    WEAK("weak"),

    /** Nothing usable could be read; no comparison is possible. */
    NONE("none"),
    ;

    public companion object {
        public fun fromId(id: String?): FingerprintStrength =
            entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * What was observed about a source at one moment.
 *
 * `contentId` is a provider-supplied stable identity where one exists — a
 * MediaStore `_ID`, or a document id from a SAF tree. It is *not* a path and is
 * never persisted as one; it is an opaque string compared for equality.
 */
public data class SourceFingerprint(
    public val sizeBytes: Long?,
    public val lastModifiedEpochMillis: Long?,
    public val contentId: String?,
) {
    public val strength: FingerprintStrength
        get() = when {
            contentId != null && sizeBytes != null -> FingerprintStrength.STRONG
            sizeBytes != null -> FingerprintStrength.WEAK
            else -> FingerprintStrength.NONE
        }

    public companion object {
        public fun of(
            sizeBytes: Long?,
            lastModifiedEpochMillis: Long?,
            contentId: String?,
        ): SourceFingerprint = SourceFingerprint(sizeBytes, lastModifiedEpochMillis, contentId)

        /** A fingerprint that can never prove anything. */
        public val UNKNOWN: SourceFingerprint = SourceFingerprint(null, null, null)
    }
}

/** The result of asking a live source for its fingerprint. */
public sealed interface SourceFingerprintResult {
    public data class Available(public val fingerprint: SourceFingerprint) : SourceFingerprintResult
    public data class Unavailable(public val error: TransferStorageError) : SourceFingerprintResult
}

/**
 * The six possible answers to "can the stored offset still be trusted?"
 *
 * Every outcome is terminal and every one of them is tested individually; there
 * is no default branch that quietly picks one.
 */
public enum class FingerprintOutcome(public val id: String) {
    /**
     * A provider identity was available on both sides and agrees. Resume from
     * the stored offset.
     */
    STRONG_MATCH("strong_match"),

    /**
     * Only size (and last-modified, where known) was available, and it agrees.
     * Resume is permitted, but the row records that the evidence was weak so a
     * verification failure later is interpretable rather than mysterious.
     */
    WEAK_MATCH("weak_match"),

    /**
     * A field the comparison depends on could not be read on one or both sides.
     * Nothing is claimed either way; the caller decides, and the default is to
     * restart rather than to gamble on an offset.
     */
    MISSING_METADATA("missing_metadata"),

    /**
     * The source is provably a different file: the identity differs, or the size
     * differs, or the last-modified time differs with no identity to overrule
     * it. The partial must be discarded.
     */
    DEFINITE_CHANGE("definite_change"),

    /** The source could not be queried at all. Not evidence of change. */
    UNAVAILABLE("unavailable"),

    /** A grant that was held for this URI is gone. Needs a user action. */
    PERMISSION_REVOKED("permission_revoked"),
    ;

    /** True only when resuming from a stored offset is justified. */
    public val allowsResume: Boolean
        get() = this == STRONG_MATCH || this == WEAK_MATCH

    /** True when the existing partial has to be thrown away. */
    public val requiresRestart: Boolean
        get() = this == DEFINITE_CHANGE

    public companion object {
        public fun fromId(id: String?): FingerprintOutcome =
            entries.firstOrNull { it.id == id } ?: UNAVAILABLE
    }
}

/**
 * The comparison itself.
 *
 * Order matters and is the whole policy:
 *
 *  1. A provider identity outranks everything else. If both sides have one, it
 *     decides alone — a size or timestamp change on a file whose identity is
 *     unchanged is the same file, edited.
 *  2. Size disagreement is always a definite change. There is no plausible
 *     resume across it.
 *  3. A last-modified change with no identity is treated as a definite change
 *     too. That will occasionally re-download a file that only had its metadata
 *     touched; the alternative is splicing two files together and publishing the
 *     result, which is the one outcome this group is not allowed to have.
 *  4. A missing field is reported as missing rather than as a mismatch, because
 *     "I could not read the size" and "the size is different" are different
 *     facts and lead to different recovery decisions.
 */
public object SourceChangePolicy {

    public fun evaluate(
        stored: SourceFingerprint,
        current: SourceFingerprintResult,
    ): FingerprintOutcome = when (current) {
        is SourceFingerprintResult.Unavailable -> when (current.error.category) {
            TransferStorageErrorCategory.PERMISSION_REVOKED ->
                FingerprintOutcome.PERMISSION_REVOKED

            else -> FingerprintOutcome.UNAVAILABLE
        }

        is SourceFingerprintResult.Available -> compare(stored, current.fingerprint)
    }

    public fun compare(
        stored: SourceFingerprint,
        current: SourceFingerprint,
    ): FingerprintOutcome {
        if (current.strength == FingerprintStrength.NONE) return FingerprintOutcome.MISSING_METADATA

        val storedId = stored.contentId
        val currentId = current.contentId
        if (storedId != null && currentId != null) {
            return if (storedId == currentId) {
                FingerprintOutcome.STRONG_MATCH
            } else {
                FingerprintOutcome.DEFINITE_CHANGE
            }
        }

        val storedSize = stored.sizeBytes
        val currentSize = current.sizeBytes
        if (storedSize == null || currentSize == null) return FingerprintOutcome.MISSING_METADATA
        if (storedSize != currentSize) return FingerprintOutcome.DEFINITE_CHANGE

        val storedModified = stored.lastModifiedEpochMillis
        val currentModified = current.lastModifiedEpochMillis
        if (storedModified != null && currentModified != null && storedModified != currentModified) {
            return FingerprintOutcome.DEFINITE_CHANGE
        }

        return FingerprintOutcome.WEAK_MATCH
    }
}
