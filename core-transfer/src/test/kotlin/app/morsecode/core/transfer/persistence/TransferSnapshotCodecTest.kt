package app.morsecode.core.transfer.persistence

import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.Tf
import app.morsecode.core.transfer.error.ErrorCategory
import app.morsecode.core.transfer.error.ErrorOrigin
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import app.morsecode.core.transfer.model.TransferSnapshot
import app.morsecode.core.transfer.plus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persistence contract for a snapshot: versioned, bounded, and *rejecting*.
 *
 * A row written by a future build describes state this build does not understand,
 * so an unknown key or a newer version is a typed error rather than a default.
 * And the one rule that must never be broken on restore: optimistic bytes are not
 * confirmed bytes. A process restart must not turn "we wrote this" into "they
 * received this".
 */
class TransferSnapshotCodecTest {

    // --- round trips --------------------------------------------------------------------

    @Test fun `a freshly queued delivery round trips`() {
        assertRoundTrips(Tf.queued())
    }

    @Test fun `a delivery mid flight round trips with its two byte counts apart`() {
        val sending = Tf.sending() +
            Tf.chunkSent(offset = 0L, length = Tf.chunk.value) +
            Tf.chunkSent(offset = Tf.chunk.value.toLong(), length = Tf.chunk.value)
        // Two chunks written, one acknowledged: the gap is exactly what a
        // restart must preserve rather than collapse.
        assertEquals(0L, sending.confirmedBytes)
        assertEquals(2L * Tf.chunk.value, sending.optimisticBytes)
        assertRoundTrips(sending)
    }

    @Test fun `optimistic bytes are never promoted to confirmed bytes on restore`() {
        val inFlight = Tf.sending() + Tf.chunkSent(offset = 0L, length = Tf.chunk.value)
        val restored = roundTrip(inFlight)
        assertEquals(0L, restored.confirmedBytes)
        assertEquals(Tf.chunk.value.toLong(), restored.optimisticBytes)
        assertEquals(inFlight.inFlightBytes, restored.inFlightBytes)
    }

    @Test fun `a delivery with a confirmed offset round trips`() {
        val confirmed = Tf.sending() +
            Tf.chunkSent(offset = 0L, length = Tf.chunk.value) +
            Tf.chunkAck(
                offset = 0L,
                length = Tf.chunk.value,
                confirmedOffset = Tf.chunk.value.toLong(),
            )
        assertEquals(Tf.chunk.value.toLong(), confirmed.confirmedBytes)
        assertRoundTrips(confirmed)
    }

    @Test fun `a failed delivery round trips with its classification intact`() {
        val failed = Tf.failedRetryable(retryCount = 2)
        val restored = roundTrip(failed)
        assertEquals(failed.state, restored.state)
        assertEquals(failed.retryCount, restored.retryCount)
        assertEquals(failed.failure?.code, restored.failure?.code)
        assertEquals(failed.failure?.retryable, restored.failure?.retryable)
        assertEquals(failed.failure?.origin, restored.failure?.origin)
        assertEquals(failed.failure?.category, restored.failure?.category)
        assertEquals(ErrorCategory.TRANSPORT, restored.failure?.category)
        assertEquals(failed.failure?.origin, restored.failure?.origin)
    }

    @Test fun `a finally failed delivery round trips`() {
        assertRoundTrips(Tf.failedFinally())
    }

    @Test fun `a completed delivery round trips`() {
        val completed = Tf.completed()
        val restored = roundTrip(completed)
        assertEquals(TransferState.COMPLETED, restored.state)
        assertEquals(completed.totalBytes, restored.confirmedBytes)
        assertTrue(restored.isTerminal)
    }

    @Test fun `a verifying delivery round trips with its verification info`() {
        val verifying = Tf.receiveEverything(Tf.receiving()) + Tf.allBytesConfirmed()
        val restored = roundTrip(verifying)
        assertEquals(TransferState.VERIFYING, restored.state)
        assertEquals(verifying.verification?.startedSnapshotVersion, restored.verification?.startedSnapshotVersion)
        assertEquals(verifying.verification?.expectedDigest, restored.verification?.expectedDigest)
    }

