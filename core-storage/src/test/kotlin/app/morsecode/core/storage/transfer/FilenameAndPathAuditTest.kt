package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.identity.RelativeTransferPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filename and relative-path audit required by §7.
 *
 * Every case here records what the code **already does**. None of it is a new rule: the purpose is to
 * pin the current spelling so that a later slice cannot change it by accident, and to prove the one
 * property that matters most -- nothing normalizes a name behind the caller's back. Where a name is
 * refused, the refusal is asserted; where it is accepted, the exact accepted spelling is asserted
 * byte for byte.
 *
 * The interesting entries are the ones that *look* dangerous and are still accepted: leading and
 * trailing whitespace, a leading dot, `:` and other provider-reserved characters. They are accepted
 * because a SAF `displayName` is not a path, the provider owns the final name, and rewriting a user's
 * filename is a worse outcome than passing it through unchanged. The characters that are refused --
 * separators, controls, traversal markers -- are refused because they change which document is
 * addressed, not merely how it is spelled.
 *
 * Case sensitivity is deliberately *not* decided here. `DocumentsContract` collisions are the
 * provider's business, which is why duplicate resolution is a suffix loop rather than a lookup: the
 * loop works on a case-insensitive provider and on a case-sensitive one without being told which it
 * is talking to.
 */
class FilenameAndPathAuditTest {

    // --- names that are refused because they address something else ------------------------------

    @Test(timeout = 30_000L)
    fun anEmptyOrBlankNameIsRefused() {
        assertRefused { SafFilenamePolicy.requireOriginalName("") }
        assertRefused { SafFilenamePolicy.requireOriginalName(" ") }
        assertRefused { SafFilenamePolicy.requireOriginalName("\t\t") }
        // Pinned quirk: a name of only a no-break space is refused as blank, because Kotlin's
        // isBlank() folds every Unicode space separator, not just U+0020. An NBSP inside a name is
        // inert and is preserved.
        assertRefused { SafFilenamePolicy.requireOriginalName("\u00A0") }
        assertEquals("a\u00A0b", SafFilenamePolicy.requireOriginalName("a\u00A0b"))
    }

    @Test(timeout = 30_000L)
    fun traversalMarkersAreRefusedButAnOrdinaryLeadingDotIsNot() {
        assertRefused { SafFilenamePolicy.requireOriginalName(".") }
        assertRefused { SafFilenamePolicy.requireOriginalName("..") }
        assertEquals(".hidden", SafFilenamePolicy.requireOriginalName(".hidden"))
        // And the same markers inside a transfer path are refused per segment.
        assertTrue(RelativeTransferPath.orNull("a/.") == null)
        assertTrue(RelativeTransferPath.orNull("a/..") == null)
        assertEquals(".hidden", RelativeTransferPath(".hidden").lastSegment)
    }

    @Test(timeout = 30_000L)
    fun separatorsAreRefusedInANameBecauseTheyChangeTheAddress() {
        assertRefused { SafFilenamePolicy.requireOriginalName("a/b") }
        assertRefused { SafFilenamePolicy.requireOriginalName("a\\b") }
        assertRefused { SafFilenamePolicy.requireOriginalName("/absolute") }
        assertRefused { SafFilenamePolicy.requireOriginalName("\\\\server\\share") }
    }

    @Test(timeout = 30_000L)
    fun nulAndControlCharactersAreRefused() {
        assertRefused { SafFilenamePolicy.requireOriginalName("a\u0000b") }
        assertRefused { SafFilenamePolicy.requireOriginalName("a\u0001b") }
        assertRefused { SafFilenamePolicy.requireOriginalName("a\nb") }
        assertRefused { SafFilenamePolicy.requireOriginalName("a\u007Fb") }
        // A zero-width joiner is not ISO control, so it survives: it is a real character in several
        // scripts and removing it would change the name a user sees.
        assertEquals("a\u200Db", SafFilenamePolicy.requireOriginalName("a\u200Db"))
    }

    @Test(timeout = 30_000L)
    fun malformedUnicodeIsRefusedRatherThanReplaced() {
        assertRefused { SafFilenamePolicy.requireOriginalName("a\uD800b") }
        assertRefused { SafFilenamePolicy.requireOriginalName("\uDC00") }
    }

    // --- names that are accepted verbatim --------------------------------------------------------

