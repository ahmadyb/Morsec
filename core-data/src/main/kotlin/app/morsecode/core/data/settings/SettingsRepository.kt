package app.morsecode.core.data.settings

import app.morsecode.core.model.Accent
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.model.MorseSettings
import app.morsecode.core.model.NetworkPorts
import app.morsecode.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for user settings.
 *
 * The Settings screen and every consumer (theme, notification policy, broadcast
 * fan-out cap, WebShare port) read the same [Flow], so a change is applied
 * everywhere at once instead of being copied into view state.
 */
public interface SettingsRepository {

    /** Always emits at least the defaults; never errors on a corrupt store. */
    public val settings: Flow<MorseSettings>

    /** Snapshot for callers outside a coroutine flow (e.g. service start-up). */
    public suspend fun current(): MorseSettings

    /** Applies [transform] atomically; the returned value is what was stored. */
    public suspend fun update(transform: (MorseSettings) -> MorseSettings): MorseSettings

    public suspend fun setThemeMode(mode: ThemeMode)

    public suspend fun setAccent(accent: Accent)

    public suspend fun setSoundsEnabled(enabled: Boolean)

    public suspend fun setNotificationsEnabled(enabled: Boolean)

    public suspend fun setDuplicatePolicy(policy: DuplicatePolicy)

    /** Cycles 2 → 3 → … → 8 → 2, exactly like the mockup's Settings row. */
    public suspend fun cycleBroadcastLimit(): Int

    public suspend fun setDeviceName(name: String?)

    public suspend fun setCrashReportingEnabled(enabled: Boolean)

    public suspend fun setOnboardingCompleted(completed: Boolean)

    public suspend fun setWebShareEnabled(enabled: Boolean)

    /** Clamped to [NetworkPorts.USER_PORT_RANGE]; returns the stored value. */
    public suspend fun setWebSharePort(port: Int): Int

    public suspend fun setReducedMotion(reduced: Boolean)
}
