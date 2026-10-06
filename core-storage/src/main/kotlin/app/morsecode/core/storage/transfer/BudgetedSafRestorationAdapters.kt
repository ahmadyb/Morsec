package app.morsecode.core.storage.transfer

/** Budget guard over the real SAF gateway; denials never call through to the provider. */
internal class BudgetedSafDocumentGateway(
    private val delegate: SafDocumentGateway,
    private val budget: SafCommitExecutionBudget,
) : SafDocumentGateway {

    override fun recheckPersistedGrant(
        grant: SafTreeGrant,
        operation: SafContainmentOperation,
    ): TransferStorageError? = budget.cancellationError() ?: delegate.recheckPersistedGrant(grant, operation)

    override fun findChild(parentUri: String, displayName: String): SafLookup {
        budget.reserveObservation()?.let { return SafLookup.Failed(it) }
        return delegate.findChild(parentUri, displayName)
    }

    override fun create(parentUri: String, mimeType: String, displayName: String): SafCreate {
        budget.reserveMutation()?.let { return SafCreate.Failed(it) }
        return delegate.create(parentUri, mimeType, displayName)
    }

    override fun rename(documentUri: String, displayName: String): SafRename {
        budget.reserveMutation()?.let { return SafRename.Failed(it) }
        return delegate.rename(documentUri, displayName)
    }

    override fun delete(documentUri: String): SafDelete {
        budget.reserveDeleteRequest()?.let { return SafDelete.Failed(it) }
        return delegate.delete(documentUri)
    }

    override fun query(documentUri: String): SafLookup {
        budget.reserveObservation()?.let { return SafLookup.Failed(it) }
        return delegate.query(documentUri)
    }

    override fun openWrite(documentUri: String): SafOpen {
        budget.reserveMutation()?.let { return SafOpen.Refused(it) }
        return delegate.openWrite(documentUri)
    }

    override fun openRead(documentUri: String): SafOpen {
        budget.reserveObservation()?.let { return SafOpen.Refused(it) }
        return delegate.openRead(documentUri)
    }

    override fun deleteAndReconcile(
        documentUri: String,
        expectedDocumentId: String,
        grant: SafTreeGrant?,
    ): SafDeletion {
        budget.reserveDeleteAndReconcile()?.let { return SafDeletion.DeleteRequestFailed(it) }
        return delegate.deleteAndReconcile(documentUri, expectedDocumentId, grant)
    }
}

/** Counts exact staging deletion as cleanup work and leaves it pending when the run is capped. */
internal class BudgetedSafStaging(
    private val delegate: SafStaging,
    private val budget: SafCommitExecutionBudget,
) : SafStaging {
    override fun length(identity: PartialIdentity): Long? = delegate.length(identity)

    override fun open(identity: PartialIdentity): SafOpen =
        budget.cancellationError()?.let { SafOpen.Refused(it) } ?: delegate.open(identity)

    override fun delete(identity: PartialIdentity): Boolean =
        budget.reserveStagingCleanup()?.let { false } ?: delegate.delete(identity)
}
