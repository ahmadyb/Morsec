package app.morsecode.core.storage.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Redaction, asserted mechanically rather than promised.
 *
 * An Android storage provider is entitled to put a real absolute path, a
 * directory name or a personal file name into the text of the exception it
 * throws. That text is useful to whoever is debugging on the device and must not
 * travel anywhere else: not into a log that leaves the device, not into a crash
 * report, not into a Room row, and not onto a screen.
 *
 * So every error is built here with a recognisable secret in its `diagnostic`,
 * and every assertion is that the secret appears in no `safeMessage()` and in no
 * `toString()`. The test is exhaustive over the subclasses rather than
 * representative, because the failure mode is a new error type added later that
 * forgets the rule.
 */
class TransferStorageErrorRedactionTest {

    private val secretPath = "/storage/emulated/0/DCIM/private-9f3a/video.mp4"
    private val secretProviderText = "java.io.FileNotFoundException: $secretPath: open failed: ENOENT"

    private val everyError: List<TransferStorageError> = listOf(
        TransferStorageError.PermissionRevoked("read", secretPath),
        TransferStorageError.PermissionRevoked("write", secretProviderText),
        TransferStorageError.NotFound("source", secretPath),
        TransferStorageError.NotFound("partial", secretProviderText),
        TransferStorageError.Io("read", secretPath),
        TransferStorageError.Io("write", secretProviderText),
        TransferStorageError.ProviderFailure("content_resolver", secretPath),
        TransferStorageError.ProviderFailure("document_provider", secretProviderText),
        TransferStorageError.InsufficientSpace(1L, 0L, secretPath),
        TransferStorageError.StateConflict("partial_longer_than_checkpoint", secretPath),
        TransferStorageError.Unsupported("seekable_stream", secretPath),
        TransferStorageError.Cancelled,
        TransferStorageError.IntegrityMismatch("digest", secretPath),
        TransferStorageError.ZeroProgress(64, 1_024L, secretPath),
    )

    @Test
    fun `no safe message contains the secret path it was built from`() {
        everyError.forEach { error ->
            val message = error.safeMessage()
            assertFalse(
                "safeMessage leaked a path: ${error.category.id} -> $message",
                message.contains(secretPath),
            )
            assertFalse(
                "safeMessage leaked a path fragment: ${error.category.id} -> $message",
                message.contains("private-9f3a"),
            )
            assertFalse(
                "safeMessage leaked a provider message: ${error.category.id} -> $message",
                message.contains("FileNotFoundException"),
            )
        }
    }

    @Test
    fun `no string form contains the secret path either`() {
        // toString() is what ends up in a log line when someone writes
        // `Log.w(TAG, "failed: $error")`, so it has to be safe too.
        everyError.forEach { error ->
            val rendered = error.toString()
            assertFalse(
                "toString leaked a path: $rendered",
                rendered.contains("private-9f3a") || rendered.contains(secretPath),
            )
        }
    }

    @Test
    fun `the diagnostic is kept for local debugging and is the only place the path lives`() {
        val error = TransferStorageError.Io("read", secretPath)
        assertEquals(secretPath, error.diagnostic)
        assertEquals("The file could not be read", error.safeMessage())
    }

    @Test
    fun `safe messages still say something useful`() {
        everyError.forEach { error ->
            assertTrue(
                "safeMessage must not be empty: ${error.category.id}",
                error.safeMessage().isNotBlank(),
            )
        }
    }

    @Test
    fun `space errors report both sizes because neither identifies the user`() {
        val error = TransferStorageError.InsufficientSpace(
            requiredBytes = 5_368_709_120L,
            availableBytes = 1_073_741_824L,
            diagnostic = secretPath,
        )
        assertTrue(error.safeMessage().contains("5368709120"))
        assertTrue(error.safeMessage().contains("1073741824"))
        assertEquals(4_294_967_296L, error.missingBytes)
        assertFalse(error.safeMessage().contains(secretPath))
    }

    // --- categorisation ---------------------------------------------------

    @Test
    fun `retryability follows the category, not the message`() {
        assertTrue(TransferStorageError.isRetryable(TransferStorageError.Io("read")))
        assertTrue(TransferStorageError.isRetryable(TransferStorageError.ProviderFailure("media_store")))
        assertFalse(TransferStorageError.isRetryable(TransferStorageError.Cancelled))
        assertFalse(TransferStorageError.isRetryable(TransferStorageError.PermissionRevoked("read")))
        assertFalse(TransferStorageError.isRetryable(TransferStorageError.NotFound("partial")))
        assertFalse(TransferStorageError.isRetryable(TransferStorageError.IntegrityMismatch("digest")))
        assertFalse(TransferStorageError.isRetryable(TransferStorageError.Unsupported("seekable_stream")))
        assertFalse(TransferStorageError.isRetryable(TransferStorageError.StateConflict("stale")))
        // Out of space is never fixed by trying again immediately.
        assertFalse(TransferStorageError.isRetryable(TransferStorageError.InsufficientSpace(2L, 1L)))
    }

    @Test
    fun `categories have distinct ids and round-trip`() {
        val all = TransferStorageErrorCategory.entries
        assertEquals(9, all.size)
        assertEquals(all.size, all.map { it.id }.distinct().size)
        all.forEach { assertEquals(it, TransferStorageErrorCategory.fromId(it.id)) }
    }
}
