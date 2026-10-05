package app.morsecode.core.storage.transfer

import android.net.Uri
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafCommitRecoveryTest {

    private val grant = SafTreeGrant(
        grantId = "grant-1",
        treeUri = Uri.parse("content://provider/tree/root"),
        rootDocumentId = "root",
        authority = "provider",
        writable = true,
    )
    private val payload = "checkpoint-recovery-bytes".toByteArray()
    private val partialId = PartialIdentity("partial-1")
    private val parentUri = SafContainment.documentUriUsingTree(grant.treeUri, grant.rootDocumentId).toString()

    private fun record(
        policy: DuplicatePolicy = DuplicatePolicy.RENAME,
        digest: Boolean = true,
    ) = SafCommitRecord(
        sessionId = SessionId("session-1"),
        transferId = TransferId("transfer-1"),
        partialId = partialId,
        treeUri = grant.treeUri.toString(),
        rootDocumentId = grant.rootDocumentId,
        parentDocumentId = grant.rootDocumentId,
        expectedFinalName = "movie.mp4",
        expectedSizeBytes = payload.size.toLong(),
        expectedDigest = if (digest) Sha256Accumulator().apply { update(payload) }.digest() else null,
        grantId = grant.grantId,
        strategy = SafCommitStrategy.TEMP_THEN_RENAME,
        duplicatePolicy = policy,
    )

    private class Staging(private val bytes: ByteArray) : SafStaging {
        var deleted: Boolean = false
            private set

        override fun length(identity: PartialIdentity): Long? = if (deleted) null else bytes.size.toLong()

        override fun open(identity: PartialIdentity): SafOpen =
            SafOpen.Opened(SafReadHandle(ByteArrayInputStream(bytes)))

        override fun delete(identity: PartialIdentity): Boolean {
            deleted = true
            return true
        }
    }

    private fun coordinator(
        gateway: RecordingSafGateway,
        staging: Staging,
        journal: InMemorySafCommitJournal,
        isCancelled: () -> Boolean = { false },
    ) = SafCommitCoordinator(
        gateway = gateway,
        staging = staging,
        journal = journal,
        isCancelled = isCancelled,
    )

    @Test
    fun `a provider-verified checkpoint resumes without recopying and committed recovery is idempotent`() {
        val gateway = RecordingSafGateway()
        val staging = Staging(payload)
        val journal = InMemorySafCommitJournal()
        val source = record()
        val tempName = temporaryDocumentName(source.expectedFinalName, partialId)
        val tempUri = "$parentUri/document/temp-1"
        gateway.addNamed(tempUri, tempName)
        gateway.written[tempUri] = payload
        val tempIdentity = SafStoredDocumentIdentity(tempUri, "temp-1")
        val checkpoint = SafCommitCheckpoint.fromRecord(
            source.copy(
                state = SafCommitState.PROVIDER_VERIFIED,
                temporaryUri = tempUri,
                temporaryIdentity = tempIdentity,
                copiedBytes = payload.size.toLong(),
            ),
            grant,
            SafCommitCheckpointPhase.PROVIDER_VERIFIED,
        )
        assertTrue(journal.save(checkpoint))

        val recovered = coordinator(gateway, staging, journal).resumeOrReconcile(checkpoint, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.Committed)
        val committed = recovered as SafCommitRecoveryOutcome.Committed
        assertEquals(SafCommitCheckpointPhase.COMMITTED, committed.checkpoint.phase)
        assertEquals(0, gateway.countOf("openWrite:"))
        assertEquals(1, gateway.countOf("rename:"))
        assertTrue(staging.deleted)

        val callsAfterFirstRecovery = gateway.calls.size
        val second = coordinator(gateway, staging, journal).resumeOrReconcile(checkpoint, grant)

        assertTrue(second is SafCommitRecoveryOutcome.Committed)
        assertEquals(callsAfterFirstRecovery + 1, gateway.calls.size)
        assertEquals(0, gateway.countOf("openWrite:"))
        assertEquals(1, gateway.countOf("rename:"))
    }

    @Test
    fun `recovery before destination creation restarts from staging across each journal boundary`() {
        val phases = listOf(
            SafCommitCheckpointPhase.READY,
            SafCommitCheckpointPhase.STAGING_VERIFICATION_INTENT,
            SafCommitCheckpointPhase.STAGING_VERIFIED,
            SafCommitCheckpointPhase.DESTINATION_RESOLUTION_INTENT,
            SafCommitCheckpointPhase.DESTINATION_RESOLVED,
        )

        phases.forEach { phase ->
            val gateway = RecordingSafGateway()
            val staging = Staging(payload)
            val journal = InMemorySafCommitJournal()
            val checkpoint = SafCommitCheckpoint.fromRecord(record(), grant, phase)
            assertTrue("checkpoint save for ${phase.id}", journal.save(checkpoint))

            val recovered = coordinator(gateway, staging, journal).resumeOrReconcile(checkpoint, grant)

            assertTrue("${phase.id} resumes to a checked commit", recovered is SafCommitRecoveryOutcome.Committed)
            assertEquals("${phase.id} creates once", 1, gateway.countOf("create:"))
            assertTrue("${phase.id} releases staging only after commit", staging.deleted)
        }
    }

    @Test
    fun `copy flush and provider verification interruption phases reconcile the exact temporary`() {
        val phases = listOf(
            SafCommitCheckpointPhase.TEMPORARY_CREATED,
            SafCommitCheckpointPhase.COPY_STARTED,
            SafCommitCheckpointPhase.COPY_COMPLETED,
            SafCommitCheckpointPhase.FLUSH_INTENT,
            SafCommitCheckpointPhase.FLUSH_COMPLETED,
            SafCommitCheckpointPhase.PROVIDER_VERIFICATION_INTENT,
            SafCommitCheckpointPhase.PROVIDER_VERIFIED,
        )

        phases.forEach { phase ->
            val gateway = RecordingSafGateway()
            val staging = Staging(payload)
            val journal = InMemorySafCommitJournal()
            val source = record()
            val temporaryName = temporaryDocumentName(source.expectedFinalName, partialId)
            val temporaryUri = "$parentUri/document/interrupted-${phase.id}"
            gateway.addNamed(temporaryUri, temporaryName)
            gateway.written[temporaryUri] = payload
            val identity = SafStoredDocumentIdentity(temporaryUri, "interrupted-${phase.id}")
            val checkpoint = SafCommitCheckpoint.fromRecord(
                source.copy(
                    state = SafCommitState.COPY_STARTED,
                    temporaryUri = temporaryUri,
                    temporaryIdentity = identity,
                    copiedBytes = payload.size.toLong(),
                ),
                grant,
                phase,
            )
            assertTrue("checkpoint save for ${phase.id}", journal.save(checkpoint))

            val recovered = coordinator(gateway, staging, journal).resumeOrReconcile(checkpoint, grant)

            assertTrue("${phase.id} verifies before promotion", recovered is SafCommitRecoveryOutcome.Committed)
            assertEquals("${phase.id} never recopies a verified exact document", 0, gateway.countOf("openWrite:"))
            assertEquals("${phase.id} promotes once", 1, gateway.countOf("rename:"))
            assertTrue("${phase.id} retains no staging after verified commit", staging.deleted)
        }
    }

    @Test
    fun `a create-intent candidate is adopted only after exact provider verification`() {
        val gateway = RecordingSafGateway()
        val staging = Staging(payload)
        val journal = InMemorySafCommitJournal()
        val source = record(digest = false)
        val candidateName = temporaryDocumentName(source.expectedFinalName, partialId)
        val candidateUri = "$parentUri/document/candidate-1"
        gateway.addNamed(candidateUri, candidateName)
        gateway.written[candidateUri] = payload
        val checkpoint = SafCommitCheckpoint.fromRecord(
            source,
            grant,
            SafCommitCheckpointPhase.TEMPORARY_CREATE_INTENT,
        )
        assertTrue(journal.save(checkpoint))

        val recovered = coordinator(gateway, staging, journal).resumeOrReconcile(checkpoint, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.Committed)
        val committed = recovered as SafCommitRecoveryOutcome.Committed
        assertEquals(0, gateway.countOf("create:"))
        assertEquals(0, gateway.countOf("openWrite:"))
        assertEquals(1, gateway.countOf("rename:"))
        assertEquals(candidateUri, committed.checkpoint.finalIdentity?.documentUri)
    }

    @Test
    fun `recovery rejects malformed identities retained in rename history before provider access`() {
        val gateway = RecordingSafGateway()
        val staging = Staging(payload)
        val journal = InMemorySafCommitJournal()
        val source = record().copy(state = SafCommitState.RECONCILIATION_REQUIRED)
        val malformed = SafStoredDocumentIdentity(
            documentUri = "$parentUri/document/uri-id",
            documentId = "different-id",
        )
        val checkpoint = SafCommitCheckpoint.fromRecord(
            source.copy(
                renameHistory = listOf(
                    SafRenameEvidence(
                        before = malformed,
                        returned = null,
                        scope = SafRenameScope.fromRecord(source, grant),
                        phase = SafRenamePhase.FINAL_PROMOTION,
                        sequence = 0,
                    ),
                ),
            ),
            grant,
            SafCommitCheckpointPhase.RECONCILIATION_REQUIRED,
        )
        assertTrue(journal.save(checkpoint))

        val recovered = coordinator(gateway, staging, journal).resumeOrReconcile(checkpoint, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.ReconciliationRequired)
        assertEquals(
            TransferStorageError.ContainmentUnknown("checkpoint_identity_malformed"),
            (recovered as SafCommitRecoveryOutcome.ReconciliationRequired).error,
        )
        assertEquals(0, gateway.providerCallCount)
        assertFalse(staging.deleted)
    }

    @Test
    fun `a mismatched grant refuses history recovery before gateway access`() {
        val gateway = RecordingSafGateway()
        val staging = Staging(payload)
        val journal = InMemorySafCommitJournal()
        val checkpoint = SafCommitCheckpoint.fromRecord(
            record(),
            grant,
            SafCommitCheckpointPhase.READY,
        )
        assertTrue(journal.save(checkpoint))
        val wrongGrant = grant.copy(grantId = "other-grant")

        val recovered = coordinator(gateway, staging, journal).resumeOrReconcile(checkpoint, wrongGrant)

        assertTrue(recovered is SafCommitRecoveryOutcome.ReconciliationRequired)
        assertEquals(0, gateway.providerCallCount)
        assertFalse(staging.deleted)
    }

    @Test
    fun `an exact content URI outside the approved parent is not trusted during recovery`() {
        val gateway = RecordingSafGateway()
        val staging = Staging(payload)
        val journal = InMemorySafCommitJournal()
        val source = record()
        val outsideUri = "content://provider/document/outside-parent"
        val identity = SafStoredDocumentIdentity(outsideUri, "outside-parent")
        gateway.addNamed(outsideUri, temporaryDocumentName(source.expectedFinalName, partialId))
        gateway.written[outsideUri] = payload
        gateway.hiddenFromChildListing += outsideUri
        val checkpoint = SafCommitCheckpoint.fromRecord(
            source.copy(
                state = SafCommitState.PROVIDER_VERIFIED,
                temporaryUri = outsideUri,
                temporaryIdentity = identity,
                copiedBytes = payload.size.toLong(),
            ),
            grant,
            SafCommitCheckpointPhase.PROVIDER_VERIFIED,
        )
        assertTrue(journal.save(checkpoint))

        val recovered = coordinator(gateway, staging, journal).resumeOrReconcile(checkpoint, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.ReconciliationRequired)
        assertEquals(0, gateway.countOf("openWrite:"))
        assertEquals(0, gateway.countOf("rename:"))
        assertEquals(0, gateway.countOf("deleteAndReconcile:"))
        assertFalse(staging.deleted)
    }

    @Test
    fun `an unverified create-intent candidate is retained for reconciliation and never deleted`() {
        val gateway = RecordingSafGateway()
        val staging = Staging(payload)
        val journal = InMemorySafCommitJournal()
        val source = record()
        val candidateName = temporaryDocumentName(source.expectedFinalName, partialId)
        val candidateUri = "$parentUri/document/other-1"
        gateway.addNamed(candidateUri, candidateName)
        gateway.written[candidateUri] = byteArrayOf(1, 2, 3)
        val checkpoint = SafCommitCheckpoint.fromRecord(
            source,
            grant,
            SafCommitCheckpointPhase.TEMPORARY_CREATE_INTENT,
        )
        assertTrue(journal.save(checkpoint))

        val recovered = coordinator(gateway, staging, journal).resumeOrReconcile(checkpoint, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.ReconciliationRequired)
        assertTrue("the candidate was not adopted as an owned identity", journal.load(partialId)?.temporaryIdentity == null)
        assertEquals(0, gateway.countOf("deleteAndReconcile:"))
        assertEquals(0, gateway.countOf("rename:"))
    }

    @Test
    fun `a returned final URI after process death is reconciled by exact candidate and digest`() {
        val gateway = RecordingSafGateway()
        val staging = Staging(payload)
        val journal = InMemorySafCommitJournal()
        val source = record()
        val tempUri = "$parentUri/document/temp-old"
        val finalUri = "$parentUri/document/final-new"
        gateway.addNamed(finalUri, source.expectedFinalName)
        gateway.written[finalUri] = payload
        val tempIdentity = SafStoredDocumentIdentity(tempUri, "temp-old")
        val checkpoint = SafCommitCheckpoint.fromRecord(
            source.copy(
                state = SafCommitState.RENAMED,
                temporaryUri = tempUri,
                temporaryIdentity = tempIdentity,
                copiedBytes = payload.size.toLong(),
            ),
            grant,
            SafCommitCheckpointPhase.FINAL_RENAMED,
        ).copy(returnedRenameUri = finalUri)
        assertTrue(journal.save(checkpoint))

        val recovered = coordinator(gateway, staging, journal).resumeOrReconcile(checkpoint, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.Committed)
        assertEquals(finalUri, (recovered as SafCommitRecoveryOutcome.Committed).checkpoint.finalIdentity?.documentUri)
        assertEquals(0, gateway.countOf("rename:"))
        assertEquals(0, gateway.countOf("openWrite:"))
        assertTrue(staging.deleted)
    }

    @Test
    fun `final rename intent result reconciliation and verification phases accept only the exact candidate`() {
        val phases = listOf(
            SafCommitCheckpointPhase.FINAL_RENAME_INTENT,
            SafCommitCheckpointPhase.FINAL_RENAMED,
            SafCommitCheckpointPhase.RENAME_RECONCILIATION_INTENT,
            SafCommitCheckpointPhase.FINAL_VERIFICATION_INTENT,
        )

        phases.forEach { phase ->
            val gateway = RecordingSafGateway()
            val staging = Staging(payload)
            val journal = InMemorySafCommitJournal()
            val sameIdentityRename = phase == SafCommitCheckpointPhase.FINAL_RENAME_INTENT
            val source = record()
            val temporaryUri = if (sameIdentityRename) {
                "$parentUri/document/final-same-identity"
            } else {
                "$parentUri/document/temp-before-${phase.id}"
            }
            val temporaryId = temporaryUri.substringAfterLast('/')
            val finalUri = if (sameIdentityRename) temporaryUri else "$parentUri/document/final-${phase.id}"
            val finalId = finalUri.substringAfterLast('/')
            gateway.addNamed(finalUri, source.expectedFinalName)
            gateway.written[finalUri] = payload
            val temporaryIdentity = SafStoredDocumentIdentity(temporaryUri, temporaryId)
            val returnedIdentity = SafStoredDocumentIdentity(finalUri, finalId)
            val checkpoint = SafCommitCheckpoint.fromRecord(
                source.copy(
                    state = SafCommitState.RENAMED,
                    temporaryUri = temporaryUri,
                    temporaryIdentity = temporaryIdentity,
                    copiedBytes = payload.size.toLong(),
                ),
                grant,
                phase,
            ).copy(
                returnedRenameUri = if (sameIdentityRename) null else finalUri,
                returnedRenameIdentity = if (sameIdentityRename) null else returnedIdentity,
            )
            assertTrue("checkpoint save for ${phase.id}", journal.save(checkpoint))

            val recovered = coordinator(gateway, staging, journal).resumeOrReconcile(checkpoint, grant)

            assertTrue("${phase.id} settles a matching, verified final child", recovered is SafCommitRecoveryOutcome.Committed)
            assertEquals("${phase.id} does not repeat the rename", 0, gateway.countOf("rename:"))
            assertEquals("${phase.id} does not recopy", 0, gateway.countOf("openWrite:"))
            assertTrue("${phase.id} releases staging only after re-verification", staging.deleted)
        }
    }

    @Test
    fun `a backup rename interruption continues forward without touching the staged replacement`() {
        val gateway = RecordingSafGateway()
        val staging = Staging(payload)
        val journal = InMemorySafCommitJournal()
        val source = record(policy = DuplicatePolicy.OVERWRITE)
        val existingUri = "$parentUri/document/old-final"
        val replacementUri = "$parentUri/document/replacement"
        val backupName = backupDocumentName(source.expectedFinalName, partialId)
        val backupUri = "$parentUri/document/renamed-backup"
        gateway.addNamed(backupUri, backupName)
        gateway.written[backupUri] = byteArrayOf(9, 8, 7)
        gateway.existing.remove(existingUri)
        gateway.addNamed(replacementUri, temporaryDocumentName(source.expectedFinalName, partialId))
        gateway.written[replacementUri] = payload
        val existing = SafStoredDocumentIdentity(existingUri, "old-final")
        val replacement = SafStoredDocumentIdentity(replacementUri, "replacement")
        val checkpoint = SafCommitCheckpoint.fromRecord(
            source.copy(
                state = SafCommitState.BACKUP_CREATED,
                existingIdentity = existing,
                temporaryUri = replacementUri,
                temporaryIdentity = replacement,
                copiedBytes = payload.size.toLong(),
            ),
            grant,
            SafCommitCheckpointPhase.BACKUP_RENAMED,
        ).copy(returnedRenameUri = backupUri)
        assertTrue(journal.save(checkpoint))

        val recovered = coordinator(gateway, staging, journal).resumeOrReconcile(checkpoint, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.Committed)
        val committed = recovered as SafCommitRecoveryOutcome.Committed
        assertEquals(backupUri, committed.checkpoint.backupIdentity?.documentUri)
        assertEquals(0, gateway.countOf("openWrite:"))
        assertEquals(1, gateway.countOf("rename:"))
        assertFalse(backupUri in gateway.existing)
        assertTrue(staging.deleted)
    }

    @Test
    fun `backup rename intent recognizes the same stored identity under its backup name`() {
        val gateway = RecordingSafGateway()
        val staging = Staging(payload)
        val journal = InMemorySafCommitJournal()
        val source = record(policy = DuplicatePolicy.OVERWRITE)
        val existingUri = "$parentUri/document/old-final"
        val replacementUri = "$parentUri/document/replacement"
        val backupName = backupDocumentName(source.expectedFinalName, partialId)
        gateway.addNamed(existingUri, backupName)
        gateway.written[existingUri] = byteArrayOf(9, 8, 7)
        gateway.addNamed(replacementUri, temporaryDocumentName(source.expectedFinalName, partialId))
        gateway.written[replacementUri] = payload
        val existing = SafStoredDocumentIdentity(existingUri, "old-final")
        val replacement = SafStoredDocumentIdentity(replacementUri, "replacement")
        val checkpoint = SafCommitCheckpoint.fromRecord(
            source.copy(
                state = SafCommitState.REPLACEMENT_READY,
                existingIdentity = existing,
                temporaryUri = replacementUri,
                temporaryIdentity = replacement,
                copiedBytes = payload.size.toLong(),
            ),
            grant,
            SafCommitCheckpointPhase.BACKUP_RENAME_INTENT,
        )
        assertTrue(journal.save(checkpoint))

        val recovered = coordinator(gateway, staging, journal).resumeOrReconcile(checkpoint, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.Committed)
        val committed = recovered as SafCommitRecoveryOutcome.Committed
        assertEquals(existing, committed.checkpoint.backupIdentity)
        assertEquals(2, committed.checkpoint.renameHistory.size)
        val backupEvidence = committed.checkpoint.renameHistory.first()
        assertEquals(existing, backupEvidence.before)
        assertEquals(existing, backupEvidence.returned)
        assertEquals(SafRenamePhase.BACKUP_RENAME, backupEvidence.phase)
        assertEquals(0, backupEvidence.sequence)
        assertEquals(SafRenameScope.fromRecord(source, grant), backupEvidence.scope)
        assertEquals(SafRenamePhase.FINAL_PROMOTION, committed.checkpoint.renameHistory.last().phase)
        assertEquals(1, committed.checkpoint.renameHistory.last().sequence)
        assertFalse(existingUri in gateway.existing)
        assertTrue(staging.deleted)
    }

    @Test
    fun `a renamed provider temporary is reachable only through its stored identity cleanup`() {
        val gateway = RecordingSafGateway(renameReturns = RecordingSafGateway.RenameReturn.NEW)
        val staging = Staging(payload)
        val journal = InMemorySafCommitJournal()
        val result = coordinator(gateway, staging, journal).commit(record(), grant) as SafCommitOutcome.Committed
        val temporary = requireNotNull(result.record.temporaryIdentity)
        assertTrue(SafCleanupPending.PROVIDER_TEMPORARY in result.pendingCleanup)
        assertTrue(result.record.stagingReleased)
        // Model the provider still exposing the old, exact temporary identity at
        // cleanup time. The earlier rename observation alone never authorizes a
        // delete; the direct-parent listing below must find this same URI/id.
        gateway.addNamed(temporary.documentUri, temporaryDocumentName("movie.mp4", partialId))
        gateway.written[temporary.documentUri] = payload
        val deleteCall = "deleteAndReconcile:${temporary.documentUri}:${temporary.documentId}"
        val writes = gateway.countOf("openWrite:")
        val renames = gateway.countOf("rename:")

        val cleaned = coordinator(gateway, staging, journal).retryPendingCleanup(result.record, grant)

        assertTrue(cleaned is SafCommitOutcome.Committed)
        assertTrue((cleaned as SafCommitOutcome.Committed).cleanupComplete)
        assertEquals(1, gateway.calls.count { it == deleteCall })
        assertEquals(writes, gateway.countOf("openWrite:"))
        assertEquals(renames, gateway.countOf("rename:"))
        assertFalse(temporary.documentUri in gateway.existing)
    }

    @Test
    fun `provider temporary cleanup refuses a same-name row that is not the stored identity`() {
        val gateway = RecordingSafGateway(renameReturns = RecordingSafGateway.RenameReturn.NEW)
        val staging = Staging(payload)
        val journal = InMemorySafCommitJournal()
        val result = coordinator(gateway, staging, journal).commit(record(), grant) as SafCommitOutcome.Committed
        val temporary = requireNotNull(result.record.temporaryIdentity)
        val temporaryName = temporaryDocumentName("movie.mp4", partialId)
        gateway.addNamed(temporary.documentUri, temporaryName)
        gateway.written[temporary.documentUri] = payload
        val replacementUri = "$parentUri/document/same-name-replacement"
        gateway.addNamed(replacementUri, temporaryName)
        gateway.hiddenFromChildListing += temporary.documentUri

        val cleaned = coordinator(gateway, staging, journal).retryPendingCleanup(result.record, grant)

        assertTrue(cleaned is SafCommitOutcome.ReconciliationRequired)
        assertTrue(temporary.documentUri in gateway.existing)
        assertTrue(replacementUri in gateway.existing)
        assertEquals(0, gateway.countOf("deleteAndReconcile:"))
    }

    @Test
    fun `cleanup intent and observed checkpoints retry only their exact reachable identity`() {
        val cases = listOf(
            SafCleanupPending.PROVIDER_TEMPORARY to SafCommitCheckpointPhase.PROVIDER_TEMPORARY_DELETE_INTENT,
            SafCleanupPending.PROVIDER_TEMPORARY to SafCommitCheckpointPhase.PROVIDER_TEMPORARY_DELETE_OBSERVED,
            SafCleanupPending.BACKUP to SafCommitCheckpointPhase.BACKUP_DELETE_INTENT,
            SafCleanupPending.BACKUP to SafCommitCheckpointPhase.BACKUP_DELETE_OBSERVED,
        )

        cases.forEachIndexed { index, (cleanupType, phase) ->
            val gateway = RecordingSafGateway()
            val staging = Staging(payload)
            val journal = InMemorySafCommitJournal()
            val source = record()
            val finalUri = "$parentUri/document/cleanup-final-$index"
            gateway.addNamed(finalUri, "movie.mp4")
            gateway.written[finalUri] = payload
            val finalIdentity = SafStoredDocumentIdentity(finalUri, "cleanup-final-$index")
            val providerUri = "$parentUri/document/cleanup-target-$index"
            val providerId = "cleanup-target-$index"
            val providerName = when (cleanupType) {
                SafCleanupPending.PROVIDER_TEMPORARY -> temporaryDocumentName(source.expectedFinalName, partialId)
                SafCleanupPending.BACKUP -> backupDocumentName(source.expectedFinalName, partialId)
                SafCleanupPending.STAGING -> error("not used in provider cleanup cases")
            }
            gateway.addNamed(providerUri, providerName)
            gateway.written[providerUri] = byteArrayOf(1, 2, 3)
            assertTrue(staging.delete(partialId))
            val providerIdentity = SafStoredDocumentIdentity(providerUri, providerId)
            val pendingRecord = source.copy(
                state = when (cleanupType) {
                    SafCleanupPending.PROVIDER_TEMPORARY -> SafCommitState.PROVIDER_TEMPORARY_CLEANUP_PENDING
                    SafCleanupPending.BACKUP -> SafCommitState.BACKUP_CLEANUP_PENDING
                    SafCleanupPending.STAGING -> error("not used in provider cleanup cases")
                },
                temporaryUri = providerUri.takeIf { cleanupType == SafCleanupPending.PROVIDER_TEMPORARY },
                temporaryIdentity = providerIdentity.takeIf { cleanupType == SafCleanupPending.PROVIDER_TEMPORARY },
                finalUri = finalUri,
                finalIdentity = finalIdentity,
                backupIdentity = providerIdentity.takeIf { cleanupType == SafCleanupPending.BACKUP },
                pendingCleanup = setOf(cleanupType),
                copiedBytes = payload.size.toLong(),
                stagingReleased = true,
            )
            val checkpoint = SafCommitCheckpoint.fromRecord(pendingRecord, grant, phase)
            assertTrue("checkpoint save for ${phase.id}", journal.save(checkpoint))

            val cleaned = coordinator(gateway, staging, journal).resumeOrReconcile(checkpoint, grant)

            assertTrue("${phase.id} completes after exact deletion and observation", cleaned is SafCommitRecoveryOutcome.Committed)
            assertFalse("${phase.id} removes only the stored document", providerUri in gateway.existing)
            assertEquals("${phase.id} deletes once", 1, gateway.countOf("deleteAndReconcile:"))
        }
    }

    @Test
    fun `staging deletion intent can resume and an observed empty cleanup is idempotently committed`() {
        val intentGateway = RecordingSafGateway()
        val intentStaging = Staging(payload)
        val intentJournal = InMemorySafCommitJournal()
        val intentFinal = "$parentUri/document/staging-intent-final"
        intentGateway.addNamed(intentFinal, "movie.mp4")
        val intentIdentity = SafStoredDocumentIdentity(intentFinal, "staging-intent-final")
        val intentRecord = record().copy(
            state = SafCommitState.STAGING_CLEANUP_PENDING,
            finalUri = intentFinal,
            finalIdentity = intentIdentity,
            pendingCleanup = setOf(SafCleanupPending.STAGING),
            copiedBytes = payload.size.toLong(),
        )
        val intentCheckpoint = SafCommitCheckpoint.fromRecord(
            intentRecord,
            grant,
            SafCommitCheckpointPhase.STAGING_DELETE_INTENT,
        )
        assertTrue(intentJournal.save(intentCheckpoint))

        val resumedIntent = coordinator(intentGateway, intentStaging, intentJournal)
            .resumeOrReconcile(intentCheckpoint, grant)
        assertTrue(resumedIntent is SafCommitRecoveryOutcome.Committed)
        assertTrue(intentStaging.deleted)

        val observedGateway = RecordingSafGateway()
        val observedStaging = Staging(payload)
        val observedJournal = InMemorySafCommitJournal()
        val observedFinal = "$parentUri/document/staging-observed-final"
        observedGateway.addNamed(observedFinal, "movie.mp4")
        assertTrue(observedStaging.delete(partialId))
        val observedRecord = record().copy(
            state = SafCommitState.STAGING_CLEANUP_PENDING,
            finalUri = observedFinal,
            finalIdentity = SafStoredDocumentIdentity(observedFinal, "staging-observed-final"),
            copiedBytes = payload.size.toLong(),
            stagingReleased = true,
        )
        val observedCheckpoint = SafCommitCheckpoint.fromRecord(
            observedRecord,
            grant,
            SafCommitCheckpointPhase.STAGING_DELETE_OBSERVED,
        )
        assertTrue(observedJournal.save(observedCheckpoint))

        val recoveredObserved = coordinator(observedGateway, observedStaging, observedJournal)
            .resumeOrReconcile(observedCheckpoint, grant)

        assertTrue(recoveredObserved is SafCommitRecoveryOutcome.Committed)
        assertEquals(0, observedGateway.countOf("deleteAndReconcile:"))
        assertEquals(SafCommitCheckpointPhase.COMMITTED, (recoveredObserved as SafCommitRecoveryOutcome.Committed).checkpoint.phase)
    }

    @Test
    fun `a checkpoint with a mismatched pending cleanup identity stops before deletion`() {
        val gateway = RecordingSafGateway(renameReturns = RecordingSafGateway.RenameReturn.NEW)
        val staging = Staging(payload)
        val journal = InMemorySafCommitJournal()
        val committed = coordinator(gateway, staging, journal).commit(record(), grant) as SafCommitOutcome.Committed
        val checkpoint = requireNotNull(committed.checkpoint)
        val corrupted = checkpoint.copy(
            pendingCleanup = listOf(
                SafPendingCleanupIdentity(
                    type = SafCleanupPending.PROVIDER_TEMPORARY,
                    documentIdentity = SafStoredDocumentIdentity(
                        "$parentUri/document/not-the-temporary",
                        "not-the-temporary",
                    ),
                ),
            ),
        )
        assertTrue(journal.save(corrupted))

        val recovered = coordinator(gateway, staging, journal).resumeOrReconcile(corrupted, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.ReconciliationRequired)
        assertEquals(TransferStorageError.StateConflict("checkpoint_cleanup_identity_mismatch"), (recovered as SafCommitRecoveryOutcome.ReconciliationRequired).error)
        assertEquals(0, gateway.countOf("deleteAndReconcile:"))
    }

    @Test
    fun `a committed checkpoint with a missing final row is reconciled instead of returned as success`() {
        val gateway = RecordingSafGateway()
        val staging = Staging(payload)
        val journal = InMemorySafCommitJournal()
        val source = record()
        val finalUri = "$parentUri/document/final-missing"
        val finalIdentity = SafStoredDocumentIdentity(finalUri, "final-missing")
        val checkpoint = SafCommitCheckpoint.fromRecord(
            source.copy(
                state = SafCommitState.COMMITTED,
                finalUri = finalUri,
                finalIdentity = finalIdentity,
                copiedBytes = payload.size.toLong(),
                stagingReleased = true,
            ),
            grant,
            SafCommitCheckpointPhase.COMMITTED,
        )
        assertTrue(journal.save(checkpoint))

        val recovered = coordinator(gateway, staging, journal).resumeOrReconcile(checkpoint, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.ReconciliationRequired)
        assertEquals(TransferStorageError.NotFound("committed_final"), (recovered as SafCommitRecoveryOutcome.ReconciliationRequired).error)
    }

    @Test
    fun `checkpoint preserves virtual sizes beyond four gibibytes as Long values`() {
        val source = record().copy(expectedSizeBytes = 5_368_709_120L)
        val checkpoint = SafCommitCheckpoint.fromRecord(source, grant)

        assertEquals(5_368_709_120L, checkpoint.expectedSizeBytes)
        assertEquals(0L, checkpoint.copiedBytes)
        assertEquals(5_368_709_120L, checkpoint.expectedSizeBytes)
    }

    @Test
    fun `a failed create-intent journal save prevents provider creation`() {
        val gateway = RecordingSafGateway()
        val staging = Staging(payload)
        // READY, staging intent/result, destination intent/result, then create intent.
        val journal = InMemorySafCommitJournal().apply { rejectOnSaveAttempt = 6 }

        val outcome = coordinator(gateway, staging, journal).commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.Failed)
        assertEquals(0, gateway.countOf("create:"))
        assertEquals(0, gateway.countOf("rename:"))
    }

    @Test
    fun `a failed provider rename persists the unresolved phase and is not blindly retried`() {
        val gateway = RecordingSafGateway(renameFailure = TransferStorageError.Io("rename"))
        val staging = Staging(payload)
        val journal = InMemorySafCommitJournal(gateway.calls)

        val outcome = coordinator(gateway, staging, journal).commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        assertEquals(1, gateway.countOf("rename:"))
        val checkpoint = requireNotNull(journal.load(partialId))
        assertEquals(SafCommitCheckpointPhase.RECONCILIATION_REQUIRED, checkpoint.phase)
        assertEquals(SafRenamePhase.FINAL_PROMOTION, checkpoint.unresolvedRenamePhase)
        assertTrue(checkpoint.lastFailure != null)

        val recovered = coordinator(gateway, staging, journal).resumeOrReconcile(checkpoint, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.ReconciliationRequired)
        assertEquals(1, gateway.countOf("rename:"))
        assertFalse(staging.deleted)
    }

    @Test
    fun `a rename result save failure reconciles the exact returned identity without repeating rename`() {
        val gateway = RecordingSafGateway()
        val staging = Staging(payload)
        val journal = InMemorySafCommitJournal(gateway.calls).apply {
            rejectNextPhase = SafCommitCheckpointPhase.FINAL_RENAMED
        }

        val outcome = coordinator(gateway, staging, journal).commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.ReconciliationRequired)
        assertEquals(1, gateway.countOf("rename:"))
        val unresolved = requireNotNull(journal.load(partialId))
        assertEquals(SafCommitCheckpointPhase.RECONCILIATION_REQUIRED, unresolved.phase)
        assertEquals(gateway.lastCreatedUri, unresolved.returnedRenameUri)
        val renameIndex = gateway.calls.indexOfFirst { it.startsWith("rename:") }
        val returnIndex = gateway.calls.indexOf("rename-result:returned")
        val resultSaveIndex = gateway.calls.indexOf("journal-save:${SafCommitCheckpointPhase.FINAL_RENAMED.id}")
        assertTrue(renameIndex >= 0 && returnIndex > renameIndex && resultSaveIndex > returnIndex)

        val recovered = coordinator(gateway, staging, journal).resumeOrReconcile(unresolved, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.Committed)
        assertEquals(1, gateway.countOf("rename:"))
        assertEquals(
            SafRenamePhase.FINAL_PROMOTION,
            (recovered as SafCommitRecoveryOutcome.Committed).checkpoint.renameHistory.single().phase,
        )
        assertTrue(staging.deleted)
    }

    @Test
    fun `a failed flush keeps the journal at intent and withholds verification and staging release`() {
        val gateway = RecordingSafGateway().apply {
            throwOn = { operation ->
                if (operation == RecordingSafGateway.OP_FLUSH) java.io.IOException("flush failed") else null
            }
        }
        val staging = Staging(payload)
        val journal = InMemorySafCommitJournal()

        val outcome = coordinator(gateway, staging, journal).commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.Failed)
        assertTrue(gateway.calls.any { it.startsWith("flush:") })
        assertEquals(SafCommitCheckpointPhase.FLUSH_INTENT, journal.load(partialId)?.phase)
        assertEquals(0, gateway.countOf("openRead:"))
        assertEquals(0, gateway.countOf("rename:"))
        assertFalse(staging.deleted)
        assertEquals(0, gateway.liveHandles)
    }

    @Test
    fun `grant revocation stops recovery before rename and never becomes missing or committed`() {
        val gateway = RecordingSafGateway()
        val staging = Staging(payload)
        val journal = InMemorySafCommitJournal()
        val source = record()
        val tempUri = "$parentUri/document/temp-revoked"
        gateway.addNamed(tempUri, temporaryDocumentName(source.expectedFinalName, partialId))
        gateway.written[tempUri] = payload
        val identity = SafStoredDocumentIdentity(tempUri, "temp-revoked")
        val checkpoint = SafCommitCheckpoint.fromRecord(
            source.copy(
                state = SafCommitState.PROVIDER_VERIFIED,
                temporaryUri = tempUri,
                temporaryIdentity = identity,
                copiedBytes = payload.size.toLong(),
            ),
            grant,
            SafCommitCheckpointPhase.PROVIDER_VERIFIED,
        )
        assertTrue(journal.save(checkpoint))
        gateway.grantFailureOn = { operation ->
            if (operation == SafContainmentOperation.RECONCILE) {
                TransferStorageError.PermissionRevoked("read")
            } else {
                null
            }
        }

        val recovered = coordinator(gateway, staging, journal).resumeOrReconcile(checkpoint, grant)

        assertTrue(recovered is SafCommitRecoveryOutcome.ReconciliationRequired)
        assertEquals(0, gateway.countOf("rename:"))
        assertEquals(0, gateway.countOf("openWrite:"))
        assertTrue(journal.load(partialId)?.phase == SafCommitCheckpointPhase.RECONCILIATION_REQUIRED)
    }

    @Test
    fun `cancellation closes the writer and retains the staging partial`() {
        val gateway = RecordingSafGateway()
        val staging = Staging(payload)
        val journal = InMemorySafCommitJournal()

        val outcome = coordinator(gateway, staging, journal, isCancelled = { true }).commit(record(), grant)

        assertTrue(outcome is SafCommitOutcome.Failed)
        assertEquals(TransferStorageError.Cancelled, (outcome as SafCommitOutcome.Failed).error)
        assertEquals(0, gateway.liveHandles)
        assertEquals(0, gateway.countOf("rename:"))
        assertFalse(staging.deleted)
    }
}
