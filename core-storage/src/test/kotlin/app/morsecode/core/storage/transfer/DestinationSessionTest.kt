package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Digest
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The sequential-write gate, driven against an in-memory sink.
 *
 * WHY A FAKE SINK AND NOT A FILE
 *   Every case here is about a decision made *before* a byte is stored, or about what happens to the
 *   frontier when storing fails. A real file would add I/O noise and, worse, could not be told to fail
 *   on the third byte. The sink below records every call, so a test can assert not just the outcome but
 *   that a refused write never reached the sink at all.
 *
 * WHAT IS NOT COVERED HERE
 *   Android providers, `content://` sources and SAF publication are covered by the Robolectric suite
 *   in this module; this file is deliberately free of Android types so the invariant is provable in
 *   plain JVM.
 */
class DestinationSessionTest {

    private val transferId = TransferId("transfer-1")
    private val identity = PartialIdentity.of(transferId, DestinationStrategy.APP_PRIVATE)

    // --- opening and reconciliation ---------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun anEmptyPartialOpensAtZero() {
        val sink = MemorySink()
        val opened = openOn(sink)
        val session = opened.session()
        assertEquals(DestinationSessionState.OPEN, session.currentState)
        assertEquals(0L, session.expectedFrontier)
        assertEquals(0L, session.confirmedFrontier)
        assertFalse(opened.opened().requiresTruncation)
    }

    @Test(timeout = 30_000L)
    fun aPartialLongerThanConfirmedBytesRequiresExplicitTruncation() {
        val sink = MemorySink(initial = ByteArray(100) { 1 })
        val opened = openOn(sink, confirmed = 40L)
        val session = opened.session()
        assertTrue(
            "extra bytes must be flagged, not silently adopted",
            opened.opened().requiresTruncation,
        )

        // Writing at the confirmed frontier would land inside the bytes that already exist.
        val refused = session.writeAt(40L, ByteArray(10))
        assertTrue(refused is DestinationWrite.Rejected)
        assertEquals(SequentialRejection.OVERLAP, (refused as DestinationWrite.Rejected).reason)
        assertEquals("a refusal never advances", 40L, session.expectedFrontier)

        // The caller says what it wants; only then may writing resume.
        val truncated = session.truncateTo(40L)
        assertTrue(truncated is TruncateOutcome.Truncated)
        assertEquals(40L, sink.length())
        val written = session.writeAt(40L, ByteArray(10) { 7 })
        assertTrue(written is DestinationWrite.Written)
        assertEquals(50L, session.expectedFrontier)
    }

    @Test(timeout = 30_000L)
    fun aPartialShorterThanConfirmedBytesCannotResume() {
        val sink = MemorySink(initial = ByteArray(10))
        val opened = LanDestinationSession.open(
            transferId = transferId,
            identity = identity,
            sink = sink,
            declaredSizeBytes = 100L,
            initialConfirmedBytes = 40L,
        )
        assertTrue(opened is DestinationOpen.Refused)
        val error = (opened as DestinationOpen.Refused).error
        assertEquals(TransferStorageErrorCategory.STATE, error.category)
        assertEquals("partial_shorter_than_confirmed", (error as TransferStorageError.StateConflict).reason)
    }

    @Test(timeout = 30_000L)
    fun aNonzeroRestoredFrontierCannotOpenWithoutTheRetainedDigest() {
        val sink = MemorySink(initial = byteArrayOf(1, 2, 3, 4))
        val direct = DestinationSession.open(
            transferId = transferId,
            identity = identity,
            sink = sink,
            initialConfirmedBytes = 4L,
        )
        assertEquals(
            "expected_sha256_required",
            ((direct as DestinationOpen.Refused).error as TransferStorageError.StateConflict).reason,
        )

        // The checkpoint is authoritative even if the caller supplies an initial zero frontier.
        val checkpoint = MemoryCheckpoint()
        checkpoint.persist(transferId, 4L, FlushDurability.DurableFlushSupported)
        val fromCheckpoint = DestinationSession.open(
            transferId = transferId,
            identity = identity,
            sink = sink,
            checkpointSink = checkpoint,
            initialConfirmedBytes = 0L,
        )
        assertEquals(
            "expected_sha256_required",
            ((fromCheckpoint as DestinationOpen.Refused).error as TransferStorageError.StateConflict).reason,
        )
        assertEquals(0, sink.writeCalls)
    }

    @Test(timeout = 30_000L)
    fun aPartialLongerThanItsDeclaredSizeCannotResume() {
        val sink = MemorySink(initial = ByteArray(101))
        val opened = LanDestinationSession.open(
            transferId = transferId,
            identity = identity,
            sink = sink,
            declaredSizeBytes = 100L,
        )
        assertTrue(opened is DestinationOpen.Refused)
        assertEquals(
            "partial_beyond_declared_size",
            ((opened as DestinationOpen.Refused).error as TransferStorageError.StateConflict).reason,
        )
    }

    // --- sequential-write policy -------------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun sequentialWritesAdvanceTheFrontierExactly() {
        val sink = MemorySink()
        val session = openOn(sink, declared = 300L).session()
        var offset = 0L
        repeat(3) {
            val chunk = ByteArray(100) { (it + it).toByte() }
            val outcome = session.writeAt(offset, chunk)
            assertTrue("chunk at $offset: $outcome", outcome is DestinationWrite.Written)
            offset = (outcome as DestinationWrite.Written).frontier
        }
        assertEquals(300L, session.expectedFrontier)
        assertEquals(300L, sink.length())
        assertArrayEquals(ByteArray(300) { (it % 100 + it % 100).toByte() }, sink.bytes())
    }

    @Test(timeout = 30_000L)
    fun aHoleIsRefused() {
        val session = openOn(MemorySink()).session()
        val outcome = session.writeAt(10L, ByteArray(5))
        assertTrue(outcome is DestinationWrite.Rejected)
        assertEquals(SequentialRejection.HOLE, (outcome as DestinationWrite.Rejected).reason)
        assertEquals(0L, session.expectedFrontier)
    }

    @Test(timeout = 30_000L)
    fun anOverlapIsRefused() {
        // Bytes already on disk with nothing confirmed: writing "at the frontier" would land inside
        // them, so the session refuses until the caller truncates explicitly.
        val sink = MemorySink(initial = ByteArray(10))
        val opened = openOn(sink)
        val session = opened.session()
        assertTrue(opened.opened().requiresTruncation)
        val outcome = session.writeAt(0L, ByteArray(5))
        assertTrue("an overlap must be refused: $outcome", outcome is DestinationWrite.Rejected)
        assertEquals(SequentialRejection.OVERLAP, (outcome as DestinationWrite.Rejected).reason)
        assertEquals("the refusal did not touch the sink", 0, sink.writeCalls)
        assertEquals(0L, session.expectedFrontier)
    }

    @Test(timeout = 30_000L)
    fun aRewindIsRefused() {
        val session = openOn(MemorySink()).session()
        session.writeAt(0L, ByteArray(10))
        val outcome = session.writeAt(4L, ByteArray(2))
        assertTrue(outcome is DestinationWrite.Rejected)
        assertEquals(SequentialRejection.REWIND, (outcome as DestinationWrite.Rejected).reason)
        assertEquals(10L, session.expectedFrontier)
    }

    @Test(timeout = 30_000L)
    fun aNegativeOffsetIsRefused() {
        val session = openOn(MemorySink()).session()
        val outcome = session.writeAt(-1L, ByteArray(4))
        assertTrue(outcome is DestinationWrite.Rejected)
        assertEquals(SequentialRejection.NEGATIVE, (outcome as DestinationWrite.Rejected).reason)
    }

    @Test(timeout = 30_000L)
    fun anOffsetThatWouldOverflowIsRefused() {
        val session = openOn(MemorySink()).session()
        val outcome = session.writeAt(Long.MAX_VALUE - 2L, ByteArray(8))
        assertTrue(outcome is DestinationWrite.Rejected)
        assertEquals(SequentialRejection.OFFSET_OVERFLOW, (outcome as DestinationWrite.Rejected).reason)
    }

    @Test(timeout = 30_000L)
    fun aWritePastTheDeclaredSizeIsRefused() {
        val sink = MemorySink(initial = ByteArray(100))
        val session = openOn(sink, declared = 100L).session()
        val outcome = session.writeAt(90L, ByteArray(20))
        assertTrue(outcome is DestinationWrite.Rejected)
        assertEquals(SequentialRejection.PAST_DECLARED_SIZE, (outcome as DestinationWrite.Rejected).reason)
        assertEquals(0L, session.expectedFrontier)
    }

    @Test(timeout = 30_000L)
    fun anEmptyAndAnOversizedWriteAreRefused() {
        val session = openOn(MemorySink()).session()
        assertEquals(
            SequentialRejection.EMPTY,
            (session.writeAt(0L, ByteArray(0)) as DestinationWrite.Rejected).reason,
        )
        assertEquals(
            SequentialRejection.TOO_LARGE,
            (session.writeAt(0L, ByteArray(300_000)) as DestinationWrite.Rejected).reason,
        )
        assertEquals(
            SequentialRejection.BUFFER_BOUNDS,
            (session.writeAt(0L, ByteArray(4), dataOffset = 2, length = 4) as DestinationWrite.Rejected).reason,
        )
    }

    @Test(timeout = 30_000L)
    fun aShortWriteIsAFailureAndDoesNotAdvanceTheFrontier() {
        val sink = MemorySink(shortWriteBytes = 5)
        val session = openOn(sink).session()
        val outcome = session.writeAt(0L, ByteArray(10))
        assertTrue("a short write must fail: $outcome", outcome is DestinationWrite.Failed)
        assertEquals(0L, session.expectedFrontier)
        // The sink holds five bytes while the frontier says zero: the next write is an overlap, not a
        // silent continuation over the half-written chunk.
        val next = session.writeAt(0L, ByteArray(10))
        assertEquals(SequentialRejection.OVERLAP, (next as DestinationWrite.Rejected).reason)
        val repaired = session.truncateTo(0L)
        assertTrue(repaired is TruncateOutcome.Truncated)
        assertTrue(session.writeAt(0L, ByteArray(10)) is DestinationWrite.Written)
    }

    @Test(timeout = 30_000L)
    fun shortWriteAfterConfirmedProgressRequiresExplicitReconciliation() {
        val sink = MemorySink()
        val checkpoint = MemoryCheckpoint()
        val session = openOn(sink, checkpoint = checkpoint).session()
        assertTrue(session.writeAt(0L, byteArrayOf(1, 2, 3, 4)) is DestinationWrite.Written)
        assertNull(session.acknowledge(4L))

        sink.shortWriteBytes = 2
        val nextChunk = byteArrayOf(5, 6, 7, 8, 9, 10)
        assertTrue(session.writeAt(4L, nextChunk) is DestinationWrite.Failed)
        assertEquals("the physical partial includes the two unconfirmed bytes", 6L, sink.length())
        assertEquals(4L, session.expectedFrontier)
        assertEquals(4L, session.confirmedFrontier)
        assertEquals(4L, checkpoint.frontier)
        assertEquals(
            SequentialRejection.OVERLAP,
            (session.writeAt(4L, nextChunk) as DestinationWrite.Rejected).reason,
        )
        assertTrue(session.acknowledge(6L) is TransferStorageError.StateConflict)
        assertEquals(4L, session.confirmedFrontier)

        assertTrue(session.truncateTo(4L) is TruncateOutcome.Truncated)
        assertEquals(4L, sink.length())
        assertTrue(session.writeAt(4L, nextChunk) is DestinationWrite.Written)
        assertEquals(10L, sink.length())
        assertEquals(10L, session.expectedFrontier)
        assertEquals("newly written bytes remain optimistic", 4L, session.confirmedFrontier)
        assertNull(session.acknowledge(10L))
        assertEquals(10L, checkpoint.frontier)
    }

    @Test(timeout = 30_000L)
    fun classifiedWriteFailureAfterProgressRequiresTruncationBeforeContinuation() {
        val secret = "content://private/secret/photo.jpg"
        val sink = MidStreamFailingSink(secret)
        val checkpoint = MemoryCheckpoint()
        val publisher = CountingPublisher()
        val session = DestinationSession.open(
            transferId = transferId,
            identity = identity,
            sink = sink,
            checkpointSink = checkpoint,
            verificationSource = sink.verificationSource(),
            publisher = publisher,
        ).session()
        val first = byteArrayOf(1, 2, 3, 4)
        val rest = byteArrayOf(5, 6, 7, 8, 9, 10)
        assertTrue(session.writeAt(0L, first) is DestinationWrite.Written)
        assertNull(session.acknowledge(4L))

        val failure = session.writeAt(4L, rest) as DestinationWrite.Failed
        assertEquals(TransferStorageErrorCategory.INSUFFICIENT_SPACE, failure.error.category)
        assertNull(failure.error.diagnostic)
        assertFalse(failure.error.toString().contains(secret))
        assertEquals(6L, sink.length())
        assertEquals(4L, session.expectedFrontier)
        assertEquals(4L, session.confirmedFrontier)
        assertEquals(4L, checkpoint.frontier)
        assertEquals(
            SequentialRejection.OVERLAP,
            (session.writeAt(4L, rest) as DestinationWrite.Rejected).reason,
        )
        assertEquals("the overlap is refused before invoking the sink", 2, sink.writeCalls)

        assertTrue(session.truncateTo(4L) is TruncateOutcome.Truncated)
        assertEquals(4L, sink.length())
        assertTrue(session.writeAt(4L, rest) is DestinationWrite.Written)
        assertEquals(10L, sink.length())
        assertEquals(4L, session.confirmedFrontier)
        assertNull(session.acknowledge(10L))
        assertTrue(session.verify(digestOf(first + rest)) is SessionVerification.Verified)
        assertEquals(CommitResult.Committed, session.commit())
        assertEquals(1, publisher.publishCount)
        session.close()
    }

    @Test(timeout = 30_000L)
    fun storageFullAtWriteTimeIsClassifiedNotGeneric() {
        val sink = MemorySink(failure = TransferStorageError.StorageFull("write"))
        val session = openOn(sink).session()
        val outcome = session.writeAt(0L, ByteArray(8))
        assertTrue(outcome is DestinationWrite.Failed)
        assertEquals(
            TransferStorageErrorCategory.INSUFFICIENT_SPACE,
            (outcome as DestinationWrite.Failed).error.category,
        )
        assertEquals(0L, session.expectedFrontier)
    }

    @Test(timeout = 30_000L)
    fun aProviderFailureAndARevocationStayDistinct() {
        val provider = openOn(MemorySink(failure = TransferStorageError.ProviderFailure("documents")))
            .session()
        val providerFailure = provider.writeAt(0L, ByteArray(8)) as DestinationWrite.Failed
        assertEquals(
            TransferStorageErrorCategory.PROVIDER,
            providerFailure.error.category,
        )

        val revoked = openOn(MemorySink(failure = TransferStorageError.PermissionRevoked("write")))
            .session()
        val revocation = revoked.writeAt(0L, ByteArray(8)) as DestinationWrite.Failed
        assertEquals(
            TransferStorageErrorCategory.PERMISSION_REVOKED,
            revocation.error.category,
        )
        assertNotEquals(providerFailure.error.category, revocation.error.category)
    }

    @Test(timeout = 30_000L)
    fun aFlushFailureDoesNotAdvanceTheFrontier() {
        val sink = MemorySink(flushFailure = true)
        val session = openOn(sink).session()
        val outcome = session.writeAt(0L, ByteArray(10))
        assertTrue("a failed flush must fail the write: $outcome", outcome is DestinationWrite.Failed)
        assertEquals(
            TransferStorageErrorCategory.IO,
            (outcome as DestinationWrite.Failed).error.category,
        )
        assertEquals(0L, session.expectedFrontier)
        assertEquals(10L, sink.length())
    }

    // --- truncation and acknowledgement ------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun truncationIsBoundedByConfirmedAndFrontier() {
        val sink = MemorySink(initial = ByteArray(100))
        val session = openOn(sink, confirmed = 20L).session()
        val belowConfirmed = session.truncateTo(10L)
        assertTrue(belowConfirmed is TruncateOutcome.Failed)
        assertEquals(
            "truncate_below_confirmed",
            ((belowConfirmed as TruncateOutcome.Failed).error as TransferStorageError.StateConflict).reason,
        )
        val beyondFrontier = session.truncateTo(120L)
        assertTrue(beyondFrontier is TruncateOutcome.Failed)
        assertEquals(
            "truncate_beyond_frontier",
            ((beyondFrontier as TruncateOutcome.Failed).error as TransferStorageError.StateConflict).reason,
        )
        assertTrue(session.truncateTo(20L) is TruncateOutcome.Truncated)
        assertEquals(20L, session.expectedFrontier)
    }

    @Test(timeout = 30_000L)
    fun acknowledgementIsMonotonicAndBoundedByTheFrontier() {
        val checkpoint = MemoryCheckpoint()
        val sink = MemorySink()
        val session = openOn(sink, checkpoint = checkpoint).session()
        session.writeAt(0L, ByteArray(10))

        val ahead = session.acknowledge(11L)
        assertTrue("cannot acknowledge bytes that were never written", ahead != null)
        val backwards = session.acknowledge(-1L)
        assertTrue(backwards != null)
        assertNull(session.acknowledge(10L))
        assertEquals(10L, session.confirmedFrontier)
        assertEquals(10L, checkpoint.frontier)
    }

    @Test(timeout = 30_000L)
    fun aCheckpointFailureLeavesTheConfirmedFrontierWhereItWas() {
        val checkpoint = MemoryCheckpoint(failing = true)
        val session = openOn(MemorySink(), checkpoint = checkpoint).session()
        session.writeAt(0L, ByteArray(10))
        val failure = session.acknowledge(10L)
        assertTrue("a failed persist must be reported", failure != null)
        assertEquals("nothing is confirmed if the checkpoint did not take it", 0L, session.confirmedFrontier)
    }

    // --- verification, commit, abort ---------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun theCorrectDigestVerifiesAndCommitsOnce() {
        val body = ByteArray(4_096) { (it * 3).toByte() }
        val sink = MemorySink()
        val digest = digestOf(body)
        val publisher = CountingPublisher()
        val session = openOn(sink, declared = 4_096L, publisher = publisher).session()
        session.writeAt(0L, body)

        val verification = session.verify(digest)
        assertTrue("expected a match: $verification", verification is SessionVerification.Verified)
        assertEquals(digest, (verification as SessionVerification.Verified).digest)
        assertEquals(DestinationSessionState.VERIFIED, session.currentState)

        // A write after verification would invalidate the digest, so it is refused by name.
        val afterVerify = session.writeAt(4_096L, ByteArray(1))
        assertEquals(
            SequentialRejection.AFTER_VERIFICATION,
            (afterVerify as DestinationWrite.Rejected).reason,
        )

        assertEquals(CommitResult.Committed, session.commit())
        assertEquals(1, publisher.publishCount)
        assertEquals(DestinationSessionState.COMMITTED, session.currentState)
        assertEquals("a duplicate commit is refused explicitly", CommitResult.Duplicate, session.commit())
        assertEquals("and does not republish", 1, publisher.publishCount)
        assertEquals(
            SequentialRejection.AFTER_COMMIT,
            (session.writeAt(4_096L, ByteArray(1)) as DestinationWrite.Rejected).reason,
        )
    }

    @Test(timeout = 30_000L)
    fun aWrongDigestDoesNotVerifyAndCannotCommit() {
        val body = ByteArray(64) { it.toByte() }
        val sink = MemorySink()
        val wrong = digestOf(ByteArray(64) { (it + 1).toByte() })
        val publisher = CountingPublisher()
        val session = openOn(sink, declared = 64L, publisher = publisher).session()
        session.writeAt(0L, body)

        val verification = session.verify(wrong)
        assertTrue("expected a mismatch: $verification", verification is SessionVerification.Mismatched)
        assertEquals(DestinationSessionState.OPEN, session.currentState)
        val commit = session.commit()
        assertTrue(commit is CommitResult.Refused)
        assertEquals(0, publisher.publishCount)
        // A mismatch leaves the session writable: nothing was verified, so the caller may truncate.
        assertTrue(session.writeAt(64L, ByteArray(0)) is DestinationWrite.Rejected)
    }

    @Test(timeout = 30_000L)
    fun verificationWithoutAnExpectedDigestIsRecordedAsSuchForAFreshSession() {
        val sink = MemorySink()
        val publisher = CountingPublisher()
        val session = openOn(sink, declared = 16L, publisher = publisher).session()
        session.writeAt(0L, ByteArray(16) { 9 })
        val verification = session.verify(null)
        assertTrue(verification is SessionVerification.Verified)
        assertFalse("must not claim a match that was never checked", (verification as SessionVerification.Verified).matchedExpected)
        assertEquals(CommitResult.Committed, session.commit())
        assertEquals(1, publisher.publishCount)
    }

    @Test(timeout = 30_000L)
    fun aResumedSessionRejectsNullOrReplacementDigestBeforeCommit() {
        val original = ByteArray(16) { (it + 1).toByte() }
        val retained = digestOf(original)
        val replacement = digestOf(ByteArray(16) { 99 })
        val sink = MemorySink(initial = original)
        val publisher = CountingPublisher()
        val session = openOn(
            sink, declared = original.size.toLong(), confirmed = original.size.toLong(),
            publisher = publisher, retainedExpectedSha256 = retained,
        ).session()

        assertEquals(
            "resume_digest_required",
            ((session.verify(null) as SessionVerification.Refused).error as TransferStorageError.StateConflict).reason,
        )
        assertTrue(session.commit() is CommitResult.Refused)
        assertEquals(
            "resume_digest_changed",
            ((session.verify(replacement) as SessionVerification.Refused).error as TransferStorageError.StateConflict).reason,
        )
        assertTrue(session.commit() is CommitResult.Refused)
        assertEquals(DestinationSessionState.OPEN, session.currentState)
        assertEquals(0, publisher.publishCount)

        // A separate, value-equal digest is accepted; the session compares with its retained copy.
        val verified = session.verify(Sha256Digest(retained.bytes))
        assertTrue(verified is SessionVerification.Verified)
        assertTrue((verified as SessionVerification.Verified).matchedExpected)
        assertEquals(CommitResult.Committed, session.commit())
        assertEquals(1, publisher.publishCount)
    }

    @Test(timeout = 30_000L)
    fun aResumedDigestMismatchCannotBeBypassedWithTheObservedDigestOrNull() {
        val original = ByteArray(16) { it.toByte() }
        val changed = original.copyOf().apply { this[12] = (this[12].toInt() xor 1).toByte() }
        val retained = digestOf(original)
        val sink = MemorySink(initial = changed)
        val publisher = CountingPublisher()
        val session = openOn(
            sink, declared = changed.size.toLong(), confirmed = changed.size.toLong(),
            publisher = publisher, retainedExpectedSha256 = retained,
        ).session()

        assertTrue(session.verify(retained) is SessionVerification.Mismatched)
        assertTrue(session.commit() is CommitResult.Refused)
        assertTrue(session.verify(digestOf(changed)) is SessionVerification.Refused)
        assertTrue(session.verify(null) is SessionVerification.Refused)
        assertTrue(session.commit() is CommitResult.Refused)
        assertEquals(DestinationSessionState.OPEN, session.currentState)
        assertEquals(0, publisher.publishCount)
    }

    @Test(timeout = 30_000L)
    fun aVerificationReadFailureAfterProgressCannotAuthorizeAResumedCommit() {
        val body = byteArrayOf(1, 2, 3, 4)
        val retained = digestOf(body)
        val sink = MemorySink(initial = body)
        val publisher = CountingPublisher()
        val secret = "content://private/secret/photo.jpg"
        var reads = 0
        val verificationSource = VerificationSource { _, buffer, dataOffset, _ ->
            reads++
            if (reads == 1) {
                buffer[dataOffset] = body[0]
                1
            } else {
                throw IOException(secret)
            }
        }
        val opened = DestinationSession.open(
            transferId = transferId,
            identity = identity,
            sink = sink,
            verificationSource = verificationSource,
            publisher = publisher,
            initialConfirmedBytes = body.size.toLong(),
            retainedExpectedSha256 = retained,
        ) as DestinationOpen.Opened
        val failure = opened.session.verify(retained, buffer = ByteArray(2)) as SessionVerification.Failed
        assertEquals(TransferStorageErrorCategory.IO, failure.error.category)
        assertNull(failure.error.diagnostic)
        assertFalse(failure.toString().contains(secret))
        assertTrue(opened.session.commit() is CommitResult.Refused)
        assertEquals(0, publisher.publishCount)
        assertEquals(2, reads)
    }

    @Test(timeout = 30_000L)
    fun aResumeAuthorizationCannotVerifyASessionBoundToADifferentDigest() {
        val body = ByteArray(16) { it.toByte() }
        val original = digestOf(body)
        val different = digestOf(body + 1.toByte())
        val metadata = SourceFingerprint.UNKNOWN
        val ready = SourceResumePolicy.authorize(metadata, SourceFingerprintResult.Available(metadata), original)
            as SourceResumeDecision.Ready
        val sink = MemorySink(initial = body)
        val session = openOn(
            sink, confirmed = body.size.toLong(), retainedExpectedSha256 = different,
        ).session()
        assertEquals(
            "resume_digest_changed",
            ((ready.verify(session) as SessionVerification.Refused).error as TransferStorageError.StateConflict).reason,
        )
        assertTrue(session.commit() is CommitResult.Refused)
    }

    @Test(timeout = 30_000L)
    fun commitBeforeVerificationIsRefused() {
        val publisher = CountingPublisher()
        val session = openOn(MemorySink(), publisher = publisher).session()
        val commit = session.commit()
        assertTrue(commit is CommitResult.Refused)
        assertEquals("not_verified", ((commit as CommitResult.Refused).error as TransferStorageError.StateConflict).reason)
        assertEquals(0, publisher.publishCount)
    }

    @Test(timeout = 30_000L)
    fun aPublishFailureLeavesTheSessionUncommitted() {
        val publisher = CountingPublisher(failure = TransferStorageError.StorageFull("publish"))
        val body = ByteArray(32) { 1 }
        val session = openOn(MemorySink(), declared = 32L, publisher = publisher).session()
        session.writeAt(0L, body)
        session.verify(digestOf(body))
        val commit = session.commit()
        assertTrue(commit is CommitResult.Refused)
        assertEquals(
            TransferStorageErrorCategory.INSUFFICIENT_SPACE,
            (commit as CommitResult.Refused).error.category,
        )
        assertEquals(DestinationSessionState.VERIFIED, session.currentState)
    }

    @Test(timeout = 30_000L)
    fun abortIsIdempotentAndRefusedAfterCommit() {
        val sink = MemorySink()
        val aborts = intArrayOf(0)
        val session = openOn(
            sink,
            declared = 8L,
            publisher = CountingPublisher(),
            abortAction = { aborts[0]++; null },
        ).session()
        session.writeAt(0L, ByteArray(8) { 4 })
        assertEquals(AbortResult.Aborted, session.abort())
        assertEquals(AbortResult.AlreadyAborted, session.abort())
        assertEquals("cleanup runs once", 1, aborts[0])
        assertEquals(
            SequentialRejection.AFTER_ABORT,
            (session.writeAt(0L, ByteArray(1)) as DestinationWrite.Rejected).reason,
        )
        session.close()

        val committed = openOn(MemorySink(), declared = 8L, publisher = CountingPublisher())
            .session()
        val body = ByteArray(8) { 2 }
        committed.writeAt(0L, body)
        committed.verify(digestOf(body))
        committed.commit()
        val refused = committed.abort()
        assertTrue("a committed partial must not be aborted", refused is AbortResult.Refused)
        assertEquals(
            "already_committed",
            ((refused as AbortResult.Refused).error as TransferStorageError.StateConflict).reason,
        )
    }

    @Test(timeout = 30_000L)
    fun abortAfterAFailedWriteStillSucceeds() {
        val sink = MemorySink(failure = TransferStorageError.Io("write"))
        val aborts = intArrayOf(0)
        val session = openOn(sink, abortAction = { aborts[0]++; null }).session()
        assertTrue(session.writeAt(0L, ByteArray(1)) is DestinationWrite.Failed)
        assertEquals(AbortResult.Aborted, session.abort())
        assertEquals(1, aborts[0])
        assertEquals(DestinationSessionState.ABORTED, session.currentState)
    }

    // --- cancellation and lifecycle ----------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun cancellationDuringVerificationIsTypedAndLeavesTheSessionOpen() {
        val sink = MemorySink(initial = ByteArray(70_000))
        val session = openOn(sink, declared = 70_000L).session()
        val verification = session.verify(null, isCancelled = { true })
        assertTrue("a cancelled pass must not report success", verification is SessionVerification.Failed)
        assertEquals(TransferStorageError.Cancelled, (verification as SessionVerification.Failed).error)
        assertEquals(DestinationSessionState.OPEN, session.currentState)
    }

    @Test(timeout = 30_000L)
    fun closeIsIdempotentAndClosesEveryResourceExactlyOnce() {
        val sinkCalls = intArrayOf(0)
        val resource = CountingCloseable(onClose = { sinkCalls[0]++ })
        val second = CountingCloseable(onClose = { sinkCalls[0]++ })
        val session = openOn(MemorySink(), resources = listOf(resource, second)).session()
        session.close()
        session.close()
        session.close()
        assertEquals("each resource closes once", 2, sinkCalls[0])
        assertEquals(1, session.closeCount)
        assertEquals(DestinationSessionState.CLOSED, session.currentState)
        assertEquals(
            SequentialRejection.AFTER_CLOSE,
            (session.writeAt(0L, ByteArray(1)) as DestinationWrite.Rejected).reason,
        )
    }

    // --- redaction and retention -------------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun errorsAndToStringCarryNoIdentity() {
        val session = openOn(MemorySink()).session()
        val text = session.toString()
        assertTrue("must be marked redacted: $text", text.contains("[redacted]"))
        assertFalse(text.contains(transferId.value))
        assertFalse(text.contains(identity.value))
        assertFalse(text.contains("transfer-1"))

        val conflict = session.truncateTo(-5L)
        assertTrue(conflict is TruncateOutcome.Failed)
        val message = (conflict as TruncateOutcome.Failed).error.toString()
        assertFalse("a failure must not print the transfer id", message.contains(transferId.value))

        val insufficient = TransferStorageError.InsufficientSpace(requiredBytes = 10, availableBytes = 1)
        assertFalse(insufficient.toString().contains("transfer"))
    }

    @Test(timeout = 30_000L)
    fun noCallerBufferIsRetainedAfterAWrite() {
        val sink = MemorySink()
        val session = openOn(sink).session()
        val chunk = ByteArray(4_096) { 0x42 }
        session.writeAt(0L, chunk)
        // The sink copies; the session must hold no array reference at all.
        for (field in session.javaClass.declaredFields) {
            if (Modifier.isStatic(field.modifiers)) continue
            field.isAccessible = true
            val value = field.get(session)
            assertFalse(
                "field ${field.name} retains the caller's buffer",
                value is ByteArray && (value as ByteArray) === chunk,
            )
        }
        // And the sink stored its own copy: mutating the caller's array afterwards changes nothing.
        chunk.fill(0)
        assertFalse("the stored bytes must be a copy", sink.bytes().all { it == 0.toByte() })
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun openOn(
        sink: MemorySink,
        declared: Long? = null,
        confirmed: Long = 0L,
        checkpoint: MemoryCheckpoint? = null,
        publisher: CountingPublisher? = null,
        abortAction: (() -> TransferStorageError?)? = null,
        resources: List<Closeable> = emptyList(),
        retainedExpectedSha256: Sha256Digest? = if (confirmed > 0L) digestOf(sink.bytes()) else null,
    ): DestinationOpen = LanDestinationSession.open(
        transferId = transferId,
        identity = identity,
        sink = sink,
        declaredSizeBytes = declared,
        checkpointSink = checkpoint,
        verificationSource = sink.verificationSource(),
        publisher = publisher,
        abortAction = abortAction,
        resources = resources,
        initialConfirmedBytes = confirmed,
        retainedExpectedSha256 = retainedExpectedSha256,
    ) {
        // The memory sink reads back through the same object; a real adapter passes a handle.
        sink.verificationSource()
    }

    private fun DestinationOpen.opened(): DestinationOpen.Opened {
        assertTrue("expected an open session, got $this", this is DestinationOpen.Opened)
        return this as DestinationOpen.Opened
    }

    private fun DestinationOpen.session(): DestinationSession = opened().session

    private fun digestOf(bytes: ByteArray): Sha256Digest {
        val digester = Sha256Digester()
        digester.update(bytes, 0, bytes.size)
        return digester.digest()
    }

    // --- fakes -------------------------------------------------------------------------------------

    /** An in-memory partial that records every call and can be told to misbehave. */
    private class MemorySink(
        initial: ByteArray = ByteArray(0),
        /** Bytes accepted by the next write only; the fault is one-shot. */
        var shortWriteBytes: Int? = null,
        private val failure: TransferStorageError? = null,
        private val flushFailure: Boolean = false,
    ) : PartialSink {

        private val data = ByteArrayOutputStream().apply { write(initial) }
        var writeCalls: Int = 0
            private set
        var truncateCalls: Int = 0
            private set

        fun bytes(): ByteArray = data.toByteArray()

        override fun length(): Long = data.size().toLong()

        override fun writeAt(offset: Long, buffer: ByteArray, dataOffset: Int, length: Int): WriteOutcome {
            writeCalls++
            failure?.let { return WriteOutcome.Failed(it) }
            val copy = buffer.copyOfRange(dataOffset, dataOffset + length)
            if (offset != data.size().toLong()) {
                // A real sink at a wrong offset would corrupt; the session should never get here.
                return WriteOutcome.Failed(TransferStorageError.Io("write"))
            }
            // One-shot: the fault is cleared as it is spent, so a repaired retry can succeed.
            val accepted = shortWriteBytes ?: length
            shortWriteBytes = null
            data.write(copy, 0, accepted)
            return WriteOutcome.Written(accepted, data.size().toLong())
        }

        override fun flush(): FlushDurability = if (flushFailure) {
            FlushDurability.FlushFailed(TransferStorageError.Io("flush"))
        } else {
            FlushDurability.DurableFlushSupported
        }

        override fun truncateTo(offset: Long): TruncateOutcome {
            truncateCalls++
            val current = bytes()
            val cut = ByteArray(offset.toInt())
            System.arraycopy(current, 0, cut, 0, minOf(current.size, offset.toInt()))
            data.reset()
            data.write(cut)
            return TruncateOutcome.Truncated(offset)
        }

        fun verificationSource(): VerificationSource = VerificationSource { offset, buffer, dataOffset, length ->
            val current = bytes()
            if (offset >= current.size) {
                -1
            } else {
                val count = minOf(length.toLong(), current.size - offset).toInt()
                System.arraycopy(current, offset.toInt(), buffer, dataOffset, count)
                count
            }
        }
    }

    /** Writes a prefix of the second chunk, then throws a controlled ENOSPC for the session to classify. */
    private class MidStreamFailingSink(private val secret: String) : PartialSink {
        private val delegate = MemorySink()
        var writeCalls: Int = 0
            private set

        override fun length(): Long = delegate.length()

        override fun writeAt(offset: Long, buffer: ByteArray, dataOffset: Int, length: Int): WriteOutcome {
            writeCalls++
            if (writeCalls == 2) {
                check(delegate.writeAt(offset, buffer, dataOffset, minOf(2, length)) is WriteOutcome.Written)
                throw IOException("ENOSPC $secret")
            }
            return delegate.writeAt(offset, buffer, dataOffset, length)
        }

        override fun flush(): FlushDurability = delegate.flush()
        override fun truncateTo(offset: Long): TruncateOutcome = delegate.truncateTo(offset)
        fun verificationSource(): VerificationSource = delegate.verificationSource()
    }

    private class MemoryCheckpoint(private val failing: Boolean = false) : CheckpointSink {
        var frontier: Long = 0L
            private set

        override fun confirmedFrontier(transferId: TransferId): Long = frontier

        override fun persist(transferId: TransferId, offset: Long, durability: FlushDurability): PersistOutcome {
            if (failing) return PersistOutcome.Failed(TransferStorageError.Io("persist"))
            frontier = offset
            return PersistOutcome.Persisted
        }
    }

    private class CountingPublisher(private val failure: TransferStorageError? = null) : DestinationPublisher {
        var publishCount: Int = 0
            private set

        override fun publish(verifiedDigest: Sha256Digest): PublicationOutcome {
            publishCount++
            failure?.let { return PublicationOutcome.Failed(it) }
            return PublicationOutcome.Published
        }
    }

    private class CountingCloseable(private val onClose: () -> Unit) : Closeable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            onClose()
        }
    }
}

