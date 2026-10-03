package app.morsecode.core.storage.transfer

import android.content.Context
import android.os.Build
import android.os.Environment
import app.morsecode.core.storage.permissions.PermissionMatrix
import app.morsecode.core.transfer.identity.RelativeTransferPath
import java.io.File

/**
 * Direct shared-storage sources for API 23-28, where that is the only way to read
 * a file the app did not create.
 *
 * The point of this class is that it is *narrower* than the filesystem, not wider.
 * Three limits, each of them a deliberate refusal:
 *
 * 1. **It does not exist on API 29+.** Scoped storage removed the ability to read
 *    shared storage by path, and this class must not become a back door to it, so
 *    [isAvailable] is false and [create] returns null. MediaStore and SAF cover
 *    those levels.
 * 2. **Only approved roots.** Nothing resolves outside the roots handed in, and
 *    resolution goes through [ApprovedRoots], so a traversal or a symlink is
 *    refused before a descriptor exists.
 * 3. **The runtime permission is re-checked on every use.** A grant can be
 *    withdrawn in Settings while a transfer sits queued, and the failure this must
 *    not produce is a transfer that silently reads nothing.
 *
 * Nothing here uses an API unavailable on 23, and no source exposes an absolute
 * path.
 */
public class LegacyFileTransferSources private constructor(
    private val sources: List<FileBackedTransferSources>,
    private val sdk: Int,
) {

    /** False on scoped storage, where the approved roots are no longer readable. */
    public val isAvailable: Boolean get() = sdk <= MAX_LEGACY_SDK && sources.isNotEmpty()

    /**
     * The source for [relativePath] in the first approved root that contains it,
     * or null when there is none — including on API 29+, where the answer is
     * always null rather than "found but unusable".
     */
    public fun create(
        relativePath: RelativeTransferPath,
        displayName: String? = null,
        mimeType: String? = null,
    ): FileBackedTransferSource? {
        if (!isAvailable) return null
        return sources.firstNotNullOfOrNull { root ->
            root.create(relativePath, displayName, mimeType)
        }
    }

    /** Whether any approved root resolves [relativePath]. */
    public fun contains(relativePath: RelativeTransferPath): Boolean =
        isAvailable && sources.any { it.contains(relativePath) }

    public companion object {

        /** The last SDK on which direct shared storage is available. */
        public const val MAX_LEGACY_SDK: Int = 28

        /** The first SDK on which it is not, so callers do not write the +1. */
        public const val FIRST_SCOPED_SDK: Int = 29

        /**
         * Builds the sources for [roots], gated on [sdk] and on the runtime
         * permission that level requires.
         *
         * [context] is reduced to its application context so a caller cannot
         * leave an activity reachable from a queued transfer.
         */
        public fun create(
            context: Context,
            roots: List<File> = defaultRoots(),
            sdk: Int = Build.VERSION.SDK_INT,
        ): LegacyFileTransferSources = LegacyFileTransferSources(
            sources = roots.mapIndexed { index, root ->
                FileBackedTransferSources(
                    root = root,
                    // An index, not the path: the key must not carry a location.
                    authority = "legacy:$index",
                    permissionGate = permissionGate(context.applicationContext, sdk),
                )
            },
            sdk = sdk,
        )

        /** The permissions still missing, for a screen to request. */
        public fun missingPermissions(context: Context, sdk: Int = Build.VERSION.SDK_INT): List<String> =
            PermissionMatrix.missing(context, PermissionMatrix.mediaRead(sdk))

        /**
         * The public shared directories this app reads from on API 23-28.
         *
         * Deprecated on 29+ by design, and only ever called for [MAX_LEGACY_SDK]
         * and below. Callers on scoped storage get an empty list rather than a
         * set of directories they cannot read.
         */
        @Suppress("DEPRECATION")
        public fun defaultRoots(): List<File> {
            if (Build.VERSION.SDK_INT > MAX_LEGACY_SDK) return emptyList()
            val external = Environment.getExternalStorageDirectory() ?: return emptyList()
            return listOf(
                Environment.DIRECTORY_DOWNLOADS,
                Environment.DIRECTORY_DCIM,
                Environment.DIRECTORY_PICTURES,
                Environment.DIRECTORY_MOVIES,
                Environment.DIRECTORY_MUSIC,
                Environment.DIRECTORY_DOCUMENTS,
            ).mapNotNull { type ->
                runCatching { File(external, type) }.getOrNull()
            }.filter { it.isDirectory }
        }

        private fun permissionGate(context: Context, sdk: Int): () -> Boolean = {
            val required = PermissionMatrix.mediaRead(sdk)
            required.isEmpty() || PermissionMatrix.granted(context, required)
        }
    }
}
