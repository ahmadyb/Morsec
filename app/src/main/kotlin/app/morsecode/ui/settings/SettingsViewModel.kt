package app.morsecode.ui.settings

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsecode.BuildConfig
import app.morsecode.core.data.repository.DiagnosticsRepository
import app.morsecode.core.data.settings.SettingsRepository
import app.morsecode.core.model.Accent
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.model.MorseSettings
import app.morsecode.core.model.SafGrant
import app.morsecode.core.model.ThemeMode
import app.morsecode.core.storage.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

public data class SettingsUiState(
    val settings: MorseSettings = MorseSettings(),
    val appVersion: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    val crashCount: Int = 0,
    val grants: List<SafGrant> = emptyList(),
    val batteryExempt: Boolean = false,
    val renameVisible: Boolean = false,
    val nameDraft: String = "",
)

@HiltViewModel
public class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    diagnostics: DiagnosticsRepository,
    private val media: MediaRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val renameVisible = MutableStateFlow(false)
    private val nameDraft = MutableStateFlow("")
    private val batteryExempt = MutableStateFlow(isIgnoringBatteryOptimizations())

    public val state: StateFlow<SettingsUiState> = combine(
        settings.settings,
        diagnostics.observeCrashCount(),
        media.observeAccess(),
        renameVisible,
        nameDraft,
    ) { prefs, crashes, access, renaming, draft ->
        SettingsUiState(
            settings = prefs,
            crashCount = crashes,
            grants = access.grants,
            batteryExempt = batteryExempt.value,
            renameVisible = renaming,
            nameDraft = draft,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SettingsUiState(),
    )

    public fun setDarkMode(dark: Boolean) {
        update { it.copy(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) }
    }

    public fun setFollowSystem(follow: Boolean) {
        update { current ->
            val mode = if (follow) {
                ThemeMode.FOLLOW_SYSTEM
            } else {
                if (current.themeMode == ThemeMode.LIGHT) ThemeMode.LIGHT else ThemeMode.DARK
            }
            current.copy(themeMode = mode)
        }
    }

    public fun setAccent(accent: Accent) {
        update { it.copy(accent = accent) }
    }

    public fun setSounds(enabled: Boolean) {
        update { it.copy(soundsEnabled = enabled) }
    }

    public fun setNotifications(enabled: Boolean) {
        update { it.copy(notificationsEnabled = enabled) }
    }

    /** Cycles Rename → Overwrite → Skip → Ask, as the reference row does. */
    public fun cycleDuplicatePolicy() {
        update { it.copy(duplicatePolicy = it.duplicatePolicy.next()) }
    }

    public fun cycleBroadcastLimit() {
        viewModelScope.launch { settings.cycleBroadcastLimit() }
    }

    public fun openRename(currentName: String?) {
        nameDraft.value = currentName.orEmpty()
        renameVisible.value = true
    }

    public fun dismissRename() {
        renameVisible.value = false
    }

    public fun setNameDraft(value: String) {
        nameDraft.value = value
    }

    public fun saveDeviceName() {
        val trimmed = nameDraft.value.trim()
        renameVisible.value = false
        update { it.copy(deviceName = trimmed.ifEmpty { null }) }
    }

    public fun setCrashReporting(enabled: Boolean) {
        update { it.copy(crashReportingEnabled = enabled) }
    }

    public fun addFolder(treeUri: String) {
        viewModelScope.launch { media.addFolder(treeUri) }
    }

    public fun removeFolder(grantId: Long) {
        viewModelScope.launch { media.removeFolder(grantId) }
    }

    /** Replays the tour by clearing the persisted completion flag. */
    public fun replayOnboarding() {
        update { it.copy(onboardingCompleted = false) }
    }

    /**
     * Opens the system battery-optimization list.
     *
     * The direct "ignore optimizations for this app" intent needs a permission
     * Play restricts, so the app opens the system list instead — one extra tap
     * for the user, no policy risk.
     */
    public fun batterySettingsIntent(): Intent = Intent(
        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    public fun refreshBatteryState() {
        batteryExempt.value = isIgnoringBatteryOptimizations()
    }

    public fun policyLabel(policy: DuplicatePolicy): Int = when (policy) {
        DuplicatePolicy.RENAME -> app.morsecode.R.string.policy_rename
        DuplicatePolicy.OVERWRITE -> app.morsecode.R.string.policy_overwrite
        DuplicatePolicy.SKIP -> app.morsecode.R.string.policy_skip
        DuplicatePolicy.ASK -> app.morsecode.R.string.policy_ask
    }

    private fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        manager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }.getOrDefault(false)

    private fun update(transform: (MorseSettings) -> MorseSettings) {
        viewModelScope.launch { settings.update(transform) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
