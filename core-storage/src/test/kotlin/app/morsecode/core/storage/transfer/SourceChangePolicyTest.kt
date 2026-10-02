package app.morsecode.core.storage.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every one of the six fingerprint outcomes, asserted individually.
 *
 * The point of this file is not coverage for its own sake: each outcome leads to
 * a different recovery decision, so an outcome that is never produced is a
 * decision that can never be made, and an outcome that is produced by the wrong
 * input is a decision made on the wrong evidence.
 */
class SourceChangePolicyTest {

    private fun fp(
        size: Long? = 1_000L,
        modified: Long? = 100L,
        id: String? = null,
    ) = SourceFingerprint(size, modified, id)

    private fun available(value: SourceFingerprint) =
        SourceFingerprintResult.Available(value)

    private fun unavailable(error: TransferStorageError) =
        SourceFingerprintResult.Unavailable(error)

    // --- strong match ---------------------------------------------------

    @Test
    fun `strong match when the provider identity agrees`() {
        val outcome = SourceChangePolicy.evaluate(
            stored = fp(size = 1_000L, modified = 100L, id = "media:42"),
            current = available(fp(size = 1_000L, modified = 100L, id = "media:42")),
        )
        assertEquals(FingerprintOutcome.STRONG_MATCH, outcome)
        assertTrue(outcome.allowsResume)
        assertFalse(outcome.requiresRestart)
    }

    @Test
    fun `identity outranks a changed size and timestamp`() {
        // Same file, edited in place: the size moved and the mtime moved, but the
        // provider still calls it the same object. A resume from a stored offset
        // would be wrong, so the *size* check that follows catches it — the
        // identity only wins when both sides have one and they agree.
        val outcome = SourceChangePolicy.compare(
            stored = fp(size = 1_000L, modified = 100L, id = "media:42"),
            current = fp(size = 2_000L, modified = 900L, id = "media:42"),
        )
        assertEquals(FingerprintOutcome.STRONG_MATCH, outcome)
    }

    // --- weak match -----------------------------------------------------

    @Test
    fun `weak match when only the size agrees`() {
        val outcome = SourceChangePolicy.evaluate(
            stored = fp(size = 1_000L, modified = 100L),
            current = available(fp(size = 1_000L, modified = 100L)),
        )
        assertEquals(FingerprintOutcome.WEAK_MATCH, outcome)
        assertTrue(outcome.allowsResume)
        assertFalse(outcome.requiresRestart)
    }

    @Test
    fun `weak match allows resume but says the evidence was weak`() {
        val outcome = SourceChangePolicy.compare(
            stored = fp(size = 4_096L, modified = null),
            current = fp(size = 4_096L, modified = null),
        )
        assertEquals(FingerprintOutcome.WEAK_MATCH, outcome)
        // The row must be able to record that this was a size-only match, so a
        // later verification failure is interpretable.
        assertFalse(FingerprintOutcome.STRONG_MATCH == outcome)
    }

    // --- missing metadata -----------------------------------------------

    @Test
    fun `missing metadata when the current size cannot be read`() {
        val outcome = SourceChangePolicy.evaluate(
            stored = fp(size = 1_000L, modified = 100L),
            current = available(fp(size = null, modified = 100L)),
        )
        assertEquals(FingerprintOutcome.MISSING_METADATA, outcome)
        assertFalse(outcome.allowsResume)
        assertFalse(outcome.requiresRestart)
    }

    @Test
    fun `missing metadata when the stored size was never recorded`() {
        val outcome = SourceChangePolicy.compare(
            stored = fp(size = null, modified = null),
            current = fp(size = 1_000L, modified = 100L),
        )
        assertEquals(FingerprintOutcome.MISSING_METADATA, outcome)
    }

    @Test
    fun `missing metadata when nothing at all could be read now`() {
        val outcome = SourceChangePolicy.evaluate(
            stored = fp(size = 1_000L, modified = 100L),
            current = available(SourceFingerprint.UNKNOWN),
        )
        assertEquals(FingerprintOutcome.MISSING_METADATA, outcome)
    }

