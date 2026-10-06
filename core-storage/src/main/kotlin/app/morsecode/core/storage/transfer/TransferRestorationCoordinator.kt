package app.morsecode.core.storage.transfer

import android.net.Uri
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Explicit, caller-invoked bounded process-restoration pass for persisted SAF
 * destination commits. Construction and database opening are inert; this class
 * starts no worker, timer, service, network operation, or automatic recovery.
 */
public class TransferRestorationCoordinator(
    internal val discovery: SafCheckpointDiscovery,
    internal val journal: SafCommitJournal,
    internal val persistedGrantResolver: PersistedSafGrantResolver,
    /** Base gateway; each checkpoint run receives a budget-enforcing wrapper. */
    public val gateway: SafDocumentGateway,
    internal val staging: SafStaging,
    public val executionPolicy: RestorationExecutionPolicy = RestorationExecutionPolicy(),
    private val monotonicClock: RestorationMonotonicClock = SystemRestorationMonotonicClock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Runs one deterministic keyset page sequence within the configured bounds.
     * The returned cursor is opaque and only resumes discovery; a later full
     * invocation without a cursor is the retry path for earlier unresolved work.
     */
    public suspend fun restore(
        continuation: RestorationContinuation? = null,
    ): RestorationRunReport {
        val outcomes = mutableListOf<RestorationCheckpointResult>()
        val seen = mutableSetOf<String>()
        val budget = RestorationExecutionBudget(executionPolicy, monotonicClock, monotonicClock.nowNanos())
        val ownerJob = currentCoroutineContext()[Job]
        var cursor = continuation?.rawAfterCommitId()
        var discovered = 0
        var slotsUsed = 0
        var remainingKnown = 0
        var hasMore = false
        var cancelled = false
        var discoveryFailed = false
        var stop = false

        suspend fun deferFirst(
            ids: List<String>,
            startIndex: Int,
        ) {
            val remaining = ids.drop(startIndex).distinct()
            if (remaining.isNotEmpty()) {
                remainingKnown += remaining.size
                outcomes += deferredResult(remaining.first())
                hasMore = true
            }
        }

        suspend fun discoverOneDeferred(after: String?) {
            val page = try {
                discovery.page(afterCommitId = after, limit = 1)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                discoveryFailed = true
                hasMore = true
                return
            }
            if (page.commitIds.size > 1) {
                discoveryFailed = true
                hasMore = true
                return
            }
            discovered += page.commitIds.size
            val next = page.commitIds.firstOrNull()
            if (next != null) {
                if ((after != null && next <= after) || next in seen) {
                    discoveryFailed = true
                    hasMore = true
                    return
                }
                outcomes += deferredResult(next)
                remainingKnown++
                hasMore = true
            } else {
                hasMore = page.hasMore
                if (page.hasMore) {
                    discoveryFailed = true
                }
            }
        }

        try {
            while (!stop) {
                if (ownerJob?.isActive == false) {
                    cancelled = true
                    hasMore = true
                    break
                }
                if (slotsUsed >= executionPolicy.maximumCheckpointsPerRun) {
                    discoverOneDeferred(cursor)
                    break
                }
                if (budget.isBlocked()) {
                    discoverOneDeferred(cursor)
                    break
                }

                val requestLimit = minOf(
                    executionPolicy.discoveryPageSize,
                    executionPolicy.maximumCheckpointsPerRun - slotsUsed,
                )
                val page = try {
                    discovery.page(afterCommitId = cursor, limit = requestLimit)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    discoveryFailed = true
                    hasMore = true
                    break
                }
                if (page.commitIds.size > requestLimit || (page.commitIds.isEmpty() && page.hasMore)) {
                    discoveryFailed = true
                    hasMore = true
                    break
                }
                discovered += page.commitIds.size
                if (page.commitIds.isEmpty()) {
                    hasMore = false
                    break
                }

                val duplicateIds = page.commitIds.groupingBy { it }.eachCount()
                    .filterValues { it > 1 }
                    .keys
                val seenInPage = mutableSetOf<String>()
                var previousInPage = cursor
                var index = 0
                while (index < page.commitIds.size) {
                    val rawId = page.commitIds[index]
                    index++
                    if (rawId.length > MAX_SAF_COMMIT_ID_LENGTH_CHARS) {
                        outcomes += result(
                            rawId,
                            RestorationClassification.INVALID_PERSISTED_STATE,
                            RestorationIssue.MALFORMED_CHECKPOINT,
                            RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
                        )
                        slotsUsed++
                        discoveryFailed = true
                        hasMore = true
                        remainingKnown += page.commitIds.drop(index).distinct().size
                        stop = true
                        break
                    }
                    if (!seenInPage.add(rawId)) continue

                    if (slotsUsed >= executionPolicy.maximumCheckpointsPerRun || budget.isBlocked()) {
                        deferFirst(page.commitIds, index - 1)
                        stop = true
                        break
                    }
                    if (ownerJob?.isActive == false) {
                        cancelled = true
                        hasMore = true
                        val currentDeferred = page.commitIds.drop(index - 1).distinct()
                        remainingKnown += currentDeferred.size
                        currentDeferred.firstOrNull()?.let { raw ->
                            outcomes += result(
                                raw,
                                RestorationClassification.CANCELLED,
                                RestorationIssue.CALLER_CANCELLED,
                                RestorationRetryGuidance.RETRY_ON_NEXT_EXPLICIT_INVOCATION,
                                processed = false,
                            )
                        }
                        stop = true
                        break
                    }
                    if (previousInPage != null && rawId <= previousInPage) {
                        outcomes += result(
                            rawId,
                            RestorationClassification.INVALID_PERSISTED_STATE,
                            RestorationIssue.DISCOVERY_FAILURE,
                            RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
                        )
                        slotsUsed++
                        discoveryFailed = true
                        hasMore = true
                        stop = true
                        break
                    }
                    previousInPage = rawId
                    if (!seen.add(rawId)) {
                        outcomes += result(
                            rawId,
                            RestorationClassification.INVALID_PERSISTED_STATE,
                            RestorationIssue.DISCOVERY_FAILURE,
                            RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
                        )
                        slotsUsed++
                        discoveryFailed = true
                        hasMore = true
                        stop = true
                        break
                    }
                    if (rawId in duplicateIds) {
                        outcomes += result(
                            rawId,
                            RestorationClassification.INVALID_PERSISTED_STATE,
                            RestorationIssue.DISCOVERY_FAILURE,
                            RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
                        )
                        slotsUsed++
                        cursor = rawId
                        continue
                    }
                    if (!isValidCommitId(rawId)) {
                        outcomes += result(
                            rawId,
                            RestorationClassification.INVALID_PERSISTED_STATE,
                            RestorationIssue.MALFORMED_CHECKPOINT,
                            RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
                        )
                        slotsUsed++
                        cursor = rawId
                        continue
                    }

                    val previousCursor = cursor
                    val attempt = processCheckpoint(rawId, ownerJob, budget)
                    outcomes += attempt.result
                    slotsUsed++
                    if (attempt.mayAdvanceCursor) cursor = rawId
                    if (attempt.cancellationObserved) {
                        cancelled = true
                        hasMore = true
                        if (!attempt.result.delivered &&
                            attempt.result.classification != RestorationClassification.ALREADY_COMPLETE
                        ) {
                            cursor = previousCursor
                        }
                        remainingKnown += page.commitIds.drop(index).distinct().size
                        hasMore = hasMore || remainingKnown > 0 || page.hasMore
                        stop = true
                        break
                    }
                    if (budget.isBlocked()) {
                        if (index < page.commitIds.size) {
                            deferFirst(page.commitIds, index)
                        } else if (page.hasMore) {
                            discoverOneDeferred(cursor)
                        }
                        stop = true
                        break
                    }
                }
                if (stop) break
                if (slotsUsed >= executionPolicy.maximumCheckpointsPerRun) {
                    if (page.hasMore) discoverOneDeferred(cursor) else hasMore = false
                    break
                }
                if (!page.hasMore) {
                    hasMore = false
                    break
                }
            }
        } catch (_: CancellationException) {
            cancelled = true
            hasMore = true
        }

        val reportContinuation = if (hasMore) RestorationContinuation(cursor) else null
        return RestorationRunReport(
            outcomes = outcomes,
            discoveredCheckpointCount = discovered,
            remainingDiscoveredCheckpointCount = remainingKnown,
            hasMoreCandidates = hasMore,
            continuation = reportContinuation,
            cancelled = cancelled,
            discoveryFailed = discoveryFailed,
        )
    }

    private suspend fun processCheckpoint(
        rawId: String,
        ownerJob: Job?,
        budget: RestorationExecutionBudget,
    ): CheckpointAttempt {
        val identifier = PartialIdentity(rawId)
        val lease = ProcessLocalCheckpointLocks.shared.tryAcquire(rawId)
            ?: return CheckpointAttempt(
                result = result(
                    rawId,
                    RestorationClassification.CHECKPOINT_LOCKED,
                    RestorationIssue.CHECKPOINT_ALREADY_ACTIVE,
                    RestorationRetryGuidance.RESCAN_FROM_BEGINNING,
                    processed = false,
                ),
                mayAdvanceCursor = true,
            )

        try {
            var entry = when (val read = withContext(ioDispatcher) { journal.read(identifier) }) {
                SafCommitJournalRead.Missing -> return CheckpointAttempt(
                    result = result(
                        rawId,
                        RestorationClassification.MANUAL_RECONCILIATION_REQUIRED,
                        RestorationIssue.CHECKPOINT_MISSING,
                        RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
                    ),
                    mayAdvanceCursor = true,
                )
                is SafCommitJournalRead.Rejected -> return CheckpointAttempt(
                    result = classifyJournalReadError(rawId, read.error),
                    mayAdvanceCursor = true,
                )
                is SafCommitJournalRead.Found -> read.entry
            }

            var conflictReclassifications = 0
            while (true) {
                val checkpoint = entry.checkpoint
                val invalid = SafCommitCheckpointValidator.validate(checkpoint)
                val committedStateMalformed = checkpoint.phase == SafCommitCheckpointPhase.COMMITTED &&
                    (!isVerifiedFinal(checkpoint) || !checkpoint.stagingReleased || checkpoint.pendingCleanup.isNotEmpty())
                if (invalid != null || committedStateMalformed ||
                    checkpoint.commitId != identifier || checkpoint.stagingIdentity != identifier
                ) {
                    return CheckpointAttempt(
                        result = result(
                            rawId,
                            RestorationClassification.INVALID_PERSISTED_STATE,
                            if (invalid is TransferStorageError.Unsupported) RestorationIssue.UNSUPPORTED_CHECKPOINT
                            else RestorationIssue.MALFORMED_CHECKPOINT,
                            RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
                            pending = checkpoint.pendingCleanup.map { it.type }.toSet(),
                        ),
                        mayAdvanceCursor = true,
                    )
                }

                terminalCheckpointResult(rawId, checkpoint)?.let { terminal ->
                    return CheckpointAttempt(terminal, mayAdvanceCursor = true)
                }
                unsupportedCancelledCleanup(checkpoint)?.let { unsupported ->
                    return CheckpointAttempt(unsupported, mayAdvanceCursor = true)
                }

                val grant = when (val resolution = try {
                    persistedGrantResolver.resolve(checkpoint)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    return CheckpointAttempt(
                        result(
                            rawId,
                            RestorationClassification.RETRYABLE_FAILURE,
                            RestorationIssue.GRANT_RESOLUTION_FAILURE,
                            RestorationRetryGuidance.RETRY_ON_NEXT_EXPLICIT_INVOCATION,
                            pending = checkpoint.pendingCleanup.map { it.type }.toSet(),
                        ),
                        mayAdvanceCursor = true,
                    )
                }) {
                    is PersistedSafGrantResolution.Available -> resolution.grant
                    PersistedSafGrantResolution.Revoked -> return CheckpointAttempt(
                        result(
                            rawId,
                            RestorationClassification.AWAIT_PERMISSION,
                            RestorationIssue.GRANT_REVOKED,
                            RestorationRetryGuidance.REAUTHORIZE_EXACT_TREE_THEN_RETRY,
                            pending = checkpoint.pendingCleanup.map { it.type }.toSet(),
                        ),
                        mayAdvanceCursor = true,
                    )
                    PersistedSafGrantResolution.InsufficientScope -> return CheckpointAttempt(
                        result(
                            rawId,
                            RestorationClassification.AWAIT_SCOPE_PERMISSION,
                            RestorationIssue.GRANT_SCOPE_INSUFFICIENT,
                            RestorationRetryGuidance.REAUTHORIZE_EXACT_TREE_THEN_RETRY,
                            pending = checkpoint.pendingCleanup.map { it.type }.toSet(),
                        ),
                        mayAdvanceCursor = true,
                    )
                    PersistedSafGrantResolution.Malformed -> return CheckpointAttempt(
                        result(
                            rawId,
                            RestorationClassification.INVALID_PERSISTED_STATE,
                            RestorationIssue.MALFORMED_CHECKPOINT,
                            RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
                            pending = checkpoint.pendingCleanup.map { it.type }.toSet(),
                        ),
                        mayAdvanceCursor = true,
                    )
                    PersistedSafGrantResolution.Mismatched -> return CheckpointAttempt(
                        result(
                            rawId,
                            RestorationClassification.MANUAL_RECONCILIATION_REQUIRED,
                            RestorationIssue.GRANT_IDENTITY_MISMATCH,
                            RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
                            pending = checkpoint.pendingCleanup.map { it.type }.toSet(),
                        ),
                        mayAdvanceCursor = true,
                    )
                }
                if (!grantMatchesCheckpoint(grant, checkpoint)) {
                    return CheckpointAttempt(
                        result(
                            rawId,
                            RestorationClassification.MANUAL_RECONCILIATION_REQUIRED,
                            RestorationIssue.GRANT_IDENTITY_MISMATCH,
                            RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
                            pending = checkpoint.pendingCleanup.map { it.type }.toSet(),
                        ),
                        mayAdvanceCursor = true,
                    )
                }
                if (!grant.writable) {
                    return CheckpointAttempt(
                        result(
                            rawId,
                            RestorationClassification.AWAIT_SCOPE_PERMISSION,
                            RestorationIssue.GRANT_SCOPE_INSUFFICIENT,
                            RestorationRetryGuidance.REAUTHORIZE_EXACT_TREE_THEN_RETRY,
                            pending = checkpoint.pendingCleanup.map { it.type }.toSet(),
                        ),
                        mayAdvanceCursor = true,
                    )
                }

                val conflict = AtomicReference<TransferStorageError.StateConflict?>(null)
                val observedJournal = ConflictObservingSafCommitJournal(journal, conflict)
                val limitedGateway = RestorationBudgetedSafDocumentGateway(gateway, budget)
                val limitedStaging = RestorationBudgetedSafStaging(staging, budget)
                val coordinator = SafCommitCoordinator(
                    gateway = limitedGateway,
                    staging = limitedStaging,
                    journal = observedJournal,
                    allowVisibleFinalCopy = false,
                    isCancelled = { ownerJob?.isActive == false },
                )
                val recovery = try {
                    withContext(NonCancellable + ioDispatcher) {
                        if (checkpoint.phase == SafCommitCheckpointPhase.CANCELLED &&
                            checkpoint.pendingCleanup.isNotEmpty()
                        ) {
                            coordinator.retryCancelledTemporaryCleanup(checkpoint, grant)
                        } else {
                            coordinator.resumeOrReconcile(checkpoint, grant)
                        }
                    }
                } catch (cancel: CancellationException) {
                    // A NonCancellable block normally joins the synchronous SAF
                    // boundary; retain this guard for dispatcher failures.
                    throw cancel
                } catch (_: Exception) {
                    return CheckpointAttempt(
                        result(
                            rawId,
                            RestorationClassification.RECONCILIATION_REQUIRED,
                            if (budget.isBlocked()) RestorationIssue.EXECUTION_LIMIT_REACHED
                            else RestorationIssue.PROVIDER_STATE_UNRESOLVED,
                            if (budget.isBlocked()) RestorationRetryGuidance.RETRY_ON_NEXT_EXPLICIT_INVOCATION
                            else RestorationRetryGuidance.RECONCILE_ON_NEXT_EXPLICIT_INVOCATION,
                            pending = checkpoint.pendingCleanup.map { it.type }.toSet(),
                            delivered = false,
                        ),
                        mayAdvanceCursor = false,
                        cancellationObserved = ownerJob?.isActive == false,
                    )
                }

                val writeConflict = conflict.get()
                if (writeConflict != null) {
                    if (conflictReclassifications >= executionPolicy.maximumRevisionConflictReclassifications) {
                        return CheckpointAttempt(
                            result(
                                rawId,
                                RestorationClassification.REVISION_CONFLICT,
                                RestorationIssue.REVISION_CONFLICT,
                                RestorationRetryGuidance.RECONCILE_ON_NEXT_EXPLICIT_INVOCATION,
                                pending = checkpoint.pendingCleanup.map { it.type }.toSet(),
                                delivered = false,
                            ),
                            mayAdvanceCursor = false,
                            cancellationObserved = ownerJob?.isActive == false,
                        )
                    }
                    conflictReclassifications++
                    entry = when (val reloaded = withContext(ioDispatcher) { journal.read(identifier) }) {
                        SafCommitJournalRead.Missing -> return CheckpointAttempt(
                            result(
                                rawId,
                                RestorationClassification.MANUAL_RECONCILIATION_REQUIRED,
                                RestorationIssue.CHECKPOINT_MISSING,
                                RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
                            ),
                            mayAdvanceCursor = true,
                        )
                        is SafCommitJournalRead.Rejected -> return CheckpointAttempt(
                            result = classifyJournalReadError(rawId, reloaded.error),
                            mayAdvanceCursor = true,
                        )
                        is SafCommitJournalRead.Found -> reloaded.entry
                    }
                    if (!sameImmutableCommitScope(checkpoint, entry.checkpoint)) {
                        return CheckpointAttempt(
                            result(
                                rawId,
                                RestorationClassification.MANUAL_RECONCILIATION_REQUIRED,
                                RestorationIssue.GRANT_IDENTITY_MISMATCH,
                                RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
                                pending = entry.checkpoint.pendingCleanup.map { it.type }.toSet(),
                            ),
                            mayAdvanceCursor = true,
                        )
                    }
                    // Loop revalidates the newest checkpoint and re-resolves the
                    // exact persisted grant before any further provider action.
                    continue
                }

                val result = classifyRecoveryOutcome(rawId, recovery, budget)
                val cancelled = ownerJob?.isActive == false
                val mayAdvance = !cancelled || result.delivered ||
                    result.classification == RestorationClassification.ALREADY_COMPLETE ||
                    result.classification == RestorationClassification.CANCELLED
                return CheckpointAttempt(
                    result = result,
                    mayAdvanceCursor = mayAdvance,
                    cancellationObserved = cancelled,
                )
            }
        } catch (_: CancellationException) {
            return CheckpointAttempt(
                result = result(
                    rawId,
                    RestorationClassification.CANCELLED,
                    RestorationIssue.CALLER_CANCELLED,
                    RestorationRetryGuidance.RETRY_ON_NEXT_EXPLICIT_INVOCATION,
                    processed = false,
                ),
                mayAdvanceCursor = false,
                cancellationObserved = true,
            )
        } catch (_: Exception) {
            return CheckpointAttempt(
                result = result(
                    rawId,
                    RestorationClassification.RETRYABLE_FAILURE,
                    RestorationIssue.JOURNAL_FAILURE,
                    RestorationRetryGuidance.RETRY_ON_NEXT_EXPLICIT_INVOCATION,
                ),
                mayAdvanceCursor = true,
            )
        } finally {
            lease.close()
        }
    }

    private fun terminalCheckpointResult(
        rawId: String,
        checkpoint: SafCommitCheckpoint,
    ): RestorationCheckpointResult? {
        val pending = checkpoint.pendingCleanup.map { it.type }.toSet()
        return when {
            checkpoint.phase == SafCommitCheckpointPhase.COMMITTED && pending.isEmpty() -> result(
                rawId,
                RestorationClassification.ALREADY_COMPLETE,
                RestorationIssue.NONE,
                RestorationRetryGuidance.NONE,
                delivered = true,
            )
            checkpoint.phase == SafCommitCheckpointPhase.CANCELLED && pending.isEmpty() -> result(
                rawId,
                RestorationClassification.CANCELLED,
                RestorationIssue.NONE,
                RestorationRetryGuidance.NONE,
            )
            checkpoint.phase == SafCommitCheckpointPhase.CANCELLED_TEMPORARY_DELETE_OBSERVED && pending.isEmpty() -> result(
                rawId,
                RestorationClassification.CANCELLED,
                RestorationIssue.NONE,
                RestorationRetryGuidance.NONE,
            )
            else -> null
        }
    }

    private fun unsupportedCancelledCleanup(checkpoint: SafCommitCheckpoint): RestorationCheckpointResult? {
        if (checkpoint.phase != SafCommitCheckpointPhase.CANCELLED || checkpoint.pendingCleanup.isEmpty()) return null
        val supported = checkpoint.pendingCleanup.map { it.type }.toSet() == setOf(SafCleanupPending.PROVIDER_TEMPORARY) &&
            checkpoint.temporaryIdentity != null
        return if (supported) null else result(
            checkpoint.commitId.value,
            RestorationClassification.MANUAL_RECONCILIATION_REQUIRED,
            RestorationIssue.PROVIDER_STATE_UNRESOLVED,
            RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
            pending = checkpoint.pendingCleanup.map { it.type }.toSet(),
        )
    }

    private fun classifyJournalReadError(
        rawId: String,
        error: TransferStorageError,
    ): RestorationCheckpointResult = when (error) {
        is TransferStorageError.StateConflict,
        is TransferStorageError.Unsupported,
        -> result(
            rawId,
            RestorationClassification.INVALID_PERSISTED_STATE,
            if (error is TransferStorageError.Unsupported) RestorationIssue.UNSUPPORTED_CHECKPOINT
            else RestorationIssue.MALFORMED_CHECKPOINT,
            RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
        )
        else -> classifyStorageError(rawId, error, emptySet(), delivered = false, isRecovery = false)
    }

    private fun classifyRecoveryOutcome(
        rawId: String,
        outcome: SafCommitRecoveryOutcome,
        budget: RestorationExecutionBudget,
    ): RestorationCheckpointResult {
        val checkpoint = when (outcome) {
            is SafCommitRecoveryOutcome.ReadyToResume -> outcome.checkpoint
            is SafCommitRecoveryOutcome.Skipped -> outcome.checkpoint
            is SafCommitRecoveryOutcome.Committed -> outcome.checkpoint
            is SafCommitRecoveryOutcome.Cancelled -> outcome.checkpoint
            is SafCommitRecoveryOutcome.ReconciliationRequired -> outcome.checkpoint
            is SafCommitRecoveryOutcome.Failed -> outcome.checkpoint
        }
        val pending = checkpoint.pendingCleanup.map { it.type }.toSet()
        // Only the existing coordinator's Committed outcome proves that a
        // provider observation established the final. Persisted identity/digest
        // alone do not turn an unknown current provider state into delivery.
        val delivered = outcome is SafCommitRecoveryOutcome.Committed
        val base = when (outcome) {
            is SafCommitRecoveryOutcome.ReadyToResume -> result(
                rawId,
                RestorationClassification.AWAIT_USER_DECISION,
                RestorationIssue.USER_DECISION_REQUIRED,
                RestorationRetryGuidance.USER_DECISION_REQUIRED,
                pending,
                delivered,
            )
            is SafCommitRecoveryOutcome.Skipped -> result(
                rawId,
                RestorationClassification.SKIPPED_BY_POLICY,
                RestorationIssue.NONE,
                RestorationRetryGuidance.NONE,
                pending,
                delivered = false,
            )
            is SafCommitRecoveryOutcome.Committed -> {
                val cleanup = cleanupClassification(pending)
                if (cleanup != null) {
                    result(
                        rawId,
                        cleanup,
                        if (budget.isBlocked()) RestorationIssue.EXECUTION_LIMIT_REACHED else RestorationIssue.NONE,
                        if (budget.isBlocked()) RestorationRetryGuidance.RETRY_ON_NEXT_EXPLICIT_INVOCATION
                        else RestorationRetryGuidance.RECONCILE_ON_NEXT_EXPLICIT_INVOCATION,
                        pending,
                        delivered = true,
                    )
                } else {
                    result(
                        rawId,
                        RestorationClassification.RESUMED_AND_COMMITTED,
                        RestorationIssue.NONE,
                        RestorationRetryGuidance.NONE,
                        delivered = true,
                    )
                }
            }
            is SafCommitRecoveryOutcome.Cancelled -> if (pending.isNotEmpty()) {
                result(
                    rawId,
                    RestorationClassification.RECONCILIATION_REQUIRED,
                    RestorationIssue.PROVIDER_STATE_UNRESOLVED,
                    RestorationRetryGuidance.RECONCILE_ON_NEXT_EXPLICIT_INVOCATION,
                    pending,
                    delivered,
                )
            } else {
                result(
                    rawId,
                    RestorationClassification.CANCELLED,
                    RestorationIssue.NONE,
                    RestorationRetryGuidance.NONE,
                )
            }
            is SafCommitRecoveryOutcome.ReconciliationRequired -> {
                val errorResult = classifyStorageError(rawId, outcome.error, pending, delivered, isRecovery = true)
                if (isReviewOrPermission(errorResult.classification)) {
                    errorResult
                } else {
                    cleanupClassification(pending)?.let { cleanup ->
                        result(
                            rawId,
                            cleanup,
                            if (budget.isBlocked()) RestorationIssue.EXECUTION_LIMIT_REACHED
                            else errorResult.issue,
                            if (budget.isBlocked()) RestorationRetryGuidance.RETRY_ON_NEXT_EXPLICIT_INVOCATION
                            else RestorationRetryGuidance.RECONCILE_ON_NEXT_EXPLICIT_INVOCATION,
                            pending,
                            delivered,
                        )
                    } ?: errorResult.copy(
                        classification = if (budget.isBlocked()) RestorationClassification.BUDGET_EXHAUSTED
                        else RestorationClassification.RECONCILIATION_REQUIRED,
                        issue = if (budget.isBlocked()) RestorationIssue.EXECUTION_LIMIT_REACHED
                        else errorResult.issue,
                        retryGuidance = if (budget.isBlocked()) RestorationRetryGuidance.RETRY_ON_NEXT_EXPLICIT_INVOCATION
                        else RestorationRetryGuidance.RECONCILE_ON_NEXT_EXPLICIT_INVOCATION,
                    )
                }
            }
            is SafCommitRecoveryOutcome.Failed -> {
                val errorResult = classifyStorageError(rawId, outcome.error, pending, delivered, isRecovery = false)
                if (budget.isBlocked() && errorResult.classification == RestorationClassification.RETRYABLE_FAILURE) {
                    errorResult.copy(
                        classification = RestorationClassification.BUDGET_EXHAUSTED,
                        issue = RestorationIssue.EXECUTION_LIMIT_REACHED,
                        retryGuidance = RestorationRetryGuidance.RETRY_ON_NEXT_EXPLICIT_INVOCATION,
                    )
                } else {
                    errorResult
                }
            }
        }
        return base
    }

    private fun classifyStorageError(
        rawId: String,
        error: TransferStorageError,
        pending: Set<SafCleanupPending>,
        delivered: Boolean,
        isRecovery: Boolean,
    ): RestorationCheckpointResult = when (error) {
        is TransferStorageError.PermissionRevoked -> result(
            rawId,
            RestorationClassification.AWAIT_PERMISSION,
            RestorationIssue.GRANT_REVOKED,
            RestorationRetryGuidance.REAUTHORIZE_EXACT_TREE_THEN_RETRY,
            pending,
            delivered,
        )
        is TransferStorageError.StorageFull,
        is TransferStorageError.InsufficientSpace,
        -> result(
            rawId,
            RestorationClassification.AWAIT_EXTERNAL_STORAGE,
            RestorationIssue.EXTERNAL_STORAGE_FULL,
            RestorationRetryGuidance.RETRY_ON_NEXT_EXPLICIT_INVOCATION,
            pending,
            delivered,
        )
        is TransferStorageError.NotFound -> if (error.subject == "partial" || error.subject == "staged_copy") {
            result(
                rawId,
                RestorationClassification.AWAIT_EXTERNAL_STORAGE,
                RestorationIssue.STAGING_UNAVAILABLE,
                RestorationRetryGuidance.RETRY_ON_NEXT_EXPLICIT_INVOCATION,
                pending,
                delivered,
            )
        } else if (isRecovery) {
            result(
                rawId,
                RestorationClassification.RECONCILIATION_REQUIRED,
                RestorationIssue.PROVIDER_STATE_UNRESOLVED,
                RestorationRetryGuidance.RECONCILE_ON_NEXT_EXPLICIT_INVOCATION,
                pending,
                delivered,
            )
        } else {
            result(
                rawId,
                RestorationClassification.MANUAL_RECONCILIATION_REQUIRED,
                RestorationIssue.CHECKPOINT_MISSING,
                RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
                pending,
                delivered,
            )
        }
        is TransferStorageError.IntegrityMismatch -> result(
            rawId,
            RestorationClassification.MANUAL_RECONCILIATION_REQUIRED,
            RestorationIssue.INTEGRITY_MISMATCH,
            RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
            pending,
            delivered,
        )
        is TransferStorageError.Unsupported -> result(
            rawId,
            RestorationClassification.AWAIT_USER_DECISION,
            RestorationIssue.UNSUPPORTED_DESTINATION,
            RestorationRetryGuidance.USER_DECISION_REQUIRED,
            pending,
            delivered,
        )
        TransferStorageError.Cancelled -> result(
            rawId,
            RestorationClassification.CANCELLED,
            RestorationIssue.CALLER_CANCELLED,
            RestorationRetryGuidance.RETRY_ON_NEXT_EXPLICIT_INVOCATION,
            pending,
            delivered,
        )
        is TransferStorageError.ContainmentUnknown -> if (isManualContainmentReason(error.reason)) {
            result(
                rawId,
                RestorationClassification.MANUAL_RECONCILIATION_REQUIRED,
                RestorationIssue.PROVIDER_STATE_UNRESOLVED,
                RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
                pending,
                delivered,
            )
        } else {
            result(
                rawId,
                RestorationClassification.RECONCILIATION_REQUIRED,
                RestorationIssue.PROVIDER_STATE_UNRESOLVED,
                RestorationRetryGuidance.RECONCILE_ON_NEXT_EXPLICIT_INVOCATION,
                pending,
                delivered,
            )
        }
        is TransferStorageError.StateConflict -> when {
            error.reason == "checkpoint_malformed" -> result(
                rawId,
                RestorationClassification.INVALID_PERSISTED_STATE,
                RestorationIssue.MALFORMED_CHECKPOINT,
                RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
                pending,
                delivered,
            )
            error.reason == "restoration_execution_budget_exhausted" -> result(
                rawId,
                RestorationClassification.BUDGET_EXHAUSTED,
                RestorationIssue.EXECUTION_LIMIT_REACHED,
                RestorationRetryGuidance.RETRY_ON_NEXT_EXPLICIT_INVOCATION,
                pending,
                delivered,
                processed = true,
            )
            isManualStateReason(error.reason) -> result(
                rawId,
                RestorationClassification.MANUAL_RECONCILIATION_REQUIRED,
                RestorationIssue.PROVIDER_STATE_UNRESOLVED,
                RestorationRetryGuidance.MANUAL_REVIEW_REQUIRED,
                pending,
                delivered,
            )
            else -> result(
                rawId,
                if (isRecovery) RestorationClassification.RECONCILIATION_REQUIRED
                else RestorationClassification.RETRYABLE_FAILURE,
                if (isRecovery) RestorationIssue.PROVIDER_STATE_UNRESOLVED else RestorationIssue.JOURNAL_FAILURE,
                if (isRecovery) RestorationRetryGuidance.RECONCILE_ON_NEXT_EXPLICIT_INVOCATION
                else RestorationRetryGuidance.RETRY_ON_NEXT_EXPLICIT_INVOCATION,
                pending,
                delivered,
            )
        }
        is TransferStorageError.Io,
        is TransferStorageError.ProviderFailure,
        is TransferStorageError.ZeroProgress,
        -> result(
            rawId,
            if (isRecovery) RestorationClassification.RECONCILIATION_REQUIRED
            else RestorationClassification.RETRYABLE_FAILURE,
            if (isRecovery) RestorationIssue.PROVIDER_STATE_UNRESOLVED else RestorationIssue.JOURNAL_FAILURE,
            if (isRecovery) RestorationRetryGuidance.RECONCILE_ON_NEXT_EXPLICIT_INVOCATION
            else RestorationRetryGuidance.RETRY_ON_NEXT_EXPLICIT_INVOCATION,
            pending,
            delivered,
        )
    }

    private fun cleanupClassification(pending: Set<SafCleanupPending>): RestorationClassification? = when {
        SafCleanupPending.BACKUP in pending -> RestorationClassification.RETRY_BACKUP_CLEANUP
        SafCleanupPending.PROVIDER_TEMPORARY in pending -> RestorationClassification.RETRY_PROVIDER_TEMPORARY_CLEANUP
        SafCleanupPending.STAGING in pending -> RestorationClassification.RETRY_STAGING_CLEANUP
        else -> null
    }

    private fun isReviewOrPermission(classification: RestorationClassification): Boolean =
        classification in setOf(
            RestorationClassification.AWAIT_PERMISSION,
            RestorationClassification.AWAIT_SCOPE_PERMISSION,
            RestorationClassification.AWAIT_EXTERNAL_STORAGE,
            RestorationClassification.AWAIT_USER_DECISION,
            RestorationClassification.MANUAL_RECONCILIATION_REQUIRED,
            RestorationClassification.INVALID_PERSISTED_STATE,
        )

    private fun result(
        rawId: String,
        classification: RestorationClassification,
        issue: RestorationIssue,
        guidance: RestorationRetryGuidance,
        pending: Set<SafCleanupPending> = emptySet(),
        delivered: Boolean = false,
        processed: Boolean = true,
    ): RestorationCheckpointResult = RestorationCheckpointResult(
        checkpoint = RedactedRestorationCheckpointId(rawId),
        classification = classification,
        issue = issue,
        retryGuidance = guidance,
        pendingCleanup = pending,
        delivered = delivered,
        processed = processed,
    )

    private fun deferredResult(rawId: String) = result(
        rawId,
        RestorationClassification.BUDGET_EXHAUSTED,
        RestorationIssue.EXECUTION_LIMIT_REACHED,
        RestorationRetryGuidance.CONTINUE_WITH_RETURNED_CURSOR,
        processed = false,
    )

    private fun isVerifiedFinal(checkpoint: SafCommitCheckpoint): Boolean =
        checkpoint.finalIdentity != null && checkpoint.verifiedDigest != null &&
            checkpoint.copiedBytes == checkpoint.expectedSizeBytes

    private fun grantMatchesCheckpoint(grant: SafTreeGrant, checkpoint: SafCommitCheckpoint): Boolean =
        grant.grantId == checkpoint.approvedTree.grantId &&
            grant.treeUri.toString() == checkpoint.approvedTree.treeUri &&
            grant.authority == checkpoint.approvedTree.authority &&
            grant.rootDocumentId == checkpoint.approvedTree.rootDocumentId &&
            SafContainment.treeDocumentIdOf(grant.treeUri) == checkpoint.approvedTree.rootDocumentId

    private fun sameImmutableCommitScope(
        old: SafCommitCheckpoint,
        fresh: SafCommitCheckpoint,
    ): Boolean =
        old.commitId == fresh.commitId &&
            old.sessionId == fresh.sessionId &&
            old.transferId == fresh.transferId &&
            old.strategy == fresh.strategy &&
            old.duplicatePolicy == fresh.duplicatePolicy &&
            old.approvedTree == fresh.approvedTree &&
            old.parentDocumentId == fresh.parentDocumentId &&
            old.stagingIdentity == fresh.stagingIdentity &&
            old.expectedFinalName == fresh.expectedFinalName &&
            old.expectedSizeBytes == fresh.expectedSizeBytes &&
            old.expectedDigest == fresh.expectedDigest

    private fun isValidCommitId(rawId: String): Boolean {
        if (rawId.isBlank()) return false
        return runCatching {
            SafCommitCheckpointValidator.validateCommitId(PartialIdentity(rawId)) == null
        }.getOrDefault(false)
    }

    private fun isManualContainmentReason(reason: String): Boolean = reason in setOf(
        "temporary_identity_changed",
        "temporary_candidate_mismatch",
        "final_identity_changed_after_publication",
        "final_identity_changed",
        "backup_identity_changed",
        "cancelled_temporary_identity_changed",
        "rename_history_malformed",
        "checkpoint_identity_malformed",
    )

    private fun isManualStateReason(reason: String): Boolean = reason in setOf(
        "checkpoint_identity_mismatch",
        "checkpoint_cleanup_identity_mismatch",
        "grant_context_mismatch",
        "checkpoint_requires_manual_reconciliation",
        "cleanup_grant_context_mismatch",
        "cleanup_state_not_authorized",
        "cleanup_checkpoint_mismatch",
        "cleanup_state_inconsistent",
        "cancelled_checkpoint_mismatch",
    )

    private data class CheckpointAttempt(
        val result: RestorationCheckpointResult,
        val mayAdvanceCursor: Boolean,
        val cancellationObserved: Boolean = false,
    )
}

