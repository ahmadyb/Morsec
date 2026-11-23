package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.integrity.Sha256Digest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Metadata comparison cannot authorize a resume without the retained whole-file SHA-256. */
class SourceChangePolicyTest {
    private val digest = Sha256Digest(ByteArray(32) { (it + 1).toByte() })

    private fun fp(size: Long? = 1_000L, modified: Long? = 100L, id: String? = "media:42") =
        SourceFingerprint(size, modified, id)

    private fun available(value: SourceFingerprint) = SourceFingerprintResult.Available(value)

    private fun decision(
        stored: SourceFingerprint,
        current: SourceFingerprint,
        expected: Sha256Digest? = digest,
    ): SourceResumeDecision = SourceResumePolicy.authorize(stored, available(current), expected)

    @Test
    fun matchingIdentityAndMetadataCanResumeWithTheExpectedDigest() {
        val comparison = SourceChangePolicy.compare(fp(), fp())
        assertEquals(FingerprintOutcome.STRONG_MATCH, comparison)
        assertFalse(comparison.requiresRestart)
        val ready = decision(fp(), fp()) as SourceResumeDecision.Ready
        assertEquals(comparison, ready.fingerprintOutcome)
        assertTrue(ready.toString().contains("[redacted]"))
        assertFalse(ready.toString().contains(digest.hex))
    }

    @Test
    fun matchingMetadataWithoutAnExpectedDigestCannotAuthorizeResume() {
        val refused = decision(fp(), fp(), expected = null) as SourceResumeDecision.Refused
        assertEquals(FingerprintOutcome.STRONG_MATCH, refused.fingerprintOutcome)
        assertEquals("expected_sha256_required", (refused.error as TransferStorageError.StateConflict).reason)
    }

    @Test
    fun theSameIdentityCannotOverrideAGrowingSize() {
        val before = fp(size = 1_000L, modified = 100L)
        val after = fp(size = 2_000L, modified = 100L)
        assertEquals(FingerprintOutcome.DEFINITE_CHANGE, SourceChangePolicy.compare(before, after))
        val refused = decision(before, after) as SourceResumeDecision.Refused
        assertTrue(refused.fingerprintOutcome.requiresRestart)
        assertEquals("source_changed_restart_required", (refused.error as TransferStorageError.StateConflict).reason)
    }

    @Test
    fun theSameIdentityCannotOverrideAShrinkingSizeEvenWhenTimestampIsMissing() {
        val before = fp(size = 1_000L, modified = null)
        val after = fp(size = 999L, modified = null)
        assertEquals(FingerprintOutcome.DEFINITE_CHANGE, SourceChangePolicy.compare(before, after))
        assertTrue(decision(before, after) is SourceResumeDecision.Refused)
    }

    @Test
    fun theSameIdentityCannotOverrideAChangedTimestamp() {
        val before = fp(size = 1_000L, modified = 100L)
        val after = fp(size = 1_000L, modified = 101L)
        assertEquals(FingerprintOutcome.DEFINITE_CHANGE, SourceChangePolicy.compare(before, after))
        assertTrue(decision(before, after) is SourceResumeDecision.Refused)
    }

    @Test
    fun timestampMismatchIsNotHiddenByAnUnknownSize() {
        val before = fp(size = null, modified = 100L)
        val after = fp(size = null, modified = 101L)
        assertEquals(FingerprintOutcome.DEFINITE_CHANGE, SourceChangePolicy.compare(before, after))
        assertTrue(decision(before, after) is SourceResumeDecision.Refused)
    }

    @Test
    fun identityMismatchIsNotHiddenByMissingSizeAndTimestamp() {
        val before = fp(size = null, modified = null, id = "first")
        val after = fp(size = null, modified = null, id = "second")
        assertEquals(FingerprintOutcome.DEFINITE_CHANGE, SourceChangePolicy.compare(before, after))
        assertTrue(decision(before, after) is SourceResumeDecision.Refused)
    }

    @Test
    fun sizeMismatchWithoutIdentityAlsoRequiresRestart() {
        val before = fp(size = 1_000L, id = null)
        val after = fp(size = 1_001L, id = null)
        assertEquals(FingerprintOutcome.DEFINITE_CHANGE, SourceChangePolicy.compare(before, after))
        assertTrue(decision(before, after) is SourceResumeDecision.Refused)
    }

    @Test
    fun timestampMismatchWithoutIdentityAlsoRequiresRestart() {
        val before = fp(modified = 100L, id = null)
        val after = fp(modified = 101L, id = null)
        assertEquals(FingerprintOutcome.DEFINITE_CHANGE, SourceChangePolicy.compare(before, after))
        assertTrue(decision(before, after) is SourceResumeDecision.Refused)
    }

    @Test
    fun missingIdentityIsWeakEvidenceAndStillNeedsTheDigest() {
        val before = fp(id = null)
        val after = fp(id = null)
        assertEquals(FingerprintOutcome.WEAK_MATCH, SourceChangePolicy.compare(before, after))
        assertTrue(decision(before, after) is SourceResumeDecision.Ready)
        assertTrue(decision(before, after, expected = null) is SourceResumeDecision.Refused)
    }

