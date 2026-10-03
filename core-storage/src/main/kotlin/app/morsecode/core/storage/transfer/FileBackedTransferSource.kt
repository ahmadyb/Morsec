package app.morsecode.core.storage.transfer

import android.net.Uri
import androidx.core.net.toUri
import app.morsecode.core.transfer.identity.RelativeTransferPath
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/*
 * A source backed by a regular file the app can open directly.
 *
 * Both the app-private staging root and the legacy shared-storage roots on
 * API 23-28 are this class; they differ only in which roots are approved and in
 * whether a runtime permission has to be checked first. Sharing one
 * implementation is deliberate: the containment rules are the part that must not
 * differ between them.
 *
 * Two rules shape it.
 *
 * Resolved location never leaves it. `uri` is a synthetic, deliberately
 * non-resolvable `content://` Uri carrying only the opaque key — not a `file://`
 * Uri, which would put an absolute private path into a value that callers log,
 * persist and put on screen. Opening goes through the `File` this object holds
 * privately, never through the Uri.
 *
 * Containment is resolved through canonical paths. `File(root, "a/../../b")`
 * normalises to something outside the root, and so does a symlink inside the
 * root that points out of it. Comparing canonical paths catches both; comparing
 * strings would catch neither.
 */

/** Guesses a MIME type from a file name; empty when it cannot. */
internal fun guessMimeType(displayName: String): String {
    val dot = displayName.lastIndexOf('.')
    if (dot < 0 || dot == displayName.length - 1) return ""
    return when (displayName.substring(dot + 1).lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "json" -> "application/json"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        else -> ""
    }
}

/**
 * A source over a real file.
 *
 * [permissionGate] is re-checked on every fingerprint and every open, not once at
 * construction: a runtime permission can be revoked while a transfer is queued,
 * and the source must notice rather than fail mid-read.
 */
public class FileBackedTransferSource internal constructor(
    override val key: TransferSourceKey,
    override val uri: Uri,
    override val displayName: String,
    override val relativePath: RelativeTransferPath,
    override val mimeType: String,
    private val file: File,
    private val permissionGate: () -> Boolean,
    private val seekable: Boolean = true,
) : TransferSource {

    override val isSeekable: Boolean get() = seekable

    override val sizeBytes: Long
        get() = if (file.exists()) file.length() else -1L

    override val lastModifiedEpochMillis: Long?
        get() = file.lastModified().takeIf { it > 0L }

    override fun fingerprint(): SourceFingerprintResult {
        if (!permissionGate()) {
            return SourceFingerprintResult.Unavailable(
                TransferStorageError.PermissionRevoked("read"),
            )
        }
        if (!file.exists()) {
            return SourceFingerprintResult.Unavailable(
                TransferStorageError.NotFound("source"),
            )
        }
        return SourceFingerprintResult.Available(
            SourceFingerprint(
                sizeBytes = file.length(),
                lastModifiedEpochMillis = lastModifiedEpochMillis,
                // The opaque key is the strongest identity available here: it is
                // stable across process restarts and does not name a location.
                contentId = key.value,
            ),
        )
    }

    override fun openAt(offset: Long): SourceOpenResult {
        if (offset < 0L) {
            return SourceOpenResult.Failed(
                TransferStorageError.StateConflict("negative_offset"),
            )
        }
        if (!permissionGate()) {
            return SourceOpenResult.Failed(TransferStorageError.PermissionRevoked("read"))
        }
        if (!file.exists()) {
            return SourceOpenResult.Failed(TransferStorageError.NotFound("source"))
        }

        // The file is opened directly rather than through a ParcelFileDescriptor:
        // this is a file the app owns, so a descriptor round trip would add a
        // resource to own and a platform behaviour to depend on for nothing.
        val random: RandomAccessFile = try {
            RandomAccessFile(file, "r")
        } catch (e: FileNotFoundException) {
            return SourceOpenResult.Failed(TransferStorageError.NotFound("source"))
        } catch (e: SecurityException) {
            return SourceOpenResult.Failed(TransferStorageError.PermissionRevoked("read"))
        } catch (e: IOException) {
            return SourceOpenResult.Failed(TransferStorageError.Io("open", e.message))
        }

        val resource = OwnedResource(random)
        return try {
            if (seekable) {
                openSeekable(resource, random.channel, offset)
            } else {
                openByDiscarding(resource, random, offset)
            }
        } catch (e: IOException) {
            resource.close()
            SourceOpenResult.Failed(TransferStorageError.Io("open", e.message))
        }
    }

    private fun openSeekable(
        resource: OwnedResource,
        channel: FileChannel,
        offset: Long,
    ): SourceOpenResult {
        val adopted = resource.adopt(channel)
        return try {
            adopted.position(offset)
            val length = channel.size()
            if (offset > length) {
                resource.close()
                return SourceOpenResult.Failed(
                    TransferStorageError.StateConflict(
                        reason = "offset_past_end",
                        diagnostic = "offset $offset exceeds length $length",
                    ),
                )
            }
            SourceOpenResult.Opened(ChannelSourceHandle(resource, adopted, offset), offset)
        } catch (e: IOException) {
            resource.close()
            SourceOpenResult.Failed(TransferStorageError.Io("seek", e.message))
        }
    }

    private fun openByDiscarding(
        resource: OwnedResource,
        random: RandomAccessFile,
        offset: Long,
    ): SourceOpenResult {
        val stream = resource.adopt(FileInputStream(random.fd))
        return when (val repositioned = NonSeekableRepositioner.reposition(
            stream = SourceStream { buffer, dataOffset, length -> stream.read(buffer, dataOffset, length) },
            targetOffset = offset,
        )) {
            is RepositionResult.Positioned ->
                SourceOpenResult.Opened(StreamSourceHandle(resource, stream, offset), offset)

            is RepositionResult.EndOfFile -> {
                resource.close()
                SourceOpenResult.Failed(
                    TransferStorageError.StateConflict(
                        reason = "source_shorter_than_offset",
                        diagnostic = "ended at ${repositioned.discarded} of $offset bytes",
                    ),
                )
            }

            is RepositionResult.Failed -> {
                resource.close()
                SourceOpenResult.Failed(repositioned.error)
            }
        }
    }
}

