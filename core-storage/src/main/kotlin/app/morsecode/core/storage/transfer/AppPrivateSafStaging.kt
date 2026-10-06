package app.morsecode.core.storage.transfer

import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException

/** Production SafStaging adapter over the same identity-derived private partial store. */
internal class AppPrivateSafStaging(
    private val partials: AppPrivatePartialStore,
) : SafStaging {

    override fun length(identity: PartialIdentity): Long? = try {
        partials.lengthOrNull(identity)
    } catch (_: Exception) {
        null
    }

    override fun open(identity: PartialIdentity): SafOpen {
        val file = try {
            partials.fileFor(identity)
        } catch (_: IllegalArgumentException) {
            return SafOpen.Refused(TransferStorageError.StateConflict("staging_identity_invalid"))
        }
        if (!file.isFile) return SafOpen.Refused(TransferStorageError.NotFound("partial"))
        return try {
            SafOpen.Opened(SafReadHandle(FileInputStream(file)))
        } catch (_: FileNotFoundException) {
            SafOpen.Refused(TransferStorageError.NotFound("partial"))
        } catch (_: SecurityException) {
            SafOpen.Refused(TransferStorageError.PermissionRevoked("read"))
        } catch (_: IOException) {
            SafOpen.Refused(TransferStorageError.Io("open"))
        } catch (_: Exception) {
            SafOpen.Refused(TransferStorageError.Io("open"))
        }
    }

    override fun delete(identity: PartialIdentity): Boolean = try {
        partials.delete(identity)
    } catch (_: Exception) {
        false
    }
}
