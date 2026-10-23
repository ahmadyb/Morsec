/*
 * The two seams the composed PAKE path needs from the connection it runs over.
 *
 * WHY TWO SEAMS AND NOT ONE
 *   The path speaks twice on the same TLS stream, in two different formats. First it carries the
 *   pairing's control records -- attempt start, the three rounds, a failure verdict, the approval --
 *   as length-prefixed frames. Then, once the record layer has been derived, it carries
 *   key-confirmation records protected with AES-GCM. Both are byte streams on one socket; they
 *   differ in framing, not in ownership.
 *
 *   Keeping them as `PairingExchangeIo` (already written for the inert exchange) plus a small
 *   [PairingRecordIo] means the composed path never touches a socket directly, and a test can drive
 *   it over the same in-memory channels the exchange tests use. It also means the path cannot read
 *   the peer's plaintext: everything that comes back is either a framed control record or an
 *   authenticated record the record layer accepted.
 *
 * WHY [PairingChannel] EXISTS
 *   The composed path needs three things from TLS: the negotiated protocol, the exporter and the
 *   AEAD factory. It must not need the `SSLSocket` itself, because the transport modules differ:
 *   production has one, the JVM tests have no Conscrypt at all. [TlsPairingChannel] is the
 *   production adapter; the test adapter in `src/test` supplies deterministic exporter bytes and is
 *   labelled as such. Neither can express "the PAKE succeeded": the exporter only derives keys, and
 *   the protocol string is compared against `TLSv1.3` before anything else happens.
 *
 * STATUS
 *   Experimental, project-defined and not independently cryptographically reviewed. The owner
 *   accepted that risk on 2026-10-19; see doc/security/owner-risk-waiver-2026-10-19.md.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordAead
import app.morsecode.core.transfer.session.SecureRecordCodec
import app.morsecode.core.transfer.session.SecureRecordLayer
import app.morsecode.core.transfer.session.SecureRecordResult
import app.morsecode.transport.lan.security.DeadlineOutcome
import app.morsecode.transport.lan.security.EstablishedTlsSession
import app.morsecode.transport.lan.security.MonotonicSocketDeadline
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

/** One protected record frame, or why there is none. */
internal sealed interface PairingRecordRead {
    /** A frame the record layer authenticated. [frame] is still encrypted; hand it to `unprotect`. */
    class Frame(val frame: ByteArray) : PairingRecordRead

    data object EndOfStream : PairingRecordRead

    data object TimedOut : PairingRecordRead

    /** A frame that cannot be one this protocol produces. Never partially read. */
    data object Invalid : PairingRecordRead
}

/**
 * Reads and writes protected record frames over the same stream the PAKE frames used.
 *
 * The interface exists so the composed path can be driven over an in-memory channel. The frame
 * bounds, the deadline behaviour and the "one call returns at most one complete frame" rule are the
 * same obligations [PairingExchangeIo] carries, for the same reasons.
 */
internal interface PairingRecordIo {
    fun readRecordFrame(budgetMillis: Long): PairingRecordRead

    fun writeRecordFrame(frame: ByteArray, budgetMillis: Long): Boolean

    fun close()
}

/**
 * The production record transport: the socket the PAKE frames already crossed.
 *
 * It shares the socket with `SocketPairingExchangeIo`, so it must not re-buffer or re-open anything:
 * a record follows the last PAKE frame on the same byte stream, in order.
 */
