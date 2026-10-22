package app.morsecode.core.transfer.session

import app.morsecode.core.transfer.identity.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two PAKE control records: the attempt start (MPS4) and a peer's decision (MPA1).
 *
 * The codec tests concentrate on the two things that go wrong in hand-written frame formats: a
 * decoder that trusts a length, and a binding check that silently ignores a field. Both are
 * exercised field by field rather than as one "hostile input" case.
 */
public class PakeRecordsTest {

    @Test
    public fun `an attempt start round-trips through the wire`() {
        val original = attemptStart()
        val decoded = requireNotNull(success(PakeAttemptStartCodec.decode(PakeAttemptStartCodec.encode(original))))
        assertEquals(original.suite, decoded.suite)
        assertEquals(original.profile, decoded.profile)
        assertEquals(original.role, decoded.role)
        assertEquals(original.secureSessionId, decoded.secureSessionId)
        assertEquals(original.controlSessionId, decoded.controlSessionId)
        assertEquals(original.attemptId, decoded.attemptId)
        assertEquals(original.attemptOrdinal, decoded.attemptOrdinal)
        assertTrue(original.codeInstanceBytes().contentEquals(decoded.codeInstanceBytes()))
        assertTrue(original.transcriptDigestBytes().contentEquals(decoded.transcriptDigestBytes()))
    }

    @Test
    public fun `an approval round-trips through the wire`() {
        val original = approval()
        val decoded = requireNotNull(success(PakeApprovalCodec.decode(PakeApprovalCodec.encode(original))))
        assertEquals(PakeApprovalDecision.APPROVE, decoded.decision)
        assertEquals(original.attemptId, decoded.attemptId)
        assertEquals(original.attemptOrdinal, decoded.attemptOrdinal)
        assertEquals(original.secureSessionId, decoded.secureSessionId)
    }

    @Test
    public fun `the two records are not interchangeable`() {
        val approvalFrame = PakeApprovalCodec.encode(approval())
        val startFrame = PakeAttemptStartCodec.encode(attemptStart())
        assertEquals(PakeRecordReject.BAD_MAGIC, rejection(PakeAttemptStartCodec.decode(approvalFrame)))
        assertEquals(PakeRecordReject.BAD_MAGIC, rejection(PakeApprovalCodec.decode(startFrame)))
    }

    @Test
    public fun `the attempt start digest is stable and changes with the attempt`() {
        val first = attemptStart().digest()
        val again = attemptStart().digest()
        assertTrue("the same record must digest the same way", first.contentEquals(again))

        val otherAttempt = attemptStart(attemptSeed = 0x21).digest()
        assertFalse("a different attempt must digest differently", first.contentEquals(otherAttempt))

        val otherOrdinal = attemptStart(ordinal = 2).digest()
        assertFalse(first.contentEquals(otherOrdinal))
    }

    @Test
    public fun `an approval binds to exactly one exchange`() {
        val record = approval()

        fun binds(
            attemptId: PakeAttemptId = record.attemptId,
            ordinal: Int = record.attemptOrdinal,
            codeInstance: ByteArray = ByteArray(16) { 0x33 },
            transcript: ByteArray = ByteArray(32) { 0x44 },
            secureSessionId: SessionId = record.secureSessionId,
            controlSessionId: String = record.controlSessionId,
            suite: PairingAuthenticationCapability = record.suite,
            profile: JpakeCurveProfile = record.profile,
            peerRole: SecurePeerRole = record.role,
        ): Boolean = record.bindsTo(
            expectedAttemptId = attemptId,
            expectedOrdinal = ordinal,
            expectedCodeInstance = codeInstance,
            expectedTranscriptDigest = transcript,
            expectedSecureSessionId = secureSessionId,
            expectedControlSessionId = controlSessionId,
            expectedSuite = suite,
            expectedProfile = profile,
            expectedPeerRole = peerRole,
        )

        assertTrue("the honest record must bind", binds())
        assertFalse("wrong attempt", binds(attemptId = attemptId(0x99)))
        assertFalse("wrong ordinal", binds(ordinal = 2))
        assertFalse("wrong code instance", binds(codeInstance = ByteArray(16) { 0x01 }))
        assertFalse("wrong transcript", binds(transcript = ByteArray(32) { 0x02 }))
        assertFalse(
            "wrong secure session",
            binds(secureSessionId = SessionId("ffffffffffffffffffffffffffffffff")),
        )
        assertFalse("wrong control session", binds(controlSessionId = "ctl-other"))
        assertFalse(
            "wrong suite",
            binds(suite = PairingAuthenticationCapability.SAS_25BIT_COMPARISON),
        )
        assertFalse(
            "wrong peer role: a reflected record names our own role",
            binds(peerRole = record.role.opposite),
        )
    }

