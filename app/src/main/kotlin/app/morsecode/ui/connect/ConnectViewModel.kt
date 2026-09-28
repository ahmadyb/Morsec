package app.morsecode.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsecode.core.data.repository.DeviceRepository
import app.morsecode.core.data.settings.SettingsRepository
import app.morsecode.core.model.FeatureArea
import app.morsecode.core.model.FeatureReadiness
import app.morsecode.core.model.MorseFormatters
import app.morsecode.core.model.Peer
import app.morsecode.core.model.RecentDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

public data class ConnectUiState(
    /** Live peers; empty until the discovery transports are wired in. */
    val peers: List<Peer> = emptyList(),
    val recentDevices: List<RecentDevice> = emptyList(),
    val deviceName: String = "",
    val webSharePort: Int = 0,
    val webShareRunning: Boolean = false,
    /** False while discovery is gated, so the radar stays still instead of lying. */
    val discoveryLive: Boolean = false,
    val scanning: Boolean = false,
)

@HiltViewModel
public class ConnectViewModel @Inject constructor(
    private val devices: DeviceRepository,
    settings: SettingsRepository,
    private val formatters: MorseFormatters,
) : ViewModel() {

    private val peers = MutableStateFlow<List<Peer>>(emptyList())
    private val scanning = MutableStateFlow(false)

    public val state: StateFlow<ConnectUiState> = combine(
        devices.observeRecentDevices(),
        settings.settings,
        peers,
        scanning,
    ) { recent, prefs, discovered, isScanning ->
        ConnectUiState(
            peers = discovered,
            recentDevices = recent,
            deviceName = prefs.deviceName ?: "",
            webSharePort = prefs.webSharePort,
            webShareRunning = prefs.webShareEnabled && FeatureReadiness.isAvailable(FeatureArea.WEBSHARE_SERVER),
            discoveryLive = FeatureReadiness.isAvailable(FeatureArea.LAN_TRANSPORT) ||
                FeatureReadiness.isAvailable(FeatureArea.NEARBY_TRANSPORT),
            scanning = isScanning,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = ConnectUiState(),
    )

    /** "Clear" on the Recent devices section: a real delete of persisted rows. */
    public fun clearRecentDevices() {
        viewModelScope.launch { devices.clearRecentDevices() }
    }

    public fun forgetDevice(peerId: String) {
        viewModelScope.launch { devices.forget(peerId) }
    }

    public fun relativeTime(epochMillis: Long): String = formatters.relativeTime(epochMillis)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