/**
 * The production entry point the tests use.
 *
 * A named object rather than a direct `DestinationSession.open` call so a test that wants the real
 * factory path is not silently calling the constructor seam.
 */
internal object LanDestinationSession {
    fun open(
        transferId: TransferId,
        identity: PartialIdentity,
        sink: PartialSink,
        declaredSizeBytes: Long? = null,
        checkpointSink: CheckpointSink? = null,
        verificationSource: VerificationSource? = null,
        publisher: DestinationPublisher? = null,
        abortAction: (() -> TransferStorageError?)? = null,
        resources: List<Closeable> = emptyList(),
        initialConfirmedBytes: Long = 0L,
        retainedExpectedSha256: Sha256Digest? = null,
        unusedSource: (() -> VerificationSource?)? = null,
    ): DestinationOpen {
        unusedSource?.invoke()
        return DestinationSession.open(
            transferId = transferId,
            identity = identity,
            sink = sink,
            declaredSizeBytes = declaredSizeBytes,
            checkpointSink = checkpointSink,
            verificationSource = verificationSource,
            publisher = publisher,
            abortAction = abortAction,
            resources = resources,
            initialConfirmedBytes = initialConfirmedBytes,
            retainedExpectedSha256 = retainedExpectedSha256,
        )
    }
}
