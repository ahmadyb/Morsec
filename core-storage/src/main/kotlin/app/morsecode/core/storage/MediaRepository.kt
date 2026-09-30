package app.morsecode.core.storage

import android.content.IntentSender
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.SafGrant
import kotlinx.coroutines.flow.Flow

/**
 * What the app is currently allowed to read.
 *
 * The Files screen and the Connection Doctor both render from this, so a missing
 * permission is explained instead of producing an empty grid that looks like the
 * device has no photos.
 */
public data class StorageAccess(
    /** Media read permission granted for the current API level. */
    public val mediaReadGranted: Boolean = false,
    /** Permission ids still missing, in the order they should be requested. */
    public val missingPermissions: List<String> = emptyList(),
    /** User-picked folders (SAF trees) that are still valid. */
    public val grants: List<SafGrant> = emptyList(),
    /** True when a grant was revoked outside the app since it was recorded. */
    public val revokedGrantUris: List<String> = emptyList(),
) {
    public val canListMedia: Boolean get() = mediaReadGranted

    /**
     * On API 33+ the platform no longer exposes arbitrary documents through
     * MediaStore, so the Files tab is driven by the folders the user picked.
     */
    public val canListDocuments: Boolean get() = mediaReadGranted || grants.isNotEmpty()

    public val isFullyBlocked: Boolean get() = !mediaReadGranted && grants.isEmpty()
}

/**
 * What the platform said when Morsecode asked it to delete a file.
 *
 * Deletion is not one answer on Android. Up to API 28 a resolver delete is the whole
 * story; on API 29 the platform answers with a consent intent **and expects the app to
 * delete again afterwards**, because the grant is a permission; from API 30 it asks the
 * user and performs the deletion itself. Reporting any of those as "deleted" before it
 * has happened would be a claim the user cannot check, so the three stay distinguishable
 * all the way to the screen.
 */
public sealed interface DeleteOutcome {
    /** The row is gone. */
    public data object Deleted : DeleteOutcome

    /**
     * The platform will not delete it without asking the user first: launch [sender],
     * and when [deleteAfterGrant] is set, perform the deletion the grant allowed with
     * [MediaRepository.deleteAfterConsent] before reporting anything.
     */
    public data class Consent(
        public val sender: IntentSender,
        public val deleteAfterGrant: Boolean,
    ) : DeleteOutcome

    /** Refused, or nothing was deleted, with no way to ask. */
    public data object Refused : DeleteOutcome
}

/**
 * Read access to the device's files.
 *
 * Everything here is real device data — MediaStore, the PackageManager and SAF
 * document trees. There is no sample content anywhere in the app: an empty list
 * means the device (or the permission state) has nothing to show, and the UI
 * says which of the two it is.
 */
public interface MediaRepository {

    /** Current permission and grant state; re-emitted when grants change. */
    public fun observeAccess(): Flow<StorageAccess>

    public fun observeImages(): Flow<List<MediaItem>>

    public fun observeVideos(): Flow<List<MediaItem>>

    public fun observeAudio(): Flow<List<MediaItem>>

    /** Launchable user apps, as shareable APK items. */
    public fun observeApps(): Flow<List<MediaItem>>

    /**
     * Documents, archives and other non-media files.
     *
     * API 32 and below: MediaStore's non-media rows. API 33 and above: the
     * contents of the SAF folders the user granted, because the platform no
     * longer exposes arbitrary shared documents to MediaStore readers.
     */
    public fun observeDocuments(): Flow<List<MediaItem>>

    /** Folder rows shown at the top of the Files tab (SAF trees + buckets). */
    public fun observeFolders(): Flow<List<MediaItem>>

    /** Children of one SAF folder, for the folder browser screen. */
    public suspend fun childrenOf(treeUri: String): List<MediaItem>

    /**
     * The folder itself, or null when the platform will not resolve it.
     *
     * The folder browser uses this for a level's real name and to tell an empty
     * folder from a path that does not exist or is no longer granted — an empty
     * child list alone cannot make that distinction.
     */
    public suspend fun folderAt(uriString: String): MediaItem?

    /** Resolves a single item by its opaque id, for the viewer and players. */
    public suspend fun itemById(id: String): MediaItem?

    /** Re-runs every query; called after a permission grant or a transfer. */
    public suspend fun refresh()

    /** Records a newly picked SAF tree and takes its persistable permission. */
    public suspend fun addFolder(treeUri: String): SafGrant?

    /** Releases a previously granted folder. */
    public suspend fun removeFolder(grantId: Long)

    /** Drops recorded grants the platform no longer honours. */
    public suspend fun pruneRevokedGrants(): Int

    /**
     * Asks the platform to delete the file at [uriString].
     *
     * Returns what the platform said, following whichever consent flow this API level
     * wants. A uri that cannot be parsed, a row that is not there and a deletion the
     * platform refuses are all [DeleteOutcome.Refused]: nothing was deleted, and the
     * caller must not report otherwise.
     */
    public suspend fun delete(uriString: String): DeleteOutcome

    /**
     * Performs the deletion that a consent grant allowed.
     *
     * On API 29 the platform's own dialog grants permission and leaves the row in
     * place, so the deletion still has to be performed and its result reported. From
     * API 30 the platform deletes the row itself and this is not called.
     */
    public suspend fun deleteAfterConsent(uriString: String): DeleteOutcome
}