    @Test fun `every state round trips`() {
        val samples: Map<TransferState, () -> TransferSnapshot> = mapOf(
            TransferState.QUEUED to { Tf.queued() },
            TransferState.NEGOTIATING to { Tf.queued() + Tf.beginNegotiation() },
            TransferState.SENDING to { Tf.sending() },
            TransferState.RECEIVING to { Tf.receiving() },
            TransferState.PAUSED_LOCAL to { Tf.pausedLocally() },
            TransferState.PAUSED_REMOTE to { Tf.sending() + Tf.remotePaused() },
            TransferState.VERIFYING to { Tf.receiveEverything(Tf.receiving()) + Tf.allBytesConfirmed() },
            TransferState.COMPLETED to { Tf.completed() },
            TransferState.FAILED_RETRYABLE to { Tf.failedRetryable() },
            TransferState.FAILED_FINAL to { Tf.failedFinally() },
            TransferState.CANCELLED to { Tf.queued() + Tf.cancelLocally() },
            TransferState.SKIPPED to { Tf.queued() + Tf.skip() },
        )
        assertEquals(12, samples.size)
        for ((state, build) in samples) {
            val snapshot = build()
            assertEquals("fixture for $state", state, snapshot.state)
            assertRoundTrips(snapshot)
        }
    }

    @Test fun `a broadcast delivery round trips with its recipient`() {
        val broadcast = Tf.broadcastQueued(Tf.transferId, Tf.recipientA)
        val restored = roundTrip(broadcast)
        assertEquals(Tf.recipientA, restored.recipientId)
        assertEquals(SessionDirection.OUTBOUND, restored.direction)
    }

    @Test fun `a delivery with no recipient round trips as null, not as empty`() {
        val restored = roundTrip(Tf.queued(recipientId = null))
        assertNull(restored.recipientId)
    }

    @Test fun `a descriptor with every optional field empty round trips`() {
        val snapshot = Tf.queued(
            descriptor = Tf.descriptor(
                mimeType = "",
                lastModified = null,
                digest = null,
            ),
        )
        val restored = roundTrip(snapshot)
        assertEquals("", restored.descriptor.mimeType)
        assertNull(restored.descriptor.lastModifiedEpochMillis)
        assertNull(restored.descriptor.expectedSha256)
    }

    @Test fun `a folder archive round trips as a folder`() {
        val restored = roundTrip(Tf.queued(descriptor = Tf.descriptor(isFolderArchive = true)))
        assertTrue(restored.descriptor.isFolderArchive)
    }

    @Test fun `a large file size round trips without narrowing`() {
        val restored = roundTrip(Tf.queued(descriptor = Tf.descriptor(totalBytes = ProtocolLimits.MAX_FILE_SIZE_BYTES)))
        assertEquals(ProtocolLimits.MAX_FILE_SIZE_BYTES, restored.totalBytes)
    }

    // --- escaping ----------------------------------------------------------------------------

    @Test fun `an escaped newline in a stored value is unescaped before it is validated`() {
        // The model refuses control characters at construction, so what is left to
        // prove is that a corrupt row on disk cannot smuggle one past the decoder.
        val row = rowWith("displayName", "a\\nb")
        assertInvalid(row)
        assertTrue(error(row).detail.contains("displayName"))
    }

    @Test fun `an escaped backslash in a stored value is unescaped before it is validated`() {
        val row = rowWith("displayName", "back\\\\slash")
        assertInvalid(row)
        assertTrue(error(row).detail.contains("displayName"))
    }

    @Test fun `a raw newline cannot carry a forged line past the duplicate key check`() {
        // The interesting attack: a value containing a newline followed by a
        // perfectly valid `state=COMPLETED` line. Because a raw newline splits into
        // its own line, the forgery lands as a duplicate key and is refused
        // instead of silently promoting a queued delivery to completed.
        val row = rowWith("displayName", "a\nstate=COMPLETED")
        assertInvalid(row)
        assertTrue(error(row).detail.contains("duplicate snapshot key state"))
    }

    @Test fun `a raw newline producing an unusable line is refused as keyless`() {
        val row = rowWith("displayName", "a\nstate")
        assertInvalid(row)
        assertTrue(error(row).detail.contains("no key"))
    }

    @Test fun `a value holding the key separator stays inside its own field`() {
        val row = rowWith("displayName", "state=COMPLETED")
        when (val result = TransferSnapshotCodec.deserialize(row)) {
            is SnapshotDecodeResult.Success ->
                assertEquals("the state must be untouched", TransferState.QUEUED, result.snapshot.state)

            is SnapshotDecodeResult.Invalid ->
                assertTrue(result.error.detail.contains("displayName"))
        }
    }

    @Test fun `a serialised snapshot contains no nul and no bare control characters`() {
        val encoded = TransferSnapshotCodec.serialize(
            Tf.queued(descriptor = Tf.descriptor(name = "holiday (1).jpg", mimeType = "image/jpeg")),
        )
        for (char in encoded) {
            assertTrue(
                "control character ${char.code} in the serialised snapshot",
                char == '\n' || char.code >= 32,
            )
        }
    }

