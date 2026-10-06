package app.morsecode.core.storage.transfer

import java.io.FileInputStream
import java.io.FileNotFoundException

/**
 * Production read/delete adapter over the app-private partial store.
 *
 * Recovery never calls [AppPrivatePartialStore.open], because that API creates a
 * missing file. A restoration read must be observational: opening a missing
 * staging identity must not manufacture an empty replacement which could be
 * mistaken for persisted data. The filename is the store's injective,
 * versioned encoding of the exact [PartialIdentity], never a display name.
 */
internal class AppPrivateSafStaging(
    private val store: AppPrivatePartialStore,
) : SafStaging {

    override fun length(identity: PartialIdentity): Long? = try {
        store.fileFor(identity).takeIf { it.isFile }?.length()
    } catch (_: Exception) {
        null
    }

    override fun open(identity: PartialIdentity): SafOpen = try {
        val file = store.fileFor(identity)
        if (!file.isFile) {
            SafOpen.Refused(TransferStorageError.NotFound("partial"))
        } else {
            SafOpen.Opened(SafReadHandle(FileInputStream(file)))
        }
    } catch (_: FileNotFoundException) {
        SafOpen.Refused(TransferStorageError.NotFound("partial"))
    } catch (_: SecurityException) {
        SafOpen.Refused(TransferStorageError.Io("open"))
    } catch (_: Exception) {
        SafOpen.Refused(TransferStorageError.Io("open"))
    }

    override fun delete(identity: PartialIdentity): Boolean = try {
        store.delete(identity)
    } catch (_: Exception) {
        false
    }
}
