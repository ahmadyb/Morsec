package app.morsecode.core.storage.transfer

import android.os.OperationCanceledException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException

/*
 * Deciding what a platform exception means.
 *
 * Two classifications matter enough to be shared, because getting either wrong
 * has a specific and expensive consequence:
 *
 * A SecurityException is a revoked grant. Downgrading it to a generic provider
 * failure turns "the user took the permission away" into "try again", and the
 * retry keeps failing for a reason the app never surfaces.
 *
 * A full medium has no exception type of its own. It arrives as an IOException
 * carrying an errno, so the message is the only signal there is. Missing it
 * reports a full destination as an ordinary IO failure that a caller is
 * entitled to retry — and the retry fills it again.
 *
 * Both survive being wrapped by the resolver, so the cause chain is walked. It
 * is walked to a bound, because this is error handling and an unbounded loop
 * driven by another process is not an acceptable shape for error handling.
 */

/** What a platform exception means, decided once and shared. */
internal object StorageFailureClassifier {

    /**
     * How far a cause chain is followed.
     *
     * Bounded: a provider is another process, and a cyclic or pathologically
     * deep chain must not turn classifying a failure into a hang.
     */
    internal const val MAX_CAUSE_DEPTH: Int = 8

    /** Signals that the platform ran out of room. There is no exception type. */
    private val STORAGE_FULL_SIGNALS = arrayOf("ENOSPC", "No space left", "not enough space")

    /** Preserve cancellation, including Android provider cancellation and bounded wrapped causes. */
    fun isCancellation(error: Throwable): Boolean = walk(error) { candidate ->
        when (candidate) {
            is InterruptedException -> {
                Thread.currentThread().interrupt()
                true
            }
            is CancellationException, is InterruptedIOException, is OperationCanceledException -> true
            else -> false
        }
    } || Thread.currentThread().isInterrupted

    /** Whether [error] is the grant having been taken away. */
    fun isRevocation(error: Throwable): Boolean = walk(error) { it is SecurityException }

    /** Whether [error] is the platform reporting a full medium. */
    fun isStorageFull(error: Throwable): Boolean = walk(error) { candidate ->
        val message = candidate.message
        !message.isNullOrBlank() && STORAGE_FULL_SIGNALS.any { message.contains(it, ignoreCase = true) }
    }

    /**
     * The typed error for [error], or null when nothing about it is recognised.
     *
     * Null rather than a default, so a caller has to decide what an
     * unrecognised failure means instead of being handed a plausible-looking
     * answer it did not earn.
     */
    fun classify(
        error: Throwable,
        operation: String,
        access: String = if (operation.contains("read", ignoreCase = true)) "read" else "write",
    ): TransferStorageError? = when {
        isCancellation(error) -> TransferStorageError.Cancelled
        isRevocation(error) -> TransferStorageError.PermissionRevoked(access)
        isStorageFull(error) -> TransferStorageError.StorageFull(operation)
        error is IOException -> TransferStorageError.Io(operation)
        else -> null
    }

    /** The typed error for [error], falling back to a provider failure. */
    fun classifyOrProviderFailure(
        error: Throwable,
        operation: String,
        access: String = if (operation.contains("read", ignoreCase = true)) "read" else "write",
    ): TransferStorageError = classify(error, operation, access)
        ?: TransferStorageError.ProviderFailure("document_provider")

    private inline fun walk(error: Throwable, matches: (Throwable) -> Boolean): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (matches(current)) return true
            current = current.cause
            depth++
        }
        return false
    }
}