    @Test fun `a path with non ascii characters round trips`() {
        val path = RelativeTransferPath("photos/фото 2024/度假村.jpg")
        val restored = roundTrip(Tf.queued(descriptor = Tf.descriptor(path = path)))
        assertEquals(path, restored.descriptor.relativePath)
    }

    // --- rejection -----------------------------------------------------------------------------

    @Test fun `an unknown key is rejected rather than ignored`() {
        val encoded = TransferSnapshotCodec.serialize(Tf.queued()) + "futureKey=1\n"
        assertInvalid(encoded)
        assertEquals("snapshot_unknown_key", (error(encoded) as TransferError.PersistedSnapshotInvalid).reason)
    }

    @Test fun `a duplicated key is rejected rather than last-write-wins`() {
        val encoded = TransferSnapshotCodec.serialize(Tf.queued()).replace(
            "confirmedBytes=0",
            "confirmedBytes=0\nconfirmedBytes=4096",
        )
        assertInvalid(encoded)
        assertEquals("snapshot_duplicate_key", (error(encoded) as TransferError.PersistedSnapshotInvalid).reason)
    }

    @Test fun `a line with no key is rejected`() {
        val encoded = TransferSnapshotCodec.serialize(Tf.queued()) + "novalue\n"
        assertInvalid(encoded)
        assertEquals("snapshot_line_missing_key", (error(encoded) as TransferError.PersistedSnapshotInvalid).reason)
    }

    @Test fun `a line with no value is not a key-only line`() {
        val encoded = "v=1\nstate=\n"
        assertInvalid(encoded)
    }

    @Test fun `a missing required field is rejected`() {
        val encoded = TransferSnapshotCodec.serialize(Tf.queued())
            .lines()
            .filterNot { it.startsWith("totalBytes=") }
            .joinToString("\n") + "\n"
        assertInvalid(encoded)
        assertEquals("snapshot_fields_incomplete", (error(encoded) as TransferError.PersistedSnapshotInvalid).reason)
    }

    @Test fun `an empty string is rejected`() {
        assertInvalid("")
    }

    @Test fun `a snapshot from a future version is refused, not guessed at`() {
        val encoded = replaceValue("v", (TransferSnapshotCodec.VERSION + 1).toString())
        assertInvalid(encoded)
        val error = error(encoded)
        assertTrue(error is TransferError.SnapshotVersionUnsupported)
        assertEquals(TransferSnapshotCodec.VERSION + 1, (error as TransferError.SnapshotVersionUnsupported).found)
    }

    @Test fun `a snapshot from before the minimum version is refused too`() {
        val encoded = replaceValue("v", (ProtocolLimits.SNAPSHOT_VERSION_MIN - 1).toString())
        assertInvalid(encoded)
        assertTrue(error(encoded) is TransferError.SnapshotVersionUnsupported)
    }

    @Test fun `a version that is not a number is rejected`() {
        val encoded = replaceValue("v", "one")
        assertInvalid(encoded)
        assertEquals("snapshot_version_invalid", (error(encoded) as TransferError.PersistedSnapshotInvalid).reason)
    }

    @Test fun `a corrupt byte count is rejected`() {
        val encoded = replaceValue("totalBytes", "not-a-number")
        assertInvalid(encoded)
        assertEquals("snapshot_field_invalid", (error(encoded) as TransferError.PersistedSnapshotInvalid).reason)
    }

    @Test fun `a negative byte count is rejected by the snapshot itself`() {
        val encoded = replaceValue("confirmedBytes", "-1")
        assertInvalid(encoded)
    }

    @Test fun `a confirmed offset past the end of the file is rejected`() {
        val encoded = replaceValue("confirmedBytes", "999999")
        assertInvalid(encoded)
    }

    @Test fun `a digest that is not hex is rejected`() {
        val encoded = replaceValue("sha256", "not-hex")
        assertInvalid(encoded)
        assertEquals("snapshot_field_invalid", (error(encoded) as TransferError.PersistedSnapshotInvalid).reason)
    }

    @Test fun `an unknown transfer state is rejected`() {
        val encoded = replaceValue("state", "dancing")
        assertInvalid(encoded)
    }

    @Test fun `an identifier that fails validation is rejected`() {
        val encoded = replaceValue("transferId", "a/b")
        assertInvalid(encoded)
    }

    // --- determinism -----------------------------------------------------------------------------

    @Test fun `serialising the same snapshot twice produces the same string`() {
        val snapshot = Tf.sending() + Tf.chunkSent(offset = 0L)
        assertEquals(
            TransferSnapshotCodec.serialize(snapshot),
            TransferSnapshotCodec.serialize(snapshot),
        )
    }

