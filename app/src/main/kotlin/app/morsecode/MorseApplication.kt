package app.morsecode

import android.app.Application
import app.morsecode.core.data.crash.CrashRecorder
import app.morsecode.core.data.db.MorseDatabase
import app.morsecode.core.data.logging.MorseLogger
import app.morsecode.core.data.repository.TransferRepository
import app.morsecode.core.model.di.ApplicationScope
import app.morsecode.core.storage.MediaRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hilt graph root.
 *
 * Three things happen at start-up, all of them real maintenance work:
 *  1. the local crash handler is installed (nothing is ever uploaded);
 *  2. the first log line records the running version, matching the export header;
 *  3. revoked SAF grants and stale closed sessions are pruned off the main thread.
 */
@HiltAndroidApp
public class MorseApplication : Application() {

    @Inject internal lateinit var crashRecorder: CrashRecorder

    @Inject internal lateinit var logger: MorseLogger

    @Inject internal lateinit var mediaRepository: MediaRepository

    @Inject internal lateinit var transferRepository: TransferRepository

    @Inject
    @ApplicationScope
    internal lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        crashRecorder.install()
        logger.i(
            TAG,
            "Morsecode ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) started",
        )
        applicationScope.launch {
            val revoked = runCatching { mediaRepository.pruneRevokedGrants() }.getOrDefault(0)
            if (revoked > 0) {
                logger.w(TAG, "Dropped $revoked revoked folder grant(s)", ERROR_REVOKED_GRANTS)
            }
            runCatching {
                transferRepository.pruneClosedSessions(
                    System.currentTimeMillis() - MorseDatabase.SESSION_RETENTION_MILLIS,
                )
            }
        }
    }

    private companion object {
        const val TAG = "app"
        const val ERROR_REVOKED_GRANTS = "E_GRANT_REVOKED"
    }
}