/**
 * Resolves a relative transfer path inside an approved root.
 *
 * Returns null for anything that would leave the root: a traversal, an absolute
 * path, or a symlink inside the root that points out of it.
 */
public object ApprovedRoots {

    /**
     * The file [relativePath] names inside [root], or null when it is not inside.
     *
     * Canonical paths are compared, so `a/../../b` and a symlink are both caught.
     */
    public fun resolveInside(root: File, relativePath: RelativeTransferPath): File? {
        if (!RelativeTransferPath.isValid(relativePath.value)) return null
        if (relativePath.value.startsWith("/")) return null
        val candidate = File(root, relativePath.value)
        return if (isInside(root, candidate)) candidate else null
    }

    /** True when [candidate] resolves to [root] or to something beneath it. */
    public fun isInside(root: File, candidate: File): Boolean = try {
        val rootPath = root.canonicalFile.path
        val targetPath = candidate.canonicalFile.path
        targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

/** Authority used by the synthetic, non-resolvable Uri a file source carries. */
private const val PRIVATE_AUTHORITY = "app.morsecode.core.storage.private"

/**
 * Builds file-backed sources inside an approved root.
 *
 * [authority] distinguishes the key namespace, so the same relative path under
 * two different roots produces two different keys.
 */
public class FileBackedTransferSources(
    private val root: File,
    private val authority: String,
    private val permissionGate: () -> Boolean = { true },
) {

    /** Opaque key for a path in this root. */
    public fun keyFor(relativePath: RelativeTransferPath): TransferSourceKey =
        TransferSourceKey("$authority:${relativePath.value}")

    /**
     * The source for [relativePath], or null when it resolves outside the root.
     *
     * A path that escapes is refused here rather than at open time, so a bad path
     * can never reach a descriptor.
     */
    public fun create(
        relativePath: RelativeTransferPath,
        displayName: String? = null,
        mimeType: String? = null,
    ): FileBackedTransferSource? {
        val file = ApprovedRoots.resolveInside(root, relativePath) ?: return null
        val name = displayName?.takeIf { it.isNotBlank() }
            ?: relativePath.lastSegment.takeIf { it.isNotBlank() }
            ?: return null
        val key = keyFor(relativePath)
        return FileBackedTransferSource(
            key = key,
            // Synthetic and deliberately not resolvable: a file:// Uri here would
            // put an absolute private path into a value callers log and persist.
            uri = "content://$PRIVATE_AUTHORITY/${Uri.encode(key.value)}".toUri(),
            displayName = name,
            relativePath = relativePath,
            mimeType = mimeType ?: guessMimeType(name),
            file = file,
            permissionGate = permissionGate,
        )
    }

    /** Whether [relativePath] resolves inside this root. */
    public fun contains(relativePath: RelativeTransferPath): Boolean =
        ApprovedRoots.resolveInside(root, relativePath) != null
}
