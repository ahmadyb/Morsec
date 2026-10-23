/*
 * What the composed path needs from the connection: keying material, and record framing.
 *
 * WHY AN INTERFACE AND NOT THE SOCKET
 *   The composed path must be exercised twice. On a device, against the bundled Conscrypt TLS 1.3
 *   session the coordinator already established, so the exporter material is produced by a real
 *   handshake. On the JVM, where there is no Conscrypt, so the order of the steps, the record
 *   framing and every refusal can be driven deterministically. Handing the path an `SSLSocket`
 *   would make the second impossible and would put socket code inside the proof path.
 *
 * WHAT THE INTERFACE CANNOT DO
 *   It cannot assert a result. It exports bytes, builds an AEAD and moves frames; the only thing
 *   that can say "the PAKE succeeded" is a confirmed `ModpJpakeSession`, and the only thing that can
 *   say "the keys agree" is a confirmation record the peer's AEAD accepted.
 *
 * FRAMING
 *   Two framings share one stream, in order: the exchange's length-prefixed frames, then the record
 *   layer's self-describing MSR1 frames. [PairingRecordIo] is the second; [PairingExchangeIo] is the
 *   first, unchanged from the inert exchange.
 *
 * STATUS
 *   Experimental, project-defined, not independently cryptographically reviewed. The owner accepted
 *   that risk on 2026-10-19; see doc/security/owner-risk-waiver-2026-10-19.md.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.SecureRecordAead
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordCodec
import app.morsecode.core.transfer.session.SecureRecordDirection
import app.morsecode.transport.lan.security.EstablishedTlsSession
import app.morsecode.transport.lan.security.MonotonicSocketDeadline
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

/** One read of a protected record frame, or the reason there is none. */
internal sealed interface PairingRecordRead {
    /** A complete frame, header included, exactly as it crossed the wire. */
    class Frame(val bytes: ByteArray) : PairingRecordRead

    /** The peer closed the stream. */
    data object EndOfStream : PairingRecordRead

    /** No complete frame within the budget. Recoverable: the caller may read again. */
    data object TimedOut : PairingRecordRead

    /** Bytes that cannot be a record of this session. Never recoverable. */
    data object Invalid : PairingRecordRead
}

/**
 * Reads and writes whole protected record frames.
 *
 * Frame boundaries come from the record header, not from a second length prefix: the record layer
 * is the only thing that knows how to delimit its own frames, and duplicating that here would give
 * the two places a way to disagree.
 */
internal interface PairingRecordIo {
    fun readFrame(sessionId: SessionId, budgetMillis: Long): PairingRecordRead

    fun writeFrame(frame: ByteArray, budgetMillis: Long): Boolean

    /** Releases the underlying stream. Idempotent. */
    fun close()
}

/**
 * The production record transport: the same socket the exchange's frames crossed, in order.
 *
 * The header is validated by [SecureRecordCodec.decodeHeader] *before* the ciphertext body is read:
 * magic, version, this session's identifier, the expected direction, a bounded sequence number, a
 * known record type and the declared length all have to hold first. That is what stops a peer from
 * making this allocate an arbitrary buffer by declaring one.
 */
