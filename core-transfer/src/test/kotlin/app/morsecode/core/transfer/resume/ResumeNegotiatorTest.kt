package app.morsecode.core.transfer.resume

import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.Tf
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.ConfirmedOffset
import app.morsecode.core.transfer.identity.ProtocolVersion
import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.integrity.Sha256Digest
import app.morsecode.core.transfer.model.TransferFileDescriptor
import app.morsecode.core.transfer.protocol.ResumeDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resume negotiation: what a receiver does with a peer's claim about a file it
 * may or may not already have.
 *
 * The rule is *never trust, always verify*. The only number that becomes a resume
 * offset is the receiver's own confirmed byte count: a descriptor match proves
 * the two ends are discussing the same file, not that the bytes are intact, so a
 * complete partial is retransmitted rather than believed. The sender's own write
 * position is carried but never read.
 */
class ResumeNegotiatorTest {

    private val negotiator = ResumeNegotiator

    /** A three-chunk file whose digest both sides know. */
    private val base: TransferFileDescriptor = Tf.descriptor(digest = Tf.knownDigest)

    // --- a receiver that holds nothing ------------------------------------------

    @Test fun `a receiver that has never seen the transfer starts at zero`() {
        assertEquals(ResumeDecision.ResumeAt(0L), negotiate(receiver = ReceiverResumeState.unknown()))
    }

    @Test fun `starting at zero is a resume decision, not a rejection`() {
        assertEquals(
            ConfirmedOffset(0L),
            negotiator.resumeOffset(proposal(), ReceiverResumeState.unknown()),
        )
    }

    // --- the offset we resume from -------------------------------------------------

    @Test fun `a chunk aligned partial resumes at the confirmed offset`() {
        assertEquals(
            ResumeDecision.ResumeAt(8_192L),
            negotiate(receiver = receiver(confirmedBytes = 8_192L)),
        )
    }

    @Test fun `an unaligned partial restarts because the offsets would drift`() {
        assertTrue(
            negotiate(receiver = receiver(confirmedBytes = 8_192L + 1L)) is ResumeDecision.RestartAtZero,
        )
        assertTrue(
            (negotiate(receiver = receiver(confirmedBytes = 8_193L)) as ResumeDecision.RestartAtZero)
                .reason.contains("not aligned"),
        )
    }

    @Test fun `a complete but unverified partial is retransmitted rather than trusted`() {
        assertEquals(
            ResumeDecision.RestartAtZero("a complete but unverified partial must be retransmitted"),
            negotiate(
                proposal = proposal(Tf.descriptor(totalBytes = 12L, digest = Tf.knownDigest)),
                receiver = receiver(
                    confirmedBytes = 12L,
                    verification = ReceiverVerificationState.NOT_STARTED,
                    descriptor = Tf.descriptor(totalBytes = 12L, digest = Tf.knownDigest),
                ),
            ),
        )
    }

    @Test fun `a partial still being verified is retransmitted too`() {
        assertTrue(
            negotiate(
                proposal = proposal(Tf.descriptor(totalBytes = 12L, digest = Tf.knownDigest)),
                receiver = receiver(
                    confirmedBytes = 12L,
                    verification = ReceiverVerificationState.PENDING,
                    descriptor = Tf.descriptor(totalBytes = 12L, digest = Tf.knownDigest),
                ),
            ) is ResumeDecision.RestartAtZero,
        )
    }

    @Test fun `a verified complete file is skipped entirely`() {
        assertEquals(
            ResumeDecision.AlreadyVerified,
            negotiate(
                proposal = proposal(Tf.descriptor(totalBytes = 12L, digest = Tf.knownDigest)),
                receiver = receiver(
                    confirmedBytes = 12L,
                    verification = ReceiverVerificationState.VERIFIED,
                    descriptor = Tf.descriptor(totalBytes = 12L, digest = Tf.knownDigest),
                ),
            ),
        )
    }

