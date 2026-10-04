package app.morsecode.core.storage.transfer

/*
 * Typed failures for the transfer storage adapters.
 *
 * Two rules shape this file, and both exist because the alternative has already
 * gone wrong in this codebase.
 *
 * 1. Every failure is a typed value with a category, so a caller can decide
 *    whether to retry, restart, ask the user, or give up. There is no
 *    catch-and-stringify path and no generic `IOException` leaking out of the
 *    adapters carrying a provider's message text.
 *
 * 2. Nothing user-visible or persisted may contain a location. A document
 *    provider is entitled to put an absolute path, a real directory name or a
 *    personal file name into its exception text, and that text must not reach a
 *    log line that leaves the device, a crash report, a row in Room, or the UI.
 *    So each error carries a `safeMessage()` built only from the category and
 *    from numbers that are not identifying, and a separate `diagnostic` that is
 *    allowed to contain a path and is never surfaced, never persisted and never
 *    included in `safeMessage()`.
 *
 * `RedactionTest` asserts the second rule mechanically: it builds every error
 * with a recognisable absolute path and asserts the path appears in no
 * `safeMessage()`.
 */

/** What went wrong, at the granularity a recovery decision needs. */
public enum class TransferStorageErrorCategory(public val id: String) {
    /** A previously granted URI permission is no longer held. */
    PERMISSION_REVOKED("permission_revoked"),

    /** The source or the partial is gone. */
    NOT_FOUND("not_found"),

    /** A read or write failed at the file or pipe level. */
    IO("io"),

    /** The content provider or document provider failed or returned nonsense. */
    PROVIDER("provider"),

    /** Not enough room for the bytes that would have to be written. */
    INSUFFICIENT_SPACE("insufficient_space"),

    /** Storage is not in the state the operation required. */
    STATE("state"),

    /** The provider cannot do what was asked and never will. */
    UNSUPPORTED("unsupported"),

    /** The caller cancelled; not a failure of the storage layer. */
    CANCELLED("cancelled"),

    /** Bytes on storage disagree with what the checkpoint claims. */
    INTEGRITY("integrity"),
    ;

    public companion object {
        public fun fromId(id: String?): TransferStorageErrorCategory =
            entries.firstOrNull { it.id == id } ?: IO
    }
}

/**
 * A typed storage failure.
 *
 * Subclasses expose only non-identifying values. `diagnostic` is the one field
 * that may name a location, and it is deliberately not part of `toString()`.
 */