    @Test fun `a round trip is stable under repetition`() {
        val snapshot = Tf.failedRetryable(retryCount = 1)
        val once = TransferSnapshotCodec.serialize(snapshot)
        var current: TransferSnapshot = snapshot
        repeat(5) {
            current = (TransferSnapshotCodec.deserialize(TransferSnapshotCodec.serialize(current))
                as SnapshotDecodeResult.Success).snapshot
        }
        assertEquals(snapshot.copy(failure = null), current.copy(failure = null))
        assertSameFailure(snapshot.failure, current.failure)
        assertEquals(once, TransferSnapshotCodec.serialize(current))
    }

    @Test fun `a restored snapshot is internally consistent`() {
        val snapshot = Tf.sending() +
            Tf.chunkSent(offset = 0L, length = Tf.chunk.value) +
            Tf.chunkAck(offset = 0L, length = Tf.chunk.value, confirmedOffset = Tf.chunk.value.toLong())
        val restored = roundTrip(snapshot)
        assertTrue(
            restored.violations().joinToString(),
            restored.isInternallyConsistent(),
        )
    }

    @Test fun `the version stamp is written into every snapshot`() {
        val encoded = TransferSnapshotCodec.serialize(Tf.queued())
        assertTrue(encoded.startsWith("v=${TransferSnapshotCodec.VERSION}\n"))
        assertEquals(ProtocolLimits.SNAPSHOT_VERSION, TransferSnapshotCodec.VERSION)
    }

    @Test fun `an empty digest is not the digest of empty content`() {
        // A missing digest is null; the digest of zero bytes is a real value. The
        // codec must not collapse the two.
        val withNull = roundTrip(Tf.queued(descriptor = Tf.descriptor(digest = null)))
        assertNull(withNull.descriptor.expectedSha256)
        val withEmpty = roundTrip(Tf.queued(descriptor = Tf.descriptor(digest = Sha256Accumulator.EMPTY)))
        assertEquals(Sha256Accumulator.EMPTY, withEmpty.descriptor.expectedSha256)
        assertNotEquals(withNull.descriptor.expectedSha256, withEmpty.descriptor.expectedSha256)
    }

    // --- helpers -------------------------------------------------------------------------------------

    /**
     * A snapshot round trips to an equal snapshot, with one deliberate exception:
     * a failure is restored from its code and classification rather than as its
     * original subtype, so a newer build can read an older build's rows. The
     * failure is therefore compared field by field.
     */
    private fun assertRoundTrips(snapshot: TransferSnapshot) {
        val restored = roundTrip(snapshot)
        assertEquals(snapshot.copy(failure = null), restored.copy(failure = null))
        assertSameFailure(snapshot.failure, restored.failure)
    }

    private fun assertSameFailure(expected: TransferError?, actual: TransferError?) {
        if (expected == null) {
            assertNull(actual)
            return
        }
        assertTrue("expected a failure to be restored", actual != null)
        actual!!
        assertEquals("code", expected.code, actual.code)
        assertEquals("retryable", expected.retryable, actual.retryable)
        assertEquals("origin", expected.origin, actual.origin)
        assertEquals("category", expected.category, actual.category)
        assertEquals("detail", expected.detail, actual.detail)
    }

    private fun roundTrip(snapshot: TransferSnapshot): TransferSnapshot {
        val encoded = TransferSnapshotCodec.serialize(snapshot)
        val result = TransferSnapshotCodec.deserialize(encoded)
        assertTrue("expected the snapshot to decode: $result", result is SnapshotDecodeResult.Success)
        return (result as SnapshotDecodeResult.Success).snapshot
    }

    private fun assertInvalid(encoded: String) {
        val result = TransferSnapshotCodec.deserialize(encoded)
        assertTrue("expected the snapshot to be refused: $result", result is SnapshotDecodeResult.Invalid)
        assertFalse((result as SnapshotDecodeResult.Invalid).error.detail.isBlank())
    }

    /** Replaces one field in an otherwise valid row, so a test can store a value
     * the model itself would never produce. */
    private fun rowWith(key: String, rawValue: String): String =
        TransferSnapshotCodec.serialize(Tf.queued())
            .lines()
            .map { line -> if (line.startsWith("$key=")) "$key=$rawValue" else line }
            .joinToString("\n") + "\n"

    private fun replaceValue(key: String, rawValue: String): String =
        rowWith(key, rawValue)

    private fun error(encoded: String): TransferError =
        (TransferSnapshotCodec.deserialize(encoded) as SnapshotDecodeResult.Invalid).error
}
