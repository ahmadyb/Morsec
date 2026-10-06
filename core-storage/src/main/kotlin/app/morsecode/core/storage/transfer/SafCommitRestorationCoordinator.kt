package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.identity.PartialIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Explicit, caller-controlled cancellation signal. No restoration is scheduled automatically. */
public fun interface SafCommitRestorationCancellation {
    public fun isCancelled(): Boolean

    public companion object {
        public val Never: SafCommitRestorationCancellation = SafCommitRestorationCancellation { false }
    }
}

public enum class SafCommitRestorationDisposition {
    COMMITTED,
    CLEANUP_PENDING,
    ALREADY_TERMINAL,
    SKIPPED,
    USER_DECISION_REQUIRED,
    CANCELLED,
    WAITING_FOR_TRANSFER,
    WAITING_FOR_GRANT,
    WAITING_FOR_STORAGE,
    RETRYABLE_FAILURE,
    RECONCILIATION_REQUIRED,
    INVALID_PERSISTED_STATE,
    PERSISTENCE_UNAVAILABLE,
    BUDGET_EXHAUSTED,
    MISSING_CHECKPOINT,
    REVISION_CONFLICT_RELOADED,
    UNKNOWN_FAILURE,
}

public enum class SafCommitRestorationStopReason {
    SCAN_COMPLETE,
    CHECKPOINT_LIMIT,
    CALLER_CANCELLED,
    RESOURCE_BUDGET_EXHAUSTED,
    DISCOVERY_UNAVAILABLE,
}

public enum class SafCommitRestorationRetryTrigger {
    NEXT_EXPLICIT_RUN,
    GRANT_RESTORED,
    TRANSFER_QUIESCENT,
    STORAGE_AVAILABLE,
    BUDGET_AVAILABLE,
}

public enum class SafCommitRestorationBudgetStop {
    NONE,
    PROVIDER_MUTATIONS,
    CLEANUP_ATTEMPTS,
    CHECKPOINT_OBSERVATIONS,
}

/** Opaque continuation: callers can pass it back but cannot inspect a persisted identifier. */
public class SafCommitRestorationCursor internal constructor(
    internal val storageCursor: SafCommitDiscoveryCursor,
) {
    override fun toString(): String = "SafCommitRestorationCursor([redacted])"

    override fun equals(other: Any?): Boolean =
        other is SafCommitRestorationCursor && storageCursor == other.storageCursor

    override fun hashCode(): Int = storageCursor.hashCode()
}

/** Opaque, in-memory retry handle. It contains no timestamp and never prints its checkpoint key. */
public class SafCommitRestorationRetryPlan internal constructor(
    public val checkpointNumber: Int,
    public val trigger: SafCommitRestorationRetryTrigger,
    internal val commitId: PartialIdentity,
) {
    override fun toString(): String =
        "SafCommitRestorationRetryPlan(checkpointNumber=$checkpointNumber, trigger=$trigger, [identity redacted])"
}

/** A redacted typed error summary. Provider messages, paths and URIs are not represented here. */
public data class SafCommitRestorationFailure(
    public val category: String,
    public val code: String,
) {
    init {
        require(category.matches(SAFE_CODE))
        require(code.matches(SAFE_CODE))
    }

    override fun toString(): String = "SafCommitRestorationFailure(category=$category, code=$code)"

    private companion object {
        val SAFE_CODE = Regex("[a-z0-9_]{1,64}")
    }
}

/** One checkpoint's structured, redacted outcome. No checkpoint, URI, name, digest or raw ID leaks. */
public data class SafCommitRestorationCheckpointReport(
    public val checkpointNumber: Int,
    public val disposition: SafCommitRestorationDisposition,
    public val phaseId: String?,
    public val pendingCleanup: Set<SafCleanupPending>,
    public val failure: SafCommitRestorationFailure?,
    public val retryTrigger: SafCommitRestorationRetryTrigger?,
) {
    init {
        require(checkpointNumber > 0)
        require(phaseId == null || phaseId.matches(Regex("[a-z0-9_]{1,64}")))
    }
}

public data class SafCommitRestorationBudgetSummary(
    public val providerMutations: Int,
    public val cleanupAttempts: Int,
    public val reconciliationObservations: Int,
    public val stoppedBy: SafCommitRestorationBudgetStop,
)

