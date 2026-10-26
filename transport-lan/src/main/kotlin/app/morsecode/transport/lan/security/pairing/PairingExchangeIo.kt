/*
 * The bounded frame transport the pairing exchange runs over.
 *
 * WHY THIS IS AN INTERFACE
 *   The exchange has to be exercisable without an emulator, without TLS and without a network,
 *   because most of what it must get right is protocol behaviour: which frame is allowed at which
 *   point, what happens when a peer abandons a round, and whether a retry leaves both sides in the
 *   same state. Those are properties of the exchange, not of a socket. Keeping the transport behind
 *   an interface lets the tests drive both peers over in-memory pipes with real serialized frames,
 *   while production uses [SocketPairingExchangeIo] over the socket the coordinator already owns.
 *
 * WHAT EVERY IMPLEMENTATION OWES THE EXCHANGE
 *   1. One call returns at most one complete frame, and never more than the declared bound. A peer
 *      must not be able to make the exchange allocate an attacker-chosen amount of memory.
 *   2. Every call is bounded by the budget it is given, and expiry is reported as a distinct
 *      answer rather than as an exception, so a deadline is a typed outcome and not a stack trace.
 *   3. A clean end of stream is distinguishable from a deadline and from a truncated or impossible
 *      frame. They lead to different failures and conflating them would hide a protocol violation
 *      behind a timeout.
 *   4. `close` is idempotent. The exchange calls it exactly once on its own path, but a caller may
 *      also close the underlying socket, and neither may produce an error.
 *
 * STATUS
 *   Experimental and not independently cryptographically reviewed. The owner accepted that risk on
 *   2026-10-19; see doc/security/owner-risk-waiver-2026-10-19.md. This file carries no key
 *   material, no authority and no payload path.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.session.JpakeMessageCodec
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.transport.lan.security.MonotonicSocketDeadline
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** The four answers one bounded frame read can produce. */
internal sealed interface PairingFrameRead {
    /** Exactly one complete frame: the length prefix followed by the declared payload. */
    class Frame(val bytes: ByteArray) : PairingFrameRead

    /** The peer closed cleanly before the first byte of a frame. */
    data object EndOfStream : PairingFrameRead

    /** The budget elapsed with no complete frame. The peer may simply be slow. */
    data object TimedOut : PairingFrameRead

    /**
     * The bytes on the wire cannot be a frame this exchange will look at: an impossible declared
     * length, a truncation, or a transport that failed mid-frame. Never a partial frame.
     */
    data object Invalid : PairingFrameRead
}

/**
 * One direction-agnostic frame channel for the pairing exchange.
 *
 * Both peers hold one; each writes what it sends and reads what the other sent. Implementations
 * must be safe to call from the single thread that drives the exchange.
 */
internal interface PairingExchangeIo {
    /** Reads at most one complete frame within [budgetMillis]. */
    fun readFrame(label: String, budgetMillis: Long): PairingFrameRead

    /** Writes one complete frame and flushes it within [budgetMillis]. False if it did not go out. */
    fun writeFrame(label: String, frame: ByteArray, budgetMillis: Long): Boolean

    /** Releases the channel. Idempotent. */
    fun close()
}

/**
 * Wire facts both implementations share.
 *
 * The widths are taken from [JpakeMessageCodec] rather than restated, so the frame bound here and
 * the round-frame bound in the codec cannot drift apart. A control record is smaller still, but the
 * transport refuses on the *declared* length before deciding which record it is, so it uses the
 * larger of the two bounds: anything above it is not a frame this protocol can produce.
 */
internal object PairingExchangeWire {
    /** The length prefix every recorded frame carries. */
    const val LENGTH_PREFIX_BYTES: Int = JpakeMessageCodec.HEADER_BYTES

    /** The largest payload any frame in this exchange may declare. */
    const val MAX_PAYLOAD_BYTES: Int = JpakeMessageCodec.MAX_FRAME_BYTES

    /** The largest complete frame, prefix included. */
    const val MAX_FRAME_BYTES: Int = LENGTH_PREFIX_BYTES + MAX_PAYLOAD_BYTES

    /** Smallest payload that could be a record at all: magic, version and an empty END tag. */
    private const val MIN_PAYLOAD_BYTES: Int = 4 + 1 + 4