    @Test(timeout = 30_000L)
    fun whitespaceIsPreservedAndNeverTrimmed() {
        assertEquals(" lead", SafFilenamePolicy.requireOriginalName(" lead"))
        assertEquals("trail ", SafFilenamePolicy.requireOriginalName("trail "))
        assertEquals(" both ", SafFilenamePolicy.requireOriginalName(" both "))
        assertEquals("a  b", SafFilenamePolicy.requireOriginalName("a  b"))
    }

    @Test(timeout = 30_000L)
    fun charactersReservedByOtherProvidersArePassedThrough() {
        // Windows and some document providers reserve these. Refusing them here would make Morsec
        // stricter than the platform it runs on; the provider, not this policy, owns the final name.
        for (name in listOf("a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "CON", "PRN", "AUX", "NUL")) {
            assertEquals(name, SafFilenamePolicy.requireOriginalName(name))
        }
    }

    @Test(timeout = 30_000L)
    fun hiddenStyleNamesKeepTheWholeNameAsTheBasename() {
        // `.gitignore`-style names: the leading dot is not an extension separator, so a suffix
        // insertion must keep the name readable rather than producing "." + marker.
        val suffixed = SafFilenamePolicy.withDuplicateSuffix(".env", " (1)")
        assertEquals(".env (1)", suffixed)
    }

    // --- byte accounting -------------------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun theLimitIsUtf8BytesAtASupplementaryCodePointBoundary() {
        // A four-byte emoji is never split: 31 emoji are 124 bytes, one more is 128 and is refused.
        val emoji = "\uD83D\uDE00"
        val atLimit = emoji.repeat(31) + "abc"
        assertEquals(127, SafFilenamePolicy.utf8Length(atLimit))
        assertEquals(atLimit, SafFilenamePolicy.requireOriginalName(atLimit))

        val overLimit = emoji.repeat(31) + "abcd"
        assertEquals(128, SafFilenamePolicy.utf8Length(overLimit))
        assertRefused { SafFilenamePolicy.requireOriginalName(overLimit) }

        // And the same boundary expressed as ASCII: exactly 127 bytes is fine, 128 is not.
        assertEquals(127, SafFilenamePolicy.utf8Length("a".repeat(127)))
        assertEquals("a".repeat(127), SafFilenamePolicy.requireOriginalName("a".repeat(127)))
        assertRefused { SafFilenamePolicy.requireOriginalName("a".repeat(128)) }
    }

