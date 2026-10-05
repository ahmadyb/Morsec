package app.morsecode.core.storage.transfer

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafFilenamePolicyTest {

    private fun assertValidUtf8AndBounded(name: String) {
        assertTrue("$name exceeds the byte budget", SafFilenamePolicy.utf8Length(name) <= SafFilenamePolicy.MAX_FILENAME_BYTES)
        val decoded = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(name.toByteArray(StandardCharsets.UTF_8)))
            .toString()
        assertEquals(name, decoded)
    }

    private fun found(name: String) = SafLookup.Found(
        SafDocumentInfo(
            documentUri = "content://provider/document/$name",
            documentId = name,
            displayName = name,
            sizeBytes = null,
            mimeType = null,
            flags = null,
            isDirectory = false,
        ),
    )

    @Test
    fun `ascii original accepts exactly the byte limit and rejects one byte above`() {
        val exact = "a".repeat(SafFilenamePolicy.MAX_FILENAME_BYTES)
        assertEquals(exact, SafFilenamePolicy.requireOriginalName(exact))
        assertTrue(runCatching {
            SafFilenamePolicy.requireOriginalName(exact + "a")
        }.isFailure)
    }

    @Test
    fun `utf8 accounting counts two three and four byte code points`() {
        assertEquals(2, SafFilenamePolicy.utf8Length("é"))
        assertEquals(3, SafFilenamePolicy.utf8Length("界"))
        assertEquals(4, SafFilenamePolicy.utf8Length("🚀"))

        listOf("é", "界", "🚀").forEach { character ->
            val count = SafFilenamePolicy.utf8Length(character)
            val exact = character.repeat(SafFilenamePolicy.MAX_FILENAME_BYTES / count)
            assertTrue(SafFilenamePolicy.utf8Length(exact) <= SafFilenamePolicy.MAX_FILENAME_BYTES)
            assertValidUtf8AndBounded(SafFilenamePolicy.withDuplicateSuffix(exact, " (1)"))
        }
    }

    @Test
    fun `combining marks are preserved as complete code points and counted in utf8`() {
        val decomposed = "e\u0301"
        assertEquals(3, SafFilenamePolicy.utf8Length(decomposed))
        assertEquals(decomposed, SafFilenamePolicy.requireOriginalName(decomposed))
        assertValidUtf8AndBounded(SafFilenamePolicy.withDuplicateSuffix(decomposed.repeat(40), " (1)"))
    }

    @Test
    fun `duplicate suffix truncates a long basename and preserves the extension`() {
        val name = "a" + "b".repeat(122) + ".mp4"
        val result = SafDuplicateNaming.withSuffix(name, 1)
        assertTrue(result.endsWith(" (1).mp4"))
        assertValidUtf8AndBounded(result)
    }

    @Test
    fun `a long extension is reserved before fitting a duplicate basename`() {
        val name = "a." + "x".repeat(117)
        val result = SafDuplicateNaming.withSuffix(name, 1)
        assertTrue(result.endsWith(" (1)." + "x".repeat(117)))
        assertValidUtf8AndBounded(result)
    }

    @Test
    fun `duplicate suffix works without an extension and for a hidden style name`() {
        val noExtension = SafDuplicateNaming.withSuffix("recording", 1)
        val hidden = SafDuplicateNaming.withSuffix(".profile", 1)
        assertEquals("recording (1)", noExtension)
        assertEquals(".profile (1)", hidden)
        assertValidUtf8AndBounded(noExtension)
        assertValidUtf8AndBounded(hidden)
    }

    @Test
    fun `large duplicate suffix bytes are included in the budget`() {
        val result = SafFilenamePolicy.withDuplicateSuffix("a.txt", " (999999999999999999999)")
        assertTrue(result.endsWith(" (999999999999999999999).txt"))
        assertValidUtf8AndBounded(result)
    }

    @Test
    fun `temporary replacement and backup suffixes fit and preserve the extension`() {
        val partial = PartialIdentity("saf:commit-🚀")
        val temporary = temporaryDocumentName("x".repeat(123) + ".mp4", partial)
        val replacement = temporaryDocumentName("x".repeat(123) + ".mp4", partial)
        val backup = backupDocumentName("x".repeat(123) + ".mp4", partial)

        assertEquals(temporary, replacement)
        assertTrue(temporary.contains(".mp4."))
        assertTrue(temporary.endsWith(".morsec-part"))
        assertTrue(temporary.contains(".saf-commit---.morsec-part"))
        assertTrue(backup.contains(".mp4."))
        assertTrue(backup.endsWith(".morsec-backup"))
        listOf(temporary, replacement, backup).forEach(::assertValidUtf8AndBounded)
    }

    @Test
    fun `suffix and extension that leave no basename are rejected`() {
        val longExtension = "a." + "e".repeat(124)
        assertTrue(runCatching {
            SafFilenamePolicy.withDuplicateSuffix(longExtension, " (1)")
        }.isFailure)
        assertTrue(runCatching {
            SafFilenamePolicy.withAppendedSuffix(longExtension, ".commit.morsec-backup")
        }.isFailure)
    }

    @Test
    fun `malformed unicode nul separators and traversal markers are rejected`() {
        listOf("\uD800", "bad\u0000name", "a/b", "a\\b", ".", "..").forEach { invalid ->
            assertTrue("accepted $invalid", runCatching {
                SafFilenamePolicy.requireOriginalName(invalid)
            }.isFailure)
        }
    }

    @Test
    fun `every duplicate candidate through the maximum remains valid and bounded`() {
        val original = "x".repeat(123) + ".mp4"
        val candidates = mutableListOf<String>()
        val choice = SafDuplicateNaming.firstFree(original) { candidate ->
            candidates += candidate
            found(candidate)
        }

        assertTrue(choice is SafNameChoice.Exhausted)
        assertEquals(SafDuplicateNaming.MAX_RENAME_ATTEMPTS + 1, candidates.size)
        candidates.forEach(::assertValidUtf8AndBounded)
        assertEquals(original, candidates.first())
        assertEquals(".mp4", candidates.first().takeLast(4))
        (1..SafDuplicateNaming.MAX_RENAME_ATTEMPTS).forEach { index ->
            assertEquals(SafDuplicateNaming.withSuffix(original, index), candidates[index])
            assertTrue(candidates[index].endsWith(".mp4"))
        }
    }

    @Test
    fun `duplicate attempts are bounded and exhaustion is explicit`() {
        var attempts = 0
        val choice = SafDuplicateNaming.firstFree("movie.mp4") {
            attempts++
            found(it)
        }

        assertTrue(choice is SafNameChoice.Exhausted)
        assertEquals(SafDuplicateNaming.MAX_RENAME_ATTEMPTS + 1, attempts)
    }

}
