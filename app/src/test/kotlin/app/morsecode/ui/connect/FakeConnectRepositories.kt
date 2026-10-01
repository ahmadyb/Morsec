package app.morsecode.ui.connect

import app.morsecode.core.data.repository.DeviceRepository
import app.morsecode.core.data.settings.SettingsRepository
import app.morsecode.core.model.Accent
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.model.MorseSettings
import app.morsecode.core.model.NetworkPorts
import app.morsecode.core.model.Peer
import app.morsecode.core.model.RecentDevice
import app.morsecode.core.model.SafGrant
import app.morsecode.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * The two repositories Connect's view model reads, narrowed to what the screen renders.
 *
 * Nothing here stands in for work the app would do: an empty recent list is a list that
 * is empty, and the settings flow emits one real [MorseSettings] the screen reports
 * honestly. Every mutation applies the same semantics the real repositories document —
 * recent rows bump their interaction count, the port clamps, the broadcast cap cycles —
 * so a test that drives one sees the app's own rules rather than a stub's.
 */
internal class FakeDeviceRepository(
    recent: List<RecentDevice> = emptyList(),
) : DeviceRepository {

    private val recentDevices = MutableStateFlow(recent)
    private val safGrants = MutableStateFlow<List<SafGrant>>(emptyList())

    override fun observeRecentDevices(limit: Int): Flow<List<RecentDevice>> =
        recentDevices.map { rows -> rows.take(limit) }

    override suspend fun remember(peer: Peer, summary: String?) {
        val current = recentDevices.value
        val existing = current.firstOrNull { it.peerId == peer.peerId }
        val row = RecentDevice.of(peer, summary, existing?.interactionCount ?: 0)
        recentDevices.value = (listOf(row) + current.filterNot { it.peerId == peer.peerId })
            .take(RECENT_LIMIT)
    }

    override suspend fun forget(peerId: String) {
        recentDevices.value = recentDevices.value.filterNot { it.peerId == peerId }
    }

    override suspend fun clearRecentDevices() {
        recentDevices.value = emptyList()
    }

    override fun observeGrants(): Flow<List<SafGrant>> = safGrants

    override suspend fun addGrant(treeUri: String, displayName: String, readWrite: Boolean): Long {
        val nextId = (safGrants.value.maxOfOrNull { it.id } ?: 0L) + 1L
        safGrants.value = safGrants.value + SafGrant(
            id = nextId,
            treeUri = treeUri,
            displayName = displayName,
            readWrite = readWrite,
        )
        return nextId
    }

    override suspend fun removeGrant(grantId: Long) {
        safGrants.value = safGrants.value.filterNot { it.id == grantId }
    }

    override suspend fun removeGrantForUri(treeUri: String) {
        safGrants.value = safGrants.value.filterNot { it.treeUri == treeUri }
    }

    override suspend fun grantCount(): Int = safGrants.value.size

    private companion object {
        const val RECENT_LIMIT: Int = DeviceRepository.RECENT_LIMIT
    }
}

/**
 * Settings, as one in-memory store: [settings] emits it, [current] reads it, [update]
 * applies the caller's transform. The field setters do exactly what their names say.
 */
internal class FakeSettingsRepository(
    initial: MorseSettings = MorseSettings(),
) : SettingsRepository {

    private val store = MutableStateFlow(initial)

    override val settings: Flow<MorseSettings> = store

    override suspend fun current(): MorseSettings = store.value

    override suspend fun update(transform: (MorseSettings) -> MorseSettings): MorseSettings {
        val next = transform(store.value)
        store.value = next
        return next
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        store.value = store.value.copy(themeMode = mode)
    }

    override suspend fun setAccent(accent: Accent) {
        store.value = store.value.copy(accent = accent)
    }

    override suspend fun setSoundsEnabled(enabled: Boolean) {
        store.value = store.value.copy(soundsEnabled = enabled)
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        store.value = store.value.copy(notificationsEnabled = enabled)
    }

    override suspend fun setDuplicatePolicy(policy: DuplicatePolicy) {
        store.value = store.value.copy(duplicatePolicy = policy)
    }

    override suspend fun cycleBroadcastLimit(): Int {
        store.value = store.value.withNextBroadcastLimit()
        return store.value.broadcastPeerLimit
    }

    override suspend fun setDeviceName(name: String?) {
        store.value = store.value.copy(deviceName = name)
    }

    override suspend fun setCrashReportingEnabled(enabled: Boolean) {
        store.value = store.value.copy(crashReportingEnabled = enabled)
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        store.value = store.value.copy(onboardingCompleted = completed)
    }

    override suspend fun setWebShareEnabled(enabled: Boolean) {
        store.value = store.value.copy(webShareEnabled = enabled)
    }

    override suspend fun setWebSharePort(port: Int): Int {
        val clamped = port.coerceIn(NetworkPorts.USER_PORT_RANGE)
        store.value = store.value.copy(webSharePort = clamped)
        return clamped
    }

    override suspend fun setReducedMotion(reduced: Boolean) {
        store.value = store.value.copy(reducedMotion = reduced)
    }
}
