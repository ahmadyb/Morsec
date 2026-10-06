package app.morsecode.core.storage.transfer

import android.provider.DocumentsContract
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferRestorationCoordinatorTest {

    private val grant = SafTreeGrant(
        grantId = "grant-1",
        treeUri = DocumentsContract.buildTreeDocumentUri("restoration.provider", "root"),
        rootDocumentId = "root",
        authority = "restoration.provider",
        writable = true,
    )
    private val bytes = "explicit-restoration-payload".toByteArray()

    private fun uri(id: String): String =
        DocumentsContract.buildDocumentUriUsingTree(grant.treeUri, id).toString()

    private fun record(id: String, duplicatePolicy: DuplicatePolicy = DuplicatePolicy.RENAME): SafCommitRecord =
        SafCommitRecord(
            sessionId = SessionId("session-$id"),
            transferId = TransferId("transfer-$id"),
            partialId = PartialIdentity(id),
            treeUri = grant.treeUri.toString(),
            rootDocumentId = grant.rootDocumentId,
            parentDocumentId = grant.rootDocumentId,
            expectedFinalName = "movie.bin",
            expectedSizeBytes = bytes.size.toLong(),
            expectedDigest = Sha256Accumulator().apply { update(bytes) }.digest(),
            grantId = grant.grantId,
            strategy = SafCommitStrategy.TEMP_THEN_RENAME,
            duplicatePolicy = duplicatePolicy,
        )

    private fun readyCheckpoint(id: String): SafCommitCheckpoint =
        SafCommitCheckpoint.fromRecord(record(id), grant, SafCommitCheckpointPhase.READY)

    private fun committedCheckpoint(id: String): SafCommitCheckpoint {
        val finalId = "final-$id"
        val finalIdentity = SafStoredDocumentIdentity(uri(finalId), finalId)
        val committedRecord = record(id).copy(
            state = SafCommitState.COMMITTED,
            finalUri = finalIdentity.documentUri,
            finalIdentity = finalIdentity,
            copiedBytes = bytes.size.toLong(),
            verifiedDigest = Sha256Accumulator().apply { update(bytes) }.digest(),
            stagingReleased = true,
        )
        return SafCommitCheckpoint.fromRecord(
            committedRecord,
            grant,
            SafCommitCheckpointPhase.COMMITTED,
        )
    }

    private class MemoryStaging(
        private val bytes: ByteArray,
        var allowDelete: Boolean = true,
    ) : SafStaging {
        var deleteCalls: Int = 0
            private set
        var deleted: Boolean = false
            private set

        override fun length(identity: PartialIdentity): Long? =
            if (deleted) null else bytes.size.toLong()

        override fun open(identity: PartialIdentity): SafOpen = if (deleted) {
            SafOpen.Refused(TransferStorageError.NotFound("partial"))
        } else {
            SafOpen.Opened(SafReadHandle(ByteArrayInputStream(bytes)))
        }

        override fun delete(identity: PartialIdentity): Boolean {
            deleteCalls++
            if (allowDelete) deleted = true
            return allowDelete
        }
    }

    private class FixedDiscovery(ids: List<String>) : SafCheckpointDiscovery {
        private val orderedIds = ids.sorted()
        val requests = mutableListOf<Pair<String?, Int>>()

        override suspend fun page(afterCommitId: String?, limit: Int): SafCheckpointCandidatePage {
            requests += afterCommitId to limit
            val remaining = orderedIds.filter { afterCommitId == null || it > afterCommitId }
            return SafCheckpointCandidatePage(
                commitIds = remaining.take(limit),
                hasMore = remaining.size > limit,
            )
        }
    }

    private class FixedGrantResolver(
        var resolution: PersistedSafGrantResolution = PersistedSafGrantResolution.Available(
            SafTreeGrant(
                grantId = "grant-1",
                treeUri = DocumentsContract.buildTreeDocumentUri("restoration.provider", "root"),
                rootDocumentId = "root",
                authority = "restoration.provider",
                writable = true,
            ),
        ),
        private val onResolve: (() -> Unit)? = null,
    ) : PersistedSafGrantResolver {
        var calls: Int = 0
            private set

        override suspend fun resolve(checkpoint: SafCommitCheckpoint): PersistedSafGrantResolution {
            calls++
            onResolve?.invoke()
            return resolution
        }
    }

    private fun coordinator(
        ids: List<String>,
        journal: SafCommitJournal,
        gateway: SafDocumentGateway = RecordingSafGateway(),
        staging: SafStaging = MemoryStaging(bytes),
        resolver: PersistedSafGrantResolver = FixedGrantResolver(),
        policy: RestorationExecutionPolicy = RestorationExecutionPolicy(maximumElapsedNanos = null),
        clock: RestorationMonotonicClock = RestorationMonotonicClock { 0L },
    ): TransferRestorationCoordinator = TransferRestorationCoordinator(
        discovery = FixedDiscovery(ids),
        journal = journal,
        persistedGrantResolver = resolver,
        gateway = gateway,
        staging = staging,
        executionPolicy = policy,
        monotonicClock = clock,
        ioDispatcher = Dispatchers.IO,
    )

    private fun seed(journal: InMemorySafCommitJournal, checkpoint: SafCommitCheckpoint) {
        assertTrue("valid checkpoint should seed", journal.save(checkpoint))
    }

    @Test
    fun `explicit restoration resumes the Room-independent SAF protocol through its existing coordinator`() = runBlocking {
        val journal = InMemorySafCommitJournal()
        seed(journal, readyCheckpoint("ready-1"))
        val gateway = RecordingSafGateway()
        val staging = MemoryStaging(bytes)
        val report = coordinator(listOf("ready-1"), journal, gateway, staging).restore()

        assertEquals(1, report.discoveredCheckpointCount)
        assertEquals(1, report.processedCheckpointCount)
        assertEquals(1, report.completedCheckpointCount)
        assertEquals(RestorationClassification.RESUMED_AND_COMMITTED, report.outcomes.single().classification)
        assertTrue(report.outcomes.single().delivered)
        assertTrue(staging.deleted)
        assertEquals(1, gateway.countOf("create:"))
        assertEquals(1, gateway.countOf("openWrite:"))
        assertEquals(1, gateway.countOf("rename:"))
        assertEquals(SafCommitCheckpointPhase.COMMITTED, journal.load(PartialIdentity("ready-1"))?.phase)
        val safeRendering = "$report ${report.outcomes}"
        assertFalse(safeRendering.contains("ready-1"))
        assertFalse(safeRendering.contains(grant.grantId))
        assertFalse(safeRendering.contains(grant.treeUri.toString()))
        assertFalse(safeRendering.contains(requireNotNull(record("ready-1").expectedDigest).hex))
    }

    @Test
    fun `malformed persisted checkpoint is rejected before grant resolution or provider access`() = runBlocking {
        val journal = InMemorySafCommitJournal()
        val malformed = readyCheckpoint("bad-version").copy(version = SafCommitCheckpoint.CURRENT_VERSION + 1)
        journal.checkpoints[malformed.commitId] = malformed
        val gateway = RecordingSafGateway()
        val resolver = FixedGrantResolver()
        val report = coordinator(listOf("bad-version"), journal, gateway, resolver = resolver).restore()

        assertEquals(RestorationClassification.INVALID_PERSISTED_STATE, report.outcomes.single().classification)
        assertEquals(RestorationIssue.UNSUPPORTED_CHECKPOINT, report.outcomes.single().issue)
        assertEquals(0, resolver.calls)
        assertEquals(0, gateway.providerCallCount)
        assertEquals(0, report.completedCheckpointCount)
    }

    @Test
    fun `all persisted-grant classifications stop before any provider operation`() = runBlocking {
        val resolutions = listOf(
            PersistedSafGrantResolution.Revoked to RestorationClassification.AWAIT_PERMISSION,
            PersistedSafGrantResolution.InsufficientScope to RestorationClassification.AWAIT_SCOPE_PERMISSION,
            PersistedSafGrantResolution.Malformed to RestorationClassification.INVALID_PERSISTED_STATE,
            PersistedSafGrantResolution.Mismatched to RestorationClassification.MANUAL_RECONCILIATION_REQUIRED,
        )
        resolutions.forEachIndexed { index, (resolution, expected) ->
            val id = "grant-$index"
            val journal = InMemorySafCommitJournal()
            seed(journal, readyCheckpoint(id))
            val gateway = RecordingSafGateway()
            val report = coordinator(
                listOf(id),
                journal,
                gateway,
                resolver = FixedGrantResolver(resolution),
            ).restore()

            assertEquals(expected, report.outcomes.single().classification)
            assertEquals(0, gateway.providerCallCount)
            assertEquals(0, report.completedCheckpointCount)
        }
    }

    @Test
    fun `committed and cancelled terminal rows are handled without grant or provider access`() = runBlocking {
        val journal = InMemorySafCommitJournal()
        seed(journal, committedCheckpoint("terminal-a"))
        seed(
            journal,
            SafCommitCheckpoint.fromRecord(
                record("terminal-b"),
                grant,
                SafCommitCheckpointPhase.CANCELLED,
            ),
        )
        val gateway = RecordingSafGateway()
        val resolver = FixedGrantResolver(PersistedSafGrantResolution.Revoked)

        val report = coordinator(listOf("terminal-a", "terminal-b"), journal, gateway, resolver = resolver).restore()

        assertEquals(2, report.processedCheckpointCount)
        assertEquals(1, report.completedCheckpointCount)
        assertEquals(RestorationClassification.ALREADY_COMPLETE, report.outcomes[0].classification)
        assertEquals(RestorationClassification.CANCELLED, report.outcomes[1].classification)
        assertEquals(0, resolver.calls)
        assertEquals(0, gateway.providerCallCount)
    }

    @Test
    fun `deterministic discovery stops at the checkpoint bound and returns an opaque cursor`() = runBlocking {
        val journal = InMemorySafCommitJournal()
        seed(journal, committedCheckpoint("bounded-a"))
        seed(
            journal,
            SafCommitCheckpoint.fromRecord(
                record("bounded-b"),
                grant,
                SafCommitCheckpointPhase.CANCELLED,
            ),
        )
        seed(journal, readyCheckpoint("bounded-c"))
        val discovery = FixedDiscovery(listOf("bounded-a", "bounded-b", "bounded-c"))
        val gateway = RecordingSafGateway()
        val restoration = TransferRestorationCoordinator(
            discovery = discovery,
            journal = journal,
            persistedGrantResolver = FixedGrantResolver(PersistedSafGrantResolution.Revoked),
            gateway = gateway,
            staging = MemoryStaging(bytes),
            executionPolicy = RestorationExecutionPolicy(
                maximumCheckpointsPerRun = 2,
                discoveryPageSize = 2,
                maximumElapsedNanos = null,
            ),
            ioDispatcher = Dispatchers.IO,
        )

        val report = restoration.restore()

        assertEquals(3, report.discoveredCheckpointCount)
        assertEquals(2, report.processedCheckpointCount)
        assertEquals(1, report.deferredCheckpointCount)
        assertTrue(report.hasMoreCandidates)
        assertNotNull(report.continuation)
        assertFalse(report.continuation.toString().contains("bounded-b"))
        assertEquals(RestorationClassification.BUDGET_EXHAUSTED, report.outcomes.last().classification)
        assertEquals(0, gateway.providerCallCount)
        assertEquals("bounded-b", discovery.requests[1].first)
        assertEquals(1, discovery.requests[1].second)
    }

    @Test
    fun `oversized corrupt discovery key is redacted and never retained in the continuation`() = runBlocking {
        val oversizedId = "x".repeat(MAX_SAF_COMMIT_ID_LENGTH_CHARS + 4_096)
        val gateway = RecordingSafGateway()
        val report = coordinator(
            ids = listOf(oversizedId),
            journal = InMemorySafCommitJournal(),
            gateway = gateway,
        ).restore()

        assertEquals(RestorationClassification.INVALID_PERSISTED_STATE, report.outcomes.single().classification)
        assertEquals(RestorationIssue.MALFORMED_CHECKPOINT, report.outcomes.single().issue)
        assertEquals("checkpoint#invalid", report.outcomes.single().checkpoint.toString())
        assertNotNull(report.continuation)
        assertNull(report.continuation?.rawAfterCommitId())
        assertFalse(report.toString().contains(oversizedId))
        assertEquals(0, gateway.providerCallCount)
        assertTrue(report.discoveryFailed)
    }

    @Test
    fun `cleanup failure remains typed and a retry does not recopy a verified final`() = runBlocking {
        val journal = InMemorySafCommitJournal()
        seed(journal, readyCheckpoint("cleanup-1"))
        val gateway = RecordingSafGateway()
        val staging = MemoryStaging(bytes, allowDelete = false)
        val restoration = coordinator(listOf("cleanup-1"), journal, gateway, staging)

        val first = restoration.restore()
        assertEquals(RestorationClassification.RETRY_STAGING_CLEANUP, first.outcomes.single().classification)
        assertEquals(setOf(SafCleanupPending.STAGING), first.outcomes.single().pendingCleanup)
        assertTrue(first.outcomes.single().delivered)
        assertEquals(1, gateway.countOf("openWrite:"))
        assertEquals(1, gateway.countOf("create:"))

        val writeCount = gateway.countOf("openWrite:")
        val createCount = gateway.countOf("create:")
        staging.allowDelete = true
        val second = restoration.restore()

        assertEquals(1, second.completedCheckpointCount)
        assertEquals(0, second.pendingCleanupItemCount)
        assertEquals(writeCount, gateway.countOf("openWrite:"))
        assertEquals(createCount, gateway.countOf("create:"))
        assertEquals(2, staging.deleteCalls)
        assertTrue(staging.deleted)
    }

    @Test
    fun `revision conflict reloads and reclassifies the latest checkpoint before retry`() = runBlocking {
        val backing = InMemorySafCommitJournal()
        seed(backing, readyCheckpoint("conflict-1"))
        val journal = ConflictOnceJournal(backing)
        val gateway = RecordingSafGateway()

        val report = coordinator(listOf("conflict-1"), journal, gateway).restore()

        assertEquals(RestorationClassification.RESUMED_AND_COMMITTED, report.outcomes.single().classification)
        assertTrue(journal.injected)
        assertTrue(journal.reloadedAfterConflict)
        assertEquals(SafCommitCheckpointPhase.COMMITTED, backing.load(PartialIdentity("conflict-1"))?.phase)
        assertEquals(1, gateway.countOf("create:"))
    }

    @Test
    fun `fake monotonic time exhausts the observation budget without provider access`() = runBlocking {
        val journal = InMemorySafCommitJournal()
        seed(journal, readyCheckpoint("time-1"))
        var now = 0L
        val resolver = FixedGrantResolver(onResolve = { now = 100L })
        val gateway = RecordingSafGateway()
        val report = coordinator(
            listOf("time-1"),
            journal,
            gateway,
            resolver = resolver,
            policy = RestorationExecutionPolicy(maximumElapsedNanos = 10L),
            clock = RestorationMonotonicClock { now },
        ).restore()

        assertEquals(RestorationClassification.BUDGET_EXHAUSTED, report.outcomes.single().classification)
        assertEquals(RestorationIssue.EXECUTION_LIMIT_REACHED, report.outcomes.single().issue)
        assertEquals(0, gateway.providerCallCount)
        assertEquals(0, report.completedCheckpointCount)
    }

    @Test
    fun `mutation and cleanup limits stop before exceeding their configured budgets`() = runBlocking {
        val mutationJournal = InMemorySafCommitJournal()
        seed(mutationJournal, readyCheckpoint("mutation-bound"))
        val mutationGateway = RecordingSafGateway()
        val mutationReport = coordinator(
            ids = listOf("mutation-bound"),
            journal = mutationJournal,
            gateway = mutationGateway,
            policy = RestorationExecutionPolicy(
                maximumProviderMutations = 0,
                maximumElapsedNanos = null,
            ),
        ).restore()

        assertEquals(RestorationClassification.BUDGET_EXHAUSTED, mutationReport.outcomes.single().classification)
        assertEquals(0, mutationGateway.countOf("create:"))
        assertEquals(0, mutationGateway.countOf("openWrite:"))
        assertEquals(0, mutationReport.completedCheckpointCount)

        val cleanupJournal = InMemorySafCommitJournal()
        seed(cleanupJournal, readyCheckpoint("cleanup-bound"))
        val cleanupGateway = RecordingSafGateway()
        val cleanupStaging = MemoryStaging(bytes)
        val cleanupReport = coordinator(
            ids = listOf("cleanup-bound"),
            journal = cleanupJournal,
            gateway = cleanupGateway,
            staging = cleanupStaging,
            policy = RestorationExecutionPolicy(
                maximumCleanupAttempts = 0,
                maximumElapsedNanos = null,
            ),
        ).restore()

        assertEquals(RestorationClassification.RETRY_STAGING_CLEANUP, cleanupReport.outcomes.single().classification)
        assertEquals(0, cleanupStaging.deleteCalls)
        assertTrue(cleanupReport.outcomes.single().delivered)
        assertEquals(1, cleanupGateway.countOf("openWrite:"))
    }

    @Test
    fun `provider failure remains reconciliation and is never reported as success`() = runBlocking {
        val journal = InMemorySafCommitJournal()
        seed(journal, readyCheckpoint("unknown-provider"))
        val gateway = RecordingSafGateway(queryFailure = TransferStorageError.ProviderFailure("document_provider"))

        val report = coordinator(listOf("unknown-provider"), journal, gateway).restore()

        assertTrue(
            report.outcomes.single().classification in setOf(
                RestorationClassification.RECONCILIATION_REQUIRED,
                RestorationClassification.RETRYABLE_FAILURE,
            ),
        )
        assertEquals(0, report.completedCheckpointCount)
        assertFalse(report.outcomes.single().delivered)
    }

    @Test
    fun `process-local lock excludes one checkpoint but leaves unrelated checkpoints independent`() {
        val locks = ProcessLocalCheckpointLocks.shared
        val initialEntries = locks.entryCountForTest()
        val first = requireNotNull(locks.tryAcquire("same-checkpoint"))
        assertNull(locks.tryAcquire("same-checkpoint"))
        val unrelated = locks.tryAcquire("unrelated-checkpoint")
        assertNotNull(unrelated)

        unrelated?.close()
        first.close()
        val reacquired = locks.tryAcquire("same-checkpoint")
        assertNotNull(reacquired)
        reacquired?.close()
        assertEquals(initialEntries, locks.entryCountForTest())
    }

    @Test
    fun `concurrent coordinators skip a locked checkpoint but restore an unrelated one`() = runBlocking {
        val journal = InMemorySafCommitJournal()
        seed(journal, readyCheckpoint("same-checkpoint"))
        seed(journal, readyCheckpoint("unrelated-checkpoint"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstGateway = BlockingSafGateway(RecordingSafGateway(), entered, release)
        val firstScope = CoroutineScope(Dispatchers.IO + Job())
        val firstRun = firstScope.launch {
            coordinator(listOf("same-checkpoint"), journal, firstGateway).restore()
        }

        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val secondGateway = RecordingSafGateway()
            val second = coordinator(
                listOf("same-checkpoint", "unrelated-checkpoint"),
                journal,
                secondGateway,
            ).restore()

            assertEquals(RestorationClassification.CHECKPOINT_LOCKED, second.outcomes[0].classification)
            assertEquals(RestorationClassification.RESUMED_AND_COMMITTED, second.outcomes[1].classification)
            assertEquals(1, secondGateway.countOf("create:"))
            assertEquals(1, secondGateway.countOf("openWrite:"))
            assertEquals(1, second.completedCheckpointCount)
        } finally {
            release.countDown()
            firstRun.join()
            firstScope.cancel()
        }

        assertEquals(SafCommitCheckpointPhase.COMMITTED, journal.load(PartialIdentity("same-checkpoint"))?.phase)
    }

    @Test
    fun `cancellation waits for an in-flight SAF boundary and does not detach or publish work`() = runBlocking {
        val journal = InMemorySafCommitJournal()
        seed(journal, readyCheckpoint("cancel-1"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delegate = RecordingSafGateway()
        val blocking = BlockingSafGateway(delegate, entered, release)
        val staging = MemoryStaging(bytes)
        val reportReference = AtomicReference<RestorationRunReport?>()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val run = scope.launch {
            reportReference.set(coordinator(listOf("cancel-1"), journal, blocking, staging).restore())
        }

        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            run.cancel()
            assertFalse("the synchronous provider boundary must remain joined", run.isCompleted)
        } finally {
            release.countDown()
        }
        run.join()
        val report = reportReference.get()
        assertNotNull(report)
        assertTrue(requireNotNull(report).cancelled)
        assertFalse(staging.deleted)
        assertEquals(0, delegate.countOf("create:"))
        assertEquals(0, delegate.countOf("openWrite:"))
        assertTrue(journal.load(PartialIdentity("cancel-1"))?.phase != SafCommitCheckpointPhase.COMMITTED)
        scope.cancel()
    }

    private class ConflictOnceJournal(
        private val delegate: SafCommitJournal,
    ) : SafCommitJournal {
        var injected = false
            private set
        var reloadedAfterConflict = false
            private set

        override fun read(commitId: PartialIdentity): SafCommitJournalRead {
            if (injected) reloadedAfterConflict = true
            return delegate.read(commitId)
        }

        override fun write(
            checkpoint: SafCommitCheckpoint,
            expectedRevision: Long,
        ): SafCommitJournalWrite {
            if (!injected) {
                injected = true
                val current = delegate.read(checkpoint.commitId) as SafCommitJournalRead.Found
                val phase = if (checkpoint.phase == SafCommitCheckpointPhase.STAGING_VERIFICATION_INTENT) {
                    SafCommitCheckpointPhase.STAGING_VERIFIED
                } else {
                    SafCommitCheckpointPhase.STAGING_VERIFICATION_INTENT
                }
                val concurrent = checkpoint.copy(phase = phase)
                assertTrue(delegate.write(concurrent, current.entry.revision) is SafCommitJournalWrite.Saved)
                return SafCommitJournalWrite.Conflict(TransferStorageError.StateConflict("stale_journal_revision"))
            }
            return delegate.write(checkpoint, expectedRevision)
        }
    }

    private class BlockingSafGateway(
        private val delegate: SafDocumentGateway,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : SafDocumentGateway by delegate {
        private var blocked = false

        override fun recheckPersistedGrant(
            grant: SafTreeGrant,
            operation: SafContainmentOperation,
        ): TransferStorageError? {
            if (!blocked) {
                blocked = true
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            return delegate.recheckPersistedGrant(grant, operation)
        }
    }
}
