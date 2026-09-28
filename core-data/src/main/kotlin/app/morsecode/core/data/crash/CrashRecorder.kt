package app.morsecode.core.data.crash

import android.content.Context
import android.os.Build
import android.os.Process
import app.morsecode.core.data.db.CrashReportEntity
import app.morsecode.core.data.db.MorseDatabase
import app.morsecode.core.data.logging.LogRedactor
import app.morsecode.core.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

/**
 * Local crash reporting (master prompt §10).
 *
 * Reports are written to the app's own database and are **never uploaded**: the
 * Crashes screen lists them, shows the redacted stack trace and offers a Share
 * action so the user decides whether anything leaves the device. Turning the
 * setting off stops recording but keeps existing rows until the user clears them.
 *
 * The handler chains to whatever handler was installed before it, so platform
 * behaviour (and the process kill) is unchanged.
 */
@Singleton
public class CrashRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MorseDatabase,
    private val settings: SettingsRepository,
) {

    @Volatile
    private var installed: Boolean = false

    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /** Installs the handler once. Safe to call from [android.app.Application.onCreate]. */
    public fun install() {
        if (installed) return
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is MorseUncaughtExceptionHandler) {
            installed = true
            return
        }
        previousHandler = current
        Thread.setDefaultUncaughtExceptionHandler(MorseUncaughtExceptionHandler())
        installed = true
    }

    /** Number of stored reports, for the Settings row subtitle. */
    public suspend fun count(): Int = database.crashReportDao().count()

    private fun record(thread: Thread, throwable: Throwable) {
        val component = componentFor(thread)
        val trace = LogRedactor.redactStackTrace(throwable, MAX_TRACE_LINES)
        val info = appInfo()
        val row = CrashReportEntity(
            occurredEpochMillis = System.currentTimeMillis(),
            component = component,
            exceptionType = throwable.javaClass.name,
            message = LogRedactor.redact(throwable.message),
            stackTrace = trace,
            appVersion = info.first,
            versionCode = info.second,
            androidSdkInt = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            recoveryNote = recoveryNoteFor(component),
        )
        // Bounded blocking writes: the process is about to die, so a coroutine
        // that outlives it would never run. The timeouts keep a stuck database
        // from turning a crash into an ANR.
        runBlocking {
            withTimeoutOrNull(RECORD_TIMEOUT_MILLIS) {
                runCatching { database.crashReportDao().insert(row) }
                runCatching { database.crashReportDao().trim(MorseDatabase.MAX_CRASH_REPORTS) }
            }
        }
    }

    private fun recordingEnabled(): Boolean = runBlocking {
        withTimeoutOrNull(SETTINGS_TIMEOUT_MILLIS) {
            runCatching { settings.current().crashReportingEnabled }.getOrDefault(true)
        }
    } ?: true

    private fun appInfo(): Pair<String, Int> = runCatching {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val version = packageInfo.versionName ?: "unknown"
        // `longVersionCode` only exists from API 28; minSdk is 23.
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }
        version to code
    }.getOrElse { "unknown" to 0 }

    private inner class MorseUncaughtExceptionHandler : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            runCatching {
                if (recordingEnabled()) record(thread, throwable)
            }
            val previous = previousHandler
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(EXIT_CODE)
            }
        }
    }

    public companion object {
        private const val MAX_TRACE_LINES = 80
        private const val RECORD_TIMEOUT_MILLIS = 1_500L
        private const val SETTINGS_TIMEOUT_MILLIS = 300L
        private const val EXIT_CODE = 10

        /**
         * Attributes a crash to the subsystem that owned the thread, so the
         * Crashes screen can name the failing component instead of showing
         * "Thread-7".
         */
        public fun componentFor(thread: Thread): String {
            val name = thread.name.lowercase()
            return when {
                "transfer" in name || "engine" in name -> "TransferService"
                "webshare" in name || "http" in name -> "WebShareServer"
                "discovery" in name || "nearby" in name || "beacon" in name -> "Discovery"
                "media" in name || "player" in name -> "MediaPlayer"
                "storage" in name || "saf" in name -> "Storage"
                else -> thread.name
            }
        }

        /**
         * What the user can expect after this crash, per component. Transfer
         * offsets are persisted as they are confirmed, so an interrupted
         * transfer resumes instead of restarting.
         */
        public fun recoveryNoteFor(component: String): String = when (component) {
            "TransferService" -> "Confirmed offsets were persisted; the transfer can resume."
            "WebShareServer" -> "Browser sessions stay revoked until re-approved."
            "Discovery" -> "Discovery restarts on the next Connect screen visit."
            "MediaPlayer" -> "Playback position is not saved; the file itself is untouched."
            else -> "No in-flight work was owned by this component."
        }
    }
}
