package app.morsecode.transport.lan.security

import app.morsecode.core.transfer.session.ControlIoFailureContext
import app.morsecode.core.transfer.session.ControlIoOperation
import app.morsecode.core.transfer.session.ControlIoPhase
import app.morsecode.core.transfer.session.ControlIoRecordType
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.controlIoRecordTypeOf
import app.morsecode.core.transfer.session.SecureControlReceiveResult
import app.morsecode.core.transfer.session.SecureControlSendResult
import app.morsecode.core.transfer.session.SecureRecordCodec
import app.morsecode.core.transfer.session.SecureRecordDirection
import app.morsecode.core.transfer.session.SecureRecordLayer
import app.morsecode.core.transfer.session.SecureRecordResult
import app.morsecode.core.transfer.session.SecureRecordType
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SessionFailure
import app.morsecode.core.transfer.session.SessionFailureCode
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded, authenticated control-only access to one installed secure record layer.
 * It has no plaintext fallback and deliberately exposes no file/data record type.
 *
 * EXECUTION MODEL, STATED PLAINLY BECAUSE IT IS EASY TO GET WRONG. [send] and [receive] are
 * **blocking** calls that perform real socket I/O under [writeLock] and [readLock] respectively.
 * They must never be called on the Android main thread. They are bounded by absolute monotonic
 * deadlines rather than by any inactivity timer, and when a deadline fires the channel closes the
 * owning socket to unblock itself.
 *
 * There is deliberately **no internal worker pool here**, and no detached task is ever created: a
 * call returns on the caller's own thread or not at all until its deadline closes the socket. The
 * bounded executor and permit semaphore in `LanPeerDiscoveryProvider` gate *control establishment*
 * -- connect, accept and pairing -- not established control I/O. Whoever holds an authenticated
 * channel owns the execution context it runs on, and a future transfer-engine or service
 * integration must supply that context rather than assume this class provides one.
 */
