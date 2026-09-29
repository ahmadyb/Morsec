package app.morsecode.ui.doctor

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsecode.R
import app.morsecode.core.model.CheckStatus
import app.morsecode.core.model.DiagnosticAction
import app.morsecode.core.model.DiagnosticCheck
import app.morsecode.core.model.NetworkPorts
import app.morsecode.core.storage.MediaRepository
import app.morsecode.core.storage.StorageAccess
import app.morsecode.core.storage.permissions.PermissionMatrix
import app.morsecode.core.model.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import javax.inject.Inject

/**
 * The Connection Doctor.
 *
 * Every check reads real platform state: the active network, the multicast
 * permission, location for Wi-Fi scanning, media permissions, SAF grants,
 * battery optimization, notification permission, Google Play services presence
 * and — by actually attempting to bind them — the three local ports the app
 * needs. Nothing is reported optimistically.
 */
@HiltViewModel
public class DoctorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val media: MediaRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val _checks = MutableStateFlow<List<DiagnosticCheck>>(emptyList())
    public val checks: StateFlow<List<DiagnosticCheck>> = _checks.asStateFlow()

    private val _running = MutableStateFlow(false)
    public val running: StateFlow<Boolean> = _running.asStateFlow()

    init {
        refresh()
    }

    public fun refresh() {
        viewModelScope.launch {
            _running.value = true
            val access = runCatching { mediaAccess() }.getOrDefault(StorageAccess())
            val freePorts = withContext(io) { portConflicts() }
            _checks.value = buildList {
                add(wifiCheck())
                add(multicastCheck())
                add(locationCheck())
                add(permissionCheck(access))
                add(storageCheck(access))
                if (access.revokedGrantUris.isNotEmpty()) add(revokedCheck(access))
                add(batteryCheck())
                add(notificationCheck())
                add(playServicesCheck())
                add(portCheck(freePorts))
            }
            _running.value = false
        }
    }

    private suspend fun mediaAccess(): StorageAccess = media.observeAccess().first()

    /** @return the first port that could not be bound, or null when all are free. */
    private fun portConflicts(): Int? {
        NetworkPorts.requiredPorts.forEach { port ->
            val free = runCatching {
                ServerSocket().use { socket ->
                    socket.reuseAddress = false
                    socket.bind(InetSocketAddress(port), BACKLOG)
                }
                true
            }.getOrDefault(false)
            if (!free) return port
        }
        return null
    }

    private fun wifiCheck(): DiagnosticCheck {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = runCatching { manager?.activeNetwork }.getOrNull()
        // getSystemService returns a nullable, so the lookup stays null-safe end to end.
        val capabilities = network?.let { runCatching { manager?.getNetworkCapabilities(it) }.getOrNull() }
        val onWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        return DiagnosticCheck(
            id = "wifi",
            titleId = R.string.doctor_check_wifi,
            detailId = if (onWifi) R.string.doctor_check_wifi_ok else R.string.doctor_check_wifi_off,
            status = if (onWifi) CheckStatus.OK else CheckStatus.ERROR,
            detailArgs = if (onWifi) listOf(TRANSPORT_LABEL) else emptyList(),
            actionId = if (onWifi) null else R.string.doctor_check_wifi_off,
            action = if (onWifi) null else DiagnosticAction.OPEN_WIFI_SETTINGS,
        )
    }

    private fun multicastCheck(): DiagnosticCheck {
        val ok = PermissionMatrix.canUseMulticast(context)
        return DiagnosticCheck(
            id = "multicast",
            titleId = R.string.doctor_check_multicast,
            detailId = if (ok) R.string.doctor_check_multicast_ok else R.string.doctor_check_multicast_bad,
            status = if (ok) CheckStatus.OK else CheckStatus.ERROR,
            detailArgs = if (ok) listOf(NetworkPorts.DISCOVERY_BEACON.toString()) else emptyList(),
        )
    }

    private fun locationCheck(): DiagnosticCheck {
        val blocked = PermissionMatrix.wifiScanBlockedByLocation(context)
        return DiagnosticCheck(
            id = "location",
            titleId = R.string.doctor_check_location,
            detailId = if (blocked) R.string.doctor_check_location_bad else R.string.doctor_check_location_ok,
            status = if (blocked) CheckStatus.WARN else CheckStatus.OK,
            detailArgs = if (blocked) listOf(Build.VERSION.SDK_INT.toString()) else emptyList(),
        )
    }

    private fun permissionCheck(access: StorageAccess): DiagnosticCheck {
        val missing = access.missingPermissions
        val ok = missing.isEmpty()
        return DiagnosticCheck(
            id = "permissions",
            titleId = R.string.doctor_check_permissions,
            detailId = if (ok) R.string.doctor_check_permissions_ok else R.string.doctor_check_permissions_bad,
            status = if (ok) CheckStatus.OK else CheckStatus.WARN,
            detailArgs = listOf(
                if (ok) context.getString(R.string.transport_label) else missing.joinToString(", ") { it.shortName() },
            ),
            actionId = if (ok) null else R.string.files_permission_grant,
            action = if (ok) null else DiagnosticAction.GRANT_STORAGE_ACCESS,
        )
    }

    private fun storageCheck(access: StorageAccess): DiagnosticCheck {
        val ok = access.canListDocuments
        return DiagnosticCheck(
            id = "storage",
            titleId = R.string.doctor_check_storage,
            detailId = if (ok) R.string.doctor_check_storage_ok else R.string.doctor_check_storage_bad,
            status = if (ok) CheckStatus.OK else CheckStatus.ERROR,
            detailArgs = if (ok) listOf(access.grants.size.toString()) else emptyList(),
            actionId = if (ok) null else R.string.files_add_folder,
            action = if (ok) null else DiagnosticAction.GRANT_STORAGE_ACCESS,
        )
    }

    private fun revokedCheck(access: StorageAccess): DiagnosticCheck = DiagnosticCheck(
        id = "revoked",
        titleId = R.string.doctor_check_revoked,
        detailId = R.string.doctor_check_revoked_bad,
        status = CheckStatus.WARN,
        detailArgs = listOf(access.revokedGrantUris.size.toString()),
        actionId = R.string.files_add_folder,
        action = DiagnosticAction.GRANT_STORAGE_ACCESS,
    )

    private fun batteryCheck(): DiagnosticCheck {
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val exempt = runCatching {
            manager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        }.getOrDefault(false)
        return DiagnosticCheck(
            id = "battery",
            titleId = R.string.doctor_check_battery,
            detailId = if (exempt) R.string.doctor_check_battery_ok else R.string.doctor_check_battery_bad,
            status = if (exempt) CheckStatus.OK else CheckStatus.WARN,
            actionId = if (exempt) null else R.string.doctor_battery_action,
            action = if (exempt) null else DiagnosticAction.REQUEST_BATTERY_EXEMPTION,
        )
    }

    private fun notificationCheck(): DiagnosticCheck {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return DiagnosticCheck(
            id = "notifications",
            titleId = R.string.doctor_check_notifications,
            detailId = if (enabled) R.string.doctor_check_notifications_ok else R.string.doctor_check_notifications_bad,
            status = if (enabled) CheckStatus.OK else CheckStatus.WARN,
            actionId = if (enabled) null else R.string.action_grant,
            action = if (enabled) null else DiagnosticAction.REQUEST_NOTIFICATION_PERMISSION,
        )
    }

    private fun playServicesCheck(): DiagnosticCheck {
        val present = runCatching {
            val info = context.packageManager.getApplicationInfo(PLAY_SERVICES_PACKAGE, 0)
            info.enabled
        }.getOrDefault(false)
        return DiagnosticCheck(
            id = "play_services",
            titleId = R.string.doctor_check_nearby,
            detailId = if (present) R.string.doctor_check_nearby_ok else R.string.doctor_check_nearby_bad,
            status = if (present) CheckStatus.OK else CheckStatus.WARN,
            actionId = if (present) null else R.string.doctor_check_nearby_bad,
            action = if (present) null else DiagnosticAction.OPEN_PLAY_STORE,
        )
    }

    private fun portCheck(conflict: Int?): DiagnosticCheck = DiagnosticCheck(
        id = "ports",
        titleId = R.string.doctor_check_ports,
        detailId = if (conflict == null) R.string.doctor_check_ports_ok else R.string.doctor_check_ports_bad,
        status = if (conflict == null) CheckStatus.OK else CheckStatus.ERROR,
        detailArgs = if (conflict == null) {
            listOf(
                NetworkPorts.WEBSHARE_HTTP.toString(),
                NetworkPorts.PEER_CONTROL.toString(),
                NetworkPorts.DISCOVERY_BEACON.toString(),
            )
        } else {
            listOf(conflict.toString())
        },
        actionId = if (conflict == null) null else R.string.doctor_check_ports_bad,
        action = if (conflict == null) null else DiagnosticAction.CHANGE_WEBSHARE_PORT,
    )

    /**
     * Opens the system battery-optimization list, the same target the battery
     * check's action uses.
     */
    public fun batterySettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Runs the corrective action a check offers; returns false when there is none. */
    public fun runAction(check: DiagnosticCheck): Boolean {
        val intent = when (check.action) {
            DiagnosticAction.OPEN_WIFI_SETTINGS ->
                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            DiagnosticAction.REQUEST_BATTERY_EXEMPTION ->
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            DiagnosticAction.REQUEST_NOTIFICATION_PERMISSION -> notificationSettingsIntent()

            DiagnosticAction.OPEN_PLAY_STORE ->
                Intent(Intent.ACTION_VIEW, "market://details?id=$PLAY_SERVICES_PACKAGE".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            DiagnosticAction.CHANGE_WEBSHARE_PORT,
            DiagnosticAction.GRANT_STORAGE_ACCESS,
            DiagnosticAction.REQUEST_NEARBY_PERMISSIONS,
            DiagnosticAction.RETRY_DISCOVERY,
            null,
            -> null
        }
        if (intent == null) return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * Where the user turns notifications for Morsecode back on.
     *
     * The per-app notification settings screen only exists from API 26, so on an
     * API 23-25 device the action opens the app details screen instead: the
     * check's button must never dead-end on a supported device.
     */
    private fun notificationSettingsIntent(): Intent {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData("package:${context.packageName}".toUri())
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun String.shortName(): String = substringAfterLast('.')

    public companion object {
        public const val PLAY_SERVICES_PACKAGE: String = "com.google.android.gms"
        private const val TRANSPORT_LABEL = "Wi-Fi"
        private const val BACKLOG = 1
    }
}
