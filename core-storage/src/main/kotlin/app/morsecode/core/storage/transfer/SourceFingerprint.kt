package app.morsecode.core.storage.transfer

/** How much identifying metadata a fingerprint contains, not proof that bytes are unchanged. */
public enum class FingerprintStrength(public val id: String) {
    /** Both a provider identity and a size were available. */
    STRONG("strong"),

    /** A size, but no provider identity, was available. */
    WEAK("weak"),

    /** No size was available. Other fields can still reveal a definite change. */
    NONE("none"),
    ;

    public companion object {
        public fun fromId(id: String?): FingerprintStrength =
            entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * Metadata observed about a source at one moment.
 *
 * [contentId] is an opaque provider identity, not evidence that the object's bytes are immutable.
 * A non-null timestamp is compared conservatively as reported metadata; a caller that cannot trust
 * the timestamp must record it as null. Matching metadata never replaces whole-file verification.
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

    // Provider identities can contain document names or paths; never render them in diagnostics.
    override fun toString(): String = "SourceFingerprint([redacted])"

    public companion object {
        public fun of(
            sizeBytes: Long?,
            lastModifiedEpochMillis: Long?,
            contentId: String?,
        ): SourceFingerprint = SourceFingerprint(sizeBytes, lastModifiedEpochMillis, contentId)

        public val UNKNOWN: SourceFingerprint = SourceFingerprint(null, null, null)
    }
}

/** The result of asking a live source for its fingerprint. */
public sealed interface SourceFingerprintResult {
    public data class Available(public val fingerprint: SourceFingerprint) : SourceFingerprintResult
    public data class Unavailable(public val error: TransferStorageError) : SourceFingerprintResult
}

/** Evidence from a metadata comparison, not an authorization to read or publish. */
public enum class FingerprintOutcome(public val id: String) {
    /** Identity, size and timestamp are all comparable and agree. */
    STRONG_MATCH("strong_match"),

    /** Size and timestamp agree but an identity is unavailable. */
    WEAK_MATCH("weak_match"),

    /** Size or timestamp is unavailable on at least one side. */
    MISSING_METADATA("missing_metadata"),

    /** Comparable identities, sizes or timestamps disagree; restart is required. */
    DEFINITE_CHANGE("definite_change"),

    /** The source could not be queried at all. */
    UNAVAILABLE("unavailable"),

    /** A grant held for the source is gone. */
    PERMISSION_REVOKED("permission_revoked"),
    ;

    public val requiresRestart: Boolean get() = this == DEFINITE_CHANGE

    public companion object {
        public fun fromId(id: String?): FingerprintOutcome =
            entries.firstOrNull { it.id == id } ?: UNAVAILABLE
    }
}

/**
 * Compare every available field before considering missing metadata or an identity match.
 *
 * Any comparable identity, size or timestamp mismatch means restart, including an in-place edit of
 * an object whose identity stayed the same. Matching metadata is not proof of matching bytes: a
 * same-size, same-timestamp edit is detected only by the expected whole-file digest at verification.
 * [SourceResumePolicy] binds that digest to a resume decision; this comparison alone permits nothing.
 */
public object SourceChangePolicy {

    public fun evaluate(
        stored: SourceFingerprint,
        current: SourceFingerprintResult,
    ): FingerprintOutcome = when (current) {
        is SourceFingerprintResult.Unavailable -> when (current.error.category) {
            TransferStorageErrorCategory.PERMISSION_REVOKED -> FingerprintOutcome.PERMISSION_REVOKED
            else -> FingerprintOutcome.UNAVAILABLE
        }

        is SourceFingerprintResult.Available -> compare(stored, current.fingerprint)
    }

    public fun compare(
        stored: SourceFingerprint,
        current: SourceFingerprint,
    ): FingerprintOutcome {
        val storedId = stored.contentId
        val currentId = current.contentId
        if (storedId != null && currentId != null && storedId != currentId) {
            return FingerprintOutcome.DEFINITE_CHANGE
        }

        val storedSize = stored.sizeBytes
        val currentSize = current.sizeBytes
        if (storedSize != null && currentSize != null && storedSize != currentSize) {
            return FingerprintOutcome.DEFINITE_CHANGE
        }

        val storedModified = stored.lastModifiedEpochMillis
        val currentModified = current.lastModifiedEpochMillis
        if (storedModified != null && currentModified != null && storedModified != currentModified) {
            return FingerprintOutcome.DEFINITE_CHANGE
        }

        if (storedSize == null || currentSize == null || storedModified == null || currentModified == null) {
            return FingerprintOutcome.MISSING_METADATA
        }
        return if (storedId != null && currentId != null) {
            FingerprintOutcome.STRONG_MATCH
        } else {
            FingerprintOutcome.WEAK_MATCH
        }
    }
}
