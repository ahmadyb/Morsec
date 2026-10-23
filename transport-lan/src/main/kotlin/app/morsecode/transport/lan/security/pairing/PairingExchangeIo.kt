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
 * The socket is not owned by this class. [close] closes it because a frame transport that outlives
 * its connection is useless, but the coordinator closes the same socket on its own cleanup path and
 * both calls are idempotent.
 */
internal class SocketPairingExchangeIo(
    private val socket: Socket,
    private val clock: MonotonicClock,
) : PairingExchangeIo {

    private val closed = AtomicBoolean(false)

    override fun readFrame(label: String, budgetMillis: Long): PairingFrameRead {
        if (closed.get() || budgetMillis <= 0L) return PairingFrameRead.TimedOut
        val deadline = MonotonicSocketDeadline(label, socket, budgetMillis, clock::nowMillis)
        try {
            val input = socket.getInputStream()
            val prefix = ByteArray(PairingExchangeWire.LENGTH_PREFIX_BYTES)
            // Read the first byte on its own so a clean close is unambiguous: a peer that closed
            // before sending anything produced EndOfStream, while a peer that closed after two
            // bytes produced Invalid.
            if (!armReadTimeout(deadline)) return PairingFrameRead.TimedOut
            val first = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                return PairingFrameRead.TimedOut
            } catch (_: IOException) {
                return if (deadline.isExpired()) PairingFrameRead.TimedOut else PairingFrameRead.Invalid
            }
            if (first < 0) return PairingFrameRead.EndOfStream
            prefix[0] = first.toByte()
            // The first byte is already in the prefix; only the rest of it is still outstanding.
            if (readFully(input, prefix, 1, prefix.size - 1, deadline) != ReadOutcome.DONE) {
                return failedRead(deadline)
            }
            val length = PairingExchangeWire.declaredLength(prefix) ?: return PairingFrameRead.Invalid
            val payload = ByteArray(length)
            if (readFully(input, payload, 0, length, deadline) != ReadOutcome.DONE) {
                return failedRead(deadline)
            }
            val frame = ByteArray(prefix.size + payload.size)
            prefix.copyInto(frame, 0)
            payload.copyInto(frame, prefix.size)
            prefix.fill(0)
            payload.fill(0)
            return PairingFrameRead.Frame(frame)
        } catch (_: IOException) {
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
        try {
            socket.close()
        } catch (_: IOException) {
            // Closing an already-closed or reset socket is the state we wanted.
        }
    }

    override fun toString(): String = "SocketPairingExchangeIo(closed=${closed.get()})"

    private enum class ReadOutcome { DONE, EOF, EXPIRED }

    /**
     * Fills `buffer[offset until offset + length]` from [input], or says why it could not.
     *
     * The caller owns the arithmetic: `offset + length` must fit in [buffer], because an
     * `InputStream.read` past the end of its array is an `IndexOutOfBoundsException` on every
     * platform, thrown from inside the provider where it looks like a transport fault rather than
     * the caller's mistake. The first frame of a pairing is where that shows up.
     */
    private fun readFully(
        input: InputStream,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        deadline: MonotonicSocketDeadline,
    ): ReadOutcome {
        var position = offset
        val end = offset + length
        while (position < end) {
            val remaining = deadline.remainingMillis()
            if (remaining <= 0L) return ReadOutcome.EXPIRED
            armReadTimeout(deadline)
            val read = try {
                input.read(buffer, position, end - position)
            } catch (_: SocketTimeoutException) {
                return ReadOutcome.EXPIRED
            }
            if (read < 0) return ReadOutcome.EOF
            if (read == 0) continue
            position += read
        }
        return ReadOutcome.DONE
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

    private fun failedRead(deadline: MonotonicSocketDeadline): PairingFrameRead =
        if (deadline.isExpired()) PairingFrameRead.TimedOut else PairingFrameRead.Invalid
}