internal class SecureLanControlChannel(
    private val socket: Socket,
    private val recordLayer: SecureRecordLayer,
    private val role: SecurePeerRole,
    private val clock: MonotonicClock,
    private val expiresAtElapsedMillis: Long,
    private val onTerminal: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    @Volatile
    private var closeTimedOut = false
    private val writeLock = Any()
    private val readLock = Any()
    private var lastNowElapsedMillis: Long = -1L

    fun send(type: SecureRecordType, payload: ByteArray): SecureControlSendResult = synchronized(writeLock) {
        if (type !in CONTROL_RECORD_TYPES || payload.isNotEmpty()) {
            return@synchronized SecureControlSendResult.Refused(SessionFailure(SessionFailureCode.INVALID_REQUEST))
        }
        unavailableFailure()?.let { return@synchronized SecureControlSendResult.Refused(it) }

        val ownedPayload = payload.copyOf()
        val encoded = try {
            recordLayer.protect(type, ownedPayload)
        } catch (_: RuntimeException) {
            return@synchronized SecureControlSendResult.Refused(
                terminate(
                    SessionFailureCode.SECURE_SESSION_RECORD_INVALID,
                    ControlIoFailureContext(
                        operation = ControlIoOperation.SEND,
                        phase = ControlIoPhase.FRAME,
                        recordType = controlIoRecordTypeOf(type),
                    ),
                ),
            )
        } finally {
            ownedPayload.fill(0)
        }
        val encodedFrame = when (encoded) {
            is SecureRecordResult.Encoded -> encoded.frameBytes()
            is SecureRecordResult.Decoded -> {
                encoded.record.clearSensitive()
                return@synchronized SecureControlSendResult.Refused(
                    terminate(SessionFailureCode.SECURE_SESSION_RECORD_INVALID),
                )
            }
            is SecureRecordResult.Failed -> {
                return@synchronized SecureControlSendResult.Refused(terminate(encoded.failure.code))
            }
        }
        // One absolute deadline for the whole write, armed before the first byte goes out. The
        // per-read inactivity timeout cannot help here: it does not apply to writes, so a peer
        // that simply stops reading would otherwise park this worker for the life of the process.
        val writeDeadline = MonotonicSocketDeadline(
            label = "control-record-write",
            socket = socket,
            budgetMillis = SecureSessionLimits.CONTROL_RECORD_WRITE_DEADLINE_MILLIS,
            monotonicMillis = clock::nowMillis,
        )
        writeDeadline.arm()
        try {
            socket.getOutputStream().apply {
                write(encodedFrame)
                flush()
            }
        } catch (_: SocketTimeoutException) {
            return@synchronized SecureControlSendResult.Refused(
                terminate(
                    SessionFailureCode.CONTROL_TIMEOUT,
                    sendTimeoutContext(type),
                ),
            )
        } catch (_: IOException) {
            // The deadline unblocks the write by closing the socket, which surfaces as an
            // IOException. Reporting that as a connect failure would misname a timeout.
            val expired = writeDeadline.isExpired()
            return@synchronized SecureControlSendResult.Refused(
                terminate(
                    if (expired) SessionFailureCode.CONTROL_TIMEOUT else SessionFailureCode.CONTROL_CONNECT_FAILED,
                    if (expired) sendTimeoutContext(type) else null,
                ),
            )
        } catch (_: RuntimeException) {
            return@synchronized SecureControlSendResult.Refused(terminate(SessionFailureCode.CONTROL_CONNECT_FAILED))
        } finally {
            writeDeadline.complete()
            encodedFrame.fill(0)
        }
        // A write that appeared to finish at the instant the deadline fired is not a success: the
        // deadline already closed the socket, so the channel is dead either way.
        if (writeDeadline.isExpired()) {
            return@synchronized SecureControlSendResult.Refused(
                terminate(SessionFailureCode.CONTROL_TIMEOUT, sendTimeoutContext(type)),
            )
        }

        if (type == SecureRecordType.SESSION_CLOSE) terminate(SessionFailureCode.OPERATION_CANCELLED)
        SecureControlSendResult.Sent
    }

    fun receive(): SecureControlReceiveResult = synchronized(readLock) {
        unavailableFailure()?.let { return@synchronized SecureControlReceiveResult.Refused(it) }
        val initialNow = observeNow()
            ?: return@synchronized SecureControlReceiveResult.Refused(terminate(SessionFailureCode.INTERNAL_TRANSPORT_FAILURE))
        val deadline = saturatingAdd(initialNow, SecureSessionLimits.CONTROL_RECORD_IO_TIMEOUT_MILLIS.toLong())
        val input = try {
            DataInputStream(socket.getInputStream())
        } catch (_: IOException) {
            return@synchronized SecureControlReceiveResult.Refused(terminate(SessionFailureCode.CONTROL_CONNECT_FAILED))
        } catch (_: RuntimeException) {
            return@synchronized SecureControlReceiveResult.Refused(terminate(SessionFailureCode.CONTROL_CONNECT_FAILED))
        }

        val headerBytes = ByteArray(SecureRecordCodec.HEADER_SIZE_BYTES)
        // How far the read has got, tracked as it progresses. `readExact` lets a read deadline
        // escape as SocketTimeoutException rather than returning false, so the catch below has to
        // be able to say whether the header had already been decoded -- hard-coding HEADER here
        // reported every body-phase timeout as an unknown-type header timeout, which is the exact
        // conflation this context exists to remove.
        var readPhase = ControlIoPhase.HEADER
        var knownType: SecureRecordType? = null
        val frame = try {
            if (!readExact(input, headerBytes, 0, headerBytes.size, deadline)) {
                // Nothing about the record is known yet. UNKNOWN is the honest answer; guessing a
                // type here would attribute the timeout to a record that may never have been sent.
                return@synchronized SecureControlReceiveResult.Refused(
                    terminate(
                        SessionFailureCode.CONTROL_TIMEOUT,
                        ControlIoFailureContext(
                            operation = ControlIoOperation.RECEIVE,
                            phase = ControlIoPhase.HEADER,
                            recordType = ControlIoRecordType.UNKNOWN,
                        ),
                    ),
                )
            }
            val expectedDirection = if (role == SecurePeerRole.INITIATOR) {
                SecureRecordDirection.RESPONDER_TO_INITIATOR
            } else {
                SecureRecordDirection.INITIATOR_TO_RESPONDER
            }
            val header = SecureRecordCodec.decodeHeader(
                header = headerBytes,
                expectedSessionId = recordLayer.sessionId,
                expectedDirection = expectedDirection,
            ) ?: return@synchronized SecureControlReceiveResult.Refused(terminate(SessionFailureCode.SECURE_SESSION_RECORD_INVALID))
            readPhase = ControlIoPhase.BODY
            knownType = header.type
            val frameBytes = ByteArray(header.frameLength)
            headerBytes.copyInto(frameBytes)
            if (!readExact(input, frameBytes, headerBytes.size, frameBytes.size - headerBytes.size, deadline)) {
                frameBytes.fill(0)
                // The header decoded, so the type is known even though the body never arrived.
                return@synchronized SecureControlReceiveResult.Refused(
                    terminate(
                        SessionFailureCode.CONTROL_TIMEOUT,
                        ControlIoFailureContext(
                            operation = ControlIoOperation.RECEIVE,
                            phase = ControlIoPhase.BODY,
                            recordType = controlIoRecordTypeOf(header.type),
                        ),
                    ),
                )
            }
            frameBytes
        } catch (_: SocketTimeoutException) {
            return@synchronized SecureControlReceiveResult.Refused(
                terminate(
                    SessionFailureCode.CONTROL_TIMEOUT,
                    ControlIoFailureContext(
                        operation = ControlIoOperation.RECEIVE,
                        phase = readPhase,
                        recordType = controlIoRecordTypeOf(knownType),
                    ),
                ),
            )
        } catch (_: EOFException) {
            return@synchronized SecureControlReceiveResult.Refused(terminate(SessionFailureCode.CONTROL_CONNECT_FAILED))
        } catch (_: IOException) {
            return@synchronized SecureControlReceiveResult.Refused(terminate(SessionFailureCode.CONTROL_CONNECT_FAILED))
        } catch (_: RuntimeException) {
            return@synchronized SecureControlReceiveResult.Refused(terminate(SessionFailureCode.SECURE_SESSION_RECORD_INVALID))
        } finally {
            headerBytes.fill(0)
        }

        try {
            when (val decoded = recordLayer.unprotect(frame)) {
                is SecureRecordResult.Decoded -> {
                    if (decoded.record.type == SecureRecordType.KEY_CONFIRMATION) {
                        decoded.record.clearSensitive()
                        return@synchronized SecureControlReceiveResult.Refused(terminate(SessionFailureCode.SECURE_SESSION_RECORD_INVALID))
                    }
                    if (decoded.record.type == SecureRecordType.SESSION_CLOSE) {
                        terminate(SessionFailureCode.OPERATION_CANCELLED)
                    }
                    SecureControlReceiveResult.Record(decoded.record)
                }
                is SecureRecordResult.Failed -> SecureControlReceiveResult.Refused(terminate(decoded.failure.code))
                is SecureRecordResult.Encoded -> SecureControlReceiveResult.Refused(terminate(SessionFailureCode.SECURE_SESSION_RECORD_INVALID))
            }
        } finally {
            frame.fill(0)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        recordLayer.close()
        closeSocket()
    }

    private fun readExact(
        input: DataInputStream,
        destination: ByteArray,
        offset: Int,
        length: Int,
        deadlineElapsedMillis: Long,
    ): Boolean {
        var position = offset
        val end = offset + length
        while (position < end) {
            val now = observeNow() ?: return false
            val remaining = deadlineElapsedMillis - now
            if (remaining <= 0L) return false
            socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            val read = input.read(destination, position, end - position)
            if (read < 0) throw EOFException()
            if (read > 0) position += read
        }
        return true
    }

    private fun unavailableFailure(): SessionFailure? {
        if (closed.get()) return SessionFailure(SessionFailureCode.OPERATION_CANCELLED)
        val now = observeNow()
            ?: return terminate(SessionFailureCode.INTERNAL_TRANSPORT_FAILURE)
        if (now >= expiresAtElapsedMillis) return terminate(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED)
        return null
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

    /**
     * A write deadline means the bytes reached the socket but never drained, which is the flush
     * phase: the frame was already sealed, so FRAME would understate how far the send got.
     */
    private fun sendTimeoutContext(type: SecureRecordType): ControlIoFailureContext =
        ControlIoFailureContext(
            operation = ControlIoOperation.SEND,
            phase = ControlIoPhase.FLUSH,
            recordType = controlIoRecordTypeOf(type),
        )

    private fun terminate(
        code: SessionFailureCode,
        context: ControlIoFailureContext? = null,
    ): SessionFailure {
        if (closed.compareAndSet(false, true)) {
            recordLayer.close()
            closeSocket()
            try {
                onTerminal()
            } catch (_: RuntimeException) {
                // Terminal teardown is best-effort and contains no recoverable secret state.
            }
        }
        return SessionFailure(code, context)
    }

    /**
     * True when the bounded teardown deadline fired rather than the close completing.
     *
     * Surfaced as state rather than as a [SessionFailure] because [close] is `AutoCloseable.close`
     * and returns nothing: a teardown deadline is not a failed operation the caller was waiting on,
     * it is a fact about cleanup. [ControlIoOperation.CLOSE] and [ControlIoPhase.CLOSE] exist in the
     * model for callers that need to report it.
     */
    internal val closeDeadlineExpired: Boolean get() = closeTimedOut

    private fun closeSocket() {
        // Bounded for the same reason as the pairing path: close can itself block on a socket
        // whose peer has vanished, and teardown must not become the new unbounded operation.
        val deadline = MonotonicSocketDeadline(
            label = "control-bounded-close",
            socket = socket,
            budgetMillis = SecureSessionLimits.BOUNDED_CLOSE_DEADLINE_MILLIS,
            monotonicMillis = clock::nowMillis,
        )
        deadline.arm()
        try {
            socket.close()
            closeTimedOut = deadline.isExpired()
        } catch (_: IOException) {
            // Teardown never exposes peer-controlled or platform exception details.
        } catch (_: RuntimeException) {
            // Teardown is idempotent across lease cancellation and expiry.
        } finally {
            deadline.complete()
        }
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private companion object {
        val CONTROL_RECORD_TYPES: Set<SecureRecordType> = setOf(
            SecureRecordType.PING,
            SecureRecordType.PONG,
            SecureRecordType.SESSION_CLOSE,
        )
    }
}
