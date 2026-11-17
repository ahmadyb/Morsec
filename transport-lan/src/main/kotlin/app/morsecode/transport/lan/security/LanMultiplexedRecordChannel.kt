package app.morsecode.transport.lan.security

import app.morsecode.core.transfer.protocol.FrameCodec
import app.morsecode.core.transfer.protocol.FrameDecodeResult
import app.morsecode.core.transfer.protocol.TransferFrame
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.SecureControlRecord
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordCodec
import app.morsecode.core.transfer.session.SecureRecordDirection
import app.morsecode.core.transfer.session.SecureRecordLayer
import app.morsecode.core.transfer.session.SecureRecordResult
import app.morsecode.core.transfer.session.SecureRecordType
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SessionFailure
import app.morsecode.core.transfer.session.SessionFailureCode
import app.morsecode.transport.lan.payload.LanPayloadRecordCodec
import app.morsecode.transport.lan.payload.LanPayloadRecordFormat
import app.morsecode.transport.lan.payload.LanPayloadProtectResult
import app.morsecode.transport.lan.payload.LanPayloadRecordLayer
import app.morsecode.transport.lan.payload.LanPayloadUnprotectResult
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/*
 * One established TLS socket carrying both control and payload records.
 *
 * WHAT THIS IS, AND WHAT IT IS NOT
 *   It is the shared owner of a single byte stream on which MSR1 control records and MPL1 payload
 *   records are interleaved. It owns the socket, both record layers, one reader and one writer
 *   arbiter, and one close lifecycle. It is not a transfer engine: it has no file, no chunk loop, no
 *   storage, no runtime, no service and no caller in production. Nothing constructs it outside the
 *   tests in this module, and the section that describes it in doc/transfer-protocol.md says the
 *   same thing.
 *
 * ONE READER, TWO FORMATS
 *   [receiveNext] is the only method that reads the socket. It reads exactly four bytes, decides
 *   which format the record claims to be, and then reads that record's fixed header and its exact
 *   remaining length. There is no forward scan for a magic value, no resynchronisation after a bad
 *   byte, and no second method that could consume the same stream from another thread. Two callers
 *   that reach [receiveNext] at once are serialised by one lock, so a record is never split between
 *   them; the second caller waits for the first to return.
 *
 *   Bytes already read are never discarded. A polling slice exists only so a blocked read can check
 *   for cancellation; when a slice expires with a header or a body half-read, the same array keeps
 *   accumulating and the same operation continues. The absolute deadline is the lifetime of the
 *   operation, and expiry means the record is abandoned -- which is terminal, because a stream whose
 *   next byte is the middle of a record has no boundary left to trust.
 *
 * WHY THE FOUR-BYTE PREFIX IS DISPATCH, NOT PARSING
 *   MSR1 and MPL1 share no magic byte, so four bytes identify the format without ambiguity. The
 *   remaining header is then handed to the record layer that owns that format: `SecureRecordCodec`
 *   for control (which validates session, direction, sequence and type/length) and
 *   `LanPayloadRecordCodec` for payload (which validates the declared length against the fixed
 *   ceiling before anything is allocated). This class frames; it does not authenticate. Every AEAD
 *   decision stays inside `SecureRecordLayer` and `LanPayloadRecordLayer`, so there is exactly one
 *   implementation of each cipher rule on this stream.
 *
 * ONE WRITER, COMPLETE RECORDS
 *   Every write takes one arbiter. While it holds ownership it seals exactly one record and writes
 *   that whole record, then flushes, then releases. `protect` is called only after ownership is held,
 *   so the sequence order of a layer is the order its bytes appear on the stream, and two records can
 *   never interleave.
 *
 *   Control has priority: a control sender registers as waiting before it competes, and a payload
 *   sender may start only when no control sender is waiting. A record already being written is never
 *   interrupted -- priority chooses the next record, not the current one. Continuous control traffic
 *   cannot starve payload forever: after [MAX_CONSECUTIVE_CONTROL_WRITES] consecutive control
 *   records, a waiting payload record is granted the next slot.
 *
 *   A sealed record is never retried. Once `protect` returns, its sequence and nonce are spent, so a
 *   partial write, a write timeout or a socket exception ends the connection rather than the record.
 *
 * CLOSING
 *   Close is idempotent and closes the control layer, the payload layer and the socket, exactly once
 *   between them. It also wakes every blocked reader and writer: readers because the socket breaks,
 *   writers because the arbiter's condition is signalled. A layer that fails closes itself, and this
 *   class turns every such refusal into a terminal outcome, so a payload failure can never leave the
 *   control half of the stream usable.
 *
 * TYPED PAYLOAD BOUNDARY
 *   Payload is a [TransferFrame], not an arbitrary array. [sendPayloadFrame] encodes exactly one MSC1
 *   frame into the MPL1 plaintext, and [receiveNext] decodes exactly one MSC1 frame out of it with
 *   `FrameCodec.decodeSingle`, which rejects an empty plaintext, a malformed frame and a plaintext
 *   with trailing bytes. There is deliberately no `sendPayload(bytes: ByteArray)` anywhere in this
 *   class, so no caller can put arbitrary bytes on the payload path and call it a frame.
 *
 * STATUS
 *   Inert. No production call site constructs this type; the live provider still installs the
 *   control-only channel, `PayloadTransferGate` still refuses every session, `CURRENT_MILESTONE` is
 *   2 and `TRANSFER_ENGINE` is still gated. Experimental, project-defined, not independently
 *   cryptographically reviewed; see doc/security/owner-risk-waiver-2026-10-19.md.
 */

