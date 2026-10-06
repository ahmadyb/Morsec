package app.morsecode.core.storage.transfer

import android.net.Uri
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafCommitRestorationCoordinatorTest {
    private val treeUri = Uri.parse("content://provider/tree/root")
    private val grant = SafTreeGrant(
        grantId = "9",
        treeUri = treeUri,
        rootDocumentId = "root",
        authority = "provider",
        writable = true,
    )
    private val bytes = "restoration-orchestration-payload".toByteArray()

    @Test
    fun `validated nonterminal checkpoint routes through existing SAF recovery and returns redacted report`() = runBlocking {
        val journal = InMemorySafCommitJournal()
        val checkpoint = checkpoint("restore-success")
        assertTrue(journal.save(checkpoint))
        val gateway = RecordingSafGateway()
        val staging = MemoryStaging(bytes)
        val factory = FakeRecoveryFactory(gateway, staging, journal)
        val resolver = FixedGrantResolver(SafCommitGrantResolution.Available(grant))
        val coordinator = coordinator(journal, resolver, factory)

        val report = coordinator.restore()

        assertEquals(1, report.processedCheckpoints)
        assertEquals(SafCommitRestorationDisposition.COMMITTED, report.checkpoints.single().disposition)
        assertEquals(SafCommitRestorationStopReason.SCAN_COMPLETE, report.stopReason)
        assertEquals(1, gateway.countOf("create:"))
        assertTrue(staging.deleted)
        val diagnostics = "$report ${report.checkpoints} ${report.retryPlans}"
        assertFalse(diagnostics.contains(treeUri.toString()))
        assertFalse(diagnostics.contains("file.bin"))
        assertFalse(diagnostics.contains("restore-success"))
    }

    @Test
    fun `active transfer is deferred before grant resolution or provider access`() = runBlocking {
        val journal = InMemorySafCommitJournal()
        val checkpoint = checkpoint("active-transfer")
        assertTrue(journal.save(checkpoint))
        journal.transferActivities[checkpoint.commitId] = SafCommitTransferActivity.ACTIVE_OR_RESUMABLE
        val gateway = RecordingSafGateway()
        val staging = MemoryStaging(bytes)
        val resolver = FixedGrantResolver(SafCommitGrantResolution.Available(grant))
        val factory = FakeRecoveryFactory(gateway, staging, journal)

        val restoration = coordinator(journal, resolver, factory)
        val report = restoration.restore()

        assertEquals(SafCommitRestorationDisposition.WAITING_FOR_TRANSFER, report.checkpoints.single().disposition)
        assertEquals(SafCommitRestorationRetryTrigger.TRANSFER_QUIESCENT, report.retryPlans.single().trigger)
        assertEquals(0, resolver.calls)
        assertEquals(0, factory.calls)
        assertTrue(gateway.calls.isEmpty())

        journal.transferActivities[checkpoint.commitId] = SafCommitTransferActivity.QUIESCENT
        val retryReport = restoration.restore(retry = report.retryPlans.single())
        assertEquals(SafCommitRestorationDisposition.COMMITTED, retryReport.checkpoints.single().disposition)
        assertEquals(1, gateway.countOf("create:"))
        assertTrue(staging.deleted)
    }

    @Test
    fun `malformed checkpoint and failed exact grant resolution do not invoke recovery`() = runBlocking {
        val invalid = checkpoint("invalid-checkpoint").copy(version = SafCommitCheckpoint.CURRENT_VERSION + 1)
        val invalidDiscovery = SingleCandidateDiscovery(candidate(invalid))
        val invalidGrantResolver = FixedGrantResolver(SafCommitGrantResolution.Available(grant))
        val unusedGateway = RecordingSafGateway()
        val unusedFactory = FakeRecoveryFactory(unusedGateway, MemoryStaging(bytes), InMemorySafCommitJournal())
        val invalidReport = coordinator(invalidDiscovery, invalidGrantResolver, unusedFactory).restore()

        assertEquals(
            SafCommitRestorationDisposition.INVALID_PERSISTED_STATE,
            invalidReport.checkpoints.single().disposition,
        )
        assertEquals(0, invalidGrantResolver.calls)
        assertEquals(0, unusedFactory.calls)
        assertTrue(unusedGateway.calls.isEmpty())

        val journal = InMemorySafCommitJournal()
        val valid = checkpoint("revoked-grant")
        assertTrue(journal.save(valid))
        val revokedResolver = FixedGrantResolver(SafCommitGrantResolution.Revoked)
        val revokedGateway = RecordingSafGateway()
        val revokedFactory = FakeRecoveryFactory(revokedGateway, MemoryStaging(bytes), journal)
        val revokedReport = coordinator(journal, revokedResolver, revokedFactory).restore()
        assertEquals(SafCommitRestorationDisposition.WAITING_FOR_GRANT, revokedReport.checkpoints.single().disposition)
        assertEquals(1, revokedResolver.calls)
        assertEquals(0, revokedFactory.calls)
        assertTrue(revokedGateway.calls.isEmpty())
    }

    @Test
    fun `reconciliation observation budget prevents the underlying provider query`() = runBlocking {
        val journal = InMemorySafCommitJournal()
        assertTrue(journal.save(checkpoint("observation-budget")))
        val gateway = RecordingSafGateway()
        val factory = FakeRecoveryFactory(gateway, MemoryStaging(bytes), journal)

        val report = coordinator(
            journal,
            FixedGrantResolver(SafCommitGrantResolution.Available(grant)),
            factory,
            policy = policy(maxObservations = 0),
        ).restore()

        assertEquals(SafCommitRestorationDisposition.BUDGET_EXHAUSTED, report.checkpoints.single().disposition)
        assertEquals(SafCommitRestorationBudgetStop.CHECKPOINT_OBSERVATIONS, report.budget.stoppedBy)
        assertEquals(0, report.budget.reconciliationObservations)
        assertEquals(0, gateway.countOf("findChild:"))
        assertEquals(0, gateway.countOf("create:"))
    }

    @Test
    fun `exact provider cleanup stays pending at budget and a later explicit retry deletes only its stored identity`() = runBlocking {
        val journal = InMemorySafCommitJournal()
        val fixture = temporaryCleanupFixture("cleanup-budget")
        assertEquals(SafCommitJournalWrite.Saved(1L), journal.write(fixture.checkpoint, 0L))
        val gateway = RecordingSafGateway()
        val tempName = temporaryDocumentName("file.bin", fixture.checkpoint.stagingIdentity)
        gateway.addNamed(fixture.temporary.documentUri, tempName)
        gateway.written[fixture.temporary.documentUri] = bytes
        gateway.addNamed(fixture.final.documentUri, "file.bin")
        gateway.written[fixture.final.documentUri] = bytes
        val replacementUri = requireNotNull(
            SafContainment.documentUriUsingTree(treeUri, "same-name-replacement"),
        ).toString()
        gateway.addNamed(replacementUri, tempName)
        val staging = MemoryStaging(bytes).also { assertTrue(it.delete(fixture.checkpoint.stagingIdentity)) }
        val factory = FakeRecoveryFactory(gateway, staging, journal)
        val resolver = FixedGrantResolver(SafCommitGrantResolution.Available(grant))
        val bounded = coordinator(
            journal,
            resolver,
            factory,
            policy = policy(maxCleanupAttempts = 0),
        ).restore()

        assertEquals(SafCommitRestorationDisposition.BUDGET_EXHAUSTED, bounded.checkpoints.single().disposition)
        assertEquals(SafCommitRestorationBudgetStop.CLEANUP_ATTEMPTS, bounded.budget.stoppedBy)
        assertEquals(setOf(SafCleanupPending.PROVIDER_TEMPORARY), bounded.checkpoints.single().pendingCleanup)
        assertEquals(0, gateway.countOf("deleteAndReconcile:"))
        assertTrue(fixture.temporary.documentUri in gateway.existing)

        val retried = coordinator(journal, resolver, factory).restore(retry = bounded.retryPlans.single())

        assertEquals(SafCommitRestorationDisposition.COMMITTED, retried.checkpoints.single().disposition)
        assertEquals(1, gateway.calls.count {
            it == "deleteAndReconcile:${fixture.temporary.documentUri}:${fixture.temporary.documentId}"
        })
        assertFalse(fixture.temporary.documentUri in gateway.existing)
        assertTrue(replacementUri in gateway.existing)
    }

    @Test
    fun `provider mutation budget stops before create and returns an explicit retry plan`() = runBlocking {
        val journal = InMemorySafCommitJournal()
        val checkpoint = checkpoint("budget-stop")
        assertTrue(journal.save(checkpoint))
        val gateway = RecordingSafGateway()
        val factory = FakeRecoveryFactory(gateway, MemoryStaging(bytes), journal)
        val report = coordinator(
            journal,
            FixedGrantResolver(SafCommitGrantResolution.Available(grant)),
            factory,
            policy = policy(maxMutations = 0),
        ).restore()

        assertEquals(SafCommitRestorationDisposition.BUDGET_EXHAUSTED, report.checkpoints.single().disposition)
        assertEquals(SafCommitRestorationBudgetStop.PROVIDER_MUTATIONS, report.budget.stoppedBy)
        assertEquals(0, report.budget.providerMutations)
        assertEquals(0, gateway.countOf("create:"))
        assertEquals(SafCommitRestorationRetryTrigger.BUDGET_AVAILABLE, report.retryPlans.single().trigger)
    }

    @Test
    fun `revision conflict reloads and reports the latest checkpoint without replaying provider work`() = runBlocking {
        val journal = InMemorySafCommitJournal()
        val checkpoint = checkpoint("revision-race")
        assertTrue(journal.save(checkpoint))
        val gateway = RecordingSafGateway()
        var advanced = false
        gateway.afterCreate = {
            if (!advanced) {
                advanced = true
                val current = journal.read(checkpoint.commitId) as SafCommitJournalRead.Found
                val changed = current.entry.checkpoint.copy(
                    lastFailure = SafCommitCheckpointFailure("state", "external_revision"),
                )
                assertTrue(journal.write(changed, current.entry.revision) is SafCommitJournalWrite.Saved)
            }
        }
        val factory = FakeRecoveryFactory(gateway, MemoryStaging(bytes), journal)

        val report = coordinator(
            journal,
            FixedGrantResolver(SafCommitGrantResolution.Available(grant)),
            factory,
        ).restore()

        assertEquals(SafCommitRestorationDisposition.REVISION_CONFLICT_RELOADED, report.checkpoints.single().disposition)
        assertEquals(SafCommitCheckpointPhase.TEMPORARY_CREATE_INTENT.id, report.checkpoints.single().phaseId)
        assertTrue(report.retryPlans.isNotEmpty())
        assertEquals(1, gateway.countOf("create:"))
        assertFalse("report must never include provider URI", report.toString().contains(treeUri.toString()))
    }

    @Test
    fun `cancellation after provider mutation preserves remaining page work for a later run`() = runBlocking {
        val journal = InMemorySafCommitJournal()
        assertTrue(journal.save(checkpoint("a-cancel-during-recovery")))
        assertTrue(journal.save(checkpoint("b-unprocessed")))
        val gateway = RecordingSafGateway()
        var cancelled = false
        gateway.afterCreate = { cancelled = true }
        val factory = FakeRecoveryFactory(gateway, MemoryStaging(bytes), journal)

        val report = coordinator(
            journal,
            FixedGrantResolver(SafCommitGrantResolution.Available(grant)),
            factory,
        ).restore(cancellation = SafCommitRestorationCancellation { cancelled })

        assertEquals(SafCommitRestorationStopReason.CALLER_CANCELLED, report.stopReason)
        assertEquals(1, report.processedCheckpoints)
        assertEquals(1, report.unprocessedWork.size)
        assertEquals(2, report.unprocessedWork.single().checkpointNumber)
        assertEquals(1, gateway.countOf("create:"))
        assertTrue(report.moreCheckpointsAvailable == true)
    }

    @Test
    fun `caller cancellation returns an explicit continuation without provider access`() = runBlocking {
        val journal = InMemorySafCommitJournal()
        val checkpoint = checkpoint("cancel-before-run")
        assertTrue(journal.save(checkpoint))
        val gateway = RecordingSafGateway()
        val factory = FakeRecoveryFactory(gateway, MemoryStaging(bytes), journal)

        val report = coordinator(
            journal,
            FixedGrantResolver(SafCommitGrantResolution.Available(grant)),
            factory,
        ).restore(cancellation = SafCommitRestorationCancellation { true })

        assertEquals(SafCommitRestorationStopReason.CALLER_CANCELLED, report.stopReason)
        assertEquals(0, report.processedCheckpoints)
        assertTrue(report.moreCheckpointsAvailable == true)
        assertTrue(gateway.calls.isEmpty())
    }

    private fun coordinator(
        discovery: SafCommitCheckpointDiscovery,
        grantResolver: SafCommitGrantResolver,
        recoveryFactory: SafCommitRecoveryCoordinatorFactory,
        policy: SafCommitRestorationPolicy = policy(),
    ) = SafCommitRestorationCoordinator(
        discovery = discovery,
        grantResolver = grantResolver,
        recoveryFactory = recoveryFactory,
        policy = policy,
        dispatcher = Dispatchers.IO,
    )

    private fun policy(
        maxMutations: Int = 32,
        maxCleanupAttempts: Int = 8,
        maxObservations: Int = 96,
    ) = SafCommitRestorationPolicy(
        maxCheckpointsPerRun = 4,
        pageSize = 2,
        maxProviderMutationsPerRun = maxMutations,
        maxCleanupAttemptsPerRun = maxCleanupAttempts,
        maxReconciliationObservationsPerCheckpoint = maxObservations,
        maxReturnedRetryPlans = 4,
    )

    private fun checkpoint(commitKey: String): SafCommitCheckpoint =
        SafCommitCheckpoint.fromRecord(record(commitKey), grant)

    private fun record(commitKey: String): SafCommitRecord = SafCommitRecord(
        sessionId = SessionId("session-$commitKey"),
        transferId = TransferId("transfer-$commitKey"),
        partialId = PartialIdentity(commitKey),
        treeUri = treeUri.toString(),
        rootDocumentId = "root",
        parentDocumentId = "root",
        expectedFinalName = "file.bin",
        expectedSizeBytes = bytes.size.toLong(),
        expectedDigest = Sha256Accumulator().apply { update(bytes) }.digest(),
        grantId = grant.grantId,
        duplicatePolicy = DuplicatePolicy.RENAME,
    )

    private fun temporaryCleanupFixture(commitKey: String): TemporaryCleanupFixture {
        val source = record(commitKey)
        val temporary = storedIdentity("temporary-$commitKey")
        val final = storedIdentity("final-$commitKey")
        val evidence = SafRenameEvidence(
            before = temporary,
            returned = final,
            reconciliation = SafRenameReconciliation.RESOLVED_TO_RETURNED,
            scope = SafRenameScope.fromRecord(source, grant),
            phase = SafRenamePhase.FINAL_PROMOTION,
            sequence = 0,
        )
        val pendingRecord = source.copy(
            state = SafCommitState.PROVIDER_TEMPORARY_CLEANUP_PENDING,
            temporaryUri = temporary.documentUri,
            temporaryIdentity = temporary,
            finalUri = final.documentUri,
            finalIdentity = final,
            renameHistory = listOf(evidence),
            verifiedDigest = requireNotNull(source.expectedDigest),
            pendingCleanup = setOf(SafCleanupPending.PROVIDER_TEMPORARY),
            copiedBytes = bytes.size.toLong(),
            stagingReleased = true,
        )
        return TemporaryCleanupFixture(
            checkpoint = SafCommitCheckpoint.fromRecord(
                pendingRecord,
                grant,
                SafCommitCheckpointPhase.PROVIDER_TEMPORARY_DELETE_INTENT,
            ),
            temporary = temporary,
            final = final,
        )
    }

    private fun storedIdentity(id: String): SafStoredDocumentIdentity {
        val uri = requireNotNull(SafContainment.documentUriUsingTree(treeUri, id)).toString()
        return SafStoredDocumentIdentity(uri, id)
    }

    private data class TemporaryCleanupFixture(
        val checkpoint: SafCommitCheckpoint,
        val temporary: SafStoredDocumentIdentity,
        val final: SafStoredDocumentIdentity,
    )

    private fun candidate(checkpoint: SafCommitCheckpoint) = SafCommitDiscoveryCandidate(
        commitId = checkpoint.commitId,
        checkpoint = checkpoint,
        revision = 1L,
        error = null,
        transferActivity = SafCommitTransferActivity.NOT_RECORDED,
        cursorAfter = SafCommitDiscoveryCursor(checkpoint.commitId.value),
    )

    private class SingleCandidateDiscovery(
        private val only: SafCommitDiscoveryCandidate,
    ) : SafCommitCheckpointDiscovery {
        override fun restorationPage(
            after: SafCommitDiscoveryCursor?,
            limit: Int,
        ): SafCommitDiscoveryPageResult {
            if (limit !in 1..SafCommitRestorationPolicy.MAX_PAGE_SIZE) {
                return SafCommitDiscoveryPageResult.Failed(
                    TransferStorageError.StateConflict("restoration_page_limit"),
                )
            }
            if (after != null && only.cursorAfter.afterCommitId <= after.afterCommitId) {
                return SafCommitDiscoveryPageResult.Page(
                    SafCommitDiscoveryPage(emptyList(), hasMore = false, nextCursor = null),
                )
            }
            return SafCommitDiscoveryPageResult.Page(
                SafCommitDiscoveryPage(listOf(only), hasMore = false, nextCursor = only.cursorAfter),
            )
        }

        override fun readForRestoration(commitId: PartialIdentity): SafCommitDiscoveryReadResult =
            if (commitId == only.commitId) SafCommitDiscoveryReadResult.Found(only)
            else SafCommitDiscoveryReadResult.Missing
    }

    private class FixedGrantResolver(
        private val result: SafCommitGrantResolution,
    ) : SafCommitGrantResolver {
        var calls: Int = 0
            private set
        override suspend fun resolve(checkpoint: SafCommitCheckpoint): SafCommitGrantResolution {
            calls++
            return result
        }
    }

    private class FakeRecoveryFactory(
        private val gateway: RecordingSafGateway,
        private val staging: SafStaging,
        private val journal: SafCommitJournal,
    ) : SafCommitRecoveryCoordinatorFactory {
        var calls: Int = 0
            private set

        override fun create(
            budget: SafCommitExecutionBudget,
            isCancelled: () -> Boolean,
        ): SafCommitCoordinator {
            calls++
            return SafCommitCoordinator(
                gateway = BudgetedSafDocumentGateway(gateway, budget),
                staging = BudgetedSafStaging(staging, budget),
                journal = journal,
                isCancelled = isCancelled,
            )
        }
    }

    private class MemoryStaging(private val content: ByteArray) : SafStaging {
        var deleted = false
            private set
        override fun length(identity: PartialIdentity): Long? = if (deleted) null else content.size.toLong()
        override fun open(identity: PartialIdentity): SafOpen =
            SafOpen.Opened(SafReadHandle(ByteArrayInputStream(content)))
        override fun delete(identity: PartialIdentity): Boolean {
            deleted = true
            return true
        }
    }
}
