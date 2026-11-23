package app.morsecode.core.storage.transfer

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** The runtime-facing result of a bounded source read. It never contains an exception or a buffer. */
public sealed interface SourceReadResult {
    /** A positive number of bytes was placed in the caller's buffer. */
    public data class Data(public val count: Int) : SourceReadResult

    public data object EndOfSource : SourceReadResult

    public data class Failure(public val error: TransferStorageError) : SourceReadResult
}

/** Opening the typed reader either transfers ownership of a handle or returns a typed failure. */
public sealed interface TypedSourceOpen {
    public data class Opened(public val reader: TypedSourceReader, public val offset: Long) : TypedSourceOpen
    public data class Failed(public val error: TransferStorageError) : TypedSourceOpen
}

/**
 * The sole runtime-facing read boundary around the existing [SourceHandle].
 *
 * A reader has one read owner. [close] is idempotent even when another thread closes it to cancel a
 * blocking read. End of source, provider failure, impossible results and cancellation all close the
 * handle automatically; a caller should still close it in `finally`. No caller buffer is retained.
 *
 * A zero-length request is refused, and a provider returning zero for a positive request fails on
 * that very call. There is consequently no retry loop in which zero progress can spin forever. A
 * failure is terminal because a failing provider may already have consumed an unknown number of
 * bytes; resuming requires a fresh open and the normal reconciliation policy.
 *
 * Cancellation exceptions and thread interruption become the typed [TransferStorageError.Cancelled]
 * outcome, rather than an I/O error. Coroutine callers must propagate that outcome as cancellation.
 * Other provider exceptions are classified here, without retaining their message, cause or stack.
 */
public class TypedSourceReader(private val handle: SourceHandle) : Closeable {
    private val closed = AtomicBoolean(false)
    @Volatile private var terminal: SourceReadResult? = null

    public fun read(buffer: ByteArray, offset: Int, length: Int): SourceReadResult {
        terminal?.let { return it }
        if (closed.get()) return SourceReadResult.Failure(TransferStorageError.StateConflict("source_closed"))
        if (offset < 0 || length <= 0 || offset > buffer.size || length > buffer.size - offset) {
            // Invalid arguments do not touch or close the provider; the owner may correct them.
            return SourceReadResult.Failure(TransferStorageError.StateConflict("invalid_read_range"))
        }
        if (Thread.currentThread().isInterrupted) {
            return finish(SourceReadResult.Failure(TransferStorageError.Cancelled))
        }

        val count = try {
            handle.read(buffer, offset, length)
        } catch (error: Exception) {
            val failure = if (isCancellation(error) || closed.get()) {
                TransferStorageError.Cancelled
            } else {
                StorageFailureClassifier.classifyOrProviderFailure(error, "read", "read")
            }
            return finish(SourceReadResult.Failure(failure))
        }
        if (Thread.currentThread().isInterrupted) {
            return finish(SourceReadResult.Failure(TransferStorageError.Cancelled))
        }
        if (closed.get()) {
            // A close racing an in-flight read is the owner's cancellation of that operation.
            return finish(SourceReadResult.Failure(TransferStorageError.Cancelled))
        }
        return when {
            count == -1 -> finish(SourceReadResult.EndOfSource)
            count < -1 || count > length -> finish(
                SourceReadResult.Failure(TransferStorageError.ProviderFailure("document_provider")),
            )
            count == 0 -> finish(
                SourceReadResult.Failure(TransferStorageError.ZeroProgress(steps = 1, bytesRemaining = length.toLong())),
            )
            else -> SourceReadResult.Data(count)
        }
    }