    @Test
    fun missingCurrentSizeRequiresTheExpectedDigest() {
        val before = fp()
        val after = fp(size = null)
        assertEquals(FingerprintOutcome.MISSING_METADATA, SourceChangePolicy.compare(before, after))
        assertTrue(decision(before, after) is SourceResumeDecision.Ready)
        val refused = decision(before, after, expected = null) as SourceResumeDecision.Refused
        assertEquals("expected_sha256_required", (refused.error as TransferStorageError.StateConflict).reason)
    }

    @Test
    fun missingStoredSizeRequiresTheExpectedDigest() {
        val before = fp(size = null)
        val after = fp()
        assertEquals(FingerprintOutcome.MISSING_METADATA, SourceChangePolicy.compare(before, after))
        assertTrue(decision(before, after) is SourceResumeDecision.Ready)
        assertTrue(decision(before, after, expected = null) is SourceResumeDecision.Refused)
    }

    @Test
    fun missingTimestampRequiresTheExpectedDigestEvenWhenIdentityAndSizeMatch() {
        val before = fp(modified = null)
        val after = fp(modified = 100L)
        assertEquals(FingerprintOutcome.MISSING_METADATA, SourceChangePolicy.compare(before, after))
        assertTrue(decision(before, after) is SourceResumeDecision.Ready)
        assertTrue(decision(before, after, expected = null) is SourceResumeDecision.Refused)
    }

    @Test
    fun noUsableMetadataStillRequiresTheExpectedDigest() {
        val before = SourceFingerprint.UNKNOWN
        val after = SourceFingerprint.UNKNOWN
        assertEquals(FingerprintOutcome.MISSING_METADATA, SourceChangePolicy.compare(before, after))
        assertTrue(decision(before, after) is SourceResumeDecision.Ready)
        assertTrue(decision(before, after, expected = null) is SourceResumeDecision.Refused)
    }

    @Test
    fun anUnavailableFingerprintIsNotAChangeAndCannotAuthorizeResume() {
        val unavailable = SourceFingerprintResult.Unavailable(
            TransferStorageError.Io("stat", "/secret/path.mp4"),
        )
        assertEquals(FingerprintOutcome.UNAVAILABLE, SourceChangePolicy.evaluate(fp(), unavailable))
        val refused = SourceResumePolicy.authorize(fp(), unavailable, digest) as SourceResumeDecision.Refused
        assertFalse(refused.fingerprintOutcome.requiresRestart)
        assertNull(refused.error.diagnostic)
        assertFalse(refused.toString().contains("/secret/path.mp4"))
    }

    @Test
    fun cancellationDuringFingerprintingIsNotTurnedIntoAProviderFailure() {
        val unavailable = SourceFingerprintResult.Unavailable(TransferStorageError.Cancelled)
        val refused = SourceResumePolicy.authorize(fp(), unavailable, digest) as SourceResumeDecision.Refused
        assertEquals(FingerprintOutcome.UNAVAILABLE, refused.fingerprintOutcome)
        assertEquals(TransferStorageError.Cancelled, refused.error)
    }

    @Test
    fun aRevokedGrantIsNotTurnedIntoAProviderFailure() {
        val unavailable = SourceFingerprintResult.Unavailable(TransferStorageError.PermissionRevoked("read"))
        val refused = SourceResumePolicy.authorize(fp(), unavailable, digest) as SourceResumeDecision.Refused
        assertEquals(FingerprintOutcome.PERMISSION_REVOKED, refused.fingerprintOutcome)
        assertEquals(TransferStorageErrorCategory.PERMISSION_REVOKED, refused.error.category)
    }

    @Test
    fun fingerprintToStringDoesNotExposeIdentityOrMetadata() {
        val fingerprint = fp(size = 1_234L, modified = 567L, id = "content://secret/filename")
        assertEquals("SourceFingerprint([redacted])", fingerprint.toString())
        assertFalse(available(fingerprint).toString().contains("secret"))
    }

    @Test
    fun strengthIsEvidenceAvailabilityNotAContentGuarantee() {
        assertEquals(FingerprintStrength.STRONG, fp().strength)
        assertEquals(FingerprintStrength.WEAK, fp(id = null).strength)
        assertEquals(FingerprintStrength.NONE, fp(size = null).strength)
    }

    @Test
    fun allSixOutcomesHaveDistinctIdsAndRoundTrip() {
        val all = FingerprintOutcome.entries
        assertEquals(6, all.size)
        assertEquals(all.size, all.map { it.id }.distinct().size)
        all.forEach { assertEquals(it, FingerprintOutcome.fromId(it.id)) }
        assertEquals(FingerprintOutcome.UNAVAILABLE, FingerprintOutcome.fromId("nonsense"))
    }
}
