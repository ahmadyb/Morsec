package app.morsecode.core.storage.transfer

import android.content.Context
import app.morsecode.core.data.db.MorseDatabase
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Explicit production wiring for callable SAF commit restoration. The returned
 * object is inert: it opens no cursor and performs no provider operation until
 * [TransferRestorationCoordinator.restore] is called by an application action.
 */
public object TransferRestorationCoordinatorFactory {

    /**
     * Builds the real Room/SAF restoration path. No fake gateway or grant
     * resolver is available through this production entry point.
     */
    public fun createForProduction(
        context: Context,
        database: MorseDatabase,
        policy: RestorationExecutionPolicy = RestorationExecutionPolicy(),
        monotonicClock: RestorationMonotonicClock = SystemRestorationMonotonicClock,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): TransferRestorationCoordinator {
        val appContext = context.applicationContext ?: context
        val gateway = DocumentsContractSafGateway(appContext.contentResolver)
        val roomJournal = RoomSafCommitJournal(database, ioDispatcher)
        val discovery = RoomSafCheckpointDiscovery(database.transferPartialDao())
        val grantResolver = RoomPersistedSafGrantResolver(database.safGrantDao(), gateway)
        // The app-private incoming directory is represented lazily. Constructing
        // the store does not create the directory or open a partial file.
        val staging = AppPrivateSafStaging(
            AppPrivatePartialStore(File(appContext.filesDir, INCOMING_DIRECTORY)),
        )
        return TransferRestorationCoordinator(
            discovery = discovery,
            journal = roomJournal,
            persistedGrantResolver = grantResolver,
            gateway = gateway,
            staging = staging,
            executionPolicy = policy,
            monotonicClock = monotonicClock,
            ioDispatcher = ioDispatcher,
        )
    }

    private const val INCOMING_DIRECTORY = "incoming"
}