/** Bounded result of one explicit run. Opaque handles may be passed to a later explicit call. */
public data class SafCommitRestorationRunReport(
    public val stopReason: SafCommitRestorationStopReason,
    public val processedCheckpoints: Int,
    public val checkpoints: List<SafCommitRestorationCheckpointReport>,
    public val retryPlans: List<SafCommitRestorationRetryPlan>,
    public val unprocessedWork: List<SafCommitRestorationRetryPlan>,
    public val continuationCursor: SafCommitRestorationCursor?,
    public val moreCheckpointsAvailable: Boolean?,
    public val failure: SafCommitRestorationFailure?,
    public val budget: SafCommitRestorationBudgetSummary,
) {
    init {
        require(processedCheckpoints >= checkpoints.size)
        require(retryPlans.size <= processedCheckpoints)
        require(unprocessedWork.size <= SafCommitRestorationPolicy.MAX_PAGE_SIZE)
    }

    override fun toString(): String =
        "SafCommitRestorationRunReport(stop=$stopReason, processed=$processedCheckpoints, " +
            "retryPlans=${retryPlans.size}, unprocessed=${unprocessedWork.size}, " +
            "more=$moreCheckpointsAvailable, budget=$budget, [identities redacted])"
}

/**
 * Callable production restoration domain. Construction is inert: only [restore] reads persisted
 * candidates, resolves exact grants, or enters the existing SAF recovery state machine.
 */