    @Test fun `a zero byte file needs no bytes but still reports already verified`() {
        assertEquals(
            ResumeDecision.AlreadyVerified,
            negotiate(
                proposal = proposal(Tf.descriptor(totalBytes = 0L, digest = Tf.knownDigest)),
                receiver = receiver(
                    confirmedBytes = 0L,
                    verification = ReceiverVerificationState.VERIFIED,
                    descriptor = Tf.descriptor(totalBytes = 0L, digest = Tf.knownDigest),
                ),
            ),
        )
    }

    @Test fun `a receiver holding more bytes than the file contains is rejected`() {
        val small = Tf.descriptor(totalBytes = 500L, digest = Tf.knownDigest)
        val decision = negotiate(
            proposal = proposal(small),
            // A receiver that has not recorded the file's size yet is exactly the
            // state that can hold more bytes than the sender says exist.
            receiver = receiver(confirmedBytes = 1_000L, totalBytes = null),
        )
        assertTrue(decision is ResumeDecision.Reject)
        assertTrue((decision as ResumeDecision.Reject).error is TransferError.UnexpectedOffset)
    }

    // --- identity and size disagreement ------------------------------------------------

    @Test fun `a different file occupying the transfer restarts`() {
        assertTrue(
            negotiate(receiver = receiver(confirmedBytes = 4_096L, fileId = Tf.otherFileId))
                is ResumeDecision.RestartAtZero,
        )
    }

    @Test fun `a changed total size restarts`() {
        val receiver = receiver(confirmedBytes = 20L).copy(totalBytes = 21L)
        assertTrue(
            negotiate(receiver = receiver) is ResumeDecision.RestartAtZero,
        )
        assertTrue(
            (negotiate(receiver = receiver) as ResumeDecision.RestartAtZero)
                .reason.contains("stored size"),
        )
    }

    @Test fun `a changed descriptor restarts`() {
        val receiver = receiver(confirmedBytes = 4_096L).copy(
            descriptorFingerprint = Tf.descriptor(name = "renamed.jpg", digest = Tf.knownDigest)
                .descriptorFingerprint,
        )
        assertTrue(negotiate(receiver = receiver) is ResumeDecision.RestartAtZero)
    }

    @Test fun `a changed chunk size restarts because the boundaries moved`() {
        val receiver = receiver(confirmedBytes = 8_192L).copy(chunkSize = ChunkSize(8_192))
        assertTrue(negotiate(receiver = receiver) is ResumeDecision.RestartAtZero)
        assertTrue(
            (negotiate(receiver = receiver) as ResumeDecision.RestartAtZero)
                .reason.contains("chunk size"),
        )
    }

    @Test fun `a changed expectation of the digest restarts`() {
        val receiver = receiver(confirmedBytes = 4_096L).copy(expectedSha256 = Tf.otherDigest)
        assertTrue(negotiate(receiver = receiver) is ResumeDecision.RestartAtZero)
    }

    @Test fun `a digest the receiver never learned is not a disagreement`() {
        val receiver = receiver(confirmedBytes = 4_096L).copy(expectedSha256 = null)
        assertEquals(ResumeDecision.ResumeAt(4_096L), negotiate(receiver = receiver))
    }

    @Test fun `a receiver with no stored descriptor cannot claim a disagreement`() {
        val receiver = receiver(confirmedBytes = 4_096L).copy(descriptorFingerprint = null)
        assertEquals(ResumeDecision.ResumeAt(4_096L), negotiate(receiver = receiver))
    }

    @Test fun `a proposal whose digest differs restarts on the descriptor fingerprint`() {
        val receiver = receiver(confirmedBytes = 4_096L)
        val decision = negotiate(
            proposal = proposal(Tf.descriptor(digest = Tf.otherDigest)),
            receiver = receiver,
        )
        assertTrue(decision is ResumeDecision.RestartAtZero)
        assertTrue(
            (decision as ResumeDecision.RestartAtZero).reason.contains("descriptor"),
        )
    }

    // --- failed verification ------------------------------------------------------------

