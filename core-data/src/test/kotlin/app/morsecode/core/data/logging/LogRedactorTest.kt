package app.morsecode.core.data.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Logs screen offers a Share action, so redaction is a security property,
 * not cosmetics. These cases are the ones the app actually logs.
 */
class LogRedactorTest {

    @Test
    fun `bearer tokens are removed from a header value`() {
        val out = LogRedactor.redact("Authorization: Bearer aB3xY9_kQ-2mNp7Rt5Vw8Zc1Df4Gh6J")
        assertFalse(out.contains("aB3xY9"))
        assertTrue(out.contains("[redacted]"))
    }

    @Test
    fun `token query parameters are removed`() {
        val out = LogRedactor.redact("GET /api/files?token=9f3k2j5h8s7d6f1a0z9x8c7v6b5n4m3q&limit=50")
        assertFalse(out.contains("9f3k2j5h8s7d6f1a0z9x8c7v6b5n4m3q"))
        assertTrue(out.contains("limit=50"))
    }

    @Test
    fun `assignment style secrets are removed but keys survive`() {
        val out = LogRedactor.redact("sessionId=8f2b1c9d4e7a6b5c0d1e2f3a4b5c6d7e accepted")
        assertTrue(out.startsWith("sessionId=[redacted]"))
        assertTrue(out.endsWith("accepted"))
    }

    @Test
    fun `device identifiers are removed`() {
        val out = LogRedactor.redact("android_id=1a2b3c4d5e6f7890 imei=490154203237518")
        assertFalse(out.contains("1a2b3c4d5e6f7890"))
        assertFalse(out.contains("490154203237518"))
    }

    @Test
    fun `private absolute paths are removed whole`() {
        val appData = LogRedactor.redact("wrote /data/user/0/app.morsecode/files/incoming/photo.jpg")
        assertTrue(appData.contains("<app-data>"))
        assertFalse(appData.contains("app.morsecode"))
        assertFalse(appData.contains("photo.jpg"))

        val shared = LogRedactor.redact("committing to /storage/emulated/0/Download/Morsecode/x.mp4")
        assertTrue(shared.contains("<storage>"))
        assertFalse(shared.contains("/storage/emulated/0"))
        assertFalse(shared.contains("x.mp4"))

        val legacy = LogRedactor.redact("legacy path /sdcard/Pictures/a.png")
        assertTrue(legacy.contains("<storage>"))
        assertFalse(legacy.contains("a.png"))

        val host = LogRedactor.redact("scratch /home/alice/secret.bin and /tmp/morsec-part")
        assertTrue(host.contains("<private-path>"))
        assertFalse(host.contains("/home/alice"))
        assertFalse(host.contains("secret.bin"))
        assertFalse(host.contains("/tmp/morsec-part"))

        val fileUri = LogRedactor.redact("source file:///data/user/0/app.morsecode/files/private.db")
        assertTrue(fileUri.contains("<file-uri>"))
        assertFalse(fileUri.contains("private.db"))
    }

    @Test
    fun `control characters are removed from log-safe text`() {
        val out = LogRedactor.redact("first\u0000second\nthird\tlast\u007f")
        assertFalse(out.any { it.code < 0x20 || it.code in 0x7f..0x9f })
        assertTrue(out.contains("first second third last "))
    }

    @Test
    fun `content URIs are redacted before logs are shared`() {
        val uri = "content://provider.documents/tree/primary%3ADownload/document/" +
            "primary%3ADownload%2Fprivate.mp4"
        val out = LogRedactor.redact("selected destination $uri")
        assertFalse(out.contains("provider.documents"))
        assertFalse(out.contains("private.mp4"))
        assertTrue(out.contains("content://[redacted]"))
    }

    @Test
    fun `long opaque identifiers are redacted regardless of token punctuation`() {
        val opaque = "A".repeat(24) + "/" + "b".repeat(24) + "=="
        val out = LogRedactor.redact("provider id=$opaque complete")
        assertFalse(out.contains(opaque))
        assertTrue(out.contains("id=[redacted]"))
        assertTrue(out.endsWith("complete"))
    }

    @Test
    fun `sha256 digests are redacted from log-safe text`() {
        val digest = "a".repeat(64)
        val out = LogRedactor.redact("verified sha256=$digest for sunset.jpg")
        assertFalse("digest bytes are not log-safe", out.contains(digest))
        assertTrue(out.contains("sha256=[redacted]"))
        assertTrue(out.contains("sunset.jpg"))
    }

    @Test
    fun `ordinary protocol lines are untouched`() {
        val line = "descriptor accepted: sunset.jpg 412 KB resume=102400 peer=Ravi's Redmi"
        assertEquals(line, LogRedactor.redact(line))
    }

    @Test
    fun `null and empty input are safe`() {
        assertEquals("", LogRedactor.redact(null))
        assertEquals("", LogRedactor.redact(""))
    }

    @Test
    fun `stack traces keep app frames and drop secrets`() {
        val throwable = IllegalStateException("handshake failed token=Q2VydGFpblRva2VuVmFsdWUxMjM0NTY3ODkwYWJj")
        throwable.stackTrace = arrayOf(
            StackTraceElement("app.morsecode.core.transfer.Handshake", "read", "Handshake.kt", 42),
            StackTraceElement("app.morsecode.core.transfer.Engine", "run", "Engine.kt", 88),
            StackTraceElement("app.morsecode.core.transfer.Engine", "start", "Engine.kt", 51),
            StackTraceElement("kotlin.coroutines.jvm.internal.BaseContinuationImpl", "resumeWith", "x.kt", 1),
        )
        val out = LogRedactor.redactStackTrace(throwable, maxLines = 10)
        assertTrue(out.contains("IllegalStateException"))
        assertTrue(out.contains("app.morsecode.core.transfer.Handshake.read"))
        assertFalse(out.contains("Q2VydGFpblRva2VuVmFsdWUxMjM0NTY3ODkwYWJj"))
    }

    @Test
    fun `causes are included and depth is bounded`() {
        val root = RuntimeException("root cause")
        val middle = RuntimeException("middle", root)
        val top = RuntimeException("top", middle)
        val out = LogRedactor.redactStackTrace(top)
        assertTrue(out.contains("Caused by: java.lang.RuntimeException: middle"))
        assertTrue(out.contains("Caused by: java.lang.RuntimeException: root cause"))
    }
}