internal class SocketPairingRecordIo(
    private val socket: Socket,
    private val clock: MonotonicClock,
    private val expectedDirection: SecureRecordDirection,
) : PairingRecordIo {

    @Volatile
    private var closed: Boolean = false

    override fun readFrame(sessionId: SessionId, budgetMillis: Long): PairingRecordRead {
        if (closed) return PairingRecordRead.EndOfStream
        if (budgetMillis <= 0L) return PairingRecordRead.TimedOut
        val deadline = MonotonicSocketDeadline("secure-record", socket, budgetMillis, clock::nowMillis)
        val input = try {
            DataInputStream(socket.getInputStream())
        } catch (_: IOException) {
            return PairingRecordRead.Invalid
        }
        return try {
            val header = ByteArray(SecureRecordCodec.HEADER_SIZE_BYTES)
            when (readFully(input, header, 0, header.size, deadline, allowCleanEof = true)) {
                ReadOutcome.CLEAN_EOF -> return PairingRecordRead.EndOfStream
                ReadOutcome.TIMED_OUT_EMPTY -> return PairingRecordRead.TimedOut
                ReadOutcome.SHORT -> return PairingRecordRead.Invalid
                ReadOutcome.DONE -> Unit
            }
            val decoded = SecureRecordCodec.decodeHeader(header, sessionId, expectedDirection)
            if (decoded == null) {
                header.fill(0)
                return PairingRecordRead.Invalid
            }
            // Half a frame is fatal, not retryable: the read cursor has already advanced past the
            // header, so a "timeout" here would silently desynchronise the stream and let the next
            // header be read from the middle of a ciphertext.
            val body = ByteArray(decoded.frameLength - header.size)
            if (readFully(input, body, 0, body.size, deadline, allowCleanEof = false) != ReadOutcome.DONE) {
                body.fill(0)
                header.fill(0)
                return PairingRecordRead.Invalid
            }
            val frame = ByteArray(decoded.frameLength)
            header.copyInto(frame, 0)
            body.copyInto(frame, header.size)
            body.fill(0)
            header.fill(0)
            PairingRecordRead.Frame(frame)
        } catch (_: IOException) {
            if (deadline.isExpired()) PairingRecordRead.TimedOut else PairingRecordRead.Invalid
        } finally {
            deadline.complete()
        }
    }

    override fun writeFrame(frame: ByteArray, budgetMillis: Long): Boolean {
        if (closed || budgetMillis <= 0L) return false
        if (frame.size > SecureRecordCodec.MAX_FRAME_SIZE_BYTES) return false
        val deadline = MonotonicSocketDeadline("secure-record-write", socket, budgetMillis, clock::nowMillis)
        deadline.arm()
        return try {
            val output = socket.getOutputStream()
            output.write(frame)
            output.flush()
            !deadline.isExpired()
        } catch (_: IOException) {
            false
        } finally {
            deadline.complete()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
    }

    override fun toString(): String = "SocketPairingRecordIo(closed=$closed)"

    private enum class ReadOutcome {
        DONE,

        /** The peer closed before a single byte arrived. */
        CLEAN_EOF,

        /** The budget ran out before a single byte arrived. Nothing was consumed; a retry is safe. */
        TIMED_OUT_EMPTY,

        /** A frame was begun and not finished. Never retryable. */
        SHORT,
    }

    private fun readFully(
        input: DataInputStream,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        deadline: MonotonicSocketDeadline,
        allowCleanEof: Boolean,
    ): ReadOutcome {
        var position = offset
        val end = offset + length
        var readAny = false
        while (position < end) {
            val remaining = deadline.remainingMillis()
            if (remaining <= 0L) {
                return if (readAny) ReadOutcome.SHORT else ReadOutcome.TIMED_OUT_EMPTY
            }
            socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            val read = try {
                input.read(buffer, position, end - position)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                return ReadOutcome.SHORT
            }
            if (read < 0) {
                if (readAny) return ReadOutcome.SHORT
                return if (allowCleanEof) ReadOutcome.CLEAN_EOF else ReadOutcome.TIMED_OUT_EMPTY
            }
            if (read == 0) continue
            readAny = true
            position += read
        }
        return ReadOutcome.DONE
    }
}

/**
 * The connection the composed path runs over: TLS facts plus both framings.
 *
 * Production supplies [SocketPairingChannel]; the JVM tests supply a double that shares a byte pipe
 * and a deterministic exporter, and the API 23 test supplies the real Conscrypt session. The
 * interface is deliberately narrow so a double cannot fake a confirmation: it has no method that
 * returns a decision.
 */
internal interface PairingChannel {
    /** The negotiated TLS protocol. The composed path requires exactly `TLSv1.3`. */
    val protocol: String

    /** The negotiated cipher suite, for diagnostics and for the authority's record. */
    val cipherSuite: String

    /** TLS-exported keying material. The only source of record keys in this path. */
    fun export(label: String, context: ByteArray, length: Int): ByteArray

    fun createAead(exportedKey: ByteArray): SecureRecordAead

    fun exchangeIo(): PairingExchangeIo

    fun recordIo(): PairingRecordIo

    fun close()
}

/**
 * The production channel: the Conscrypt session plus the socket underneath it.
 *
 * [EstablishedTlsSession.export] refuses a socket Conscrypt does not own, so this adapter cannot be
 * pointed at a JCA socket by accident.
 */
internal class SocketPairingChannel(
    private val session: EstablishedTlsSession,
    private val clock: MonotonicClock,
    private val localRole: SecurePeerRole,
) : PairingChannel {
    private var records: PairingRecordIo? = null

    override val protocol: String get() = session.protocol

    override val cipherSuite: String get() = session.cipherSuite

    override fun export(label: String, context: ByteArray, length: Int): ByteArray =
        session.export(label, context, length)

    override fun createAead(exportedKey: ByteArray): SecureRecordAead = session.createAead(exportedKey)

    override fun exchangeIo(): PairingExchangeIo = SocketPairingExchangeIo(session.socket, clock)

    override fun recordIo(): PairingRecordIo {
        // The receive direction is the peer's send direction, which is the mirror of this role's.
        val expected = if (localRole == SecurePeerRole.INITIATOR) {
            SecureRecordDirection.RESPONDER_TO_INITIATOR
        } else {
            SecureRecordDirection.INITIATOR_TO_RESPONDER
        }
        return SocketPairingRecordIo(session.socket, clock, expected).also { records = it }
    }

    override fun close() {
        // The TLS session owns the socket; closing it here would close a channel the caller may
        // still be draining. The framings are released, and the caller closes the session.
        records?.close()
        records = null
    }

    override fun toString(): String = "SocketPairingChannel(protocol=$protocol)"
}

/**
 * Wraps an exchange IO so the exchange's own `close()` does not close a socket the record phase
 * still needs.
 *
 * The exchange closes what it is handed when it finishes -- correct when it owns the connection, and
 * wrong here, where the same socket carries the key-confirmation records afterwards. Ownership stays
 * with [PakeSessionCoordinator], which closes the channel exactly once at the end.
 */
internal class NonClosingExchangeIo(private val delegate: PairingExchangeIo) : PairingExchangeIo {
    override fun readFrame(label: String, budgetMillis: Long): PairingFrameRead =
        delegate.readFrame(label, budgetMillis)

    override fun writeFrame(label: String, frame: ByteArray, budgetMillis: Long): Boolean =
        delegate.writeFrame(label, frame, budgetMillis)

    override fun close() = Unit

    override fun toString(): String = "NonClosingExchangeIo"
}
