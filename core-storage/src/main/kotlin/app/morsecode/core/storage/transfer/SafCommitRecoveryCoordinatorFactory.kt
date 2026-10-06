package app.morsecode.core.storage.transfer

import android.content.ContentResolver

/** Testable construction seam; the production implementation always starts from DocumentsContract. */
internal fun interface SafCommitRecoveryCoordinatorFactory {
    fun create(
        budget: SafCommitExecutionBudget,
        isCancelled: () -> Boolean,
    ): SafCommitCoordinator
}

internal class ProductionSafCommitRecoveryCoordinatorFactory(
    private val resolver: ContentResolver,
    private val staging: SafStaging,
    private val journal: SafCommitJournal,
) : SafCommitRecoveryCoordinatorFactory {
    override fun create(
        budget: SafCommitExecutionBudget,
        isCancelled: () -> Boolean,
    ): SafCommitCoordinator = SafCommitCoordinatorFactory.createForRestoration(
        resolver = resolver,
        staging = staging,
        journal = journal,
        budget = budget,
        isCancelled = isCancelled,
    )
}
