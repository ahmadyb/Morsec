package app.morsecode.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.morsecode.core.model.Accent
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.model.MorseSettings
import app.morsecode.core.model.NetworkPorts
import app.morsecode.core.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** One DataStore file for the whole app; the delegate must be a top-level val. */
private val Context.morseSettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "morsecode_settings",
)

/**
 * DataStore backed [SettingsRepository].
 *
 * Reads fall back to defaults on a corrupt or unreadable store rather than
 * crashing the app at start-up, and every write is clamped to the ranges the
 * [MorseSettings] `require` block accepts, so a malformed persisted value can
 * never throw during a later `update`.
 */
@Singleton
internal class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private val store: DataStore<Preferences> get() = context.morseSettingsStore

    override val settings: Flow<MorseSettings> = store.data
        .catch { error ->
            // A truncated DataStore file must not brick the app: serve defaults.
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { it.toSettings() }

    override suspend fun current(): MorseSettings = settings.first()

    override suspend fun update(transform: (MorseSettings) -> MorseSettings): MorseSettings {
        val stored = store.edit { preferences ->
            preferences.writeFrom(transform(preferences.toSettings()).sanitized())
        }
        return stored.toSettings()
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        update { it.copy(themeMode = mode) }
    }

    override suspend fun setAccent(accent: Accent) {
        update { it.copy(accent = accent) }
    }

    override suspend fun setSoundsEnabled(enabled: Boolean) {
        update { it.copy(soundsEnabled = enabled) }
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        update { it.copy(notificationsEnabled = enabled) }
    }

    override suspend fun setDuplicatePolicy(policy: DuplicatePolicy) {
        update { it.copy(duplicatePolicy = policy) }
    }

    override suspend fun cycleBroadcastLimit(): Int =
        update { it.withNextBroadcastLimit() }.broadcastPeerLimit

    override suspend fun setDeviceName(name: String?) {
        update { it.copy(deviceName = name) }
    }

    override suspend fun setCrashReportingEnabled(enabled: Boolean) {
        update { it.copy(crashReportingEnabled = enabled) }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        update { it.copy(onboardingCompleted = completed) }
    }

    override suspend fun setWebShareEnabled(enabled: Boolean) {
        update { it.copy(webShareEnabled = enabled) }
    }

    override suspend fun setWebSharePort(port: Int): Int =
        update { it.copy(webSharePort = port) }.webSharePort

    override suspend fun setReducedMotion(reduced: Boolean) {
        update { it.copy(reducedMotion = reduced) }
    }

}

private fun Preferences.toSettings(): MorseSettings {
    val defaults = MorseSettings()
    return MorseSettings(
        themeMode = ThemeMode.fromId(this[DataStoreSettingsRepositoryKeys.THEME_MODE]),
        accent = Accent.fromId(this[DataStoreSettingsRepositoryKeys.ACCENT]),
        soundsEnabled = this[DataStoreSettingsRepositoryKeys.SOUNDS] ?: defaults.soundsEnabled,
        notificationsEnabled =
            this[DataStoreSettingsRepositoryKeys.NOTIFICATIONS] ?: defaults.notificationsEnabled,
        duplicatePolicy = DuplicatePolicy.fromId(this[DataStoreSettingsRepositoryKeys.DUPLICATE_POLICY]),
        broadcastPeerLimit = this[DataStoreSettingsRepositoryKeys.BROADCAST_LIMIT]
            ?.coerceIn(MorseSettings.BROADCAST_LIMIT_RANGE)
            ?: defaults.broadcastPeerLimit,
        deviceName = this[DataStoreSettingsRepositoryKeys.DEVICE_NAME]?.takeIf { it.isNotBlank() },
        crashReportingEnabled =
            this[DataStoreSettingsRepositoryKeys.CRASH_REPORTING] ?: defaults.crashReportingEnabled,
        onboardingCompleted =
            this[DataStoreSettingsRepositoryKeys.ONBOARDING_COMPLETED] ?: defaults.onboardingCompleted,
        webShareEnabled = this[DataStoreSettingsRepositoryKeys.WEBSHARE_ENABLED] ?: defaults.webShareEnabled,
        webSharePort = this[DataStoreSettingsRepositoryKeys.WEBSHARE_PORT]
            ?.coerceIn(NetworkPorts.USER_PORT_RANGE)
            ?: defaults.webSharePort,
        reducedMotion = this[DataStoreSettingsRepositoryKeys.REDUCED_MOTION] ?: defaults.reducedMotion,
    )
}

private fun MutablePreferences.writeFrom(settings: MorseSettings) {
    val keys = DataStoreSettingsRepositoryKeys
    this[keys.THEME_MODE] = settings.themeMode.id
    this[keys.ACCENT] = settings.accent.id
    this[keys.SOUNDS] = settings.soundsEnabled
    this[keys.NOTIFICATIONS] = settings.notificationsEnabled
    this[keys.DUPLICATE_POLICY] = settings.duplicatePolicy.id
    this[keys.BROADCAST_LIMIT] = settings.broadcastPeerLimit
    val deviceName = settings.deviceName
    if (deviceName == null) remove(keys.DEVICE_NAME) else this[keys.DEVICE_NAME] = deviceName
    this[keys.CRASH_REPORTING] = settings.crashReportingEnabled
    this[keys.ONBOARDING_COMPLETED] = settings.onboardingCompleted
    this[keys.WEBSHARE_ENABLED] = settings.webShareEnabled
    this[keys.WEBSHARE_PORT] = settings.webSharePort
    this[keys.REDUCED_MOTION] = settings.reducedMotion
}

/** Last line of defence: keeps a stored value inside the model's invariants. */
private fun MorseSettings.sanitized(): MorseSettings = copy(
    broadcastPeerLimit = broadcastPeerLimit.coerceIn(MorseSettings.BROADCAST_LIMIT_RANGE),
    webSharePort = webSharePort.coerceIn(NetworkPorts.USER_PORT_RANGE),
    deviceName = deviceName
        ?.trim()
        ?.take(DataStoreSettingsRepositoryKeys.MAX_DEVICE_NAME_LENGTH)
        ?.takeIf { it.isNotEmpty() },
)

/**
 * Key table exposed to the file-level extensions above. Kept separate from the
 * repository's private companion because Kotlin does not allow an extension
 * function outside the class to see its private members.
 */
internal object DataStoreSettingsRepositoryKeys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val ACCENT = stringPreferencesKey("accent")
    val SOUNDS = booleanPreferencesKey("sounds_enabled")
    val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
    val DUPLICATE_POLICY = stringPreferencesKey("duplicate_policy")
    val BROADCAST_LIMIT = intPreferencesKey("broadcast_peer_limit")
    val DEVICE_NAME = stringPreferencesKey("device_name")
    val CRASH_REPORTING = booleanPreferencesKey("crash_reporting_enabled")
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    val WEBSHARE_ENABLED = booleanPreferencesKey("webshare_enabled")
    val WEBSHARE_PORT = intPreferencesKey("webshare_port")
    val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")

    /** The mockup's device name field accepts at most 24 visible characters. */
    const val MAX_DEVICE_NAME_LENGTH = 24
}
