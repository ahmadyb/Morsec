package app.morsecode.core.storage.transfer

/** Caller-injected resource limits for one explicit restoration invocation. */
public data class SafCommitRestorationPolicy(
    public val maxCheckpointsPerRun: Int = 32,
    public val pageSize: Int = 16,
    public val maxProviderMutationsPerRun: Int = 24,
    public val maxCleanupAttemptsPerRun: Int = 12,
    public val maxReconciliationObservationsPerCheckpoint: Int = 24,
    public val maxReturnedRetryPlans: Int = 32,
) {
    init {
        require(maxCheckpointsPerRun in 1..MAX_CHECKPOINTS_PER_RUN)
        require(pageSize in 1..MAX_PAGE_SIZE)
        require(maxProviderMutationsPerRun in 0..MAX_PROVIDER_MUTATIONS_PER_RUN)
        require(maxCleanupAttemptsPerRun in 0..MAX_CLEANUP_ATTEMPTS_PER_RUN)
        require(maxReconciliationObservationsPerCheckpoint in 0..MAX_OBSERVATIONS_PER_CHECKPOINT)
        require(maxReturnedRetryPlans in maxCheckpointsPerRun..MAX_RETURNED_RETRY_PLANS) {
            "retry-plan capacity must cover every checkpoint processed in one run"
        }
    }

    public companion object {
        public const val MAX_CHECKPOINTS_PER_RUN: Int = 256
        public const val MAX_PAGE_SIZE: Int = 100
        public const val MAX_PROVIDER_MUTATIONS_PER_RUN: Int = 512
        public const val MAX_CLEANUP_ATTEMPTS_PER_RUN: Int = 256
        public const val MAX_OBSERVATIONS_PER_CHECKPOINT: Int = 512
        public const val MAX_RETURNED_RETRY_PLANS: Int = 256
    }
}

/** Safe reason an execution budget stopped new provider work. */
internal enum class SafCommitBudgetStop {
    PROVIDER_MUTATIONS,
    CLEANUP_ATTEMPTS,
    CHECKPOINT_OBSERVATIONS,
}

internal data class SafCommitBudgetUsage(
    val providerMutations: Int,
    val cleanupAttempts: Int,
    val reconciliationObservations: Int,
    val stop: SafCommitBudgetStop?,
)

/**
 * Per-run accounting shared by the gateway and staging adapters. Recovery is synchronous while
 * this budget is active; counters are synchronized so the stop remains safe if a caller cancels
 * from another thread.
 */
internal class SafCommitExecutionBudget(
    private val policy: SafCommitRestorationPolicy,
    private val isCancelled: () -> Boolean,
) {
    private var mutations = 0
    private var cleanupAttempts = 0
    private var observations = 0
    private var observationsForCheckpoint = 0
    private var stop: SafCommitBudgetStop? = null

    @Synchronized
    fun beginCheckpoint() {
        observationsForCheckpoint = 0
    }

    @Synchronized
    fun usage(): SafCommitBudgetUsage = SafCommitBudgetUsage(
        providerMutations = mutations,
        cleanupAttempts = cleanupAttempts,
        reconciliationObservations = observations,
        stop = stop,
    )

    fun cancellationError(): TransferStorageError? = try {
        if (isCancelled()) TransferStorageError.Cancelled else null
    } catch (_: Exception) {
        TransferStorageError.Cancelled
    }

    @Synchronized
    fun reserveMutation(): TransferStorageError? {
        cancellationError()?.let { return it }
        if (mutations >= policy.maxProviderMutationsPerRun) {
            stop = SafCommitBudgetStop.PROVIDER_MUTATIONS
            return TransferStorageError.StateConflict("restoration_mutation_budget_exhausted")
        }
        mutations++
        return null
    }

    @Synchronized
    fun reserveObservation(): TransferStorageError? {
        cancellationError()?.let { return it }
        if (observationsForCheckpoint >= policy.maxReconciliationObservationsPerCheckpoint) {
            stop = SafCommitBudgetStop.CHECKPOINT_OBSERVATIONS
            return TransferStorageError.StateConflict("restoration_observation_budget_exhausted")
        }
        observationsForCheckpoint++
        observations++
        return null
    }

    /** Reserves cleanup, mutation, and both mandatory pre/post-delete observations as one unit. */
    @Synchronized
    fun reserveDeleteAndReconcile(): TransferStorageError? {
        cancellationError()?.let { return it }
        if (cleanupAttempts >= policy.maxCleanupAttemptsPerRun) {
            stop = SafCommitBudgetStop.CLEANUP_ATTEMPTS
            return TransferStorageError.StateConflict("restoration_cleanup_budget_exhausted")
        }
        if (mutations >= policy.maxProviderMutationsPerRun) {
            stop = SafCommitBudgetStop.PROVIDER_MUTATIONS
            return TransferStorageError.StateConflict("restoration_mutation_budget_exhausted")
        }
        if (observationsForCheckpoint > policy.maxReconciliationObservationsPerCheckpoint - 2) {
            stop = SafCommitBudgetStop.CHECKPOINT_OBSERVATIONS
            return TransferStorageError.StateConflict("restoration_observation_budget_exhausted")
        }
        cleanupAttempts++
        mutations++
        observationsForCheckpoint += 2
        observations += 2
        return null
    }

    @Synchronized
    fun reserveDeleteRequest(): TransferStorageError? {
        cancellationError()?.let { return it }
        if (cleanupAttempts >= policy.maxCleanupAttemptsPerRun) {
            stop = SafCommitBudgetStop.CLEANUP_ATTEMPTS
            return TransferStorageError.StateConflict("restoration_cleanup_budget_exhausted")
        }
        if (mutations >= policy.maxProviderMutationsPerRun) {
            stop = SafCommitBudgetStop.PROVIDER_MUTATIONS
            return TransferStorageError.StateConflict("restoration_mutation_budget_exhausted")
        }
        cleanupAttempts++
        mutations++
        return null
    }

    @Synchronized
    fun reserveStagingCleanup(): TransferStorageError? {
        cancellationError()?.let { return it }
        if (cleanupAttempts >= policy.maxCleanupAttemptsPerRun) {
            stop = SafCommitBudgetStop.CLEANUP_ATTEMPTS
            return TransferStorageError.StateConflict("restoration_cleanup_budget_exhausted")
        }
        cleanupAttempts++
        return null
    }
}
