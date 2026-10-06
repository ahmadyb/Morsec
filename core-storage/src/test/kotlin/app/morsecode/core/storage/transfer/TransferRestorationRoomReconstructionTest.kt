package app.morsecode.core.storage.transfer

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.morsecode.core.data.db.MORSE_MIGRATION_1_2
import app.morsecode.core.data.db.MorseDatabase
import app.morsecode.core.data.db.SafGrantEntity
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room close/discard/reopen reconstruction tests. These model persisted-state
 * reconstruction; they do not claim Android OS process-death coverage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferRestorationRoomReconstructionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databases = linkedMapOf<String, MorseDatabase>()
    private val names = mutableListOf<String>()
    private var provider: FakeSafProvider? = null
    private val stagedFiles = mutableListOf<Pair<AppPrivatePartialStore, PartialIdentity>>()
    private var treeUri: Uri? = null
    private var authority: String? = null
    private var treePermissionHeld = false

    private val payload = "room-reopened-restoration-payload".toByteArray()

    @After
    fun closeResources() {
        databases.values.forEach(MorseDatabase::close)
        databases.clear()
        stagedFiles.forEach { (store, identity) -> store.delete(identity) }
        stagedFiles.clear()
        names.forEach { name -> context.deleteDatabase(name) }
        names.clear()
        if (treePermissionHeld) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    requireNotNull(treeUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            treePermissionHeld = false
        }
        provider = null
    }

    private fun registerProvider() {
        if (provider != null) return
        val providerAuthority = "restoration.${UUID.randomUUID()}.provider"
        authority = providerAuthority
        treeUri = DocumentsContract.buildTreeDocumentUri(providerAuthority, "root")
        provider = Robolectric.buildContentProvider(FakeSafProvider::class.java)
            .create(ProviderInfo().apply {
                this.authority = providerAuthority
                grantUriPermissions = true
            })
            .get()
            .also { it.reset("root", requireNotNull(treeUri)) }
        context.contentResolver.takePersistableUriPermission(
            requireNotNull(treeUri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        treePermissionHeld = true
    }

    private fun openDatabase(name: String = "restoration-${UUID.randomUUID()}.db"): MorseDatabase {
        databases.remove(name)?.close()
        val database = Room.databaseBuilder(context, MorseDatabase::class.java, name)
            .addMigrations(MORSE_MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        databases[name] = database
        if (name !in names) names += name
        return database
    }

    private fun reopenDatabase(name: String): MorseDatabase = openDatabase(name)

    private fun exactGrant(grantId: String = "1"): SafTreeGrant {
        val tree = requireNotNull(treeUri)
        return SafTreeGrant(
            grantId = grantId,
            treeUri = tree,
            rootDocumentId = requireNotNull(SafContainment.treeDocumentIdOf(tree)),
            authority = requireNotNull(authority),
            writable = true,
        )
    }

    private fun record(
        id: String,
        grant: SafTreeGrant = exactGrant(),
        parentDocumentId: String = grant.rootDocumentId,
    ): SafCommitRecord = SafCommitRecord(
        sessionId = SessionId("room-session-$id"),
        transferId = TransferId("room-transfer-$id"),
        partialId = PartialIdentity(id),
        treeUri = grant.treeUri.toString(),
        rootDocumentId = grant.rootDocumentId,
        parentDocumentId = parentDocumentId,
        expectedFinalName = "restored.bin",
        expectedSizeBytes = payload.size.toLong(),
        expectedDigest = Sha256Accumulator().apply { update(payload) }.digest(),
        grantId = grant.grantId,
        strategy = SafCommitStrategy.TEMP_THEN_RENAME,
        duplicatePolicy = DuplicatePolicy.RENAME,
    )

    private fun storedIdentity(id: String): SafStoredDocumentIdentity = SafStoredDocumentIdentity(
        documentUri = DocumentsContract.buildDocumentUriUsingTree(requireNotNull(treeUri), id).toString(),
        documentId = id,
    )

    private class TestStaging(
        private val content: ByteArray,
    ) : SafStaging {
        var deleted = false
            private set

        override fun length(identity: PartialIdentity): Long? = if (deleted) null else content.size.toLong()

        override fun open(identity: PartialIdentity): SafOpen = if (deleted) {
            SafOpen.Refused(TransferStorageError.NotFound("partial"))
        } else {
            SafOpen.Opened(SafReadHandle(ByteArrayInputStream(content)))
        }

        override fun delete(identity: PartialIdentity): Boolean {
            deleted = true
            return true
        }
    }

    private class GrantResolver(private val grant: SafTreeGrant) : PersistedSafGrantResolver {
        override suspend fun resolve(checkpoint: SafCommitCheckpoint): PersistedSafGrantResolution =
            PersistedSafGrantResolution.Available(grant)
    }

    @Test
    fun `Room keyset discovery is bounded ordered and excludes clean terminal rows`() = runBlocking {
        registerProvider()
        val database = openDatabase()
        val journal = RoomSafCommitJournal(database, Dispatchers.IO)
        val approved = exactGrant("grant-room")
        val ready = SafCommitCheckpoint.fromRecord(record("discover-a", approved), approved)
        val final = storedIdentity("discover-final")
        val deliveredRecord = record("discover-b", approved).copy(
            state = SafCommitState.PUBLISHED_OR_VISIBLE,
            finalUri = final.documentUri,
            finalIdentity = final,
            copiedBytes = payload.size.toLong(),
            verifiedDigest = Sha256Accumulator().apply { update(payload) }.digest(),
            pendingCleanup = setOf(SafCleanupPending.STAGING),
        )
        val published = SafCommitCheckpoint.fromRecord(
            deliveredRecord,
            approved,
            SafCommitCheckpointPhase.PUBLISHED,
        )
        val terminalFinal = storedIdentity("discover-terminal-final")
        val committedRecord = record("discover-z-committed", approved).copy(
            state = SafCommitState.COMMITTED,
            finalUri = terminalFinal.documentUri,
            finalIdentity = terminalFinal,
            copiedBytes = payload.size.toLong(),
            verifiedDigest = Sha256Accumulator().apply { update(payload) }.digest(),
            stagingReleased = true,
        )
        val committed = SafCommitCheckpoint.fromRecord(
            committedRecord,
            approved,
            SafCommitCheckpointPhase.COMMITTED,
        )
        val cancelled = SafCommitCheckpoint.fromRecord(
            record("discover-y-cancelled", approved),
            approved,
            SafCommitCheckpointPhase.CANCELLED,
        )
        listOf(ready, published, committed, cancelled).forEach { checkpoint ->
            assertTrue(journal.write(checkpoint, 0L) is SafCommitJournalWrite.Saved)
        }

        val discovery = RoomSafCheckpointDiscovery(database.transferPartialDao())
        val first = discovery.page(afterCommitId = null, limit = 1)
        val second = discovery.page(afterCommitId = first.commitIds.single(), limit = 1)

        assertEquals(listOf("discover-a"), first.commitIds)
        assertTrue(first.hasMore)
        assertEquals(listOf("discover-b"), second.commitIds)
        assertFalse(second.hasMore)
        assertFalse(first.toString().contains("discover-a"))
    }

    @Test
    fun `Room checkpoint and provider state reconstruct across create and copy crash boundaries`() = runBlocking {
        registerProvider()
        val phases = listOf(
            SafCommitCheckpointPhase.TEMPORARY_CREATE_INTENT,
            SafCommitCheckpointPhase.TEMPORARY_CREATED,
            SafCommitCheckpointPhase.COPY_STARTED,
            SafCommitCheckpointPhase.COPY_COMPLETED,
            SafCommitCheckpointPhase.FLUSH_INTENT,
            SafCommitCheckpointPhase.FLUSH_COMPLETED,
            SafCommitCheckpointPhase.PROVIDER_VERIFICATION_INTENT,
            SafCommitCheckpointPhase.PROVIDER_VERIFIED,
        )

        phases.forEach { phase ->
            val databaseName = "restore-boundary-${phase.id}-${UUID.randomUUID()}.db"
            val firstDatabase = openDatabase(databaseName)
            val grant = exactGrant("grant-${phase.id}")
            val source = record("room-boundary-${phase.id}", grant)
            val temporaryId = "temporary-${phase.id}"
            val temporary = storedIdentity(temporaryId)
            val savedRecord = if (phase == SafCommitCheckpointPhase.TEMPORARY_CREATE_INTENT) {
                source
            } else {
                source.copy(
                    state = SafCommitState.COPY_STARTED,
                    temporaryUri = temporary.documentUri,
                    temporaryIdentity = temporary,
                    copiedBytes = payload.size.toLong(),
                    verifiedDigest = Sha256Accumulator().apply { update(payload) }.digest(),
                )
            }
            val checkpoint = SafCommitCheckpoint.fromRecord(savedRecord, grant, phase)
            val firstJournal = RoomSafCommitJournal(firstDatabase, Dispatchers.IO)
            assertEquals(SafCommitJournalWrite.Saved(1L), firstJournal.write(checkpoint, 0L))

            // Simulate process state loss: close every Room adapter/DAO user and
            // reconstruct from the same on-disk DB name and the provider double.
            val reopenedDatabase = reopenDatabase(databaseName)
            val reopenedJournal = RoomSafCommitJournal(reopenedDatabase, Dispatchers.IO)
            val gateway = RecordingSafGateway()
            val temporaryName = temporaryDocumentName(source.expectedFinalName, source.partialId)
            gateway.addNamed(temporary.documentUri, temporaryName)
            gateway.written[temporary.documentUri] = payload
            val staging = TestStaging(payload)
            val restoration = TransferRestorationCoordinator(
                discovery = RoomSafCheckpointDiscovery(reopenedDatabase.transferPartialDao()),
                journal = reopenedJournal,
                persistedGrantResolver = GrantResolver(grant),
                gateway = gateway,
                staging = staging,
                executionPolicy = RestorationExecutionPolicy(maximumElapsedNanos = null),
                ioDispatcher = Dispatchers.IO,
            )

            val report = restoration.restore()

            assertEquals("${phase.id}: ${report.outcomes}", RestorationClassification.RESUMED_AND_COMMITTED, report.outcomes.single().classification)
            assertTrue(staging.deleted)
            assertEquals(0, gateway.countOf("openWrite:"))
            assertEquals(1, gateway.countOf("rename:"))
            assertEquals(
                SafCommitCheckpointPhase.COMMITTED,
                reopenedJournal.read(checkpoint.commitId).let { (it as SafCommitJournalRead.Found).entry.checkpoint.phase },
            )
        }
    }

    @Test
    fun `Room reopen at final rename boundaries reconciles exact returned identity without recopying`() = runBlocking {
        registerProvider()
        val phases = listOf(
            SafCommitCheckpointPhase.FINAL_RENAME_INTENT,
            SafCommitCheckpointPhase.FINAL_RENAMED,
            SafCommitCheckpointPhase.RENAME_RECONCILIATION_INTENT,
            SafCommitCheckpointPhase.FINAL_VERIFICATION_INTENT,
        )

        phases.forEach { phase ->
            val databaseName = "restore-final-${phase.id}-${UUID.randomUUID()}.db"
            val firstDatabase = openDatabase(databaseName)
            val grant = exactGrant("grant-final-${phase.id}")
            val source = record("room-final-${phase.id}", grant)
            val sameIdentityRename = phase == SafCommitCheckpointPhase.FINAL_RENAME_INTENT
            val temporary = if (sameIdentityRename) {
                storedIdentity("final-in-place-${phase.id}")
            } else {
                storedIdentity("temporary-before-${phase.id}")
            }
            val final = if (sameIdentityRename) temporary else storedIdentity("final-returned-${phase.id}")
            val renamedRecord = source.copy(
                state = SafCommitState.RENAMED,
                temporaryUri = temporary.documentUri,
                temporaryIdentity = temporary,
                copiedBytes = payload.size.toLong(),
                verifiedDigest = Sha256Accumulator().apply { update(payload) }.digest(),
            )
            val checkpoint = SafCommitCheckpoint.fromRecord(renamedRecord, grant, phase).copy(
                returnedRenameUri = final.documentUri.takeUnless { sameIdentityRename },
                returnedRenameIdentity = final.takeUnless { sameIdentityRename },
            )
            assertEquals(
                SafCommitJournalWrite.Saved(1L),
                RoomSafCommitJournal(firstDatabase, Dispatchers.IO).write(checkpoint, 0L),
            )

            val reopened = reopenDatabase(databaseName)
            val journal = RoomSafCommitJournal(reopened, Dispatchers.IO)
            val gateway = RecordingSafGateway()
            gateway.addNamed(final.documentUri, source.expectedFinalName)
            gateway.written[final.documentUri] = payload
            val staging = TestStaging(payload)
            val report = TransferRestorationCoordinator(
                discovery = RoomSafCheckpointDiscovery(reopened.transferPartialDao()),
                journal = journal,
                persistedGrantResolver = GrantResolver(grant),
                gateway = gateway,
                staging = staging,
                executionPolicy = RestorationExecutionPolicy(maximumElapsedNanos = null),
                ioDispatcher = Dispatchers.IO,
            ).restore()

            assertEquals(
                "${phase.id}: ${report.outcomes}",
                RestorationClassification.RESUMED_AND_COMMITTED,
                report.outcomes.single().classification,
            )
            assertTrue(staging.deleted)
            assertEquals(0, gateway.countOf("openWrite:"))
            assertEquals(0, gateway.countOf("create:"))
            assertEquals(0, gateway.countOf("rename:"))
            assertEquals(
                SafCommitCheckpointPhase.COMMITTED,
                (journal.read(checkpoint.commitId) as SafCommitJournalRead.Found).entry.checkpoint.phase,
            )
        }
    }

    @Test
    fun `Room reopen resumes exact provider temporary and backup cleanup intents`() = runBlocking {
        registerProvider()
        val cases = listOf(
            SafCleanupPending.PROVIDER_TEMPORARY to SafCommitCheckpointPhase.PROVIDER_TEMPORARY_DELETE_INTENT,
            SafCleanupPending.PROVIDER_TEMPORARY to SafCommitCheckpointPhase.PROVIDER_TEMPORARY_DELETE_OBSERVED,
            SafCleanupPending.BACKUP to SafCommitCheckpointPhase.BACKUP_DELETE_INTENT,
            SafCleanupPending.BACKUP to SafCommitCheckpointPhase.BACKUP_DELETE_OBSERVED,
        )

        cases.forEachIndexed { index, (cleanupType, phase) ->
            val databaseName = "restore-cleanup-${phase.id}-${UUID.randomUUID()}.db"
            val firstDatabase = openDatabase(databaseName)
            val isBackup = cleanupType == SafCleanupPending.BACKUP
            val grant = exactGrant("grant-cleanup-$index")
            val source = record("room-cleanup-$index", grant).copy(
                duplicatePolicy = if (isBackup) DuplicatePolicy.OVERWRITE else DuplicatePolicy.RENAME,
            )
            val final = storedIdentity("cleanup-final-$index")
            val cleanupTarget = storedIdentity("cleanup-target-$index")
            val replacement = if (isBackup) {
                storedIdentity("cleanup-replacement-$index")
            } else {
                cleanupTarget
            }
            val existing = if (isBackup) storedIdentity("cleanup-existing-$index") else null
            val scope = SafRenameScope.fromRecord(source, grant)
            val history = buildList {
                if (existing != null) {
                    add(
                        SafRenameEvidence(
                            before = existing,
                            returned = cleanupTarget,
                            reconciliation = SafRenameReconciliation.RESOLVED_TO_RETURNED,
                            scope = scope,
                            phase = SafRenamePhase.BACKUP_RENAME,
                            sequence = 0,
                        ),
                    )
                }
                add(
                    SafRenameEvidence(
                        before = replacement,
                        returned = final,
                        reconciliation = SafRenameReconciliation.RESOLVED_TO_RETURNED,
                        scope = scope,
                        phase = SafRenamePhase.FINAL_PROMOTION,
                        sequence = if (existing == null) 0 else 1,
                    ),
                )
            }
            val cleanupRecord = source.copy(
                state = if (isBackup) SafCommitState.BACKUP_CLEANUP_PENDING
                else SafCommitState.PROVIDER_TEMPORARY_CLEANUP_PENDING,
                temporaryUri = replacement.documentUri,
                temporaryIdentity = replacement,
                finalUri = final.documentUri,
                finalIdentity = final,
                existingIdentity = existing,
                backupIdentity = cleanupTarget.takeIf { isBackup },
                renameHistory = history,
                verifiedDigest = Sha256Accumulator().apply { update(payload) }.digest(),
                pendingCleanup = setOf(cleanupType),
                copiedBytes = payload.size.toLong(),
                stagingReleased = true,
            )
            val checkpoint = SafCommitCheckpoint.fromRecord(cleanupRecord, grant, phase)
            assertEquals(
                SafCommitJournalWrite.Saved(1L),
                RoomSafCommitJournal(firstDatabase, Dispatchers.IO).write(checkpoint, 0L),
            )

            val reopened = reopenDatabase(databaseName)
            val journal = RoomSafCommitJournal(reopened, Dispatchers.IO)
            val gateway = RecordingSafGateway()
            gateway.addNamed(final.documentUri, source.expectedFinalName)
            gateway.written[final.documentUri] = payload
            val cleanupName = if (isBackup) {
                backupDocumentName(source.expectedFinalName, source.partialId)
            } else {
                temporaryDocumentName(source.expectedFinalName, source.partialId)
            }
            gateway.addNamed(cleanupTarget.documentUri, cleanupName)
            gateway.written[cleanupTarget.documentUri] = byteArrayOf(1, 2, 3)
            val staging = TestStaging(payload).also { assertTrue(it.delete(source.partialId)) }
            val report = TransferRestorationCoordinator(
                discovery = RoomSafCheckpointDiscovery(reopened.transferPartialDao()),
                journal = journal,
                persistedGrantResolver = GrantResolver(grant),
                gateway = gateway,
                staging = staging,
                executionPolicy = RestorationExecutionPolicy(maximumElapsedNanos = null),
                ioDispatcher = Dispatchers.IO,
            ).restore()

            assertEquals(
                "${phase.id}: ${report.outcomes}",
                RestorationClassification.RESUMED_AND_COMMITTED,
                report.outcomes.single().classification,
            )
            assertEquals(1, gateway.countOf("deleteAndReconcile:"))
            assertFalse(cleanupTarget.documentUri in gateway.existing)
            assertEquals(0, gateway.countOf("openWrite:"))
            assertEquals(0, gateway.countOf("rename:"))
            assertEquals(0, report.pendingCleanupItemCount)
        }
    }

    @Test
    fun `Room reconstruction completes cleanup of a published checkpoint without recopying final`() = runBlocking {
        registerProvider()
        val databaseName = "restore-published-${UUID.randomUUID()}.db"
        val database = openDatabase(databaseName)
        val journal = RoomSafCommitJournal(database, Dispatchers.IO)
        val grant = exactGrant("grant-published")
        val source = record("room-published", grant)
        val final = storedIdentity("published-final")
        val delivered = source.copy(
            state = SafCommitState.PUBLISHED_OR_VISIBLE,
            finalUri = final.documentUri,
            finalIdentity = final,
            copiedBytes = payload.size.toLong(),
            verifiedDigest = Sha256Accumulator().apply { update(payload) }.digest(),
            pendingCleanup = setOf(SafCleanupPending.STAGING),
        )
        val checkpoint = SafCommitCheckpoint.fromRecord(
            delivered,
            grant,
            SafCommitCheckpointPhase.PUBLISHED,
        )
        assertEquals(SafCommitJournalWrite.Saved(1L), journal.write(checkpoint, 0L))
        val reopened = reopenDatabase(databaseName)
        val reopenedJournal = RoomSafCommitJournal(reopened, Dispatchers.IO)
        val gateway = RecordingSafGateway()
        gateway.addNamed(final.documentUri, source.expectedFinalName)
        gateway.written[final.documentUri] = payload
        val staging = TestStaging(payload)

        val report = TransferRestorationCoordinator(
            discovery = RoomSafCheckpointDiscovery(reopened.transferPartialDao()),
            journal = reopenedJournal,
            persistedGrantResolver = GrantResolver(grant),
            gateway = gateway,
            staging = staging,
            executionPolicy = RestorationExecutionPolicy(maximumElapsedNanos = null),
            ioDispatcher = Dispatchers.IO,
        ).restore()

        assertEquals(RestorationClassification.RESUMED_AND_COMMITTED, report.outcomes.single().classification)
        assertTrue(staging.deleted)
        assertEquals(0, gateway.countOf("openWrite:"))
        assertEquals(0, gateway.countOf("rename:"))
        assertEquals(SafCommitCheckpointPhase.COMMITTED, reopenedJournal.read(checkpoint.commitId)
            .let { (it as SafCommitJournalRead.Found).entry.checkpoint.phase })
    }

    @Test
    fun `production factory wires Room journal discovery DocumentsContract and real grant resolution inertly`() = runBlocking {
        registerProvider()
        val database = openDatabase()
        val incoming = File(context.filesDir, "incoming")
        val existedBefore = incoming.exists()

        val coordinator = TransferRestorationCoordinatorFactory.createForProduction(
            context = context,
            database = database,
            ioDispatcher = Dispatchers.IO,
        )

        assertTrue(coordinator.gateway is DocumentsContractSafGateway)
        assertTrue(coordinator.journal is RoomSafCommitJournal)
        assertTrue(coordinator.discovery is RoomSafCheckpointDiscovery)
        assertTrue(coordinator.persistedGrantResolver is RoomPersistedSafGrantResolver)
        assertTrue(coordinator.staging is AppPrivateSafStaging)
        assertEquals(existedBefore, incoming.exists())
        assertEquals(0, requireNotNull(provider).callCount("android:createDocument"))
        assertEquals(0, requireNotNull(provider).callCount("android:renameDocument"))
        assertEquals(0, requireNotNull(provider).callCount("android:isChildDocument"))
    }

    @Test
    fun `production resolver rejects substitute grants and distinguishes revoked malformed and mismatched scope`() = runBlocking {
        registerProvider()
        val database = openDatabase()
        val exactUri = requireNotNull(treeUri).toString()
        val grantEntity = SafGrantEntity(treeUri = exactUri, displayName = "Download", readWrite = true)
        assertFalse(grantEntity.toString().contains(exactUri))
        assertFalse(grantEntity.toString().contains("Download"))
        val rowId = database.safGrantDao().insert(grantEntity)
        val gateway = DocumentsContractSafGateway(context.contentResolver)
        val resolver = RoomPersistedSafGrantResolver(database.safGrantDao(), gateway)
        val grant = exactGrant(rowId.toString())
        val checkpoint = SafCommitCheckpoint.fromRecord(record("grant-resolver", grant), grant)

        val available = resolver.resolve(checkpoint)
        assertTrue(available is PersistedSafGrantResolution.Available)
        assertEquals(rowId.toString(), (available as PersistedSafGrantResolution.Available).grant.grantId)

        val substituteId = checkpoint.copy(
            approvedTree = checkpoint.approvedTree.copy(grantId = (rowId + 100L).toString()),
        )
        assertEquals(PersistedSafGrantResolution.Mismatched, resolver.resolve(substituteId))

        val malformedScope = checkpoint.copy(
            approvedTree = checkpoint.approvedTree.copy(authority = "other.provider"),
        )
        assertEquals(PersistedSafGrantResolution.Malformed, resolver.resolve(malformedScope))

        context.contentResolver.releasePersistableUriPermission(
            requireNotNull(treeUri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        treePermissionHeld = false
        assertEquals(PersistedSafGrantResolution.Revoked, resolver.resolve(checkpoint))
    }

    @Test
    fun `production grant resolver rejects noncanonical ids and insufficient persisted write scope`() = runBlocking {
        registerProvider()
        val database = openDatabase()
        val exactUri = requireNotNull(treeUri).toString()
        val rowId = database.safGrantDao().insert(
            SafGrantEntity(treeUri = exactUri, displayName = "Read only", readWrite = false),
        )
        val grant = exactGrant(rowId.toString())
        val checkpoint = SafCommitCheckpoint.fromRecord(record("read-only-grant", grant), grant)
        val resolver = RoomPersistedSafGrantResolver(
            database.safGrantDao(),
            DocumentsContractSafGateway(context.contentResolver),
        )

        assertEquals(PersistedSafGrantResolution.InsufficientScope, resolver.resolve(checkpoint))
        val noncanonical = checkpoint.copy(
            approvedTree = checkpoint.approvedTree.copy(grantId = "0${rowId}"),
        )
        assertEquals(PersistedSafGrantResolution.Malformed, resolver.resolve(noncanonical))
    }

    @Test
    fun `production factory explicitly restores app private staging through real DocumentsContract`() = runBlocking {
        registerProvider()
        val database = openDatabase()
        val exactUri = requireNotNull(treeUri).toString()
        val rowId = database.safGrantDao().insert(
            SafGrantEntity(treeUri = exactUri, displayName = "Download", readWrite = true),
        )
        val grant = exactGrant(rowId.toString())
        val parentId = "factory-parent"
        requireNotNull(provider).addDocument(
            id = parentId,
            name = "Factory folder",
            mime = DocumentsContract.Document.MIME_TYPE_DIR,
            flags = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE,
            parentId = grant.rootDocumentId,
        )
        val gateway = DocumentsContractSafGateway(context.contentResolver)
        val actualGrant = requireNotNull(gateway.resolveGrant(rowId.toString(), exactUri, requireNotNull(authority)))
        val id = PartialIdentity("saf_staged:${UUID.randomUUID().toString().replace('-', '_')}")
        val stagingStore = AppPrivatePartialStore(File(context.filesDir, "incoming"))
        stagedFiles += stagingStore to id
        val staged = stagingStore.open(id)
        try {
            val write = staged.writeAt(0L, payload, 0, payload.size)
            assertTrue(write is WriteOutcome.Written)
            assertEquals(FlushDurability.DurableFlushSupported, staged.flush())
        } finally {
            staged.close()
        }
        val checkpoint = SafCommitCheckpoint.fromRecord(
            record(id.value, grant, parentDocumentId = parentId),
            actualGrant,
        )
        val journal = RoomSafCommitJournal(database, Dispatchers.IO)
        assertEquals(SafCommitJournalWrite.Saved(1L), journal.write(checkpoint, 0L))
        val initialCreateCalls = requireNotNull(provider).callCount("android:createDocument")

        val coordinator = TransferRestorationCoordinatorFactory.createForProduction(
            context = context,
            database = database,
            ioDispatcher = Dispatchers.IO,
        )
        assertEquals(initialCreateCalls, requireNotNull(provider).callCount("android:createDocument"))
        val report = coordinator.restore()

        assertEquals(RestorationClassification.RESUMED_AND_COMMITTED, report.outcomes.single().classification)
        assertEquals(1, requireNotNull(provider).callCount("android:createDocument"))
        assertEquals(1, requireNotNull(provider).callCount("android:renameDocument"))
        assertTrue(requireNotNull(provider).callCount("android:isChildDocument") > 0)
        assertTrue(stagingStore.lengthOrNull(id) == null)
        assertEquals(SafCommitCheckpointPhase.COMMITTED, journal.read(id)
            .let { (it as SafCommitJournalRead.Found).entry.checkpoint.phase })
        assertTrue(stagingStore.delete(id))
    }
}