    @Test
    public fun `a frame that is short, long or padded is refused`() {
        val frame = PakeApprovalCodec.encode(approval())
        assertEquals(PakeRecordReject.FRAME_TOO_SHORT, rejection(PakeApprovalCodec.decode(ByteArray(0))))
        assertEquals(
            PakeRecordReject.FRAME_TOO_SHORT,
            rejection(PakeApprovalCodec.decode(frame.copyOfRange(0, frame.size - 1))),
        )
        assertEquals(
            PakeRecordReject.FRAME_TOO_SHORT,
            rejection(PakeApprovalCodec.decode(frame + byteArrayOf(0x00))),
        )
    }

    @Test
    public fun `the declared length must match the frame`() {
        val frame = PakeApprovalCodec.encode(approval())
        val lying = frame.copyOf()
        lying[0] = 0x00
        lying[1] = 0x00
        lying[2] = 0x00
        lying[3] = 0x7F
        assertEquals(PakeRecordReject.FRAME_TOO_SHORT, rejection(PakeApprovalCodec.decode(lying)))

        // 257 > the 256-byte record bound, so this is refused on the declared length itself.
        val oversized = frame.copyOf()
        oversized[2] = 0x01
        oversized[3] = 0x01
        assertEquals(PakeRecordReject.FRAME_TOO_LARGE, rejection(PakeApprovalCodec.decode(oversized)))
    }

    @Test
    public fun `the header is checked before any field`() {
        val frame = PakeApprovalCodec.encode(approval())
        val badMagic = frame.copyOf().also { it[4] = 0x00 }
        assertEquals(PakeRecordReject.BAD_MAGIC, rejection(PakeApprovalCodec.decode(badMagic)))

        val badVersion = frame.copyOf().also { it[8] = 0x02 }
        assertEquals(PakeRecordReject.BAD_VERSION, rejection(PakeApprovalCodec.decode(badVersion)))
    }

    @Test
    public fun `duplicate, unordered, unknown and unterminated fields are refused`() {
        val base = fields(approval())
        assertEquals(
            "a repeated tag must be refused",
            PakeRecordReject.DUPLICATE_FIELD,
            rejection(PakeApprovalCodec.decode(raw(APPROVAL_MAGIC, listOf(base[0], base[0]) + base.drop(1)))),
        )
        assertEquals(
            "a descending tag must be refused",
            PakeRecordReject.FIELD_ORDER_NOT_CANONICAL,
            rejection(PakeApprovalCodec.decode(raw(APPROVAL_MAGIC, base.reversed()))),
        )
        assertEquals(
            "an unknown tag must be refused, never skipped",
            PakeRecordReject.UNKNOWN_FIELD,
            rejection(
                PakeApprovalCodec.decode(
                    raw(APPROVAL_MAGIC, base + (0x00FF to byteArrayOf(0x00))),
                ),
            ),
        )
        assertEquals(
            "a missing END tag must be refused",
            PakeRecordReject.MISSING_FIELD,
            rejection(PakeApprovalCodec.decode(raw(APPROVAL_MAGIC, base, terminate = false))),
        )
        assertEquals(
            "an END tag with a payload must be refused",
            PakeRecordReject.MALFORMED_END_TAG,
            rejection(PakeApprovalCodec.decode(raw(APPROVAL_MAGIC, base + (0x0000 to byteArrayOf(0x00))))),
        )
    }

