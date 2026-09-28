package app.morsecode.core.storage.apps

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MediaKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Apps tab.
 *
 * Only launchable apps are listed, which is exactly the set the manifest's
 * `<queries>` intent filter makes visible on API 30+ — no QUERY_ALL_PACKAGES,
 * so the app stays within Play policy while still sharing real APK files.
 *
 * The APK path from [android.content.pm.ApplicationInfo.sourceDir] is readable
 * without extra permission on every supported API level, and its size and mtime
 * are read from the file so the row shows real numbers.
 */
@Singleton
internal class InstalledAppsReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    public fun apps(): List<MediaItem> {
        val packageManager = context.packageManager
        val installed = runCatching {
            packageManager.getInstalledApplications(0)
        }.getOrDefault(emptyList())

        return installed.mapNotNull { info ->
            val packageName = info.packageName ?: return@mapNotNull null
            // getLaunchIntentForPackage is null for non-launchable system parts.
            if (packageManager.getLaunchIntentForPackage(packageName) == null) return@mapNotNull null
            val sourceDir = info.sourceDir ?: return@mapNotNull null
            val apk = File(sourceDir)
            val label = runCatching { packageManager.getApplicationLabel(info).toString() }
                .getOrDefault(packageName)
            val versionName = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull()
            MediaItem(
                id = "app:$packageName",
                displayName = label,
                kind = MediaKind.APK,
                mimeType = MIME_APK,
                sizeBytes = if (apk.canRead()) apk.length() else 0L,
                dateModifiedEpochMillis = if (apk.canRead()) apk.lastModified() else info.firstInstallTime,
                uriString = Uri.fromFile(apk).toString(),
                title = label,
                bucket = versionName,
                splitCount = info.splitSourceDirs?.size ?: 0,
            )
        }
    }

    public fun byPackage(packageName: String): MediaItem? =
        apps().firstOrNull { it.id == "app:$packageName" }

    public companion object {
        public const val MIME_APK: String = "application/vnd.android.package-archive"

        /** Intent filter used in the manifest so launcher apps stay visible. */
        public const val VISIBILITY_ACTION: String = PackageManager.ACTION_MAIN
    }
}
