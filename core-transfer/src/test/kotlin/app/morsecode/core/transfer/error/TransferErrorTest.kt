package app.morsecode.core.transfer.error

import app.morsecode.core.transfer.ProtocolLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The error model: every failure says what it is, whether it can be retried, who
 * caused it and which subsystem owns the fix — and none of them can leak a
 * private path, a secret, file contents or a stack trace.
 */
class TransferErrorTest {

    @Test fun `every error answers all six classification questions`() {
        val samples: List<TransferError> = listOf(
            TransferError.MalformedFrame("bad magic"),
            TransferError.ProtocolVersionMismatch(1, 9),
            TransferError.FrameTooLarge(1_000L, 10),
            TransferError.PayloadTooLarge(1_000L, 10),
            TransferError.InvalidFieldLength("path", 10L, 5),
            TransferError.InvalidIdentifier("sessionId"),
            TransferError.InvalidPath("traversal"),
            TransferError.UnexpectedFrameType("DATA_CHUNK", "PAUSE"),
            TransferError.UnexpectedSequence(1L, 2L),
            TransferError.UnexpectedOffset(1L, 2L),
            TransferError.UnsupportedChunkSize(7L, 1_024, 262_144),
            TransferError.DescriptorMismatch("relativePath"),
            TransferError.ChunkChecksumMismatch(0L, 1L, 2L),
            TransferError.FileChecksumMismatch("a".repeat(64), "b".repeat(64)),
            TransferError.SourceUnavailable("gone"),
            TransferError.DestinationUnavailable("gone"),
            TransferError.StorageFull(1_000L),
            TransferError.PermissionRevoked("revoked"),
            TransferError.TransportDisconnected("closed"),
            TransferError.PeerRejected("no"),
            TransferError.CancelledLocally,
            TransferError.CancelledRemotely,
            TransferError.RetryLimitReached(5),
            TransferError.SnapshotVersionUnsupported(9, 1),
            TransferError.PersistedSnapshotInvalid("malformed_row"),
            TransferError.PersistenceConflict("stale_snapshot_revision"),
            TransferError.PersistenceFailure("write", storageFull = false),
            TransferError.PersistenceFailure("write", storageFull = true),
            TransferError.UnexpectedInternal("boom"),
        )
        for (error in samples) {
            assertTrue("${error.code} needs a code", error.code.isNotBlank())
            assertTrue("${error.code} must be final or retryable", error.isFinal != error.retryable)
            assertTrue("${error.code} needs an origin", error.origin in ErrorOrigin.entries)
            assertTrue("${error.code} needs a category", error.category in ErrorCategory.entries)
            assertTrue(
                "${error.code} detail must be bounded",
                error.detail.toByteArray(Charsets.UTF_8).size <=
                    ProtocolLimits.MAX_ERROR_DETAIL_BYTES,
            )
            assertTrue("${error.code} must describe itself", error.describe().startsWith(error.code))
        }
    }

    @Test fun `retryable errors are not final and vice versa`() {
        assertTrue(TransferError.TransportDisconnected("x").retryable)
        assertFalse(TransferError.TransportDisconnected("x").isFinal)
        assertFalse(TransferError.MalformedFrame("x").retryable)
        assertTrue(TransferError.MalformedFrame("x").isFinal)
    }

    @Test fun `classification flags agree with the category`() {
        assertTrue(TransferError.MalformedFrame("x").isProtocolRelated)
        assertTrue(TransferError.PermissionRevoked("x").isStorageRelated)
        assertTrue(TransferError.DestinationUnavailable("x").isStorageRelated)
        assertTrue(TransferError.TransportDisconnected("x").isTransportRelated)
        assertFalse(TransferError.TransportDisconnected("x").isProtocolRelated)
    }

    @Test fun `checksum failures are integrity failures and are retryable`() {
        val chunk = TransferError.ChunkChecksumMismatch(4_096L, 1L, 2L)
        assertEquals(ErrorCategory.INTEGRITY, chunk.category)
        assertTrue(chunk.retryable)
        val file = TransferError.FileChecksumMismatch("a".repeat(64), "b".repeat(64))
        assertEquals(ErrorCategory.INTEGRITY, file.category)
        assertTrue(file.retryable)
    }

    @Test fun `full file checksum failures never render digest bytes`() {
        val expected = "a".repeat(64)
        val observed = "b".repeat(64)
        val error = TransferError.FileChecksumMismatch(expected, observed)
        assertFalse(error.detail.contains(expected))
        assertFalse(error.detail.contains(observed))
        assertFalse(error.describe().contains(expected))
        assertFalse(error.toString().contains(observed))
    }

    @Test fun `local and remote cancellations are distinguishable`() {
        assertEquals(ErrorOrigin.LOCAL, TransferError.CancelledLocally.origin)
        assertEquals(ErrorOrigin.REMOTE, TransferError.CancelledRemotely.origin)
        assertEquals(ErrorCategory.LOCAL_ACTION, TransferError.CancelledLocally.category)
        assertEquals(ErrorCategory.REMOTE, TransferError.CancelledRemotely.category)
        assertFalse(TransferError.CancelledLocally.retryable)
        assertFalse(TransferError.CancelledRemotely.retryable)
    }