/**
 * Nonblocking process-local lock registry. Reference counts remove idle keys;
 * unrelated checkpoint ids receive independent mutexes. This deliberately does
 * not claim cross-process serialization.
 */
internal class ProcessLocalCheckpointLocks private constructor() {
    private class Entry {
        val mutex: Mutex = Mutex()
        var references: Int = 0
    }

    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun tryAcquire(commitId: String): Lease? {
        val entry = entries.getOrPut(commitId) { Entry() }
        entry.references++
        if (!entry.mutex.tryLock()) {
            entry.references--
            if (entry.references == 0) entries.remove(commitId)
            return null
        }
        return Lease { release(commitId, entry) }
    }

    private fun release(commitId: String, entry: Entry) {
        entry.mutex.unlock()
        synchronized(this) {
            entry.references--
            if (entry.references == 0 && !entry.mutex.isLocked && entries[commitId] === entry) {
                entries.remove(commitId)
            }
        }
    }

    @Synchronized
    fun entryCountForTest(): Int = entries.size

    internal class Lease(private val releaseAction: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) releaseAction()
        }
    }

    companion object {
        val shared = ProcessLocalCheckpointLocks()
    }
}

/** Per-run counters and optional monotonic deadline. */
private class RestorationExecutionBudget(
    private val policy: RestorationExecutionPolicy,
    private val clock: RestorationMonotonicClock,
    private val startedAtNanos: Long,
) {
    private var mutationCount = 0
    private var cleanupCount = 0
    private var observationCount = 0
    private var blocked = false

    @Synchronized
    fun tryMutation(): Boolean = synchronized(this) {
        if (!available()) return@synchronized false
        if (mutationCount >= policy.maximumProviderMutations) {
            blocked = true
            return@synchronized false
        }
        mutationCount++
        true
    }

    @Synchronized
    fun tryCleanupAttempt(): Boolean = synchronized(this) {
        if (!available()) return@synchronized false
        if (cleanupCount >= policy.maximumCleanupAttempts) {
            blocked = true
            return@synchronized false
        }
        cleanupCount++
        true
    }

    @Synchronized
    fun tryObservations(count: Int = 1): Boolean = synchronized(this) {
        if (count < 0 || !available()) return@synchronized false
        if (observationCount > policy.maximumReconciliationObservations - count) {
            blocked = true
            return@synchronized false
        }
        observationCount += count
        true
    }

    @Synchronized
    fun isBlocked(): Boolean = synchronized(this) {
        if (!blocked) available()
        blocked
    }

    private fun available(): Boolean {
        val limit = policy.maximumElapsedNanos ?: return !blocked
        val elapsed = clock.nowNanos() - startedAtNanos
        if (elapsed < 0L || elapsed >= limit) blocked = true
        return !blocked
    }
}