/** What one call to [LanMultiplexedRecordChannel.receiveNext] produced. */
internal sealed interface LanMultiplexedReceive {
    /** One authenticated control record. */
    class Control(val record: SecureControlRecord) : LanMultiplexedReceive

    /** One authenticated payload record, unwrapped into exactly one MSC1 frame. */
    class Payload(val frame: TransferFrame) : LanMultiplexedReceive

    /**
     * A terminal refusal. The channel is closed by the time this is returned.
     *
     * The code is one of the existing typed codes; no peer-controlled text is carried.
     */
    class Refused(val failure: SessionFailure) : LanMultiplexedReceive

    /**
     * The absolute deadline passed.
     *
     * [streamUsable] is true only when no byte of the next record had been consumed, which is the
     * one case where the stream still has a trustworthy boundary. When it is false the channel has
     * been closed, because the position of the next record is unknown.
     */
    class TimedOut(val streamUsable: Boolean) : LanMultiplexedReceive

    /**
     * A local cancellation was observed at a polling slice.
     *
     * [streamUsable] carries the same meaning as in [TimedOut]. Cancellation is a local decision,
     * not evidence about the peer.
     */
    class Cancelled(val streamUsable: Boolean) : LanMultiplexedReceive
}

/** The outcome of one send. */
internal sealed interface LanMultiplexedSend {
    /** The complete record reached the socket and was flushed. */
    object Sent : LanMultiplexedSend

    /** The record was not written, or the write failed. A failure here always closes the channel. */
    class Refused(val failure: SessionFailure) : LanMultiplexedSend
}

