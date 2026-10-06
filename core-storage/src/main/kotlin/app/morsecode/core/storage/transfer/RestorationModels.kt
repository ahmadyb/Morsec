package app.morsecode.core.storage.transfer

import java.security.MessageDigest

/**
 * Per-invocation limits. The coordinator never sleeps, schedules a retry, or
 * stores retry timestamps. The elapsed bound is checked between synchronous
 * provider operations; an already-open bounded copy is allowed to reach its
 * existing coordinator boundary before the budget stops later operations.
 */
public data class RestorationExecutionPolicy(
    public val maximumCheckpointsPerRun: Int = 16,
    public val discoveryPageSize: Int = 8,
    public val maximumProviderMutations: Int = 128,
    public val maximumCleanupAttempts: Int = 24,
    public val maximumReconciliationObservations: Int = 1_024,
    public val maximumRevisionConflictReclassifications: Int = 1,
    public val maximumElapsedNanos: Long? = 30_000_000_000L,
) {
    init {
        require(maximumCheckpointsPerRun in 1..MAX_CHECKPOINTS_PER_RUN) {
            "checkpoint bound must be within the supported range"
        }
        require(discoveryPageSize in 1..MAX_DISCOVERY_PAGE_SIZE) {
            "discovery page size must be within the supported bound"
        }
        require(maximumProviderMutations in 0..MAX_PROVIDER_MUTATIONS_PER_RUN) {
            "provider mutation bound must be within the supported range"
        }
        require(maximumCleanupAttempts in 0..MAX_CLEANUP_ATTEMPTS_PER_RUN) {
            "cleanup bound must be within the supported range"
        }
        require(maximumReconciliationObservations in 0..MAX_RECONCILIATION_OBSERVATIONS_PER_RUN) {
            "observation bound must be within the supported range"
        }
        require(maximumRevisionConflictReclassifications in 0..MAX_REVISION_CONFLICT_RECLASSIFICATIONS) {
            "conflict reclassification bound must be within the supported range"
        }
        require(maximumElapsedNanos == null || maximumElapsedNanos >= 0L) {
            "elapsed-time bound must not be negative"
        }
    }

    public companion object {
        public const val MAX_DISCOVERY_PAGE_SIZE: Int = 128
        public const val MAX_CHECKPOINTS_PER_RUN: Int = 256
        public const val MAX_PROVIDER_MUTATIONS_PER_RUN: Int = 2_048
        public const val MAX_CLEANUP_ATTEMPTS_PER_RUN: Int = 256
        public const val MAX_RECONCILIATION_OBSERVATIONS_PER_RUN: Int = 8_192
        public const val MAX_REVISION_CONFLICT_RECLASSIFICATIONS: Int = 4
    }
}

/** Monotonic time only; wall-clock time is deliberately not part of retry state. */
public fun interface RestorationMonotonicClock {
    public fun nowNanos(): Long
}

public object SystemRestorationMonotonicClock : RestorationMonotonicClock {
    override fun nowNanos(): Long = System.nanoTime()
}

/** Typed, redacted terminal classification for one discovered checkpoint. */
public enum class RestorationClassification {
    RESUMED_AND_COMMITTED,
    ALREADY_COMPLETE,
    SKIPPED_BY_POLICY,
    RETRY_STAGING_CLEANUP,
    RETRY_PROVIDER_TEMPORARY_CLEANUP,
    RETRY_BACKUP_CLEANUP,
    AWAIT_PERMISSION,
    AWAIT_SCOPE_PERMISSION,
    AWAIT_EXTERNAL_STORAGE,
    AWAIT_USER_DECISION,
    RECONCILIATION_REQUIRED,
    MANUAL_RECONCILIATION_REQUIRED,
    INVALID_PERSISTED_STATE,
    REVISION_CONFLICT,
    CHECKPOINT_LOCKED,
    RETRYABLE_FAILURE,
    CANCELLED,
    BUDGET_EXHAUSTED,
}

/** Safe machine-readable reason. It never carries an exception or provider diagnostic. */
public enum class RestorationIssue {
    NONE,
    CHECKPOINT_MISSING,
    MALFORMED_CHECKPOINT,
    UNSUPPORTED_CHECKPOINT,
    GRANT_REVOKED,
    GRANT_SCOPE_INSUFFICIENT,
    GRANT_IDENTITY_MISMATCH,
    GRANT_RESOLUTION_FAILURE,
    JOURNAL_FAILURE,
    USER_DECISION_REQUIRED,
    STAGING_UNAVAILABLE,
    EXTERNAL_STORAGE_FULL,
    UNSUPPORTED_DESTINATION,
    INTEGRITY_MISMATCH,
    PROVIDER_STATE_UNRESOLVED,
    REVISION_CONFLICT,
    CHECKPOINT_ALREADY_ACTIVE,
    EXECUTION_LIMIT_REACHED,
    DISCOVERY_FAILURE,
    CALLER_CANCELLED,
}

/** Guidance for a caller that may choose to invoke restoration again explicitly. */
public enum class RestorationRetryGuidance {
    NONE,
    RETRY_ON_NEXT_EXPLICIT_INVOCATION,
    RECONCILE_ON_NEXT_EXPLICIT_INVOCATION,
    REAUTHORIZE_EXACT_TREE_THEN_RETRY,
    USER_DECISION_REQUIRED,
    MANUAL_REVIEW_REQUIRED,
    CONTINUE_WITH_RETURNED_CURSOR,
    RESCAN_FROM_BEGINNING,
}