    @Test
    public fun `a field whose declared length runs past the frame is refused`() {
        val base = fields(approval())
        val frame = raw(APPROVAL_MAGIC, base)
        // Inflate the first field's declared length.
        frame[9 + 2] = 0x7F
        frame[9 + 3] = 0x7F.toByte()
        assertEquals(PakeRecordReject.FIELD_LENGTH_OUT_OF_BOUNDS, rejection(PakeApprovalCodec.decode(frame)))
    }

    @Test
    public fun `out of range scalar fields are refused rather than defaulted`() {
        val base = fields(approval())
        fun withTag(tag: Int, value: ByteArray): ByteArray {
            val filtered = base.filterNot { it.first == tag } + (tag to value)
            return raw(APPROVAL_MAGIC, filtered.sortedBy { it.first })
        }
        assertEquals(
            "ordinal 0",
            PakeRecordReject.ORDINAL_INVALID,
            rejection(PakeApprovalCodec.decode(withTag(TAG_ORDINAL, byteArrayOf(0)))),
        )
        assertEquals(
            "ordinal 4",
            PakeRecordReject.ORDINAL_INVALID,
            rejection(PakeApprovalCodec.decode(withTag(TAG_ORDINAL, byteArrayOf(4)))),
        )
        assertEquals(
            "decision 0 is not a decision",
            PakeRecordReject.DECISION_INVALID,
            rejection(PakeApprovalCodec.decode(withTag(TAG_DECISION, byteArrayOf(0)))),
        )
        assertEquals(
            "decision 3 is not a decision",
            PakeRecordReject.DECISION_INVALID,
            rejection(PakeApprovalCodec.decode(withTag(TAG_DECISION, byteArrayOf(3)))),
        )
        assertEquals(
            "NONE is never a real suite",
            PakeRecordReject.SUITE_INVALID,
            rejection(PakeApprovalCodec.decode(withTag(TAG_SUITE, byteArrayOf(0, 0)))),
        )
        assertEquals(
            "an unknown profile must be refused",
            PakeRecordReject.PROFILE_INVALID,
            rejection(PakeApprovalCodec.decode(withTag(TAG_PROFILE, byteArrayOf(9)))),
        )
        assertEquals(
            "a short attempt id must be refused",
            PakeRecordReject.FIELD_LENGTH_OUT_OF_BOUNDS,
            rejection(PakeApprovalCodec.decode(withTag(TAG_ATTEMPT_ID, ByteArray(15)))),
        )
    }

    @Test
    public fun `a failure signal round-trips and says only the verdict`() {
        for (verdict in PakeAttemptVerdict.entries) {
            val decoded = requireNotNull(
                (PakeAttemptFailureCodec.decode(PakeAttemptFailureCodec.encode(PakeAttemptFailure(verdict)))
                    as? PakeAttemptFailureResult.Success)?.record,
            )
            assertEquals(verdict, decoded.verdict)
        }
    }

    @Test
    public fun `a failure signal cannot express why the attempt failed`() {
        // The shape is the guarantee: one constructor parameter, one enum, two values. If any of
        // these change, a peer would gain a way to distinguish a wrong code from a failed proof.
        assertEquals(2, PakeAttemptVerdict.entries.size)
        assertEquals(setOf(1, 2), PakeAttemptVerdict.entries.map { it.wireId }.toSet())
        val constructors = PakeAttemptFailure::class.java.declaredConstructors
        assertEquals("a failure record carries exactly one value", 1, constructors.size)
        assertEquals(
            "the only value a failure record carries is the verdict",
            listOf(PakeAttemptVerdict::class.java),
            constructors.single().parameterTypes.toList(),
        )
        val declared = PakeAttemptFailure::class.java.declaredFields.filterNot { it.isSynthetic }
        assertEquals(
            "no field may be added: a cause field would be a cryptographic oracle",
            listOf("verdict"),
            declared.map { it.name }.sorted(),
        )
    }