public class SafCommitRestorationCoordinator internal constructor(
    private val discovery: SafCommitCheckpointDiscovery,
    private val grantResolver: SafCommitGrantResolver,
    private val recoveryFactory: SafCommitRecoveryCoordinatorFactory,
    private val policy: SafCommitRestorationPolicy,
    private val dispatcher: CoroutineDispatcher,
) {

    /**
     * Runs one bounded pass. A retry handle is optional and targets exactly one previously
     * reported checkpoint; [continuationCursor] continues stable commit-id paging. No worker,
     * timer, network transfer, prompt, or self-scheduling is started.
     */
    public suspend fun restore(
        continuationCursor: SafCommitRestorationCursor? = null,
        retry: SafCommitRestorationRetryPlan? = null,
        cancellation: SafCommitRestorationCancellation = SafCommitRestorationCancellation.Never,
    ): SafCommitRestorationRunReport = withContext(dispatcher) {
        val parentJob = currentCoroutineContext()[Job]
        val cancelled: () -> Boolean = {
            try {
                cancellation.isCancelled() || parentJob?.isActive == false
            } catch (_: Exception) {
                true
            }
        }
        val executionBudget = SafCommitExecutionBudget(policy, cancelled)
        val reports = ArrayList<SafCommitRestorationCheckpointReport>(policy.maxCheckpointsPerRun)
        val retryPlans = ArrayList<SafCommitRestorationRetryPlan>(policy.maxReturnedRetryPlans)
        val unprocessed = ArrayList<SafCommitRestorationRetryPlan>(policy.pageSize)
        val visited = HashSet<PartialIdentity>(policy.maxCheckpointsPerRun)
        var ordinal = 0
        var processed = 0
        var cursor = continuationCursor?.storageCursor
        var stop = SafCommitRestorationStopReason.SCAN_COMPLETE
        var more: Boolean? = false
        var globalFailure: SafCommitRestorationFailure? = null

        fun recordProcessed(result: ProcessedCheckpoint) {
            reports += result.report
            result.retryPlan?.let(retryPlans::add)
        }

        if (retry != null) {
            if (cancelled()) {
                stop = SafCommitRestorationStopReason.CALLER_CANCELLED
                unprocessed += retry
                more = true
            } else {
                ordinal++
                processed++
                visited += retry.commitId
                val result = processRetry(retry, ordinal, executionBudget, cancelled)
                recordProcessed(result)
                if (executionBudget.usage().stop != null) {
                    stop = SafCommitRestorationStopReason.RESOURCE_BUDGET_EXHAUSTED
                    more = true
                } else if (result.report.disposition == SafCommitRestorationDisposition.CANCELLED && cancelled()) {
                    stop = SafCommitRestorationStopReason.CALLER_CANCELLED
                    more = true
                }
            }
        }

        if (stop == SafCommitRestorationStopReason.SCAN_COMPLETE &&
            processed >= policy.maxCheckpointsPerRun
        ) {
            stop = SafCommitRestorationStopReason.CHECKPOINT_LIMIT
            more = true
        }

        if (stop == SafCommitRestorationStopReason.SCAN_COMPLETE) {
            while (processed < policy.maxCheckpointsPerRun) {
                if (cancelled()) {
                    stop = SafCommitRestorationStopReason.CALLER_CANCELLED
                    more = true
                    break
                }
                val available = policy.maxCheckpointsPerRun - processed
                val pageLimit = minOf(policy.pageSize, available)
                val page = when (val read = discovery.restorationPage(cursor, pageLimit)) {
                    is SafCommitDiscoveryPageResult.Page -> read.value
                    is SafCommitDiscoveryPageResult.Failed -> {
                        stop = SafCommitRestorationStopReason.DISCOVERY_UNAVAILABLE
                        globalFailure = failureSummary(read.error)
                        more = true
                        break
                    }
                }
                if (page.candidates.isEmpty()) {
                    cursor = null
                    more = false
                    break
                }

                var stoppedWithinPage = false
                for ((index, candidate) in page.candidates.withIndex()) {
                    if (processed >= policy.maxCheckpointsPerRun) {
                        stop = SafCommitRestorationStopReason.CHECKPOINT_LIMIT
                        more = index < page.candidates.size || page.hasMore
                        addUnprocessed(page.candidates.drop(index), ordinal + 1, unprocessed)
                        stoppedWithinPage = true
                        break
                    }
                    if (cancelled()) {
                        stop = SafCommitRestorationStopReason.CALLER_CANCELLED
                        more = true
                        addUnprocessed(page.candidates.drop(index), ordinal + 1, unprocessed)
                        stoppedWithinPage = true
                        break
                    }
                    val commitId = candidate.commitId
                    if (commitId != null && !visited.add(commitId)) {
                        cursor = candidate.cursorAfter
                        continue
                    }
                    ordinal++
                    processed++
                    val result = processCandidate(candidate, ordinal, executionBudget, cancelled)
                    recordProcessed(result)
                    cursor = candidate.cursorAfter

                    if (executionBudget.usage().stop != null) {
                        stop = SafCommitRestorationStopReason.RESOURCE_BUDGET_EXHAUSTED
                        more = index < page.candidates.lastIndex || page.hasMore
                        addUnprocessed(page.candidates.drop(index + 1), ordinal + 1, unprocessed)
                        stoppedWithinPage = true
                        break
                    }
                    if (result.report.disposition == SafCommitRestorationDisposition.CANCELLED && cancelled()) {
                        stop = SafCommitRestorationStopReason.CALLER_CANCELLED
                        more = index < page.candidates.lastIndex || page.hasMore
                        addUnprocessed(page.candidates.drop(index + 1), ordinal + 1, unprocessed)
                        stoppedWithinPage = true
                        break
                    }
                }
                if (stoppedWithinPage) break
                cursor = page.nextCursor
                if (!page.hasMore) {
                    cursor = null
                    more = false
                    break
                }
                more = true
            }
            if (processed >= policy.maxCheckpointsPerRun && more != false) {
                stop = SafCommitRestorationStopReason.CHECKPOINT_LIMIT
                more = true
            }
        }

        SafCommitRestorationRunReport(
            stopReason = stop,
            processedCheckpoints = processed,
            checkpoints = reports.toList(),
            retryPlans = retryPlans.toList(),
            unprocessedWork = unprocessed.toList(),
            continuationCursor = cursor?.let(::SafCommitRestorationCursor),
            moreCheckpointsAvailable = more,
            failure = globalFailure,
            budget = executionBudget.usage().toPublicSummary(),
        )
    }

    private suspend fun processRetry(
        retry: SafCommitRestorationRetryPlan,
        ordinal: Int,
        budget: SafCommitExecutionBudget,
        cancelled: () -> Boolean,
    ): ProcessedCheckpoint {
        return when (val result = discovery.readForRestoration(retry.commitId)) {
            SafCommitDiscoveryReadResult.Missing -> ProcessedCheckpoint(
                report = report(
                    ordinal,
                    SafCommitRestorationDisposition.MISSING_CHECKPOINT,
                    null,
                    emptySet(),
                    null,
                    null,
                ),
                retryPlan = null,
            )
            is SafCommitDiscoveryReadResult.Failed -> errorResult(
                ordinal,
                retry.commitId,
                result.error,
                SafCommitRestorationDisposition.PERSISTENCE_UNAVAILABLE,
            )
            is SafCommitDiscoveryReadResult.Found -> processCandidate(result.candidate, ordinal, budget, cancelled)
        }
    }

    private suspend fun processCandidate(
        candidate: SafCommitDiscoveryCandidate,
        ordinal: Int,
        budget: SafCommitExecutionBudget,
        cancelled: () -> Boolean,
    ): ProcessedCheckpoint {
        val commitId = candidate.commitId
        candidate.error?.let { error ->
            val (classifiedDisposition, trigger) = classifyError(error)
            val disposition = classifiedDisposition ?: when (error) {
                TransferStorageError.Cancelled -> SafCommitRestorationDisposition.CANCELLED
                is TransferStorageError.StateConflict,
                is TransferStorageError.ContainmentUnknown,
                is TransferStorageError.Unsupported,
                is TransferStorageError.IntegrityMismatch,
                -> SafCommitRestorationDisposition.INVALID_PERSISTED_STATE
                else -> SafCommitRestorationDisposition.PERSISTENCE_UNAVAILABLE
            }
            return ProcessedCheckpoint(
                report = report(ordinal, disposition, null, emptySet(), error, trigger),
                retryPlan = commitId?.let { trigger?.let { why -> retryPlan(ordinal, why, it) } },
            )
        }
        val checkpoint = candidate.checkpoint
            ?: return ProcessedCheckpoint(
                report = report(
                    ordinal,
                    SafCommitRestorationDisposition.INVALID_PERSISTED_STATE,
                    null,
                    emptySet(),
                    TransferStorageError.StateConflict("checkpoint_missing_from_discovery"),
                    null,
                ),
                retryPlan = null,
            )
        val validationError = SafCommitCheckpointValidator.validate(checkpoint)
        if (validationError != null) {
            return ProcessedCheckpoint(
                report = report(
                    ordinal,
                    SafCommitRestorationDisposition.INVALID_PERSISTED_STATE,
                    checkpoint.phase.id,
                    checkpoint.pendingCleanup.map { it.type }.toSet(),
                    validationError,
                    null,
                ),
                retryPlan = null,
            )
        }
        if (candidate.transferActivity == SafCommitTransferActivity.MALFORMED) {
            return ProcessedCheckpoint(
                report = report(
                    ordinal,
                    SafCommitRestorationDisposition.INVALID_PERSISTED_STATE,
                    checkpoint.phase.id,
                    pendingTypes(checkpoint),
                    TransferStorageError.StateConflict("transfer_activity_malformed"),
                    null,
                ),
                retryPlan = null,
            )
        }
        if (candidate.transferActivity == SafCommitTransferActivity.UNAVAILABLE) {
            val trigger = SafCommitRestorationRetryTrigger.NEXT_EXPLICIT_RUN
            return ProcessedCheckpoint(
                report = report(
                    ordinal,
                    SafCommitRestorationDisposition.PERSISTENCE_UNAVAILABLE,
                    checkpoint.phase.id,
                    pendingTypes(checkpoint),
                    TransferStorageError.Io("transfer_activity_read"),
                    trigger,
                ),
                retryPlan = commitId?.let { retryPlan(ordinal, trigger, it) },
            )
        }
        if (candidate.transferActivity == SafCommitTransferActivity.ACTIVE_OR_RESUMABLE) {
            val trigger = SafCommitRestorationRetryTrigger.TRANSFER_QUIESCENT
            return ProcessedCheckpoint(
                report = report(
                    ordinal,
                    SafCommitRestorationDisposition.WAITING_FOR_TRANSFER,
                    checkpoint.phase.id,
                    pendingTypes(checkpoint),
                    null,
                    trigger,
                ),
                retryPlan = commitId?.let { retryPlan(ordinal, trigger, it) },
            )
        }
        if (isFullyCommitted(checkpoint)) {
            return ProcessedCheckpoint(
                report = report(
                    ordinal,
                    SafCommitRestorationDisposition.ALREADY_TERMINAL,
                    checkpoint.phase.id,
                    emptySet(),
                    null,
                    null,
                ),
                retryPlan = null,
            )
        }
        if ((checkpoint.phase == SafCommitCheckpointPhase.CANCELLED ||
                checkpoint.phase == SafCommitCheckpointPhase.CANCELLED_TEMPORARY_DELETE_OBSERVED) &&
            checkpoint.pendingCleanup.isEmpty()
        ) {
            return ProcessedCheckpoint(
                report = report(
                    ordinal,
                    SafCommitRestorationDisposition.CANCELLED,
                    checkpoint.phase.id,
                    emptySet(),
                    null,
                    null,
                ),
                retryPlan = null,
            )
        }
        if (cancelled()) {
            val trigger = SafCommitRestorationRetryTrigger.NEXT_EXPLICIT_RUN
            return ProcessedCheckpoint(
                report = report(
                    ordinal,
                    SafCommitRestorationDisposition.CANCELLED,
                    checkpoint.phase.id,
                    pendingTypes(checkpoint),
                    TransferStorageError.Cancelled,
                    trigger,
                ),
                retryPlan = commitId?.let { retryPlan(ordinal, trigger, it) },
            )
        }

        val grant = when (val resolution = try {
            grantResolver.resolve(checkpoint)
        } catch (cancelledException: CancellationException) {
            throw cancelledException
        } catch (_: Exception) {
            SafCommitGrantResolution.Unavailable(
                SafCommitGrantResolution.Code.DATABASE_QUERY_FAILED,
            )
        }) {
            is SafCommitGrantResolution.Available -> resolution.grant
            SafCommitGrantResolution.Revoked -> return ProcessedCheckpoint(
                report = report(
                    ordinal,
                    SafCommitRestorationDisposition.WAITING_FOR_GRANT,
                    checkpoint.phase.id,
                    pendingTypes(checkpoint),
                    TransferStorageError.PermissionRevoked("write"),
                    SafCommitRestorationRetryTrigger.GRANT_RESTORED,
                ),
                retryPlan = commitId?.let {
                    retryPlan(ordinal, SafCommitRestorationRetryTrigger.GRANT_RESTORED, it)
                },
            )
            is SafCommitGrantResolution.Malformed -> return ProcessedCheckpoint(
                report = report(
                    ordinal,
                    SafCommitRestorationDisposition.INVALID_PERSISTED_STATE,
                    checkpoint.phase.id,
                    pendingTypes(checkpoint),
                    TransferStorageError.StateConflict("grant_${resolution.code.name.lowercase()}"),
                    null,
                ),
                retryPlan = null,
            )
            is SafCommitGrantResolution.Unavailable -> {
                val trigger = SafCommitRestorationRetryTrigger.NEXT_EXPLICIT_RUN
                return ProcessedCheckpoint(
                    report = report(
                        ordinal,
                        SafCommitRestorationDisposition.PERSISTENCE_UNAVAILABLE,
                        checkpoint.phase.id,
                        pendingTypes(checkpoint),
                        TransferStorageError.Io("grant_resolution"),
                        trigger,
                    ),
                    retryPlan = commitId?.let { retryPlan(ordinal, trigger, it) },
                )
            }
        }

        if (cancelled()) {
            val trigger = SafCommitRestorationRetryTrigger.NEXT_EXPLICIT_RUN
            return ProcessedCheckpoint(
                report = report(
                    ordinal,
                    SafCommitRestorationDisposition.CANCELLED,
                    checkpoint.phase.id,
                    pendingTypes(checkpoint),
                    TransferStorageError.Cancelled,
                    trigger,
                ),
                retryPlan = commitId?.let { retryPlan(ordinal, trigger, it) },
            )
        }

        budget.beginCheckpoint()
        val recovery = try {
            recoveryFactory.create(
                budget = budget,
                isCancelled = cancelled,
            ).resumeOrReconcile(checkpoint, grant)
        } catch (cancelledException: CancellationException) {
            throw cancelledException
        } catch (_: Exception) {
            return ProcessedCheckpoint(
                report = report(
                    ordinal,
                    SafCommitRestorationDisposition.UNKNOWN_FAILURE,
                    checkpoint.phase.id,
                    pendingTypes(checkpoint),
                    TransferStorageError.ProviderFailure("document_provider"),
                    SafCommitRestorationRetryTrigger.NEXT_EXPLICIT_RUN,
                ),
                retryPlan = commitId?.let {
                    retryPlan(ordinal, SafCommitRestorationRetryTrigger.NEXT_EXPLICIT_RUN, it)
                },
            )
        }

        val recoveryError = recovery.errorOrNull()
        if (recoveryError?.isRevisionConflict() == true) {
            return reloadAfterRevisionConflict(ordinal, checkpoint, recoveryError)
        }
        val stoppedByBudget = budget.usage().stop
        if (stoppedByBudget != null) {
            val latestCheckpoint = recovery.checkpointOrNull() ?: checkpoint
            val trigger = SafCommitRestorationRetryTrigger.BUDGET_AVAILABLE
            return ProcessedCheckpoint(
                report = report(
                    ordinal,
                    SafCommitRestorationDisposition.BUDGET_EXHAUSTED,
                    latestCheckpoint.phase.id,
                    pendingTypes(latestCheckpoint),
                    recoveryError,
                    trigger,
                ),
                retryPlan = retryPlan(ordinal, trigger, latestCheckpoint.commitId),
            )
        }
        return summarizeRecovery(ordinal, recovery)
    }

    private suspend fun reloadAfterRevisionConflict(
        ordinal: Int,
        original: SafCommitCheckpoint,
        error: TransferStorageError,
    ): ProcessedCheckpoint {
        return when (val latest = discovery.readForRestoration(original.commitId)) {
            SafCommitDiscoveryReadResult.Missing -> ProcessedCheckpoint(
                report = report(
                    ordinal,
                    SafCommitRestorationDisposition.MISSING_CHECKPOINT,
                    null,
                    emptySet(),
                    error,
                    null,
                ),
                retryPlan = null,
            )
            is SafCommitDiscoveryReadResult.Failed -> ProcessedCheckpoint(
                report = report(
                    ordinal,
                    SafCommitRestorationDisposition.PERSISTENCE_UNAVAILABLE,
                    null,
                    emptySet(),
                    latest.error,
                    SafCommitRestorationRetryTrigger.NEXT_EXPLICIT_RUN,
                ),
                retryPlan = retryPlan(
                    ordinal,
                    SafCommitRestorationRetryTrigger.NEXT_EXPLICIT_RUN,
                    original.commitId,
                ),
            )
            is SafCommitDiscoveryReadResult.Found -> {
                val candidate = latest.candidate
                val checkpoint = candidate.checkpoint
                when {
                    candidate.error != null -> ProcessedCheckpoint(
                        report = report(
                            ordinal,
                            SafCommitRestorationDisposition.INVALID_PERSISTED_STATE,
                            null,
                            emptySet(),
                            candidate.error,
                            null,
                        ),
                        retryPlan = null,
                    )
                    checkpoint == null -> ProcessedCheckpoint(
                        report = report(
                            ordinal,
                            SafCommitRestorationDisposition.INVALID_PERSISTED_STATE,
                            null,
                            emptySet(),
                            TransferStorageError.StateConflict("checkpoint_missing_from_reload"),
                            null,
                        ),
                        retryPlan = null,
                    )
                    isFullyCommitted(checkpoint) -> ProcessedCheckpoint(
                        report = report(
                            ordinal,
                            SafCommitRestorationDisposition.ALREADY_TERMINAL,
                            checkpoint.phase.id,
                            emptySet(),
                            error,
                            null,
                        ),
                        retryPlan = null,
                    )
                    candidate.transferActivity == SafCommitTransferActivity.ACTIVE_OR_RESUMABLE -> {
                        val trigger = SafCommitRestorationRetryTrigger.TRANSFER_QUIESCENT
                        ProcessedCheckpoint(
                            report = report(
                                ordinal,
                                SafCommitRestorationDisposition.WAITING_FOR_TRANSFER,
                                checkpoint.phase.id,
                                pendingTypes(checkpoint),
                                error,
                                trigger,
                            ),
                            retryPlan = retryPlan(ordinal, trigger, checkpoint.commitId),
                        )
                    }
                    else -> {
                        val trigger = SafCommitRestorationRetryTrigger.NEXT_EXPLICIT_RUN
                        ProcessedCheckpoint(
                            report = report(
                                ordinal,
                                SafCommitRestorationDisposition.REVISION_CONFLICT_RELOADED,
                                checkpoint.phase.id,
                                pendingTypes(checkpoint),
                                error,
                                trigger,
                            ),
                            retryPlan = retryPlan(ordinal, trigger, checkpoint.commitId),
                        )
                    }
                }
            }
        }
    }

    private fun summarizeRecovery(
        ordinal: Int,
        recovery: SafCommitRecoveryOutcome,
    ): ProcessedCheckpoint {
        val checkpoint = recovery.checkpointOrNull()
        val error = recovery.errorOrNull()
        val pending = checkpoint?.let(::pendingTypes).orEmpty()
        val phase = checkpoint?.phase?.id
        val disposition: SafCommitRestorationDisposition
        val trigger: SafCommitRestorationRetryTrigger?

        when (recovery) {
            is SafCommitRecoveryOutcome.Committed -> when {
                isFullyCommitted(recovery.checkpoint) -> {
                    disposition = SafCommitRestorationDisposition.COMMITTED
                    trigger = null
                }
                recovery.checkpoint.pendingCleanup.isNotEmpty() || !recovery.checkpoint.stagingReleased -> {
                    disposition = SafCommitRestorationDisposition.CLEANUP_PENDING
                    trigger = SafCommitRestorationRetryTrigger.NEXT_EXPLICIT_RUN
                }
                else -> {
                    disposition = SafCommitRestorationDisposition.RECONCILIATION_REQUIRED
                    trigger = null
                }
            }
            is SafCommitRecoveryOutcome.Skipped -> {
                disposition = SafCommitRestorationDisposition.SKIPPED
                trigger = null
            }
            is SafCommitRecoveryOutcome.ReadyToResume -> {
                disposition = SafCommitRestorationDisposition.USER_DECISION_REQUIRED
                trigger = null
            }
            is SafCommitRecoveryOutcome.Cancelled -> {
                if (recovery.checkpoint.pendingCleanup.isNotEmpty()) {
                    disposition = SafCommitRestorationDisposition.CLEANUP_PENDING
                    trigger = SafCommitRestorationRetryTrigger.NEXT_EXPLICIT_RUN
                } else {
                    disposition = SafCommitRestorationDisposition.CANCELLED
                    trigger = null
                }
            }
            is SafCommitRecoveryOutcome.ReconciliationRequired,
            is SafCommitRecoveryOutcome.Failed,
            -> {
                val classified = classifyError(error)
                disposition = when {
                    error == TransferStorageError.Cancelled -> SafCommitRestorationDisposition.CANCELLED
                    classified.first != null -> classified.first
                    pending.isNotEmpty() -> SafCommitRestorationDisposition.CLEANUP_PENDING
                    else -> SafCommitRestorationDisposition.RECONCILIATION_REQUIRED
                }
                trigger = classified.second
            }
        }
        return ProcessedCheckpoint(
            report = report(ordinal, disposition, phase, pending, error, trigger),
            retryPlan = if (trigger == null || checkpoint == null) null else retryPlan(
                ordinal,
                trigger,
                checkpoint.commitId,
            ),
        )
    }

    private fun errorResult(
        ordinal: Int,
        commitId: PartialIdentity,
        error: TransferStorageError,
        defaultDisposition: SafCommitRestorationDisposition,
    ): ProcessedCheckpoint {
        val (disposition, trigger) = classifyError(error)
        val effectiveDisposition = disposition ?: when (error) {
            is TransferStorageError.StateConflict,
            is TransferStorageError.ContainmentUnknown,
            is TransferStorageError.Unsupported,
            is TransferStorageError.IntegrityMismatch,
            -> SafCommitRestorationDisposition.INVALID_PERSISTED_STATE
            else -> defaultDisposition
        }
        return ProcessedCheckpoint(
            report = report(ordinal, effectiveDisposition, null, emptySet(), error, trigger),
            retryPlan = trigger?.let { retryPlan(ordinal, it, commitId) },
        )
    }

    private fun classifyError(
        error: TransferStorageError?,
    ): Pair<SafCommitRestorationDisposition?, SafCommitRestorationRetryTrigger?> {
        if (error == null) return null to null
        return when (error) {
            is TransferStorageError.PermissionRevoked ->
                SafCommitRestorationDisposition.WAITING_FOR_GRANT to SafCommitRestorationRetryTrigger.GRANT_RESTORED
            is TransferStorageError.StorageFull,
            is TransferStorageError.InsufficientSpace,
            -> SafCommitRestorationDisposition.WAITING_FOR_STORAGE to SafCommitRestorationRetryTrigger.STORAGE_AVAILABLE
            is TransferStorageError.Io,
            is TransferStorageError.ProviderFailure,
            -> SafCommitRestorationDisposition.RETRYABLE_FAILURE to SafCommitRestorationRetryTrigger.NEXT_EXPLICIT_RUN
            TransferStorageError.Cancelled ->
                SafCommitRestorationDisposition.CANCELLED to SafCommitRestorationRetryTrigger.NEXT_EXPLICIT_RUN
            is TransferStorageError.StateConflict -> if (error.isRevisionConflict()) {
                SafCommitRestorationDisposition.REVISION_CONFLICT_RELOADED to
                    SafCommitRestorationRetryTrigger.NEXT_EXPLICIT_RUN
            } else {
                null to null
            }
            is TransferStorageError.NotFound,
            is TransferStorageError.ContainmentUnknown,
            is TransferStorageError.Unsupported,
            is TransferStorageError.IntegrityMismatch,
            is TransferStorageError.ZeroProgress,
            -> SafCommitRestorationDisposition.RECONCILIATION_REQUIRED to null
        }
    }

    private fun report(
        ordinal: Int,
        disposition: SafCommitRestorationDisposition,
        phaseId: String?,
        pending: Set<SafCleanupPending>,
        error: TransferStorageError?,
        retryTrigger: SafCommitRestorationRetryTrigger?,
    ): SafCommitRestorationCheckpointReport = SafCommitRestorationCheckpointReport(
        checkpointNumber = ordinal,
        disposition = disposition,
        phaseId = phaseId,
        pendingCleanup = pending,
        failure = error?.let(::failureSummary),
        retryTrigger = retryTrigger,
    )

    private fun failureSummary(error: TransferStorageError): SafCommitRestorationFailure {
        val code = when (error) {
            is TransferStorageError.PermissionRevoked -> "permission_revoked"
            is TransferStorageError.NotFound -> error.subject
            is TransferStorageError.Io -> error.operation
            is TransferStorageError.ProviderFailure -> error.provider
            is TransferStorageError.InsufficientSpace -> "insufficient_space"
            is TransferStorageError.StorageFull -> error.operation
            is TransferStorageError.StateConflict -> error.reason
            is TransferStorageError.ContainmentUnknown -> error.reason
            is TransferStorageError.Unsupported -> error.capability
            TransferStorageError.Cancelled -> "cancelled"
            is TransferStorageError.IntegrityMismatch -> error.subject
            is TransferStorageError.ZeroProgress -> "zero_progress"
        }.takeIf { it.matches(SAFE_CODE) } ?: "unspecified"
        return SafCommitRestorationFailure(error.category.id, code)
    }

    private fun retryPlan(
        ordinal: Int,
        trigger: SafCommitRestorationRetryTrigger,
        commitId: PartialIdentity,
    ): SafCommitRestorationRetryPlan = SafCommitRestorationRetryPlan(ordinal, trigger, commitId)

    private fun addUnprocessed(
        candidates: List<SafCommitDiscoveryCandidate>,
        firstOrdinal: Int,
        destination: MutableList<SafCommitRestorationRetryPlan>,
    ) {
        candidates.asSequence()
            .mapNotNull { it.commitId }
            .take(policy.pageSize - destination.size)
            .forEachIndexed { index, commitId ->
                destination += retryPlan(
                    ordinal = firstOrdinal + index,
                    trigger = SafCommitRestorationRetryTrigger.NEXT_EXPLICIT_RUN,
                    commitId = commitId,
                )
            }
    }

    private fun SafCommitBudgetUsage.toPublicSummary(): SafCommitRestorationBudgetSummary =
        SafCommitRestorationBudgetSummary(
            providerMutations = providerMutations,
            cleanupAttempts = cleanupAttempts,
            reconciliationObservations = reconciliationObservations,
            stoppedBy = when (stop) {
                null -> SafCommitRestorationBudgetStop.NONE
                SafCommitBudgetStop.PROVIDER_MUTATIONS -> SafCommitRestorationBudgetStop.PROVIDER_MUTATIONS
                SafCommitBudgetStop.CLEANUP_ATTEMPTS -> SafCommitRestorationBudgetStop.CLEANUP_ATTEMPTS
                SafCommitBudgetStop.CHECKPOINT_OBSERVATIONS -> SafCommitRestorationBudgetStop.CHECKPOINT_OBSERVATIONS
            },
        )

    private fun SafCommitRecoveryOutcome.checkpointOrNull(): SafCommitCheckpoint? = when (this) {
        is SafCommitRecoveryOutcome.ReadyToResume -> checkpoint
        is SafCommitRecoveryOutcome.Skipped -> checkpoint
        is SafCommitRecoveryOutcome.Committed -> checkpoint
        is SafCommitRecoveryOutcome.Cancelled -> checkpoint
        is SafCommitRecoveryOutcome.ReconciliationRequired -> checkpoint
        is SafCommitRecoveryOutcome.Failed -> checkpoint
    }

    private fun SafCommitRecoveryOutcome.errorOrNull(): TransferStorageError? = when (this) {
        is SafCommitRecoveryOutcome.ReconciliationRequired -> error
        is SafCommitRecoveryOutcome.Failed -> error
        else -> null
    }

    private fun TransferStorageError.isRevisionConflict(): Boolean =
        this is TransferStorageError.StateConflict && reason in REVISION_CONFLICT_CODES

    private fun isFullyCommitted(checkpoint: SafCommitCheckpoint): Boolean =
        checkpoint.phase == SafCommitCheckpointPhase.COMMITTED &&
            checkpoint.pendingCleanup.isEmpty() && checkpoint.stagingReleased

    private fun pendingTypes(checkpoint: SafCommitCheckpoint): Set<SafCleanupPending> =
        checkpoint.pendingCleanup.map { it.type }.toSet()

    private data class ProcessedCheckpoint(
        val report: SafCommitRestorationCheckpointReport,
        val retryPlan: SafCommitRestorationRetryPlan?,
    )

    private companion object {
        val SAFE_CODE = Regex("[a-z0-9_]{1,64}")
        val REVISION_CONFLICT_CODES = setOf(
            "stale_journal_revision",
            "journal_revision_invalid",
            "journal_revision_exhausted",
        )
    }
}