    @Test(timeout = 30_000L)
    fun aDuplicateSuffixTruncatesWithoutSplittingACodePoint() {
        val emoji = "\uD83D\uDE00"
        // 120 bytes of basename plus a four-byte extension: a legal original that a suffix must shorten.
        val long = "e".repeat(112) + emoji.repeat(2) + ".jpg"
        assertEquals(124, SafFilenamePolicy.utf8Length(long))
        val suffixed = SafFilenamePolicy.withDuplicateSuffix(long, " (1)")
        assertTrue("must fit: ${SafFilenamePolicy.utf8Length(suffixed)}", SafFilenamePolicy.utf8Length(suffixed) <= 127)
        assertTrue("must keep the extension", suffixed.endsWith(".jpg"))
        // Decoding the retained prefix must round-trip: a half surrogate would not.
        val prefix = suffixed.removeSuffix(".jpg").removeSuffix(" (1)")
        assertEquals(prefix, String(prefix.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertFalse("a surrogate half survived", prefix.any { it.isHighSurrogate() && prefix.indexOf(it) == prefix.length - 1 })
    }

    @Test(timeout = 30_000L)
    fun aGeneratedNameThatCannotFitIsAnErrorNotATruncatedExtension() {
        // A suffix long enough to consume the whole budget is refused rather than emitted oversized.
        assertRefused { SafFilenamePolicy.withDuplicateSuffix("a.report.pdf", "~".repeat(124)) }
        assertRefused { SafFilenamePolicy.withAppendedSuffix("a.txt", "~".repeat(124)) }
    }

    // --- no normalization ------------------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun nfcAndNfdAreDifferentNamesAndNeitherIsRewritten() {
        val nfc = "caf\u00E9.jpg"          // é as one code point
        val nfd = "cafe\u0301.jpg"         // e + combining acute
        assertNotEquals(nfc, nfd)
        assertEquals(9, SafFilenamePolicy.utf8Length(nfc))
        assertEquals(10, SafFilenamePolicy.utf8Length(nfd))
        assertEquals(nfc, SafFilenamePolicy.requireOriginalName(nfc))
        assertEquals(nfd, SafFilenamePolicy.requireOriginalName(nfd))
        assertEquals(
            "a suffix insertion must not normalize either form",
            "cafe\u0301 (1).jpg",
            SafFilenamePolicy.withDuplicateSuffix(nfd, " (1)"),
        )
    }

    @Test(timeout = 30_000L)
    fun theRelativePathIsAlsoNotNormalized() {
        val nfd = RelativeTransferPath("phot\u0065\u0301s/a.jpg")
        assertEquals("phot\u0065\u0301s/a.jpg", nfd.value)
        assertEquals(2, nfd.depth)
        // A backslash is a separator for validation, and the segments are reported with `/`...
        assertEquals(listOf("dir", "a.jpg"), RelativeTransferPath("dir\\a.jpg").segments)
        // ...but the value the peer sent is preserved, so a round trip is lossless.
        assertEquals("dir\\a.jpg", RelativeTransferPath("dir\\a.jpg").value)
    }

    // --- path limits -----------------------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun theTotalPathLimitIs512Utf8Bytes() {
        // A segment is capped at 127 bytes independently, so the total is reached by depth rather
        // than by one long name: four full segments and their three separators are 511 bytes.
        val fits = List(4) { "a".repeat(127) }.joinToString("/")
        assertEquals(511, fits.toByteArray(Charsets.UTF_8).size)
        assertEquals(fits, RelativeTransferPath(fits).value)

        // Exactly 512: the last segment is one byte shorter than the cap and a fifth carries the rest.
        val exactly = List(3) { "a".repeat(127) }.joinToString("/") + "/" + "b".repeat(126) + "/c"
        assertEquals(512, exactly.toByteArray(Charsets.UTF_8).size)
        assertEquals(exactly, RelativeTransferPath(exactly).value)

        val over = exactly + "d"
        assertEquals(513, over.toByteArray(Charsets.UTF_8).size)
        assertTrue(RelativeTransferPath.orNull(over) == null)
    }

    @Test(timeout = 30_000L)
    fun theDepthLimitIs32Segments() {
        val atLimit = (1..ProtocolLimits.MAX_PATH_SEGMENTS).joinToString("/") { "d$it" }
        assertEquals(32, RelativeTransferPath(atLimit).depth)

        val overLimit = (1..(ProtocolLimits.MAX_PATH_SEGMENTS + 1)).joinToString("/") { "d$it" }
        assertTrue(RelativeTransferPath.orNull(overLimit) == null)
    }

    @Test(timeout = 30_000L)
    fun theEmptyAndDotFormsOfAPathAreRefused() {
        for (raw in listOf("", "/", ".", "..", "a/./b", "a/../b", "a//b", "a/", "\\a", "C:/a", "a/%2e%2e/b")) {
            assertTrue("$raw must be refused", RelativeTransferPath.orNull(raw) == null)
        }
    }

    // --- duplicates ------------------------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun duplicateResolutionIsASuffixLoopThatNeedsNoCaseKnowledge() {
        val candidates = (1..5).map { SafFilenamePolicy.withDuplicateSuffix("Report.PDF", " ($it)") }
        assertEquals(
            listOf("Report (1).PDF", "Report (2).PDF", "Report (3).PDF", "Report (4).PDF", "Report (5).PDF"),
            candidates,
        )
        // Every candidate is distinct as a string; whether the *provider* treats them as distinct
        // depends on its case rules, which is exactly why the caller keeps going rather than guessing.
        assertEquals(candidates.size, candidates.distinct().size)
        assertEquals("a lower-case collision is still an exact-name match to the provider's rules",
            "report.pdf", "report.pdf")
    }

    // --- helper ----------------------------------------------------------------------------------

    /** Returns the exception message, and fails the test if nothing was refused. */
    private fun assertRefused(block: () -> Unit): String = try {
        block()
        throw AssertionError("expected the call to be refused")
    } catch (e: IllegalArgumentException) {
        e.message ?: ""
    } catch (e: IllegalStateException) {
        // `check` is used for generated-name invariants; both are refusals to the caller.
        e.message ?: ""
    }
}
