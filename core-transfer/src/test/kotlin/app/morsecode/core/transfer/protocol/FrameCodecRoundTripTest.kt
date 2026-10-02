package app.morsecode.core.transfer.protocol

import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.Tf
import app.morsecode.core.transfer.error.ErrorCategory
import app.morsecode.core.transfer.error.ErrorOrigin
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.ProtocolVersion
import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.integrity.Crc32
import app.morsecode.core.transfer.integrity.Crc32Test
import app.morsecode.core.transfer.integrity.Sha256Digest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every frame type must survive encode → decode unchanged, at the field
 * boundaries as well as in the ordinary case.
 */
class FrameCodecRoundTripTest {

    @Test fun `all fifteen frame types are declared with stable wire ids`() {
        assertEquals(15, FrameType.all.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15), FrameType.all.map { it.id })
        for (type in FrameType.all) {
            assertEquals(type, FrameType.fromId(type.id))
            assertEquals(type, FrameType.fromWireName(type.wireName))
        }
        assertEquals(null, FrameType.fromId(0))
        assertEquals(null, FrameType.fromId(16))
    }

    @Test fun `header size is fixed and documented`() {
        assertEquals(44, ProtocolLimits.HEADER_SIZE_BYTES)
        val frame = FrameCodec.pause(Tf.sessionId, Tf.transferId)
        val encoded = FrameCodec.encode(frame)
        // 44 header + "sess-1" + "tr-1" + no recipient + an empty reason.
        assertEquals(44 + 6 + 4 + 0 + 2, encoded.size)
        assertEquals(encoded.size, frame.encodedSize())
    }

    @Test fun `handshake round trip`() {
        val frame = FrameCodec.handshake(
            Tf.sessionId, Tf.transferId, Tf.recipientA,
            capabilities = "v1;crc32;sha256;resume",
            maxChunkSize = ChunkSize.MAX,
            resumeSupported = true,
        )
        val decoded = roundTrip(frame)
        assertEquals(FrameType.HANDSHAKE, decoded.type)
        assertEquals(Tf.recipientA, decoded.recipientId)
        val payload = parse(decoded) as FramePayload.Handshake
        assertEquals("v1;crc32;sha256;resume", payload.capabilities)
        assertEquals(ChunkSize.MAX, payload.maxChunkSize)
        assertTrue(payload.resumeSupported)
    }

    @Test fun `handshake accept round trip`() {
        val frame = FrameCodec.handshakeAccept(
            Tf.sessionId, Tf.transferId, chunkSize = Tf.chunk,
            resumeSupported = true, resumeFromOffset = 8_192L,
        )
        val payload = parse(roundTrip(frame)) as FramePayload.HandshakeAccept
        assertEquals(Tf.chunk, payload.chunkSize)
        assertEquals(8_192L, payload.resumeFromOffset)
        assertTrue(payload.resumeSupported)
    }

    @Test fun `handshake reject round trip with and without a reason`() {
        val withReason = parse(
            roundTrip(FrameCodec.handshakeReject(Tf.sessionId, Tf.transferId, reason = "no space")),
        ) as FramePayload.HandshakeReject
        assertEquals("no space", withReason.reason)
        val empty = parse(
            roundTrip(FrameCodec.handshakeReject(Tf.sessionId, Tf.transferId)),
        ) as FramePayload.HandshakeReject
        assertEquals("", empty.reason)
    }

    @Test fun `file metadata round trip`() {
        val descriptor = Tf.descriptor(
            totalBytes = 1_234_567L,
            digest = Tf.knownDigest,
            lastModified = 1_700_000_000_000L,
            isFolderArchive = true,
            mimeType = "video/mp4",
        )
        val frame = FrameCodec.fileMetadata(Tf.sessionId, Tf.transferId, descriptor)
        val payload = parse(roundTrip(frame)) as FramePayload.FileMetadata
        assertEquals(descriptor, payload.descriptor)
        assertEquals(descriptor.descriptorFingerprint, payload.descriptor.descriptorFingerprint)
    }

    @Test fun `file metadata round trip with every optional field empty`() {
        val descriptor = Tf.descriptor(
            name = "a",
            path = RelativeTransferPath("a"),
            mimeType = "",
            totalBytes = 0L,
            lastModified = null,
            digest = null,
        )
        val payload = parse(
            roundTrip(FrameCodec.fileMetadata(Tf.sessionId, Tf.transferId, descriptor)),
        ) as FramePayload.FileMetadata
        assertEquals("", payload.descriptor.mimeType)
        assertEquals(null, payload.descriptor.lastModifiedEpochMillis)
        assertEquals(null, payload.descriptor.expectedSha256)
        assertEquals(0L, payload.descriptor.totalBytes)
    }

    @Test fun `file metadata round trip at the maximum field lengths`() {
        val descriptor = Tf.descriptor(
            name = "n".repeat(ProtocolLimits.MAX_TEXT_LENGTH_BYTES),
            mimeType = "application/" + "o".repeat(ProtocolLimits.MAX_TEXT_LENGTH_BYTES - 12),
            path = RelativeTransferPath("p".repeat(ProtocolLimits.MAX_PATH_SEGMENT_BYTES)),
            totalBytes = ProtocolLimits.MAX_FILE_SIZE_BYTES,
            digest = Tf.knownDigest,
        )
        val payload = parse(
            roundTrip(FrameCodec.fileMetadata(Tf.sessionId, Tf.transferId, descriptor)),
        ) as FramePayload.FileMetadata
        assertEquals(descriptor, payload.descriptor)
    }

    @Test fun `resume proposal round trip`() {
        val proposal = FramePayload.ResumeProposal(
            fileId = Tf.fileId,
            totalBytes = ProtocolLimits.MAX_FILE_SIZE_BYTES,
            chunkSize = ChunkSize.MAX,
            expectedSha256 = Tf.knownDigest,
            relativePath = Tf.path,
            lastModifiedEpochMillis = null,
            senderAdvertisedBytes = 9_999L,
        )
        val payload = parse(
            roundTrip(FrameCodec.resumeProposal(Tf.sessionId, Tf.transferId, proposal)),
        ) as FramePayload.ResumeProposal
        assertEquals(proposal, payload)
        assertEquals(null, payload.lastModifiedEpochMillis)
    }

    @Test fun `every resume decision survives the round trip`() {
        val decisions: List<ResumeDecision> = listOf(
            ResumeDecision.ResumeAt(0L),
            ResumeDecision.ResumeAt(8_192L),
            ResumeDecision.ResumeAt(ProtocolLimits.MAX_FILE_SIZE_BYTES),
            ResumeDecision.RestartAtZero("descriptor changed"),
            ResumeDecision.AlreadyVerified,
            ResumeDecision.Reject(TransferError.DescriptorMismatch("relativePath")),
        )
        for (decision in decisions) {
            val response = FramePayload.ResumeResponse(Tf.fileId, decision)
            val payload = parse(
                roundTrip(FrameCodec.resumeResponse(Tf.sessionId, Tf.transferId, response)),
            ) as FramePayload.ResumeResponse
            if (decision is ResumeDecision.Reject) {
                // An error that crosses the wire comes back rehydrated from its
                // code and classification, not as the original subtype: that is
                // what lets a newer build restore an older build's error row.
                val actual = payload.decision as ResumeDecision.Reject
                assertEquals(decision.error.code, actual.error.code)
                assertEquals(decision.error.retryable, actual.error.retryable)
                assertEquals(decision.error.origin, actual.error.origin)
            } else {
                assertEquals("decision $decision", decision, payload.decision)
            }
            assertEquals(response.offset, payload.offset)
        }
    }

    @Test fun `data chunk round trip at the maximum payload`() {
        val bytes = Crc32Test.deterministicBytes(ProtocolLimits.MAX_PAYLOAD_BYTES)
        val frame = FrameCodec.dataChunk(
            Tf.sessionId, Tf.transferId,
            offset = 65_536L, sequence = 1L, bytes = bytes,
        )
        val decoded = roundTrip(frame)
        assertEquals(FrameType.DATA_CHUNK, decoded.type)
        assertEquals(65_536L, decoded.offset)
        assertEquals(1L, decoded.sequence)
        assertTrue(decoded.payloadEquals(frame))
        assertTrue(decoded.payloadIsIntact())
        val payload = parse(decoded) as FramePayload.DataChunk
        assertEquals(65_536L, payload.offset)
        assertEquals(1L, payload.sequence)
        assertEquals(Crc32.compute(bytes), payload.crc32)
    }

    @Test fun `data chunk round trip with a one byte payload`() {
        val frame = FrameCodec.dataChunk(
            Tf.sessionId, Tf.transferId, offset = 0L, sequence = 0L, bytes = ByteArray(1) { 0x7F },
        )
        val decoded = roundTrip(frame)
        assertEquals(1, decoded.payload.size)
        assertTrue(decoded.payloadIsIntact())
    }

    @Test fun `data chunk round trip honours a sub range of the source buffer`() {
        val source = Crc32Test.deterministicBytes(100)
        val frame = FrameCodec.dataChunk(
            Tf.sessionId, Tf.transferId, offset = 0L, sequence = 0L,
            bytes = source, payloadOffset = 10, length = 20,
        )
        val decoded = roundTrip(frame)
        assertEquals(20, decoded.payload.size)
        assertEquals(source[10], decoded.payload[0])
        assertEquals(source[29], decoded.payload[19])
    }

    @Test fun `chunk ack round trip`() {
        val payload = parse(
            roundTrip(
                FrameCodec.chunkAck(
                    Tf.sessionId, Tf.transferId,
                    offset = 4_096L, length = 4_096, confirmedOffset = 8_192L,
                    accepted = false,
                ),
            ),
        ) as FramePayload.ChunkAck
        assertEquals(4_096L, payload.offset)
        assertEquals(4_096, payload.length)
        assertEquals(8_192L, payload.confirmedOffset)
        assertEquals(false, payload.accepted)
    }

    @Test fun `pause resume and cancel round trip`() {
        val pause = parse(roundTrip(FrameCodec.pause(Tf.sessionId, Tf.transferId, "user")))
        assertEquals(FramePayload.Pause("user"), pause)
        val resume = parse(
            roundTrip(FrameCodec.resume(Tf.sessionId, Tf.transferId, fromOffset = 4_096L)),
        ) as FramePayload.Resume
        assertEquals(4_096L, resume.fromOffset)
        assertEquals("", resume.reason)
        val cancel = parse(roundTrip(FrameCodec.cancel(Tf.sessionId, Tf.transferId, "done")))
        assertEquals(FramePayload.Cancel("done"), cancel)
    }

    @Test fun `error frame round trip keeps the classification`() {
        val error = TransferError.ChunkChecksumMismatch(4_096L, 1L, 2L)
        val restored = (parse(
            roundTrip(FrameCodec.error(Tf.sessionId, Tf.transferId, error)),
        ) as FramePayload.ErrorFrame).toError()
        assertEquals(error.code, restored.code)
        assertEquals(error.retryable, restored.retryable)
        assertEquals(error.origin, restored.origin)
        assertEquals(error.category, restored.category)
        assertEquals(ErrorOrigin.REMOTE, restored.origin)
        assertEquals(ErrorCategory.INTEGRITY, restored.category)
    }

    @Test fun `verification result round trip with and without a digest`() {
        val withDigest = parse(
            roundTrip(
                FrameCodec.verificationResult(
                    Tf.sessionId, Tf.transferId, success = true,
                    totalBytes = 12L, observedDigest = Tf.knownDigest,
                ),
            ),
        ) as FramePayload.VerificationResult
        assertTrue(withDigest.success)
        assertEquals(12L, withDigest.totalBytes)
        assertEquals(Tf.knownDigest, withDigest.observedDigest)
        val without = parse(
            roundTrip(
                FrameCodec.verificationResult(
                    Tf.sessionId, Tf.transferId, success = false,
                    totalBytes = 0L, observedDigest = null,
                ),
            ),
        ) as FramePayload.VerificationResult
        assertEquals(false, without.success)
        assertEquals(null, without.observedDigest)
    }

    @Test fun `file complete round trip`() {
        val payload = parse(
            roundTrip(
                FrameCodec.fileComplete(
                    Tf.sessionId, Tf.transferId, Tf.fileId,
                    totalBytes = ProtocolLimits.MAX_FILE_SIZE_BYTES, digest = Tf.knownDigest,
                ),
            ),
        ) as FramePayload.FileComplete
        assertEquals(Tf.fileId, payload.fileId)
        assertEquals(ProtocolLimits.MAX_FILE_SIZE_BYTES, payload.totalBytes)
        assertEquals(Tf.knownDigest, payload.digest)
    }

    @Test fun `session complete round trip with no transfer id`() {
        val decoded = roundTrip(FrameCodec.sessionComplete(Tf.sessionId, 3, 4_096L))
        assertEquals(null, decoded.transferId)
        assertEquals(FrameType.SESSION_COMPLETE, decoded.type)
        val payload = parse(decoded) as FramePayload.SessionComplete
        assertEquals(3, payload.itemCount)
        assertEquals(4_096L, payload.totalBytes)
    }

    @Test fun `multi byte utf8 text fields survive the round trip`() {
        val descriptor = Tf.descriptor(name = "фото-度假村-🎉.jpg")
        val payload = parse(
            roundTrip(FrameCodec.fileMetadata(Tf.sessionId, Tf.transferId, descriptor)),
        ) as FramePayload.FileMetadata
        assertEquals("фото-度假村-🎉.jpg", payload.descriptor.displayName)
    }

    @Test fun `multi byte utf8 identifiers survive the round trip`() {
        // Identifiers are ASCII-only by policy, but the *path* is not, and it is
        // the field a peer is most likely to send non-ASCII text in.
        val descriptor = Tf.descriptor(path = RelativeTransferPath("photos/фото 2024/度假村.jpg"))
        val payload = parse(
            roundTrip(FrameCodec.fileMetadata(Tf.sessionId, Tf.transferId, descriptor)),
        ) as FramePayload.FileMetadata
        assertEquals("photos/фото 2024/度假村.jpg", payload.descriptor.relativePath.value)
    }

    @Test fun `boundary sequence numbers and offsets survive the round trip`() {
        for (sequence in listOf(0L, 1L, 4_194_303L)) {
            val decoded = roundTrip(
                FrameCodec.dataChunk(
                    Tf.sessionId, Tf.transferId,
                    offset = 0L, sequence = sequence, bytes = ByteArray(1),
                ),
            )
            assertEquals(sequence, decoded.sequence)
        }
        for (offset in listOf(0L, 1L, ProtocolLimits.MAX_FILE_SIZE_BYTES)) {
            val decoded = roundTrip(
                FrameCodec.chunkAck(Tf.sessionId, Tf.transferId, offset, 1, offset),
            )
            assertEquals(offset, decoded.offset)
        }
        val notApplicable = roundTrip(FrameCodec.pause(Tf.sessionId, Tf.transferId))
        assertEquals(NOT_APPLICABLE, notApplicable.sequence)
        assertEquals(NOT_APPLICABLE, notApplicable.offset)
    }

    @Test fun `concatenated frames decode one after another`() {
        val first = FrameCodec.encode(FrameCodec.pause(Tf.sessionId, Tf.transferId, "one"))
        val second = FrameCodec.encode(FrameCodec.pause(Tf.sessionId, Tf.transferId, "two"))
        val stream = first + second
        val firstResult = FrameCodec.decode(stream) as FrameDecodeResult.Success
        assertEquals(first.size, firstResult.consumedBytes)
        val secondResult = FrameCodec.decode(
            stream, firstResult.consumedBytes, stream.size - firstResult.consumedBytes,
        ) as FrameDecodeResult.Success
        assertEquals(
            FramePayload.Pause("two"),
            FramePayloads.parse(secondResult.frame).let { (it as PayloadResult.Success).payload },
        )
        assertEquals(second.size, secondResult.consumedBytes)
        assertEquals(first.size + second.size, stream.size)
    }

    @Test fun `maximum frame size is the bound the codec actually produces`() {
        val frame = FrameCodec.dataChunk(
            sessionId = app.morsecode.core.transfer.identity.SessionId("s".repeat(ProtocolLimits.MAX_ID_LENGTH_BYTES)),
            transferId = app.morsecode.core.transfer.identity.TransferId("t".repeat(ProtocolLimits.MAX_ID_LENGTH_BYTES)),
            recipientId = Tf.recipientA,
            offset = 0L,
            sequence = 0L,
            bytes = Crc32Test.deterministicBytes(ProtocolLimits.MAX_PAYLOAD_BYTES),
        )
        val encoded = FrameCodec.encode(frame)
        assertEquals(ProtocolLimits.MAX_FRAME_SIZE_BYTES - (ProtocolLimits.MAX_ID_LENGTH_BYTES - 6), encoded.size)
        assertTrue(encoded.size <= ProtocolLimits.MAX_FRAME_SIZE_BYTES)
        assertTrue(FrameCodec.decodeSingle(encoded) is FrameDecodeResult.Success)
    }

    // --- helpers -------------------------------------------------------------

    private fun roundTrip(frame: TransferFrame): TransferFrame {
        val encoded = FrameCodec.encode(frame)
        val result = FrameCodec.decodeSingle(encoded)
        assertTrue(
            "expected the frame to decode: $result",
            result is FrameDecodeResult.Success,
        )
        val decoded = (result as FrameDecodeResult.Success).frame
        assertEquals(frame.type, decoded.type)
        assertEquals(frame.sessionId, decoded.sessionId)
        assertEquals(frame.transferId, decoded.transferId)
        assertEquals(frame.recipientId, decoded.recipientId)
        assertEquals(frame.version, decoded.version)
        assertTrue(decoded.payloadIsIntact())
        return decoded
    }

    private fun parse(frame: TransferFrame): FramePayload {
        val result = FramePayloads.parse(frame)
        assertTrue("expected the payload to parse: $result", result is PayloadResult.Success)
        return (result as PayloadResult.Success).payload
    }

    @Test fun `an empty payload parses for the types that allow one`() {
        val frame = FrameCodec.frame(FrameType.PAUSE, Tf.sessionId, Tf.transferId)
        assertTrue(FrameCodec.decodeSingle(FrameCodec.encode(frame)) is FrameDecodeResult.Success)
    }

    @Test fun `protocol version is carried on the wire`() {
        val frame = FrameCodec.pause(Tf.sessionId, Tf.transferId, version = ProtocolVersion.CURRENT)
        val decoded = (FrameCodec.decodeSingle(FrameCodec.encode(frame)) as FrameDecodeResult.Success).frame
        assertEquals(ProtocolVersion.CURRENT, decoded.version)
        assertEquals(1, decoded.version.value)
    }

    @Test fun `a digest that is not thirty two bytes is refused by the digest helper`() {
        assertEquals(null, Sha256Digest.of(ByteArray(31)))
    }
}
