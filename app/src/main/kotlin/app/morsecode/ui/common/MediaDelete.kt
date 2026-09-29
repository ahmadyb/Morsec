package app.morsecode.ui.common

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the platform said when Morsecode asked it to delete a file. */
public sealed interface DeleteOutcome {
    /** The row is gone. */
    public data object Deleted : DeleteOutcome

    /**
     * The platform will delete it once the user agrees. Launch [sender] and treat
     * `RESULT_OK` as deleted — until then nothing has been deleted.
     */
    public data class Consent(public val sender: IntentSender) : DeleteOutcome

    /** Refused, with no way to ask. */
    public data object Refused : DeleteOutcome
}

/**
 * Deleting a file the way Android actually asks for it.
 *
 * Up to API 28 a resolver delete is the whole story. On API 29 the platform can
 * answer with a recovery intent instead of deleting, and from API 30 an app that
 * did not contribute a media row has to ask the user through
 * `MediaStore.createDeleteRequest`. Both come back as an [IntentSender] for the
 * caller to launch, so this never reports a deletion the platform only offered to
 * ask about — §4.5 wants confirmation and recoverable behaviour where the storage
 * APIs allow it, and a toast claiming success would be neither.
 */
public object MediaDelete {

    public suspend fun request(context: Context, uri: Uri): DeleteOutcome = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val attempt = runCatching { resolver.delete(uri, null, null) }
        if (attempt.isSuccess) {
            return@withContext if (attempt.getOrDefault(0) > 0) DeleteOutcome.Deleted else DeleteOutcome.Refused
        }

        val cause = attempt.exceptionOrNull()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29 carries the consent flow inside the exception it throws.
            @Suppress("DEPRECATION")
            val sender = (cause as? android.app.RecoverableSecurityException)?.action?.intentSender
            if (sender != null) return@withContext DeleteOutcome.Consent(sender)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sender = runCatching {
                MediaStore.createDeleteRequest(resolver, listOf(uri)).intentSender
            }.getOrNull()
            if (sender != null) return@withContext DeleteOutcome.Consent(sender)
        }
        DeleteOutcome.Refused
    }
}