    @Test fun `a partial that failed its own verification restarts`() {
        assertEquals(
            ResumeDecision.RestartAtZero("the stored partial failed verification"),
            negotiate(receiver = receiver(confirmedBytes = 8_192L, verification = ReceiverVerificationState.FAILED)),
        )
    }

    @Test fun `a failed verification outranks an otherwise perfect match`() {
        val receiver = receiver(
            confirmedBytes = 4_096L,
            verification = ReceiverVerificationState.FAILED,
        )
        assertEquals(
            ResumeDecision.RestartAtZero("the stored partial failed verification"),
            negotiate(receiver = receiver),
        )
    }

    // --- rejecting a proposal ---------------------------------------------------------------

    @Test fun `an undecodable version cannot reach the negotiator at all`() {
        // ProtocolVersion refuses to be built outside the supported range, so the
        // negotiator never sees one. That is the whole point of a validated type:
        // the guard lives in the constructor instead of in every consumer.
        for (value in listOf(-1, 0, 2, 9, Int.MAX_VALUE)) {
            var threw = false
            try {
                ProtocolVersion(value)
            } catch (expected: IllegalArgumentException) {
                threw = true
            }
            assertTrue("version $value must be refused", threw)
            assertNull(ProtocolVersion.orNull(value))
        }
        assertTrue(ProtocolVersion.isValid(ProtocolVersion.CURRENT.value))
    }

    // --- sender advertised bytes are advisory only --------------------------------------------

    @Test fun `sender advertised bytes never extend the resume offset`() {
        val receiver = receiver(confirmedBytes = 4_096L)
        assertEquals(
            ResumeDecision.ResumeAt(4_096L),
            negotiate(receiver = receiver, proposal = proposal().copy(senderAdvertisedBytes = 1_000_000L)),
        )
    }

    @Test fun `sender advertised bytes never shrink the resume offset`() {
        val receiver = receiver(confirmedBytes = 8_192L)
        assertEquals(
            ResumeDecision.ResumeAt(8_192L),
            negotiate(receiver = receiver, proposal = proposal().copy(senderAdvertisedBytes = 1L)),
        )
    }