    @Test
    public fun `the failure signal is not interchangeable with the other records`() {
        val failure = PakeAttemptFailureCodec.encode(PakeAttemptFailure(PakeAttemptVerdict.RETRY))
        assertEquals(PakeRecordReject.BAD_MAGIC, rejection(PakeAttemptStartCodec.decode(failure)))
        assertEquals(PakeRecordReject.BAD_MAGIC, rejection(PakeApprovalCodec.decode(failure)))
        assertEquals(
            PakeRecordReject.BAD_MAGIC,
            rejection(PakeAttemptFailureCodec.decode(PakeApprovalCodec.encode(approval()))),
        )
    }

    @Test
    public fun `an unknown verdict is refused rather than defaulted`() {
        fun frame(value: Int): ByteArray =
            raw(FAILURE_MAGIC, listOf(TAG_VERDICT to byteArrayOf(value.toByte())))
        assertEquals(PakeRecordReject.VERDICT_INVALID, rejection(PakeAttemptFailureCodec.decode(frame(0))))
        assertEquals(PakeRecordReject.VERDICT_INVALID, rejection(PakeAttemptFailureCodec.decode(frame(3))))
        assertEquals(PakeRecordReject.VERDICT_INVALID, rejection(PakeAttemptFailureCodec.decode(frame(255))))
        assertEquals(
            PakeRecordReject.FIELD_LENGTH_OUT_OF_BOUNDS,
            rejection(PakeAttemptFailureCodec.decode(raw(FAILURE_MAGIC, listOf(TAG_VERDICT to ByteArray(2))))),
        )
        assertEquals(
            PakeRecordReject.MISSING_FIELD,
            rejection(PakeAttemptFailureCodec.decode(raw(FAILURE_MAGIC, emptyList()))),
        )
    }

    @Test
    public fun `a failure signal is small and redacted`() {
        val frame = PakeAttemptFailureCodec.encode(PakeAttemptFailure(PakeAttemptVerdict.ABORT))
        assertTrue("measured ${frame.size} bytes", frame.size <= 4 + 256)
        assertEquals("PakeAttemptFailure(verdict=ABORT)", PakeAttemptFailure(PakeAttemptVerdict.ABORT).toString())
    }

    @Test
    public fun `a record never exceeds its bound`() {
        val frame = PakeAttemptStartCodec.encode(attemptStart())
        assertTrue("measured ${frame.size} bytes", frame.size <= 4 + 256)
    }

    @Test
    public fun `records redact themselves`() {
        assertEquals("PakeAttemptStart(ordinal=1, role=INITIATOR, ids=[redacted])", attemptStart().toString())
        assertTrue(approval().toString().startsWith("PakeApproval(decision=APPROVE"))
        assertTrue(approval().toString().contains("[redacted]"))
    }

    // --- helpers ------------------------------------------------------------------------

    private fun attemptId(seed: Int = 0x11): PakeAttemptId =
        requireNotNull(PakeAttemptId.fromBytes(ByteArray(16) { seed.toByte() }))

    private fun attemptStart(
        attemptSeed: Int = 0x11,
        ordinal: Int = 1,
    ): PakeAttemptStart = PakeAttemptStart(
        suite = PairingAuthenticationCapability.JPAKE_MODP3072_ENTERED_CODE_V1,
        profile = JpakeCurveProfile.MODP_3072,
        role = SecurePeerRole.INITIATOR,
        secureSessionId = SECURE_ID,
        controlSessionId = "ctl-session",
        attemptId = attemptId(attemptSeed),
        attemptOrdinal = ordinal,
        codeInstanceId = ByteArray(16) { 0x33 },
        transcriptDigest = ByteArray(32) { 0x44 },
    )

    private fun approval(): PakeApproval = PakeApproval(
        decision = PakeApprovalDecision.APPROVE,
        suite = PairingAuthenticationCapability.JPAKE_MODP3072_ENTERED_CODE_V1,
        profile = JpakeCurveProfile.MODP_3072,
        role = SecurePeerRole.RESPONDER,
        secureSessionId = SECURE_ID,
        controlSessionId = "ctl-session",
        attemptId = attemptId(),
        attemptOrdinal = 1,
        codeInstanceId = ByteArray(16) { 0x33 },
        transcriptDigest = ByteArray(32) { 0x44 },
    )

