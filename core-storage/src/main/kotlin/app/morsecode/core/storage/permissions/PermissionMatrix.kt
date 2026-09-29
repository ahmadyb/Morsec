package app.morsecode.core.storage.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime permission matrix, resolved per API level (master prompt §11, §4).
 *
 * Permissions are requested **contextually**: the lists here are what a given
 * feature needs at the current SDK, and each screen asks only for its own set at
 * the moment the user triggers the action — never all of them at launch.
 *
 * API 23 is the floor, so every branch below is reachable on a supported device.
 *
 * InlinedApi is suppressed for the whole matrix: these are compile-time constants,
 * and each one is only ever returned from a branch that tests the SDK level first.
 * The level arrives as a parameter (defaulting to `Build.VERSION.SDK_INT`) so the
 * matrix is unit-testable, which is exactly why lint cannot see the guard itself.
 */
@SuppressLint("InlinedApi")
public object PermissionMatrix {

    /** Reading photos/videos/audio. API 33 split READ_EXTERNAL_STORAGE per media type. */
    public fun mediaRead(sdk: Int = Build.VERSION.SDK_INT): List<String> = when {
        sdk >= 33 -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )

        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * Writing received files.
     *
     * API 29+ writes through MediaStore/SAF and needs nothing; API 23-28 needs
     * WRITE_EXTERNAL_STORAGE to create files in shared folders.
     */
    public fun mediaWrite(sdk: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdk <= 28) listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyList()

    /**
     * LAN discovery: Wi-Fi state plus a multicast lock so UDP beacons are
     * delivered while the screen is on.
     */
    public fun lanDiscovery(sdk: Int = Build.VERSION.SDK_INT): List<String> = when {
        // API 23-32: a Wi-Fi scan/read requires location permission.
        sdk <= 32 -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyList()
    }

    /**
     * Nearby Connections. API 31+ replaced the location requirement with the
     * neverForLocation Bluetooth permissions; older levels reuse location.
     */
    public fun nearby(sdk: Int = Build.VERSION.SDK_INT): List<String> = when {
        sdk >= 31 -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

        else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Ongoing transfer notification. Only a runtime permission from API 33. */
    public fun notifications(sdk: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdk >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    /** Union of everything a full LAN or Nearby transfer can need, in ask order. */
    public fun transfer(sdk: Int = Build.VERSION.SDK_INT): List<String> =
        (mediaRead(sdk) + mediaWrite(sdk) + lanDiscovery(sdk) + nearby(sdk) + notifications(sdk)).distinct()

    public fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    public fun granted(context: Context, permissions: List<String>): Boolean =
        permissions.all { isGranted(context, it) }

    /** The subset of [permissions] that is still missing, preserving order. */
    public fun missing(context: Context, permissions: List<String>): List<String> =
        permissions.filterNot { isGranted(context, it) }

    /**
     * True when Wi-Fi multicast beacons can be received.
     *
     * CHANGE_WIFI_MULTICAST_STATE is an install-time permission, so this only
     * fails if the manifest entry is missing. Android 12+ additionally needs
     * location *enabled* (not granted) for Wi-Fi scanning, which the Connection
     * Doctor reports as its own check.
     */
    public fun canUseMulticast(context: Context): Boolean =
        isGranted(context, Manifest.permission.CHANGE_WIFI_MULTICAST_STATE)

    /**
     * True when the platform will deliver Wi-Fi scan results: location must be
     * enabled on API 23-32, and on API 33+ the nearby permissions cover it.
     */
    public fun wifiScanBlockedByLocation(context: Context, sdk: Int = Build.VERSION.SDK_INT): Boolean =
        sdk <= 32 && !isLocationEnabled(context)

    private fun isLocationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return true
        // isLocationEnabled is API 28+; the provider checks cover API 23-27.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            manager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                @Suppress("DEPRECATION")
                manager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        }
    }
}