/** Local cleanup is budgeted with provider cleanup but never treated as a provider mutation. */
private class RestorationBudgetedSafStaging(
    private val delegate: SafStaging,
    private val budget: RestorationExecutionBudget,
) : SafStaging {
    override fun length(identity: PartialIdentity): Long? = delegate.length(identity)

    override fun open(identity: PartialIdentity): SafOpen = delegate.open(identity)

    override fun delete(identity: PartialIdentity): Boolean =
        budget.tryCleanupAttempt() && delegate.delete(identity)
}

/** Ensures every coordinator provider call shares one per-run allowance. */
private class RestorationBudgetedSafDocumentGateway(
    private val delegate: SafDocumentGateway,
    private val budget: RestorationExecutionBudget,
) : SafDocumentGateway, SafContainmentProver {
    private val containmentDelegate = delegate as? SafContainmentProver
    private val exhausted = TransferStorageError.StateConflict("restoration_execution_budget_exhausted")

    override fun isChildDocument(parentDocumentUri: Uri, childDocumentUri: Uri): SafChildAnswer {
        val prover = containmentDelegate ?: return SafChildAnswer.Indeterminate
        return if (budget.tryObservations()) {
            prover.isChildDocument(parentDocumentUri, childDocumentUri)
        } else {
            SafChildAnswer.Indeterminate
        }
    }

    override fun documentPath(documentUri: Uri): SafPathAnswer {
        val prover = containmentDelegate ?: return SafPathAnswer.Indeterminate
        return if (budget.tryObservations()) {
            prover.documentPath(documentUri)
        } else {
            SafPathAnswer.Indeterminate
        }
    }

    override fun recheckPersistedGrant(
        grant: SafTreeGrant,
        operation: SafContainmentOperation,
    ): TransferStorageError? = if (budget.tryObservations()) {
        delegate.recheckPersistedGrant(grant, operation)
    } else {
        exhausted
    }

    override fun findChild(parentUri: String, displayName: String): SafLookup =
        if (budget.tryObservations()) delegate.findChild(parentUri, displayName) else SafLookup.Failed(exhausted)

    override fun create(parentUri: String, mimeType: String, displayName: String): SafCreate =
        // The production gateway queries the returned document once before it
        // reports a successful create, so reserve that observation up front.
        if (budget.tryObservations() && budget.tryMutation()) {
            delegate.create(parentUri, mimeType, displayName)
        } else {
            SafCreate.Failed(exhausted)
        }

    override fun rename(documentUri: String, displayName: String): SafRename =
        if (budget.tryMutation()) delegate.rename(documentUri, displayName) else SafRename.Failed(exhausted)

    override fun delete(documentUri: String): SafDelete =
        if (budget.tryMutation()) delegate.delete(documentUri) else SafDelete.Failed(exhausted)

    override fun query(documentUri: String): SafLookup =
        if (budget.tryObservations()) delegate.query(documentUri) else SafLookup.Failed(exhausted)

    override fun openWrite(documentUri: String): SafOpen =
        if (budget.tryMutation()) delegate.openWrite(documentUri) else SafOpen.Refused(exhausted)

    override fun openRead(documentUri: String): SafOpen =
        if (budget.tryObservations()) delegate.openRead(documentUri) else SafOpen.Refused(exhausted)

    override fun deleteAndReconcile(
        documentUri: String,
        expectedDocumentId: String,
        grant: SafTreeGrant?,
    ): SafDeletion {
        if (!budget.tryCleanupAttempt() || !budget.tryObservations(count = 3) || !budget.tryMutation()) {
            return SafDeletion.QueryUnknown("restoration execution budget exhausted")
        }
        return delegate.deleteAndReconcile(documentUri, expectedDocumentId, grant)
    }
}

/** Observes the journal's typed CAS result before the SAF coordinator masks it. */
private class ConflictObservingSafCommitJournal(
    private val delegate: SafCommitJournal,
    private val conflict: AtomicReference<TransferStorageError.StateConflict?>,
) : SafCommitJournal {
    override fun read(commitId: PartialIdentity): SafCommitJournalRead = delegate.read(commitId)

    override fun write(
        checkpoint: SafCommitCheckpoint,
        expectedRevision: Long,
    ): SafCommitJournalWrite {
        val result = delegate.write(checkpoint, expectedRevision)
        if (result is SafCommitJournalWrite.Conflict) conflict.compareAndSet(null, result.error)
        return result
    }
}