    // --- redaction -----------------------------------------------------------

    @Test fun `absolute unix paths are removed from details`() {
        val redacted = ErrorDetailRedactor.redact("cannot open /storage/emulated/0/DCIM/photo.jpg")
        assertFalse(redacted.contains("/storage"))
        assertFalse(redacted.contains("DCIM"))
        assertTrue(redacted.contains("[redacted]"))
    }

    @Test fun `absolute paths are removed even when they are not the first token`() {
        val redacted = ErrorDetailRedactor.redact("write failed at /data/user/0/app/files/part")
        assertFalse(redacted.contains("/data"))
        assertTrue(redacted.contains("[redacted]"))
    }

    @Test fun `windows drive paths are removed`() {
        val redacted = ErrorDetailRedactor.redact("cannot open C:\\Users\\alice\\secret.txt")
        assertFalse(redacted.contains("alice"))
        assertTrue(redacted.contains("[redacted]"))
    }

    @Test fun `control characters are replaced not printed`() {
        val redacted = ErrorDetailRedactor.redact("bad\u0000\u0007byte")
        for (char in redacted) {
            assertTrue("control character survived redaction", char.code >= 32)
        }
    }

    @Test fun `sha256 digests are redacted from error details`() {
        val digest = "a".repeat(64)
        val redacted = ErrorDetailRedactor.redact("digest=$digest")
        assertFalse(redacted.contains(digest))
        assertTrue(redacted.contains("digest=[redacted]"))
    }

    @Test fun `persistence diagnostics retain only safe reason tokens`() {
        val raw = "content://provider/private-document sha256=${"a".repeat(64)}"
        val invalid = TransferError.PersistedSnapshotInvalid(raw)
        val conflict = TransferError.PersistenceConflict(raw)
        val failure = TransferError.PersistenceFailure(raw, storageFull = false)

        assertEquals("malformed_row", invalid.reason)
        assertEquals("revision_conflict", conflict.reason)
        assertEquals("saved", failure.operation)
        assertFalse(invalid.toString().contains("provider"))
        assertFalse(conflict.toString().contains("private-document"))
        assertFalse(failure.toString().contains("sha256"))
        assertFalse(invalid.detail.contains("provider"))
    }

    @Test fun `other long opaque blobs are redacted`() {
        val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc123def456ghi789"
        val redacted = ErrorDetailRedactor.redact("authorization: $token")
        assertFalse(redacted.contains("eyJhbGciOi"))
        assertTrue(redacted.contains("[redacted]"))
    }

    @Test fun `details are bounded by utf8 bytes and never split a character`() {
        val long = "é".repeat(500) // 1,000 bytes as 500 two-byte characters
        val redacted = ErrorDetailRedactor.redact(long, 100)
        assertTrue(
            redacted.toByteArray(Charsets.UTF_8).size <= 100,
        )
        assertTrue(redacted.isNotEmpty())
        assertTrue(redacted.all { it == 'é' })
    }

    @Test fun `a stack trace is reduced to a bounded redacted message`() {
        val trace = "java.lang.IllegalStateException: boom\n\tat app.morsecode.core.transfer.X.y(X.kt:1)"
        val redacted = ErrorDetailRedactor.redact(trace)
        assertFalse(redacted.contains("at app.morsecode"))
        assertTrue(redacted.toByteArray(Charsets.UTF_8).size <= ProtocolLimits.MAX_ERROR_DETAIL_BYTES)
    }

    @Test fun `errors built from hostile detail are already redacted`() {
        val error = TransferError.SourceUnavailable("open failed for /sdcard/private/secret.bin")
        assertFalse(error.detail.contains("/sdcard"))
        assertFalse(error.describe().contains("secret.bin"))
    }

    @Test fun `restored errors round trip their classification`() {
        val original = TransferError.StorageFull(4_096L)
        val restored = TransferError.restore(
            code = original.code,
            detail = original.detail,
            retryable = original.retryable,
            origin = original.origin,
            category = original.category,
        )
        assertEquals(original.code, restored.code)
        assertEquals(original.detail, restored.detail)
        assertEquals(original.retryable, restored.retryable)
        assertEquals(original.origin, restored.origin)
        assertEquals(original.category, restored.category)
    }

    @Test fun `restoring re-redacts the detail`() {
        val restored = TransferError.restore(
            code = "source_unavailable",
            detail = "open failed for /sdcard/private/secret.bin",
            retryable = true,
            origin = ErrorOrigin.LOCAL,
            category = ErrorCategory.STORAGE,
        )
        assertFalse(restored.detail.contains("/sdcard"))
    }

    @Test fun `every origin and category parses back from its id`() {
        for (origin in ErrorOrigin.entries) {
            assertEquals(origin, ErrorOrigin.fromId(origin.id))
        }
        for (category in ErrorCategory.entries) {
            assertEquals(category, ErrorCategory.fromId(category.id))
        }
        assertEquals(ErrorOrigin.UNKNOWN, ErrorOrigin.fromId("nonsense"))
        assertEquals(ErrorCategory.UNKNOWN, ErrorCategory.fromId("nonsense"))
    }
}