    @Test fun `a proposal is refused before it can describe a negative advertised position`() {
        var threw = false
        try {
            proposal().copy(senderAdvertisedBytes = -1L)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a negative advertised position must be rejected", threw)
    }

    // --- determinism and idempotence -------------------------------------------------------------

    @Test fun `the same inputs always produce the same decision`() {
        val cases = listOf(
            proposal() to ReceiverResumeState.unknown(),
            proposal() to receiver(confirmedBytes = 8_192L),
            proposal() to receiver(confirmedBytes = 8_193L),
            proposal() to receiver(confirmedBytes = 4_096L, verification = ReceiverVerificationState.FAILED),
            proposal() to receiver(confirmedBytes = 12L, verification = ReceiverVerificationState.VERIFIED),
        )
        for ((proposal, receiver) in cases) {
            val first = negotiator.negotiate(proposal, receiver)
            repeat(5) {
                assertEquals(
                    "the decision for $receiver must be stable",
                    first,
                    negotiator.negotiate(proposal, receiver),
                )
            }
        }
    }

    @Test fun `a deterministic sweep of every offset up to three chunks classifies correctly`() {
        // A deterministic sweep stands in for a property test: the offset is the
        // seed, so a failure names the exact input.
        val chunkSize = Tf.chunk.value.toLong()
        // One byte short of the end, so every offset in range is still a partial.
        val maximum = 3L * chunkSize - 1L
        var resumed = 0
        for (confirmed in 0L..maximum) {
            val decision = negotiate(receiver = receiver(confirmedBytes = confirmed))
            when (decision) {
                is ResumeDecision.ResumeAt -> {
                    assertEquals("offset $confirmed", confirmed, decision.offset)
                    assertEquals("offset $confirmed should be aligned", 0L, confirmed % chunkSize)
                    resumed += 1
                }
                is ResumeDecision.RestartAtZero -> assertFalse(
                    "offset $confirmed should have resumed",
                    confirmed % chunkSize == 0L,
                )
                else -> throw AssertionError("unexpected decision at $confirmed: $decision")
            }
        }
        assertEquals("zero, one chunk and two chunks", 3, resumed)
    }

    @Test fun `large aligned offsets resume and their neighbours do not`() {
        val big = Tf.descriptor(totalBytes = 2_000_000L, digest = Tf.knownDigest)
        for (confirmed in listOf(4_096L, 8_192L, 262_144L, 1_048_576L)) {
            assertEquals(
                "offset $confirmed",
                ResumeDecision.ResumeAt(confirmed),
                negotiate(
                    proposal = proposal(big),
                    receiver = receiver(confirmedBytes = confirmed, descriptor = big),
                ),
            )
        }
        for (confirmed in listOf(4_095L, 4_097L, 8_191L, 8_193L)) {
            assertTrue(
                "offset $confirmed",
                negotiate(
                    proposal = proposal(big),
                    receiver = receiver(confirmedBytes = confirmed, descriptor = big),
                ) is ResumeDecision.RestartAtZero,
            )
        }
    }

    // --- resumeOffset convenience -------------------------------------------------------------------

    @Test fun `resumeOffset reports nothing when the answer is not a plain offset`() {
        assertNull(
            negotiator.resumeOffset(
                proposal(),
                receiver(confirmedBytes = 8_192L, verification = ReceiverVerificationState.FAILED),
            ),
        )
        assertNull(
            negotiator.resumeOffset(
                proposal(),
                receiver(confirmedBytes = 12L, verification = ReceiverVerificationState.VERIFIED),
            ),
        )
        assertNotNull(negotiator.resumeOffset(proposal(), receiver(confirmedBytes = 8_192L)))
    }

    @Test fun `every receiver verification state round trips through its id`() {
        for (state in ReceiverVerificationState.entries) {
            assertEquals(state, ReceiverVerificationState.fromId(state.id))
        }
        assertEquals(ReceiverVerificationState.NOT_STARTED, ReceiverVerificationState.fromId(null))
        assertEquals(ReceiverVerificationState.NOT_STARTED, ReceiverVerificationState.fromId("nonsense"))
    }

    @Test fun `a receiver state cannot hold more confirmed bytes than the file contains`() {
        var threw = false
        try {
            receiver(confirmedBytes = 100L).copy(totalBytes = 50L)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private fun proposal(descriptor: TransferFileDescriptor = base): ResumeProposal =
        ResumeProposal(
            protocolVersion = ProtocolVersion.CURRENT,
            fileId = descriptor.fileId,
            totalBytes = descriptor.totalBytes,
            chunkSize = descriptor.chunkSize,
            expectedSha256 = descriptor.expectedSha256,
            relativePath = descriptor.relativePath,
            lastModifiedEpochMillis = descriptor.lastModifiedEpochMillis,
            descriptorFingerprint = descriptor.descriptorFingerprint,
        )

    private fun negotiate(
        proposal: ResumeProposal = proposal(),
        receiver: ReceiverResumeState,
    ): ResumeDecision = negotiator.negotiate(proposal, receiver)

    private fun receiver(
        confirmedBytes: Long,
        fileId: app.morsecode.core.transfer.identity.FileId = Tf.fileId,
        verification: ReceiverVerificationState = ReceiverVerificationState.NOT_STARTED,
        descriptor: TransferFileDescriptor = base,
        totalBytes: Long? = descriptor.totalBytes,
    ): ReceiverResumeState = ReceiverResumeState(
        knownTransfer = true,
        fileId = fileId,
        totalBytes = totalBytes,
        confirmedBytes = confirmedBytes,
        descriptorFingerprint = descriptor.descriptorFingerprint,
        chunkSize = descriptor.chunkSize,
        verification = verification,
        expectedSha256 = descriptor.expectedSha256,
        lastModifiedEpochMillis = descriptor.lastModifiedEpochMillis,
    )
}