    /**
     * The declared payload length, or null if it cannot describe a frame this exchange accepts.
     *
     * Computed in a `Long` on purpose. A four-byte big-endian value read into an `Int` goes negative
     * above 2 GiB, and a negative length that is then compared with a bound is exactly how an
     * allocation bug gets past a check.
     */
    fun declaredLength(prefix: ByteArray): Int? {
        if (prefix.size != LENGTH_PREFIX_BYTES) return null
        val value = ((prefix[0].toLong() and 0xFF) shl 24) or
            ((prefix[1].toLong() and 0xFF) shl 16) or
            ((prefix[2].toLong() and 0xFF) shl 8) or
            (prefix[3].toLong() and 0xFF)
        if (value < MIN_PAYLOAD_BYTES) return null
        if (value > MAX_PAYLOAD_BYTES) return null
        return value.toInt()
    }
}

/**
 * The production transport: one socket the coordinator already established and owns.
 *
 * Deadlines are the existing [MonotonicSocketDeadline] rather than a second mechanism, with one
 * deliberate difference between the two directions.
 *
 * A *write* is a single blocking call with no way to notice cancellation, so its deadline is armed
 * and expiry closes the socket; that is the only thing that releases a worker parked in `write` on
 * a peer that has stopped reading.
 *
 * A *read* is called by the exchange with a slice of the round budget and is polled repeatedly so
 * cancellation is noticed. Arming a deadline per slice would close the socket the first time a
 * legitimately slow peer -- someone typing a code -- used up fifty milliseconds, so the read path
 * does not arm the timer. It bounds bytes instead: every `read` gets `soTimeout` set to the budget
 * that is left, the loop checks what is left before each one, and the exchange enforces the total
 * by counting down the slices. A peer that dribbles one byte at a time therefore runs out of its
 * own slice, not out of the connection.
 *
 * A slice that expires part-way through a frame is the one thing this design cannot treat as "no
 * frame yet": the bytes already taken are kept and the next call continues the same frame. Losing
 * them would make the rest of that frame look like a new length prefix, which is a permanent
 * desynchronisation of a stream the peer wrote correctly. What bounds a peer is therefore the
 * exchange's total budget for the frame, not any single slice.
 *
 * The socket is not owned by this class. [close] closes it because a frame transport that outlives
 * its connection is useless, but the coordinator closes the same socket on its own cleanup path and
 * both calls are idempotent.
 */