internal class SocketPairingRecordIo(
    private val socket: Socket,
    private val sessionId: SessionId,
    private val localRole: SecurePeerRole,
    private val clock: MonotonicClock,
) : PairingRecordIo {

    @Volatile
    private var closed: Boolean = false

    /**
     * Reads one whole frame, or reports why there is none.
     *
     * The deadline is checked, never armed: arming aborts the socket, which is the right behaviour
     * for a blocked write and the wrong one for a read that may still be waiting on a peer which is
     * itself waiting on a human. Exhausting the budget here abandons the socket to its owner rather
     * than killing it.
     */
    override fun readRecordFrame(budgetMillis: Long): PairingRecordRead {
        if (closed) return PairingRecordRead.EndOfStream
        if (budgetMillis <= 0L) return PairingRecordRead.TimedOut
        val input = try {
            DataInputStream(socket.inputStream)
        } catch (_: IOException) {
            return PairingRecordRead.Invalid
        }
        val deadline = MonotonicSocketDeadline("secure-record-read", socket, budgetMillis, clock::nowMillis)
        try {
            val header = ByteArray(SecureRecordCodec.HEADER_SIZE_BYTES)
            when (readFully(input, header, 0, header.size, deadline, cleanEofAllowed = true)) {
                ReadProgress.COMPLETE -> Unit
                ReadProgress.CLEAN_EOF -> return PairingRecordRead.EndOfStream
                ReadProgress.EXPIRED -> return PairingRecordRead.TimedOut
                ReadProgress.BROKEN -> return PairingRecordRead.Invalid
            }
            // The codec's own header check, not a hand-rolled one: it validates the magic, version,
            // this exact session id, the expected direction, the sequence number, the type and the
            // declared length before a single byte is allocated for the ciphertext.
            val expectedDirection = SecureRecordLayer.directionFrom(localRole).opposite
            val decoded = SecureRecordCodec.decodeHeader(header, sessionId, expectedDirection)
                ?: return PairingRecordRead.Invalid
            val frame = ByteArray(decoded.frameLength)
            header.copyInto(frame)
            val bodyLength = decoded.frameLength - header.size
            val progress = readFully(input, frame, header.size, bodyLength, deadline, cleanEofAllowed = false)
            if (progress != ReadProgress.COMPLETE) {
                frame.fill(0)
                return when (progress) {
                    ReadProgress.EXPIRED -> PairingRecordRead.TimedOut
                    ReadProgress.CLEAN_EOF, ReadProgress.BROKEN -> PairingRecordRead.Invalid
                    ReadProgress.COMPLETE -> PairingRecordRead.Invalid
                }
            }
            return PairingRecordRead.Frame(frame)
        } finally {
            deadline.complete()
        }
    }

    /**
     * Writes one whole frame, or gives up.
     *
     * The deadline is armed here. A write blocked on a peer that stopped reading has no socket-level
     * timeout, so the only way out is to close the socket, and the deadline already does exactly
     * that when its budget runs out.
     */
    override fun writeRecordFrame(frame: ByteArray, budgetMillis: Long): Boolean {
        if (closed || budgetMillis <= 0L) return false
        if (frame.size > SecureRecordCodec.MAX_FRAME_SIZE_BYTES) return false
        val deadline = MonotonicSocketDeadline("secure-record-write", socket, budgetMillis, clock::nowMillis)
        deadline.arm()
        try {
            val output = DataOutputStream(socket.outputStream)
            output.write(frame)
            output.flush()
            return deadline.complete() == DeadlineOutcome.Resolved
        } catch (_: IOException) {
            return false
        }
    }

    /** Stops this reader. The socket belongs to its owner, so it is not closed from here. */
    override fun close() {
        closed = true
    }

    override fun toString(): String = "SocketPairingRecordIo(closed=$closed, ids=[redacted])"

    private fun readFully(
        input: DataInputStream,
        frame: ByteArray,
        offset: Int,
        length: Int,
        deadline: MonotonicSocketDeadline,
        cleanEofAllowed: Boolean,
    ): ReadProgress {
        var position = offset
        val end = offset + length
        var readAny = false
        while (position < end) {
            val remaining = deadline.remainingMillis()
            if (remaining <= 0L) return ReadProgress.EXPIRED
            val read = try {
                socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                input.read(frame, position, end - position)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                return ReadProgress.BROKEN
            }
            if (read < 0) {
                return if (!readAny && cleanEofAllowed) ReadProgress.CLEAN_EOF else ReadProgress.BROKEN
            }
            if (read == 0) continue
            readAny = true
            position += read
        }
        return ReadProgress.COMPLETE
    }

    private enum class ReadProgress { COMPLETE, CLEAN_EOF, EXPIRED, BROKEN }
}

/**
 * The TLS facts the composed path needs, and nothing else.
 *
 * [export] is the RFC 5705 / RFC 9266 exporter. It is the only source of keying material in this
 * path: PAKE keys come from the entered code inside the PAKE library, and record keys come from
 * here, bound to the transcript digest. Neither can be supplied by a caller, which is what stops a
 * "preconstructed success" from being expressible.
 */
internal interface PairingChannel : AutoCloseable {
    /** The negotiated TLS protocol. Compared against `TLSv1.3` before anything is derived. */
    val protocol: String

    /** The negotiated cipher suite, bound into the transcript by the caller. */
    val cipherSuite: String

    fun export(label: String, context: ByteArray, length: Int): ByteArray

    fun createAead(exportedKey: ByteArray): SecureRecordAead
}

/**
 * Production adapter: TLS through the bundled Conscrypt provider.
 *
 * It is a thin wrapper on purpose. Every check that matters -- protocol, exporter labels, key
 * widths -- lives in `EstablishedTlsSession` already, and duplicating it here would give two places
 * for it to disagree with itself.
 */
internal class TlsPairingChannel(private val session: EstablishedTlsSession) : PairingChannel {
    override val protocol: String get() = session.protocol

    override val cipherSuite: String get() = session.cipherSuite

    override fun export(label: String, context: ByteArray, length: Int): ByteArray =
        session.export(label, context, length)

    override fun createAead(exportedKey: ByteArray): SecureRecordAead = session.createAead(exportedKey)

    override fun close() {
        session.clearFingerprints()
    }

    override fun toString(): String = "TlsPairingChannel(protocol=$protocol, ids=[redacted])"
}

/**
 * Reads one protected record and hands it to [layer] for authentication.
 *
 * Shared by the coordinator and by the tests so both exercise the same framing. A record that the
 * layer refuses is reported as null rather than as a record: an unauthenticated frame is not a
 * message, and the caller must not see its bytes.
 */
internal fun readProtectedRecord(
    io: PairingRecordIo,
    layer: SecureRecordLayer,
    budgetMillis: Long,
): SecureRecordResult? {
    val frame = when (val read = io.readRecordFrame(budgetMillis)) {
        is PairingRecordRead.Frame -> read.frame
        PairingRecordRead.EndOfStream, PairingRecordRead.TimedOut, PairingRecordRead.Invalid -> return null
    }
    return try {
        // A record that cannot be authenticated is not a message. `unprotect` reports that as a
        // Failed result; anything it throws is treated the same way rather than escaping the
        // coordinator, because a caller must never see a partially trusted frame.
        runCatching { layer.unprotect(frame) }.getOrElse { null }
    } finally {
        frame.fill(0)
    }
}