    private fun finish(result: SourceReadResult): SourceReadResult {
        terminal = result
        close()
        return result
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            handle.close()
        } catch (_: Exception) {
            // Closing is best effort; neither its message nor its exception escapes the boundary.
        }
    }

    override fun toString(): String = "TypedSourceReader([redacted])"

    public companion object {
        /**
         * Open through the typed boundary, without exposing a raw handle to the caller. A nonzero
         * offset requires a digest-bearing resume decision and a fresh fingerprint comparison.
         */
        public fun openAt(
            source: TransferSource,
            offset: Long,
            resumeAuthorization: SourceResumeDecision.Ready? = null,
        ): TypedSourceOpen {
            if (offset < 0L) {
                return TypedSourceOpen.Failed(TransferStorageError.StateConflict("negative_offset"))
            }
            if (Thread.currentThread().isInterrupted) {
                return TypedSourceOpen.Failed(TransferStorageError.Cancelled)
            }
            if (offset > 0L) {
                val authorization = resumeAuthorization ?: return TypedSourceOpen.Failed(
                    TransferStorageError.StateConflict("resume_authorization_required"),
                )
                val revalidated = try {
                    authorization.revalidate(source.fingerprint())
                } catch (error: Exception) {
                    return TypedSourceOpen.Failed(
                        if (isCancellation(error)) TransferStorageError.Cancelled
                        else StorageFailureClassifier.classifyOrProviderFailure(error, "query", "read"),
                    )
                }
                if (revalidated is SourceResumeDecision.Refused) return TypedSourceOpen.Failed(revalidated.error)
                if (Thread.currentThread().isInterrupted) return TypedSourceOpen.Failed(TransferStorageError.Cancelled)
            }
            val opened = try {
                source.openAt(offset)
            } catch (error: Exception) {
                return TypedSourceOpen.Failed(
                    if (isCancellation(error)) TransferStorageError.Cancelled
                    else StorageFailureClassifier.classifyOrProviderFailure(error, "open", "read"),
                )
            }
            return when (opened) {
                is SourceOpenResult.Failed -> TypedSourceOpen.Failed(
                    if (Thread.currentThread().isInterrupted) TransferStorageError.Cancelled
                    else redactOpenFailure(opened.error),
                )
                is SourceOpenResult.Opened -> {
                    val reader = TypedSourceReader(opened.handle)
                    when {
                        Thread.currentThread().isInterrupted -> {
                            reader.close()
                            TypedSourceOpen.Failed(TransferStorageError.Cancelled)
                        }
                        opened.offset != offset || opened.handle.openedAtOffset != offset -> {
                            reader.close()
                            TypedSourceOpen.Failed(TransferStorageError.StateConflict("source_offset_mismatch"))
                        }
                        else -> TypedSourceOpen.Opened(reader, opened.offset)
                    }
                }
            }
        }

        /** Open adapters predate this boundary and can carry diagnostics; do not forward them. */
        private fun redactOpenFailure(error: TransferStorageError): TransferStorageError = when (error.category) {
            TransferStorageErrorCategory.PERMISSION_REVOKED -> TransferStorageError.PermissionRevoked("read")
            TransferStorageErrorCategory.NOT_FOUND -> TransferStorageError.NotFound("source")
            TransferStorageErrorCategory.IO -> TransferStorageError.Io("open")
            TransferStorageErrorCategory.PROVIDER -> TransferStorageError.ProviderFailure("document_provider")
            TransferStorageErrorCategory.INSUFFICIENT_SPACE -> TransferStorageError.StorageFull("open")
            TransferStorageErrorCategory.STATE -> TransferStorageError.StateConflict("source_open_failed")
            TransferStorageErrorCategory.UNSUPPORTED -> TransferStorageError.Unsupported("source_open")
            TransferStorageErrorCategory.CANCELLED -> TransferStorageError.Cancelled
            TransferStorageErrorCategory.INTEGRITY -> TransferStorageError.IntegrityMismatch("source")
        }

        /** Use the same bounded cancellation classification as the source adapters. */
        private fun isCancellation(error: Throwable): Boolean = StorageFailureClassifier.isCancellation(error)
    }
}