internal class SocketPairingExchangeIo(
    private val socket: Socket,
    private val clock: MonotonicClock,
) : PairingExchangeIo {

    private val closed = AtomicBoolean(false)

    /**
     * The frame currently being assembled, prefix first.
     *
     * WHY STATE SURVIVES A CALL
     *   The exchange polls this transport in short slices so it can notice cancellation between
     *   them. A slice can therefore expire after two of the four length bytes, or after the prefix
     *   and half the payload, have been taken off the stream. Returning "nothing yet" and starting
     *   the next call at a fresh length prefix would then read the rest of that frame *as* a prefix:
     *   the stream desynchronises permanently, and the peer sees a protocol refusal for a frame it
     *   sent correctly. The bytes already consumed are kept here and the next call continues the
     *   same frame, so a deadline costs time and never data. The [PairingRecordIo] sibling in
     *   `PairingChannel.kt` documents the same rule and treats a half frame as fatal; this class can
     *   do better because it is polled, so it keeps the progress instead of discarding it.
     */
    private var assembling: ByteArray? = null

    /** Bytes of [assembling] already filled. */
    private var assembled: Int = 0

    /** Declared payload length, or -1 until the prefix has been read and validated. */
    private var declaredLength: Int = -1

    override fun readFrame(label: String, budgetMillis: Long): PairingFrameRead {
        if (closed.get() || budgetMillis <= 0L) return PairingFrameRead.TimedOut
        val deadline = MonotonicSocketDeadline(label, socket, budgetMillis, clock::nowMillis)
        try {
            val input = socket.getInputStream()
            var buffer = assembling ?: ByteArray(PairingExchangeWire.LENGTH_PREFIX_BYTES).also {
                assembling = it
                assembled = 0
                declaredLength = -1
            }
            // Phase one: the four-byte length prefix.
            if (declaredLength < 0) {
                when (fill(input, buffer, deadline, cleanEofWhenEmpty = assembled == 0)) {
                    Fill.END_OF_STREAM -> {
                        discardAssembly()
                        return PairingFrameRead.EndOfStream
                    }
                    Fill.TIMED_OUT -> return PairingFrameRead.TimedOut
                    Fill.INVALID -> {
                        discardAssembly()
                        return PairingFrameRead.Invalid
                    }
                    Fill.DONE -> Unit
                }
                val length = PairingExchangeWire.declaredLength(buffer) ?: run {
                    discardAssembly()
                    return PairingFrameRead.Invalid
                }
                val whole = ByteArray(PairingExchangeWire.LENGTH_PREFIX_BYTES + length)
                buffer.copyInto(whole, 0)
                buffer.fill(0)
                buffer = whole
                assembling = whole
                // The prefix is already in the new buffer; only the payload is outstanding.
                assembled = PairingExchangeWire.LENGTH_PREFIX_BYTES
                declaredLength = length
            }
            // Phase two: the declared payload.
            when (fill(input, buffer, deadline, cleanEofWhenEmpty = false)) {
                Fill.TIMED_OUT -> return PairingFrameRead.TimedOut
                // A frame that was begun and not finished is never a clean close: reporting it as
                // one would let a truncated frame read as an orderly peer that had finished.
                Fill.END_OF_STREAM, Fill.INVALID -> {
                    discardAssembly()
                    return PairingFrameRead.Invalid
                }
                Fill.DONE -> Unit
            }
            // The completed frame is handed over, so it is released without being wiped: wiping
            // here would zero the very bytes the caller is about to read.
            releaseAssembly()
            return PairingFrameRead.Frame(buffer)
        } catch (_: IOException) {
            discardAssembly()
            return if (deadline.isExpired()) PairingFrameRead.TimedOut else PairingFrameRead.Invalid
        } finally {
            deadline.complete()
        }
    }

    override fun writeFrame(label: String, frame: ByteArray, budgetMillis: Long): Boolean {
        if (closed.get() || budgetMillis <= 0L) return false
        if (frame.size > PairingExchangeWire.MAX_FRAME_BYTES) return false
        val deadline = MonotonicSocketDeadline(label, socket, budgetMillis, clock::nowMillis)
        deadline.arm()
        try {
            val output = socket.getOutputStream()
            output.write(frame)
            // Flushing is part of the contract, not an optimisation: an unflushed frame leaves the
            // peer waiting for bytes that exist only in a buffer, and the peer cannot tell that
            // from a dead connection.
            output.flush()
            return !deadline.isExpired()
        } catch (_: IOException) {
            return false
        } finally {
            deadline.complete()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        discardAssembly()
        try {
            socket.close()
        } catch (_: IOException) {
            // Closing an already-closed or reset socket is the state we wanted.
        }
    }

    override fun toString(): String = "SocketPairingExchangeIo(closed=${closed.get()})"

    private enum class Fill { DONE, TIMED_OUT, END_OF_STREAM, INVALID }

    /**
     * Fills the frame under construction, recording progress in [assembled] whatever the outcome.
     *
     * The caller owns no arithmetic here: the fill position is the field, so a slice that expires
     * mid-frame leaves the next call exactly where this one stopped. `read` is bounded by the
     * remaining budget for every attempt, which is why an unresponsive peer costs a deadline and not
     * a hung worker.
     */
    private fun fill(
        input: InputStream,
        buffer: ByteArray,
        deadline: MonotonicSocketDeadline,
        cleanEofWhenEmpty: Boolean,
    ): Fill {
        while (assembled < buffer.size) {
            if (!armReadTimeout(deadline)) return Fill.TIMED_OUT
            val read = try {
                input.read(buffer, assembled, buffer.size - assembled)
            } catch (_: SocketTimeoutException) {
                return Fill.TIMED_OUT
            } catch (_: IOException) {
                return if (deadline.isExpired()) Fill.TIMED_OUT else Fill.INVALID
            }
            if (read < 0) {
                return when {
                    // Nothing of this frame has been taken, so the peer is allowed to have closed.
                    assembled == 0 && cleanEofWhenEmpty -> Fill.END_OF_STREAM
                    assembled == 0 -> Fill.TIMED_OUT
                    // A frame that was begun and not finished: never a clean close.
                    else -> Fill.INVALID
                }
            }
            if (read == 0) continue
            assembled += read
        }
        return Fill.DONE
    }

    /** Drops a refused or abandoned frame, wiping whatever was read into it. */
    private fun discardAssembly() {
        assembling?.fill(0)
        releaseAssembly()
    }

    /** Forgets the frame under construction without touching its bytes. */
    private fun releaseAssembly() {
        assembling = null
        assembled = 0
        declaredLength = -1
    }

    /**
     * Sets the per-read timeout from what is left of this call's budget.
     *
     * False when nothing is left, which is a deadline and not a broken frame. The timeout is always
     * at least one millisecond because zero means "infinite" to [Socket].
     */
    private fun armReadTimeout(deadline: MonotonicSocketDeadline): Boolean {
        val remaining = deadline.remainingMillis()
        if (remaining <= 0L) return false
        socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
        return true
    }
}