internal class LanMultiplexedRecordChannel(
    private val socket: Socket,
    private val controlLayer: SecureRecordLayer,
    private val payloadLayer: LanPayloadRecordLayer,
    private val role: SecurePeerRole,
    private val clock: MonotonicClock,
    private val expiresAtElapsedMillis: Long,
    /** Checked once per polling slice, so a blocked read can abandon a peer that went quiet. */
    private val isCancelled: () -> Boolean = { false },
    /** Runs once, after the layers and the socket are closed. Best effort, never rethrown. */
    private val onTerminal: () -> Unit = {},
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    /**
     * The failure that ended this channel, kept so every later caller sees the same one.
     *
     * A writer that was waiting for ownership when the socket died must not be told something
     * different from the writer that hit the failure, and a reader that arrives afterwards must not
     * be told the channel simply closed. First cause wins; nothing overwrites it.
     */
    private val terminal = AtomicReference<SessionFailure?>(null)

    /** One reader. Held for the whole of [receiveNext] so two callers cannot split a record. */
    private val readLock = Any()

    private val writeLock = ReentrantLock()
    private val writerAvailable = writeLock.newCondition()
    private var writing = false
    private var controlWaiting = 0
    private var payloadWaiting = 0
    private var consecutiveControlWrites = 0

    private var lastNowElapsedMillis: Long = -1L

    fun isOpen(): Boolean = !closed.get()

    // --- receive ---------------------------------------------------------------------------------

    /**
     * Reads exactly one record and returns it typed.
     *
     * The deadline is absolute, in the same monotonic milliseconds as the session. When it is not
     * supplied the call gets the smaller of the control read budget and the session's remaining
     * lifetime, so a record can never outlive the session that authorises it.
     */
    fun receiveNext(
        deadlineElapsedMillis: Long = operationDeadlineMillis(),
    ): LanMultiplexedReceive = synchronized(readLock) {
        unavailableFailure()?.let { return LanMultiplexedReceive.Refused(it) }

        val input: InputStream = try {
            socket.getInputStream()
        } catch (_: IOException) {
            return LanMultiplexedReceive.Refused(terminate(SessionFailureCode.CONTROL_CONNECT_FAILED))
        } catch (_: RuntimeException) {
            return LanMultiplexedReceive.Refused(terminate(SessionFailureCode.CONTROL_CONNECT_FAILED))
        }

        val prefix = ByteArray(PREFIX_BYTES)
        try {
            when (val read = readInto(input, prefix, 0, PREFIX_BYTES, deadlineElapsedMillis)) {
                is ReadOutcome.Partial -> return interrupted(read.bytesRead, read.cancelled)
                is ReadOutcome.Complete -> Unit
            }
            return when {
                matches(prefix, MSR1_MAGIC) -> readControl(input, prefix, deadlineElapsedMillis)
                matches(prefix, LanPayloadRecordFormat.magicBytes()) ->
                    readPayload(input, prefix, deadlineElapsedMillis)
                // A stream whose next four bytes are neither format has no boundary to resynchronise
                // to, so this is the end of the connection rather than a record to skip.
                else -> LanMultiplexedReceive.Refused(
                    terminate(SessionFailureCode.SECURE_SESSION_RECORD_INVALID),
                )
            }
        } catch (failure: TerminalReadFailure) {
            return LanMultiplexedReceive.Refused(terminate(failure.code))
        } catch (_: SocketTimeoutException) {
            return LanMultiplexedReceive.Refused(terminate(SessionFailureCode.CONTROL_TIMEOUT))
        } catch (_: EOFException) {
            return LanMultiplexedReceive.Refused(terminate(SessionFailureCode.CONTROL_CONNECT_FAILED))
        } catch (_: IOException) {
            return LanMultiplexedReceive.Refused(terminate(SessionFailureCode.CONTROL_CONNECT_FAILED))
        } catch (_: RuntimeException) {
            return LanMultiplexedReceive.Refused(
                terminate(SessionFailureCode.SECURE_SESSION_RECORD_INVALID),
            )
        } finally {
            prefix.fill(0)
        }
    }

    private fun readControl(
        input: InputStream,
        prefix: ByteArray,
        deadlineElapsedMillis: Long,
    ): LanMultiplexedReceive {
        val header = ByteArray(SecureRecordCodec.HEADER_SIZE_BYTES)
        prefix.copyInto(header)
        val restOfHeader = SecureRecordCodec.HEADER_SIZE_BYTES - PREFIX_BYTES
        when (val read = readInto(input, header, PREFIX_BYTES, restOfHeader, deadlineElapsedMillis)) {
            is ReadOutcome.Partial -> {
                val consumed = PREFIX_BYTES + read.bytesRead
                header.fill(0)
                return interrupted(consumed, read.cancelled)
            }
            is ReadOutcome.Complete -> Unit
        }
        // The control codec validates magic, version, session, direction, sequence and type/length,
        // so a header that does not belong to this connection is refused before any body is sized.
        val decodedHeader = SecureRecordCodec.decodeHeader(
            header = header,
            expectedSessionId = controlLayer.sessionId,
            expectedDirection = receiveDirection(),
        )
        if (decodedHeader == null) {
            header.fill(0)
            return LanMultiplexedReceive.Refused(
                terminate(SessionFailureCode.SECURE_SESSION_RECORD_INVALID),
            )
        }
        val frameSize = decodedHeader.frameLength
        val frame = ByteArray(frameSize)
        header.copyInto(frame)
        header.fill(0)
        try {
            when (
                val read = readInto(
                    input,
                    frame,
                    SecureRecordCodec.HEADER_SIZE_BYTES,
                    frameSize - SecureRecordCodec.HEADER_SIZE_BYTES,
                    deadlineElapsedMillis,
                )
            ) {
                is ReadOutcome.Partial -> {
                    val consumed = SecureRecordCodec.HEADER_SIZE_BYTES + read.bytesRead
                    frame.fill(0)
                    return interrupted(consumed, read.cancelled)
                }
                is ReadOutcome.Complete -> Unit
            }
            return when (val decoded = controlLayer.unprotect(frame)) {
                is SecureRecordResult.Decoded -> {
                    if (decoded.record.type == SecureRecordType.KEY_CONFIRMATION) {
                        // Key confirmation is a pairing record. After pairing there is no sequence
                        // on which it is legal, so seeing one means the peer or the stream is wrong.
                        decoded.record.clearSensitive()
                        return LanMultiplexedReceive.Refused(
                            terminate(SessionFailureCode.SECURE_SESSION_RECORD_INVALID),
                        )
                    }
                    if (decoded.record.type == SecureRecordType.SESSION_CLOSE) {
                        // The peer's orderly close is terminal: there is no record after it, and
                        // handing the record back from a channel that is already closed would invite
                        // a caller to read again.
                        decoded.record.clearSensitive()
                        return LanMultiplexedReceive.Refused(
                            terminate(SessionFailureCode.OPERATION_CANCELLED),
                        )
                    }
                    LanMultiplexedReceive.Control(decoded.record)
                }
                is SecureRecordResult.Failed ->
                    LanMultiplexedReceive.Refused(terminate(decoded.failure.code))
                is SecureRecordResult.Encoded -> LanMultiplexedReceive.Refused(
                    terminate(SessionFailureCode.SECURE_SESSION_RECORD_INVALID),
                )
            }
        } finally {
            frame.fill(0)
        }
    }

    private fun readPayload(
        input: InputStream,
        prefix: ByteArray,
        deadlineElapsedMillis: Long,
    ): LanMultiplexedReceive {
        val header = ByteArray(LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES)
        prefix.copyInto(header)
        val restOfHeader = LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES - PREFIX_BYTES
        when (val read = readInto(input, header, PREFIX_BYTES, restOfHeader, deadlineElapsedMillis)) {
            is ReadOutcome.Partial -> {
                val consumed = PREFIX_BYTES + read.bytesRead
                header.fill(0)
                return interrupted(consumed, read.cancelled)
            }
            is ReadOutcome.Complete -> Unit
        }
        // The declared length is read as an unsigned 32-bit value and bounded by the format before a
        // single byte is allocated. A declared length outside the profile is a terminal framing
        // failure, not something to skip.
        val declaredLength = readUnsigned32(header, LanPayloadRecordFormat.OFF_DECLARED_LENGTH)
        val recordSize = LanPayloadRecordCodec.wireSizeFor(declaredLength)
        if (recordSize == null ||
            recordSize > LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_WIRE_BYTES.toLong()
        ) {
            header.fill(0)
            return LanMultiplexedReceive.Refused(
                terminate(SessionFailureCode.SECURE_SESSION_RECORD_INVALID),
            )
        }
        val total = recordSize.toInt()
        val record = ByteArray(total)
        header.copyInto(record)
        header.fill(0)
        try {
            when (
                val read = readInto(
                    input,
                    record,
                    LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES,
                    total - LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES,
                    deadlineElapsedMillis,
                )
            ) {
                is ReadOutcome.Partial -> {
                    val consumed = LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES + read.bytesRead
                    record.fill(0)
                    return interrupted(consumed, read.cancelled)
                }
                is ReadOutcome.Complete -> Unit
            }
            // The payload layer checks magic, version, session, direction, the exact expected
            // sequence and the tag, and closes itself on any failure.
            return when (val opened = payloadLayer.unprotect(record)) {
                is LanPayloadUnprotectResult.Opened -> {
                    val body = opened.body
                    try {
                        when (val decoded = FrameCodec.decodeSingle(body)) {
                            is FrameDecodeResult.Success -> LanMultiplexedReceive.Payload(decoded.frame)
                            is FrameDecodeResult.Invalid -> LanMultiplexedReceive.Refused(
                                terminate(SessionFailureCode.SECURE_SESSION_RECORD_INVALID),
                            )
                        }
                    } finally {
                        body.fill(0)
                    }
                }
                is LanPayloadUnprotectResult.Refused -> LanMultiplexedReceive.Refused(
                    terminate(SessionFailureCode.SECURE_SESSION_RECORD_INVALID),
                )
            }
        } finally {
            record.fill(0)
        }
    }

    // --- send ------------------------------------------------------------------------------------

    /**
     * Writes one control record. The record types are the post-pairing set; key confirmation is not
     * one of them, because a confirmed session has already spent it.
     */
    fun sendControl(type: SecureRecordType): LanMultiplexedSend {
        if (type !in CONTROL_RECORD_TYPES) {
            return LanMultiplexedSend.Refused(SessionFailure(SessionFailureCode.INVALID_REQUEST))
        }
        return writeRecord(control = true) {
            when (val result = controlLayer.protect(type, EMPTY_PLAINTEXT)) {
                is SecureRecordResult.Encoded -> SealOutcome.Sealed(result.frameBytes())
                is SecureRecordResult.Failed -> SealOutcome.Refused(result.failure)
                is SecureRecordResult.Decoded -> SealOutcome.Refused(
                    SessionFailure(SessionFailureCode.SECURE_SESSION_RECORD_INVALID),
                )
            }
        }.also { outcome ->
            if (outcome is LanMultiplexedSend.Sent && type == SecureRecordType.SESSION_CLOSE) {
                // The layer closed itself when it sealed this record. Nothing after it may be sent.
                close()
            }
        }
    }

    /**
     * Writes one payload record carrying exactly one MSC1 frame.
     *
     * The frame is encoded before writer ownership is taken: encoding is pure and consumes no
     * sequence, so failing here costs nothing. Sealing happens under ownership, which is what makes
     * the layer's sequence order identical to the stream's byte order.
     */
    fun sendPayloadFrame(frame: TransferFrame): LanMultiplexedSend {
        val encoded = try {
            FrameCodec.encode(frame)
        } catch (_: RuntimeException) {
            return LanMultiplexedSend.Refused(SessionFailure(SessionFailureCode.INVALID_REQUEST))
        }
        if (encoded.size > LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES) {
            encoded.fill(0)
            return LanMultiplexedSend.Refused(SessionFailure(SessionFailureCode.INVALID_REQUEST))
        }
        return writeRecord(control = false) {
            when (val result = payloadLayer.protect(encoded)) {
                is LanPayloadProtectResult.Protected -> SealOutcome.Sealed(result.wireRecord)
                is LanPayloadProtectResult.Refused -> SealOutcome.Refused(
                    SessionFailure(SessionFailureCode.SECURE_SESSION_RECORD_INVALID),
                )
            }
        }.also { encoded.fill(0) }
    }

    /**
     * The single write path.
     *
     * Ownership is taken before [seal] runs, so `protect` is never called by two writers at once and
     * a payload record consumes its sequence in the same order as its bytes reach the socket. Every
     * failure after that point is terminal: the sequence is spent and the record is not retried.
     */
    private fun writeRecord(control: Boolean, seal: () -> SealOutcome): LanMultiplexedSend {
        unavailableFailure()?.let { return LanMultiplexedSend.Refused(it) }
        if (!acquireWriter(control)) {
            return LanMultiplexedSend.Refused(
                terminal.get() ?: SessionFailure(SessionFailureCode.OPERATION_CANCELLED),
            )
        }
        var wire: ByteArray? = null
        try {
            // The owner may have closed, or the lifetime may have run out, while this writer was
            // queued behind another record. Checked again under ownership, before a nonce is spent.
            unavailableFailure()?.let { return LanMultiplexedSend.Refused(it) }
            val sealed = try {
                seal()
            } catch (_: RuntimeException) {
                SealOutcome.Refused(SessionFailure(SessionFailureCode.INTERNAL_TRANSPORT_FAILURE))
            }
            when (sealed) {
                is SealOutcome.Refused ->
                    return LanMultiplexedSend.Refused(terminate(sealed.failure.code))
                is SealOutcome.Sealed -> wire = sealed.bytes
            }
            // A record sealed at the very end of the lifetime is not written: the nonce is spent, so
            // the channel is terminal either way, but nothing reaches a session that has expired.
            val now = observeNow()
                ?: return LanMultiplexedSend.Refused(
                    terminate(SessionFailureCode.INTERNAL_TRANSPORT_FAILURE),
                )
            if (now >= expiresAtElapsedMillis) {
                return LanMultiplexedSend.Refused(
                    terminate(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED),
                )
            }
            val output = try {
                socket.getOutputStream()
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            }
            if (output == null) {
                return LanMultiplexedSend.Refused(terminate(SessionFailureCode.CONTROL_CONNECT_FAILED))
            }
            // No SO_TIMEOUT here: it bounds reads, not writes, and mutating it would disturb a
            // reader blocked on the same socket. A write blocked because the peer stopped reading is
            // released by the owner closing the socket, which is what the lifetime expiry does.
            output.write(wire)
            output.flush()
            return LanMultiplexedSend.Sent
        } catch (_: SocketTimeoutException) {
            return LanMultiplexedSend.Refused(terminate(SessionFailureCode.CONTROL_TIMEOUT))
        } catch (_: IOException) {
            return LanMultiplexedSend.Refused(terminate(SessionFailureCode.CONTROL_CONNECT_FAILED))
        } catch (_: RuntimeException) {
            return LanMultiplexedSend.Refused(
                terminate(SessionFailureCode.INTERNAL_TRANSPORT_FAILURE),
            )
        } finally {
            wire?.fill(0)
            releaseWriter()
        }
    }

    // --- writer arbitration ----------------------------------------------------------------------

    /**
     * Takes writer ownership, or returns false when the channel closed while waiting.
     *
     * Both callers register before they wait, so the other side can see them and step back. A payload
     * caller proceeds only when no control caller is waiting, or when the control burst has reached
     * [MAX_CONSECUTIVE_CONTROL_WRITES]. A control caller stops taking new slots at that same bound
     * while a payload record waits -- without that second half the counter would never matter: a
     * control thread that reacquired the lock first would keep winning and the waiting payload record
     * would never see its turn. The pair is the documented anti-starvation policy.
     */
    private fun acquireWriter(control: Boolean): Boolean {
        writeLock.lock()
        try {
            if (control) controlWaiting++ else payloadWaiting++
            var registered = true
            try {
                while (true) {
                    if (closed.get()) return false
                    if (!writing) {
                        if (control) {
                            if (consecutiveControlWrites < MAX_CONSECUTIVE_CONTROL_WRITES ||
                                payloadWaiting == 0
                            ) {
                                controlWaiting--
                                registered = false
                                consecutiveControlWrites++
                                writing = true
                                return true
                            }
                        } else if (controlWaiting == 0 ||
                            consecutiveControlWrites >= MAX_CONSECUTIVE_CONTROL_WRITES
                        ) {
                            payloadWaiting--
                            registered = false
                            consecutiveControlWrites = 0
                            writing = true
                            return true
                        }
                    }
                    try {
                        writerAvailable.await()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
            } finally {
                if (registered) {
                    if (control) controlWaiting-- else payloadWaiting--
                }
            }
        } finally {
            writeLock.unlock()
        }
    }

    private fun releaseWriter() {
        writeLock.lock()
        try {
            writing = false
            writerAvailable.signalAll()
        } finally {
            writeLock.unlock()
        }
    }

    // --- lifecycle -------------------------------------------------------------------------------

    /**
     * Closes everything this channel owns, exactly once.
     *
     * The order is deliberate: both record layers first, so their keys are destroyed while the
     * socket still exists to be closed; then the socket; then the parent's terminal hook. A reader
     * blocked in `read` is released by the socket closing, and a writer waiting for ownership is
     * released by the signal below.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Never block here. A writer inside write() holds the lock until its socket call returns, and
        // waiting for the lock would deadlock the close that is supposed to release it. If tryLock
        // fails somebody is already inside the arbiter: a waiter re-reads `closed` before it proceeds,
        // and the record in progress releases the lock and signals as it unwinds.
        if (writeLock.tryLock()) {
            try {
                writerAvailable.signalAll()
            } finally {
                writeLock.unlock()
            }
        }
        try {
            controlLayer.close()
        } catch (_: RuntimeException) {
            // A layer that will not close is still closed to us; nothing is retried.
        }
        try {
            payloadLayer.close()
        } catch (_: RuntimeException) {
            // Same rule for the payload half.
        }
        try {
            socket.close()
        } catch (_: IOException) {
            // Teardown never exposes platform or peer-controlled text.
        } catch (_: RuntimeException) {
            // Teardown is idempotent across cancellation and expiry.
        }
        try {
            onTerminal()
        } catch (_: RuntimeException) {
            // The terminal hook is best effort and carries no recoverable secret state.
        }
    }

    override fun toString(): String =
        "LanMultiplexedRecordChannel(role=$role, open=${!closed.get()}, records=[redacted])"

    // --- reading ---------------------------------------------------------------------------------

    /**
     * How one read of a fixed byte count ended.
     *
     * [Partial] carries how far the read got, which is what decides whether the stream still has a
     * usable boundary. It never means the bytes should be re-read: the caller's array already holds
     * them and the operation is over.
     */
    /** The result of sealing exactly one record, before any of it reaches the socket. */
    private sealed interface SealOutcome {
        class Sealed(val bytes: ByteArray) : SealOutcome
        class Refused(val failure: SessionFailure) : SealOutcome
    }

    private sealed interface ReadOutcome {
        class Complete : ReadOutcome
        class Partial(val bytesRead: Int, val cancelled: Boolean) : ReadOutcome
    }

    /**
     * A read that cannot continue for a reason the caller must report as a typed failure.
     *
     * Used for the monotonic clock going backwards or returning a negative value. It is not an
     * `IOException`: nothing about the socket is wrong, and reporting it as a connection failure
     * would blame the peer for a local fault.
     */
    private class TerminalReadFailure(val code: SessionFailureCode) : RuntimeException()

    /**
     * Fills `destination[from, from + count)` or reports why it stopped.
     *
     * One polling slice exists only so a blocked read can look at cancellation. A slice that expires
     * while bytes are still outstanding returns to the top of the same loop with `position` intact,
     * so a header or a body that arrived in pieces keeps being assembled into the same array. The
     * absolute deadline is the only thing that ends the operation, and a peer that is slow but
     * progressing runs until that deadline rather than until a slice.
     */
    private fun readInto(
        input: InputStream,
        destination: ByteArray,
        from: Int,
        count: Int,
        deadlineElapsedMillis: Long,
    ): ReadOutcome {
        var position = from
        val end = from + count
        while (position < end) {
            // Checked once a record has started, and again after every timed-out slice. A caller
            // that cancels at a clean boundary consumes nothing; one that cancels inside a record
            // stops promptly even while the peer keeps sending, because the loop below can otherwise
            // run without ever timing out.
            if (position > from && isCancelled()) {
                return ReadOutcome.Partial(position - from, cancelled = true)
            }
            val now = observeNow()
                ?: throw TerminalReadFailure(SessionFailureCode.INTERNAL_TRANSPORT_FAILURE)
            val remaining = deadlineElapsedMillis - now
            if (remaining <= 0L) {
                return ReadOutcome.Partial(position - from, cancelled = false)
            }
            socket.soTimeout = remaining
                .coerceAtMost(MULTIPLEXED_POLL_SLICE_MILLIS.toLong())
                .coerceAtLeast(1L)
                .toInt()
            val read = try {
                input.read(destination, position, end - position)
            } catch (_: SocketTimeoutException) {
                if (isCancelled()) {
                    return ReadOutcome.Partial(position - from, cancelled = true)
                }
                continue
            }
            if (read < 0) throw EOFException()
            if (read > 0) position += read
        }
        return ReadOutcome.Complete()
    }

    /**
     * What a partial read means.
     *
     * With no byte consumed the record has not started, so the stream is still at a boundary and the
     * channel stays open. With any byte consumed the next record's first byte is somewhere inside
     * data this call has already eaten, so the connection is terminal -- including for a local
     * cancellation, which is a decision about this caller and not about the stream.
     */
    private fun interrupted(bytesRead: Int, cancelled: Boolean): LanMultiplexedReceive {
        if (bytesRead > 0) {
            terminate(
                if (cancelled) SessionFailureCode.OPERATION_CANCELLED
                else SessionFailureCode.CONTROL_TIMEOUT,
            )
            return if (cancelled) LanMultiplexedReceive.Cancelled(streamUsable = false)
            else LanMultiplexedReceive.TimedOut(streamUsable = false)
        }
        return if (cancelled) LanMultiplexedReceive.Cancelled(streamUsable = true)
        else LanMultiplexedReceive.TimedOut(streamUsable = true)
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun matches(prefix: ByteArray, magic: ByteArray): Boolean =
        prefix.size == 4 && magic.size == 4 && magic.indices.all { prefix[it] == magic[it] }

    /** The direction the peer's records must carry. */
    private fun receiveDirection(): SecureRecordDirection =
        if (role == SecurePeerRole.INITIATOR) SecureRecordDirection.RESPONDER_TO_INITIATOR
        else SecureRecordDirection.INITIATOR_TO_RESPONDER

    /** Four big-endian bytes as a non-negative Long, so a declared length is bounded before use. */
    private fun readUnsigned32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 4) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }

    private fun operationDeadlineMillis(): Long {
        val now = observeNow() ?: 0L
        val byBudget = saturatingAdd(now, SecureSessionLimits.CONTROL_RECORD_IO_TIMEOUT_MILLIS.toLong())
        return if (expiresAtElapsedMillis < byBudget) expiresAtElapsedMillis else byBudget
    }

    private fun observeNow(): Long? {
        val now = try {
            clock.nowMillis()
        } catch (_: RuntimeException) {
            return null
        }
        if (now < 0L || (lastNowElapsedMillis >= 0L && now < lastNowElapsedMillis)) return null
        lastNowElapsedMillis = now
        return now
    }

    private fun unavailableFailure(): SessionFailure? {
        terminal.get()?.let { return it }
        if (closed.get()) return SessionFailure(SessionFailureCode.OPERATION_CANCELLED)
        val now = observeNow() ?: return terminate(SessionFailureCode.INTERNAL_TRANSPORT_FAILURE)
        if (now >= expiresAtElapsedMillis) return terminate(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED)
        return null
    }

    /** Records the first terminal cause, closes once, and reports that same cause to every caller. */
    private fun terminate(code: SessionFailureCode): SessionFailure {
        val failure = SessionFailure(code)
        terminal.compareAndSet(null, failure)
        close()
        return terminal.get() ?: failure
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private companion object {
        /** Four bytes: enough to name a format, not enough to parse one. */
        const val PREFIX_BYTES: Int = 4

        /**
         * A slice is a cancellation poll, not the operation's lifetime.
         *
         * It bounds how long a blocked read waits before it looks at [isCancelled]; the absolute
         * deadline given to [receiveNext] bounds the operation.
         */
        const val MULTIPLEXED_POLL_SLICE_MILLIS: Int = 50

        /**
         * How many control records may be written back to back while a payload record waits.
         *
         * Without a bound, a control caller that always has another record ready would keep a
         * payload writer waiting for as long as the session lasts. This is the documented
         * anti-starvation rule: at the next complete-record boundary the payload record is granted
         * the writer, and control resumes after it.
         */
        const val MAX_CONSECUTIVE_CONTROL_WRITES: Int = 32

        /**
         * The control record magic.
         *
         * `SecureRecordCodec` keeps its copy private, so this file names the four bytes it needs for
         * dispatch. A test asserts that these are the bytes the control codec actually writes, so
         * the duplication cannot drift.
         */
        val MSR1_MAGIC: ByteArray = byteArrayOf(0x4D, 0x53, 0x52, 0x31)

        val EMPTY_PLAINTEXT: ByteArray = ByteArray(0)

        /** The post-pairing control records this channel may send. */
        val CONTROL_RECORD_TYPES: Set<SecureRecordType> = setOf(
            SecureRecordType.PING,
            SecureRecordType.PONG,
            SecureRecordType.SESSION_CLOSE,
        )
    }
}