    private fun fields(record: PakeApproval): List<Pair<Int, ByteArray>> = listOf(
        TAG_SUITE to byteArrayOf(0x00, 0x02),
        TAG_PROFILE to byteArrayOf(0x01),
        TAG_ROLE to byteArrayOf(0x02),
        TAG_SECURE_SESSION_ID to ByteArray(16) { 0x55 },
        TAG_CONTROL_SESSION_ID to "ctl-session".toByteArray(),
        TAG_ATTEMPT_ID to ByteArray(16) { 0x11 },
        TAG_CODE_INSTANCE to ByteArray(16) { 0x33 },
        TAG_ORDINAL to byteArrayOf(0x01),
        TAG_TRANSCRIPT_DIGEST to ByteArray(32) { 0x44 },
        TAG_DECISION to byteArrayOf(record.decision.wireId.toByte()),
    )

    /** Hand-built frame, so hostile shapes the encoder would never produce can be expressed. */
    private fun raw(
        magic: ByteArray,
        fields: List<Pair<Int, ByteArray>>,
        terminate: Boolean = true,
    ): ByteArray {
        // Magic and version only: the 4-byte length prefix is prepended afterwards and is not part
        // of its own count.
        var size = 4 + 1
        for ((_, value) in fields) size += 4 + value.size
        if (terminate) size += 4
        val payload = java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.BIG_ENDIAN)
        payload.put(magic)
        payload.put(1.toByte())
        for ((tag, value) in fields) {
            payload.putShort(tag.toShort())
            payload.putShort(value.size.toShort())
            payload.put(value)
        }
        if (terminate) {
            payload.putShort(0)
            payload.putShort(0)
        }
        val body = payload.array()
        val out = ByteArray(4 + body.size)
        out[0] = ((body.size ushr 24) and 0xFF).toByte()
        out[1] = ((body.size ushr 16) and 0xFF).toByte()
        out[2] = ((body.size ushr 8) and 0xFF).toByte()
        out[3] = (body.size and 0xFF).toByte()
        body.copyInto(out, 4)
        return out
    }

    private fun success(result: PakeAttemptStartResult): PakeAttemptStart? =
        (result as? PakeAttemptStartResult.Success)?.record

    private fun rejection(result: PakeAttemptFailureResult): PakeRecordReject? =
        (result as? PakeAttemptFailureResult.Rejected)?.reason

    private fun success(result: PakeApprovalResult): PakeApproval? =
        (result as? PakeApprovalResult.Success)?.record

    private fun rejection(result: PakeAttemptStartResult): PakeRecordReject? =
        (result as? PakeAttemptStartResult.Rejected)?.reason

    private fun rejection(result: PakeApprovalResult): PakeRecordReject? =
        (result as? PakeApprovalResult.Rejected)?.reason

    private companion object {
        val SECURE_ID = SessionId("0123456789abcdef0123456789abcdef")
        val APPROVAL_MAGIC = byteArrayOf(0x4D, 0x50, 0x41, 0x31)
        val FAILURE_MAGIC = byteArrayOf(0x4D, 0x50, 0x53, 0x46)
        const val TAG_VERDICT = 0x0001
        const val TAG_SUITE = 0x0001
        const val TAG_PROFILE = 0x0002
        const val TAG_ROLE = 0x0003
        const val TAG_SECURE_SESSION_ID = 0x0004
        const val TAG_CONTROL_SESSION_ID = 0x0005
        const val TAG_ATTEMPT_ID = 0x0006
        const val TAG_CODE_INSTANCE = 0x0007
        const val TAG_ORDINAL = 0x0008
        const val TAG_TRANSCRIPT_DIGEST = 0x0009
        const val TAG_DECISION = 0x000A
    }
}
