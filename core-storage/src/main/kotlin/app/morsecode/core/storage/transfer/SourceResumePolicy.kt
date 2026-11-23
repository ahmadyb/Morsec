package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.integrity.Sha256Digest

/** A resume decision; only [Ready] carries the retained expected whole-file digest. */
public sealed interface SourceResumeDecision {
    /**
     * Metadata did not rule out resume and an expected SHA-256 was retained. This is not proof that
     * the source is unchanged. A resumed destination independently retains its expected digest and
     * refuses publication without a matching full-file verification.
     */
    public class Ready internal constructor(
        public val fingerprintOutcome: FingerprintOutcome,
        private val storedFingerprint: SourceFingerprint,
        private val expectedSha256: Sha256Digest,
    ) : SourceResumeDecision {
        /** Re-check the live source immediately before the typed opener resumes it. */
        internal fun revalidate(current: SourceFingerprintResult): SourceResumeDecision =
            SourceResumePolicy.authorize(storedFingerprint, current, expectedSha256)

        /** Verify against the same expected digest that authorized this resume. */
        public fun verify(
            session: DestinationSession,
            buffer: ByteArray = ByteArray(DestinationVerifier.VERIFY_BUFFER_BYTES),
            isCancelled: () -> Boolean = { false },
        ): SessionVerification = session.verify(expected = expectedSha256, buffer = buffer, isCancelled = isCancelled)

        override fun toString(): String = "SourceResumeDecision.Ready([redacted])"
    }

    /** Resume is not authorized. A changed fingerprint requires a restart, not a silent resume. */
    public data class Refused(
        public val fingerprintOutcome: FingerprintOutcome,
        public val error: TransferStorageError,
    ) : SourceResumeDecision
}

/**
 * Turns fingerprint evidence into a resume decision without reading source bytes.
 *
 * An expected SHA-256 is required even for complete matching metadata: identity, size and timestamp
 * do not prove content equality. In particular, missing metadata can never become a successful
 * decision merely by treating an unknown value as a match. An inaccessible source is still refused.
 * This object does not persist or publish anything; the caller owns the retained digest across
 * process restoration. A resumed [DestinationSession] requires the same persisted value at open and
 * independently enforces final comparison before commit.
 */
public object SourceResumePolicy {
    public fun authorize(
        stored: SourceFingerprint,
        current: SourceFingerprintResult,
        expectedSha256: Sha256Digest?,
    ): SourceResumeDecision {
        val outcome = SourceChangePolicy.evaluate(stored, current)
        return when (outcome) {
            FingerprintOutcome.DEFINITE_CHANGE -> SourceResumeDecision.Refused(
                outcome,
                TransferStorageError.StateConflict("source_changed_restart_required"),
            )
            FingerprintOutcome.PERMISSION_REVOKED -> SourceResumeDecision.Refused(
                outcome,
                TransferStorageError.PermissionRevoked("read"),
            )
            FingerprintOutcome.UNAVAILABLE -> SourceResumeDecision.Refused(
                outcome,
                if (current is SourceFingerprintResult.Unavailable &&
                    current.error.category == TransferStorageErrorCategory.CANCELLED
                ) TransferStorageError.Cancelled else TransferStorageError.ProviderFailure("document_provider"),
            )
            FingerprintOutcome.STRONG_MATCH,
            FingerprintOutcome.WEAK_MATCH,
            FingerprintOutcome.MISSING_METADATA,
            -> if (expectedSha256 == null) {
                SourceResumeDecision.Refused(outcome, TransferStorageError.StateConflict("expected_sha256_required"))
            } else {
                SourceResumeDecision.Ready(outcome, stored, expectedSha256)
            }
        }
    }
}
