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
     * The platform will not delete it without asking the user first. Launch
     * [sender] and treat its result as an answer, not as a deletion: when
     * [deleteAfterGrant] is set the grant is a permission and the delete still has
     * to be performed, with [MediaDelete.completeAfterConsent].
     */
    public data class Consent(
        public val sender: IntentSender,
        public val deleteAfterGrant: Boolean,
    ) : DeleteOutcome

    /** Refused, with no way to ask. */
    public data object Refused : DeleteOutcome
}

/**
 * Deleting a file the way Android actually asks for it.
 *
 * Up to API 28 a resolver delete is the whole story. On API 29 the platform answers
 * with a `RecoverableSecurityException` carrying a consent intent, and — this is the
 * part that is easy to get wrong — the grant it returns is a *permission*: the row
 * is still there and the app has to delete it again itself. From API 30,
 * `MediaStore.createDeleteRequest` asks the same question and the system performs the
 * deletion when the user agrees.
 *
 * Both come back as an [IntentSender] for the caller to launch, so this never reports
 * a deletion the platform only offered to ask about, and never reports the API 29
 * grant as one either: a toast claiming success would be neither. §4.5 wants
 * confirmation and recoverable behaviour where the storage APIs allow it.
 */
public object MediaDelete {

    /** Asks the platform to delete [uri], following whatever consent flow it wants. */
    public suspend fun request(context: Context, uri: Uri): DeleteOutcome = withContext(Dispatchers.IO) {
        val attempt = attempt(context, uri)
        if (attempt.isSuccess) return@withContext outcomeOf(attempt)

        val cause = attempt.exceptionOrNull()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29 carries the consent flow inside the exception it throws.
            @Suppress("DEPRECATION")
            val sender = (cause as? android.app.RecoverableSecurityException)
                ?.userAction
                ?.actionIntent
                ?.intentSender
            if (sender != null) {
                return@withContext DeleteOutcome.Consent(sender, deleteAfterGrant = true)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30 and above delete the row themselves once the user agrees.
            val sender = runCatching {
                MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
            }.getOrNull()
            if (sender != null) {
                return@withContext DeleteOutcome.Consent(sender, deleteAfterGrant = false)
            }
        }
        DeleteOutcome.Refused
    }

    /**
     * Performs the deletion the user just consented to, on API 29 where the consent
     * was a permission rather than a deletion.
     */
    public suspend fun completeAfterConsent(context: Context, uri: Uri): DeleteOutcome =
        withContext(Dispatchers.IO) { outcomeOf(attempt(context, uri)) }

    private fun attempt(context: Context, uri: Uri): Result<Int> = runCatching {
        context.contentResolver.delete(uri, null, null)
    }

    /** Zero rows changed is a refusal: nothing was deleted, and the app must not say otherwise. */
    private fun outcomeOf(attempt: Result<Int>): DeleteOutcome =
        if (attempt.getOrDefault(0) > 0) DeleteOutcome.Deleted else DeleteOutcome.Refused
}
