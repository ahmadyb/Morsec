package app.morsecode.core.storage.transfer

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.morsecode.core.transfer.identity.RelativeTransferPath

/*
 * A source backed by a MediaStore row.
 *
 * Everything is reached through the ContentResolver, and nothing here asks for a
 * filesystem path: `MediaStore.MediaColumns.DATA` is deprecated, unreadable on
 * scoped storage, and — the reason that matters here — putting an absolute path
 * into a value that callers log and persist. The row's `_id` is the identity.
 *
 * The three ways this source can fail are kept distinct because they lead to
 * three different recoveries:
 *
 *   * the row is gone (deleted media) — restart, nothing to resume;
 *   * the grant is gone (revoked permission) — stop and ask the user;
 *   * the provider failed — retry.
 *
 * A missing size is reported as -1 rather than as zero. Zero is a real length and
 * takes the zero-byte transfer path; "the provider would not say" is a different
 * fact and disables offset validation rather than pretending the file is empty.
 */

/** One MediaStore row, as read through the resolver. */
internal data class MediaStoreRow(
    val id: Long?,
    val displayName: String?,
    val sizeBytes: Long?,
    val mimeType: String?,
    val modifiedEpochMillis: Long?,
)

/**
 * A MediaStore-backed source.
 *
 * [capability] was measured on a real descriptor when this source was created, so
 * [isSeekable] is an observation rather than an assumption about MediaStore.
 */
public class MediaStoreTransferSource internal constructor(
    override val key: TransferSourceKey,
    override val uri: Uri,
    override val displayName: String,
    override val relativePath: RelativeTransferPath,
    override val mimeType: String,
    private val mediaId: Long?,
    private val reportedSizeBytes: Long,
    private val reportedModifiedEpochMillis: Long?,
    private val capability: ProbeResult,
    private val resolver: ContentResolver,
    private val probe: SeekabilityProbe,
) : TransferSource {

    override val isSeekable: Boolean get() = capability.seekability.canSeek

    override val sizeBytes: Long get() = reportedSizeBytes

    override val lastModifiedEpochMillis: Long? get() = reportedModifiedEpochMillis

    override fun fingerprint(): SourceFingerprintResult = try {
        val row = queryRow()
            ?: return SourceFingerprintResult.Unavailable(
                TransferStorageError.NotFound("source"),
            )
        SourceFingerprintResult.Available(
            SourceFingerprint(
                sizeBytes = row.sizeBytes,
                lastModifiedEpochMillis = row.modifiedEpochMillis,
                contentId = row.id?.let { contentIdFor(it) },
            ),
        )
    } catch (e: SecurityException) {
        SourceFingerprintResult.Unavailable(TransferStorageError.PermissionRevoked("read"))
    } catch (e: Exception) {
        SourceFingerprintResult.Unavailable(
            TransferStorageError.ProviderFailure("media_store", e.message),
        )
    }

    override fun openAt(offset: Long): SourceOpenResult =
        when (val opened = ProviderSourceOpener.open(resolver, uri, offset, probe)) {
            is ProviderOpenResult.Opened ->
                SourceOpenResult.Opened(opened.handle, opened.offset)

            is ProviderOpenResult.Failed ->
                SourceOpenResult.Failed(opened.error)
        }

    private fun queryRow(): MediaStoreRow? {
        val cursor = resolver.query(uri, PROJECTION, null, null, null) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return MediaStoreRow(
                id = it.longOrNull(MediaStore.MediaColumns._ID),
                displayName = it.stringOrNull(OpenableColumns.DISPLAY_NAME),
                sizeBytes = it.longOrNull(OpenableColumns.SIZE),
                mimeType = it.stringOrNull(MediaStore.MediaColumns.MIME_TYPE),
                modifiedEpochMillis = it.longOrNull(DATE_MODIFIED)?.times(1_000L),
            )
        }
    }

    internal companion object {
        /** The `_id` is the identity: stable, and not a location. */
        public fun contentIdFor(mediaId: Long): String = "media:$mediaId"

        /** `date_modified` is in seconds; the rest of the app uses milliseconds. */
        internal const val DATE_MODIFIED = "date_modified"

        internal val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            DATE_MODIFIED,
        )
    }
}

/** Builds MediaStore sources. */
public object MediaStoreTransferSources {

    /**
     * The source for [uri].
     *
     * A source is returned even when the row cannot be read, so that
     * [TransferSource.fingerprint] can report *why* on the next recovery pass
     * rather than the source simply not existing.
     */
    public fun create(
        resolver: ContentResolver,
        uri: Uri,
        relativePath: RelativeTransferPath,
        displayName: String? = null,
        mimeType: String? = null,
        probe: SeekabilityProbe = ParcelDescriptorSeekabilityProbe,
    ): MediaStoreTransferSource {
        val row = try {
            readRow(resolver, uri)
        } catch (e: Exception) {
            null
        }
        val capability = probeCapability(resolver, uri, probe)
        val name = displayName
            ?: row?.displayName
            ?: relativePath.lastSegment.takeIf { it.isNotBlank() }
            ?: "file"

        return MediaStoreTransferSource(
            key = TransferSourceKey("media:${uri}"),
            uri = uri,
            displayName = name,
            relativePath = relativePath,
            mimeType = mimeType ?: row?.mimeType ?: guessMimeType(name),
            mediaId = row?.id,
            // -1 means "the provider would not say", not "empty".
            reportedSizeBytes = row?.sizeBytes ?: -1L,
            reportedModifiedEpochMillis = row?.modifiedEpochMillis,
            capability = capability,
            resolver = resolver,
            probe = probe,
        )
    }

    private fun readRow(resolver: ContentResolver, uri: Uri): MediaStoreRow? {
        val cursor = resolver.query(uri, MediaStoreTransferSource.PROJECTION, null, null, null)
            ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return MediaStoreRow(
                id = it.longOrNull(MediaStore.MediaColumns._ID),
                displayName = it.stringOrNull(OpenableColumns.DISPLAY_NAME),
                sizeBytes = it.longOrNull(OpenableColumns.SIZE),
                mimeType = it.stringOrNull(MediaStore.MediaColumns.MIME_TYPE),
                modifiedEpochMillis = it.longOrNull(MediaStoreTransferSource.DATE_MODIFIED)
                    ?.times(1_000L),
            )
        }
    }

    /**
     * Measures seekability on a real descriptor at creation time.
     *
     * One extra open per transfer, which is what makes [TransferSource.isSeekable]
     * an observation. A failure here is not an error: it leaves the capability
     * unknown, and the open path falls back to discarding bytes — which always
     * works, seekable or not.
     */
    private fun probeCapability(
        resolver: ContentResolver,
        uri: Uri,
        probe: SeekabilityProbe,
    ): ProbeResult = try {
        resolver.openFileDescriptor(uri, "r")?.use { probe.probe(it) }
            ?: ProbeResult(Seekability.UNKNOWN, null, ProbeEvidence.DESCRIPTOR_UNUSABLE)
    } catch (e: Exception) {
        ProbeResult(Seekability.UNKNOWN, null, ProbeEvidence.DESCRIPTOR_UNUSABLE)
    }
}