/** Opaque keyset cursor. Raw commit ids are never exposed by its diagnostics. */
public class RestorationContinuation internal constructor(
    private val afterCommitId: String?,
) {
    init {
        require(afterCommitId == null || afterCommitId.length <= MAX_SAF_COMMIT_ID_LENGTH_CHARS) {
            "restoration cursor exceeds the supported key bound"
        }
    }

    internal fun rawAfterCommitId(): String? = afterCommitId

    override fun toString(): String = "RestorationContinuation([checkpoint cursor redacted])"
}

/** Stable pseudonymous label for correlating one report without exposing its key. */
public class RedactedRestorationCheckpointId internal constructor(rawCommitId: String) {
    // Valid commit ids are at most 128 ASCII characters. Avoid allocating or
    // hashing an attacker-sized corrupt SQLite key just to redact its report.
    private val shortFingerprint: String = if (rawCommitId.length <= MAX_SAF_COMMIT_ID_LENGTH_CHARS) {
        MessageDigest.getInstance("SHA-256")
            .digest(rawCommitId.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    } else {
        "invalid"
    }

    override fun toString(): String = "checkpoint#$shortFingerprint"

}

/** A single bounded outcome; no checkpoint, URI, path, grant, digest, or stack is retained. */
public data class RestorationCheckpointResult(
    public val checkpoint: RedactedRestorationCheckpointId,
    public val classification: RestorationClassification,
    public val issue: RestorationIssue,
    public val retryGuidance: RestorationRetryGuidance,
    public val pendingCleanup: Set<SafCleanupPending> = emptySet(),
    /** True only when an existing verified final is known to be delivered. */
    public val delivered: Boolean = false,
    /** False for a deferred candidate, a busy lock, or cancellation before completion. */
    public val processed: Boolean = true,
) {
    override fun toString(): String =
        "RestorationCheckpointResult(checkpoint=$checkpoint, classification=$classification, " +
            "issue=$issue, retry=$retryGuidance, cleanup=${pendingCleanup.map { it.id }.sorted()}, " +
            "delivered=$delivered, processed=$processed)"
}

/** Summary guidance; `automaticRetryScheduled` is always false by design. */
public class RestorationRetryPlan internal constructor(
    public val explicitRescanRecommended: Boolean,
    public val continueDiscoveryRecommended: Boolean,
    public val continuation: RestorationContinuation?,
) {
    public val automaticRetryScheduled: Boolean get() = false

    override fun toString(): String =
        "RestorationRetryPlan(explicitRescan=$explicitRescanRecommended, " +
            "continueDiscovery=$continueDiscoveryRecommended, automaticRetry=false)"
}

/** Redacted result of one manually invoked, bounded restoration pass. */
public class RestorationRunReport internal constructor(
    outcomes: List<RestorationCheckpointResult>,
    public val discoveredCheckpointCount: Int,
    public val remainingDiscoveredCheckpointCount: Int,
    public val hasMoreCandidates: Boolean,
    public val continuation: RestorationContinuation?,
    public val cancelled: Boolean,
    public val discoveryFailed: Boolean,
) {
    public val outcomes: List<RestorationCheckpointResult> = outcomes.toList()
    public val processedCheckpointCount: Int get() = outcomes.count { it.processed }
    public val completedCheckpointCount: Int
        get() = outcomes.count { it.delivered || it.classification == RestorationClassification.ALREADY_COMPLETE }
    public val pendingCleanupItemCount: Int get() = outcomes.sumOf { it.pendingCleanup.size }
    public val awaitingPermissionCount: Int
        get() = outcomes.count {
            it.classification == RestorationClassification.AWAIT_PERMISSION ||
                it.classification == RestorationClassification.AWAIT_SCOPE_PERMISSION
        }
    public val reconciliationRequiredCount: Int
        get() = outcomes.count {
            it.classification == RestorationClassification.RECONCILIATION_REQUIRED ||
                it.classification == RestorationClassification.MANUAL_RECONCILIATION_REQUIRED
        }
    public val failedCheckpointCount: Int
        get() = outcomes.count {
            it.classification == RestorationClassification.INVALID_PERSISTED_STATE ||
                it.classification == RestorationClassification.REVISION_CONFLICT ||
                it.classification == RestorationClassification.RETRYABLE_FAILURE
        }
    public val deferredCheckpointCount: Int get() = outcomes.count { !it.processed }
    public val retryPlan: RestorationRetryPlan = RestorationRetryPlan(
        explicitRescanRecommended = discoveryFailed || outcomes.any { it.retryGuidance in setOf(
            RestorationRetryGuidance.RETRY_ON_NEXT_EXPLICIT_INVOCATION,
            RestorationRetryGuidance.RECONCILE_ON_NEXT_EXPLICIT_INVOCATION,
            RestorationRetryGuidance.REAUTHORIZE_EXACT_TREE_THEN_RETRY,
            RestorationRetryGuidance.RESCAN_FROM_BEGINNING,
        ) },
        continueDiscoveryRecommended = hasMoreCandidates,
        continuation = continuation,
    )

    override fun toString(): String =
        "RestorationRunReport(discovered=$discoveredCheckpointCount, processed=$processedCheckpointCount, " +
            "completed=$completedCheckpointCount, cleanupPending=$pendingCleanupItemCount, " +
            "permissionWaits=$awaitingPermissionCount, reconciliation=$reconciliationRequiredCount, " +
            "failed=$failedCheckpointCount, deferred=$deferredCheckpointCount, " +
            "hasMore=$hasMoreCandidates, cancelled=$cancelled, discoveryFailed=$discoveryFailed)"
}