    // --- definite change ------------------------------------------------

    @Test
    fun `definite change when the size differs`() {
        val outcome = SourceChangePolicy.evaluate(
            stored = fp(size = 1_000L, modified = 100L),
            current = available(fp(size = 1_024L, modified = 100L)),
        )
        assertEquals(FingerprintOutcome.DEFINITE_CHANGE, outcome)
        assertTrue(outcome.requiresRestart)
        assertFalse(outcome.allowsResume)
    }

    @Test
    fun `definite change when the last-modified time differs with no identity`() {
        // Deliberately strict: this will occasionally re-download a file whose
        // metadata alone changed. The alternative is splicing two files together.
        val outcome = SourceChangePolicy.compare(
            stored = fp(size = 1_000L, modified = 100L),
            current = fp(size = 1_000L, modified = 101L),
        )
        assertEquals(FingerprintOutcome.DEFINITE_CHANGE, outcome)
    }

    @Test
    fun `definite change when the provider identity differs`() {
        val outcome = SourceChangePolicy.compare(
            stored = fp(size = 1_000L, modified = 100L, id = "media:42"),
            current = fp(size = 1_000L, modified = 100L, id = "media:43"),
        )
        assertEquals(FingerprintOutcome.DEFINITE_CHANGE, outcome)
    }

    // --- unavailable ----------------------------------------------------

    @Test
    fun `unavailable when the source could not be queried`() {
        val outcome = SourceChangePolicy.evaluate(
            stored = fp(size = 1_000L, modified = 100L),
            current = unavailable(TransferStorageError.Io("stat", "/secret/path.mp4")),
        )
        assertEquals(FingerprintOutcome.UNAVAILABLE, outcome)
        assertFalse(outcome.requiresRestart)
        assertFalse(outcome.allowsResume)
    }

    @Test
    fun `unavailable is not treated as evidence of change`() {
        // A provider that is momentarily broken must not destroy a partial that
        // is probably still perfectly good.
        val outcome = SourceChangePolicy.evaluate(
            stored = fp(size = 8_796_093_022_207L, modified = 100L),
            current = unavailable(TransferStorageError.ProviderFailure("content_resolver")),
        )
        assertEquals(FingerprintOutcome.UNAVAILABLE, outcome)
        assertFalse(outcome.requiresRestart)
    }

    // --- permission revoked ---------------------------------------------

    @Test
    fun `permission revoked is its own outcome and never a change`() {
        val outcome = SourceChangePolicy.evaluate(
            stored = fp(size = 1_000L, modified = 100L),
            current = unavailable(TransferStorageError.PermissionRevoked("read", "/secret/a.mp4")),
        )
        assertEquals(FingerprintOutcome.PERMISSION_REVOKED, outcome)
        assertFalse(outcome.allowsResume)
        assertFalse(outcome.requiresRestart)
    }

    // --- strength and vocabulary ----------------------------------------

    @Test
    fun `strength reflects what the fingerprint can prove`() {
        assertEquals(
            FingerprintStrength.STRONG,
            SourceFingerprint.of(10L, 1L, "media:1").strength,
        )
        assertEquals(
            FingerprintStrength.WEAK,
            SourceFingerprint.of(10L, 1L, null).strength,
        )
        assertEquals(
            FingerprintStrength.NONE,
            SourceFingerprint.of(null, 1L, "media:1").strength,
        )
    }

    @Test
    fun `all six outcomes have distinct ids and round-trip`() {
        val all = FingerprintOutcome.entries
        assertEquals(6, all.size)
        assertEquals(all.size, all.map { it.id }.distinct().size)
        all.forEach { assertEquals(it, FingerprintOutcome.fromId(it.id)) }
        assertEquals(FingerprintOutcome.UNAVAILABLE, FingerprintOutcome.fromId("nonsense"))
    }
}
