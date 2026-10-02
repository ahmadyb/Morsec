package app.morsecode.core.storage.transfer

import android.os.ParcelFileDescriptor
import java.io.Closeable

/*
 * Descriptor ownership.
 *
 * A ParcelFileDescriptor handed out by a ContentResolver is a real file
 * descriptor, and wrapping it in an InputStream or a FileChannel creates the
 * classic ambiguity: closing the stream closes the descriptor, closing the
 * descriptor closes the descriptor, and whichever happens second either throws
 * or — far worse — closes a descriptor that another component has since been
 * handed by the kernel.
 *
 * The rule here is that exactly one object owns the descriptor and closes it,
 * and that closing is idempotent so the second close is a no-op rather than a
 * crash or a use-after-free. Derived streams and channels are *lent* by the
 * handle and must not be closed by the caller; the handle closes them, once, in
 * its own close().
 *
 * `closeCount` exists so a test can prove the descriptor was closed exactly
 * once on each of the six paths ADR-0003 §5 names: success, seek failure, read
 * failure, write failure, verification failure and cancellation.
 */
public class OwnedFileDescriptor internal constructor(
    private var delegate: ParcelFileDescriptor?,
) : Closeable {

    public val isOpen: Boolean get() = delegate != null

    /**
     * The raw descriptor number, or -1 once closed. Useful in a diagnostic only;
     * it is never persisted, because a descriptor number is meaningless after
     * the process that minted it has gone.
     */
    public val rawFd: Int get() = delegate?.fd ?: -1

    /** How many times this object has actually closed the descriptor. */
    public var closeCount: Int = 0
        private set

    internal fun orNull(): ParcelFileDescriptor? = delegate

    override fun close() {
        val current = delegate ?: return
        delegate = null
        closeCount++
        runCatching { current.close() }
    }
}

/**
 * Owns an arbitrary closeable and anything derived from it, and closes each
 * exactly once.
 *
 * The generic form of [OwnedSourceResource] for resources that are not
 * descriptors — a `RandomAccessFile`, for instance. App-private partials use a
 * `RandomAccessFile` rather than a `FileOutputStream` precisely because a channel
 * taken from a `FileOutputStream` is write-only, so verification could not read
 * the file back through it, and rather than `FileChannel.open` because that needs
 * `java.nio.file` and therefore API 26, while this module supports API 23.
 */
public class OwnedResource internal constructor(
    primary: Closeable,
) : Closeable {

    private var primary: Closeable? = primary
    private val derived = mutableListOf<Closeable>()

    /** How many times this object has actually closed the primary resource. */
    public var closeCount: Int = 0
        private set

    /** How many derived resources this object has closed. */
    public var derivedCloseCount: Int = 0
        private set

    /**
     * Registers a resource derived from the primary one so that [close] closes it
     * too. The caller must not close it as well.
     */
    internal fun <T : Closeable> adopt(value: T): T {
        derived += value
        return value
    }

    override fun close() {
        derived.asReversed().forEach { closeable ->
            derivedCloseCount++
            runCatching { closeable.close() }
        }
        derived.clear()
        val current = primary ?: return
        primary = null
        closeCount++
        runCatching { current.close() }
    }
}

/**
 * Owns a descriptor and a stream derived from it, and closes both exactly once,
 * stream first then descriptor.
 *
 * This is the narrow piece of machinery that lets callers write `use {}` around
 * a handle without having to remember which of the two things underneath it they
 * are responsible for.
 */
public class OwnedSourceResource internal constructor(
    private val descriptor: OwnedFileDescriptor,
) : Closeable {

    private val derived = mutableListOf<Closeable>()

    /** How many derived streams or channels this resource has closed. */
    public var derivedCloseCount: Int = 0
        private set

    internal fun descriptor(): OwnedFileDescriptor = descriptor

    /**
     * Registers a stream or channel derived from the owned descriptor so that
     * [close] closes it too. The caller must not close it as well.
     */
    internal fun <T : Closeable> adopt(value: T): T {
        derived += value
        return value
    }

    override fun close() {
        derived.asReversed().forEach { closeable ->
            derivedCloseCount++
            runCatching { closeable.close() }
        }
        derived.clear()
        descriptor.close()
    }
}