public sealed class TransferStorageError(
    public val category: TransferStorageErrorCategory,
) {

    /**
     * Summary safe to show to a user, write to a log that leaves the device,
     * and persist in a database row. Contains no path, no URI, no provider text
     * and no exception message.
     */
    public abstract fun safeMessage(): String

    /**
     * Detail for local debugging only: may contain a path or provider text.
     * Never surfaced, never persisted, never serialised.
     */
    public open val diagnostic: String? get() = null

    /** A permission that was granted for this URI is no longer held. */
    public data class PermissionRevoked(
        /** `read` or `write`, never the URI itself. */
        public val grant: String,
        override val diagnostic: String? = null,
    ) : TransferStorageError(TransferStorageErrorCategory.PERMISSION_REVOKED) {
        override fun safeMessage(): String = "Access to the file was revoked ($grant)"
    }

    /** The named object does not exist. */
    public data class NotFound(
        /** What was being looked for: `source`, `partial`, `staged_copy`. */
        public val subject: String,
        override val diagnostic: String? = null,
    ) : TransferStorageError(TransferStorageErrorCategory.NOT_FOUND) {
        override fun safeMessage(): String = "The $subject no longer exists"
    }

    /** A read or write failed. */
    public data class Io(
        /** `read`, `write`, `open`, `close`, `sync`. */
        public val operation: String,
        override val diagnostic: String? = null,
    ) : TransferStorageError(TransferStorageErrorCategory.IO) {
        override fun safeMessage(): String = "The file could not be $operation"
    }

    /** A provider failed or returned something unusable. */
    public data class ProviderFailure(
        /** `content_resolver`, `document_provider`, `media_store`. */
        public val provider: String,
        override val diagnostic: String? = null,
    ) : TransferStorageError(TransferStorageErrorCategory.PROVIDER) {
        override fun safeMessage(): String = "The $provider failed"
    }

    /** Not enough room. Both numbers are sizes, neither is a location. */
    public data class InsufficientSpace(
        public val requiredBytes: Long,
        public val availableBytes: Long,
        override val diagnostic: String? = null,
    ) : TransferStorageError(TransferStorageErrorCategory.INSUFFICIENT_SPACE) {
        public val missingBytes: Long get() = (requiredBytes - availableBytes).coerceAtLeast(0L)
        override fun safeMessage(): String =
            "Not enough space: $requiredBytes bytes needed, $availableBytes available"
    }

    /**
     * The medium filled up during [operation].
     *
     * Separate from [InsufficientSpace] because that one reports two measured
     * sizes and this one cannot: the platform reports a full medium as an
     * IOException carrying an errno, with no required or available figure to
     * quote. Inventing 0 and 0 would report a number that means the opposite
     * of what happened.
     */
    public data class StorageFull(
        /** `copy`, `flush`, `create`, `rename`, `open`. */
        public val operation: String,
        override val diagnostic: String? = null,
    ) : TransferStorageError(TransferStorageErrorCategory.INSUFFICIENT_SPACE) {
        override fun safeMessage(): String =
            "The destination ran out of space during $operation"
    }

    /** Storage is in a state the operation cannot proceed from. */
    public data class StateConflict(
        /** Short machine-readable reason, e.g. `partial_longer_than_checkpoint`. */
        public val reason: String,
        override val diagnostic: String? = null,
    ) : TransferStorageError(TransferStorageErrorCategory.STATE) {
        override fun safeMessage(): String = "The transfer cannot continue ($reason)"
    }

    /** The destination could not be proven to lie inside the selected tree. */
    public data class ContainmentUnknown(
        /** Short machine-readable reason; never a URI or provider message. */
        public val reason: String,
        override val diagnostic: String? = null,
    ) : TransferStorageError(TransferStorageErrorCategory.STATE) {
        override fun safeMessage(): String = "The destination could not be verified"
    }

    /** The provider cannot do this, on this device, ever. */
    public data class Unsupported(
        /** Short machine-readable capability, e.g. `seekable_stream`. */
        public val capability: String,
        override val diagnostic: String? = null,
    ) : TransferStorageError(TransferStorageErrorCategory.UNSUPPORTED) {
        override fun safeMessage(): String = "This storage location does not support $capability"
    }

    /** Cancelled by the caller. */
    public data object Cancelled : TransferStorageError(TransferStorageErrorCategory.CANCELLED) {
        override fun safeMessage(): String = "The transfer was cancelled"
    }

    /** Bytes on disk disagree with the checkpoint or with the expected digest. */
    public data class IntegrityMismatch(
        /** `length`, `digest`, `offset`. */
        public val subject: String,
        override val diagnostic: String? = null,
    ) : TransferStorageError(TransferStorageErrorCategory.INTEGRITY) {
        override fun safeMessage(): String = "The file failed its $subject check"
    }

    /**
     * The non-seekable reposition loop made no progress for
     * [app.morsecode.core.storage.transfer.NonSeekableRepositioner.MAX_ZERO_PROGRESS_STEPS]
     * consecutive reads.
     */
    public data class ZeroProgress(
        public val steps: Int,
        public val bytesRemaining: Long,
        override val diagnostic: String? = null,
    ) : TransferStorageError(TransferStorageErrorCategory.PROVIDER) {
        override fun safeMessage(): String =
            "The file could not be advanced after $steps attempts ($bytesRemaining bytes left)"
    }

    final override fun toString(): String =
        "${category.id}:${safeMessage()}"

    public companion object {
        /** True when a retry has any chance of succeeding. */
        public fun isRetryable(error: TransferStorageError): Boolean = when (error.category) {
            TransferStorageErrorCategory.IO,
            TransferStorageErrorCategory.PROVIDER,
            -> true

            TransferStorageErrorCategory.INSUFFICIENT_SPACE,
            TransferStorageErrorCategory.CANCELLED,
            -> false

            // Revoked permission and a missing file are only fixable by the user,
            // and are surfaced as such rather than retried in a loop.
            TransferStorageErrorCategory.PERMISSION_REVOKED,
            TransferStorageErrorCategory.NOT_FOUND,
            -> false

            TransferStorageErrorCategory.STATE,
            TransferStorageErrorCategory.UNSUPPORTED,
            TransferStorageErrorCategory.INTEGRITY,
            -> false
        }
    }
}
