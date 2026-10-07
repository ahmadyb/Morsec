package app.morsecode.core.storage.permissions

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

public class PermissionMatrixTest {
    @Test
    public fun lanDiscoveryDoesNotRequestRuntimePermissionOnSupportedApiLevels() {
        listOf(23, 28, 30, 31, 32, 33, 34, 36).forEach { sdk ->
            assertEquals("API $sdk", emptyList<String>(), PermissionMatrix.lanDiscovery(sdk))
        }
    }

    @Test
    public fun nearbyRetainsItsSeparatePlatformRuntimeRequirements() {
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), PermissionMatrix.nearby(30))
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            PermissionMatrix.nearby(31),
        )
    }
}
