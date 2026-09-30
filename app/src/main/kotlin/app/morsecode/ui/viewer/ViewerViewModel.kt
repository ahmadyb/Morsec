package app.morsecode.ui.viewer

import android.content.IntentSender
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MorseFormatters
import app.morsecode.core.model.applySortOrder
import app.morsecode.core.storage.DeleteOutcome
import app.morsecode.core.storage.MediaRepository
import app.morsecode.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

public data class ViewerUiState(
    /** Every image the viewer can swipe through, in the order Files was showing them. */
    val items: List<MediaItem> = emptyList(),
    /** The image being shown. */
    val index: Int = 0,
    /** True until the image query has answered. */
    val loading: Boolean = true,
    val infoVisible: Boolean = false,
    val deleteConfirmVisible: Boolean = false,
    /** One-shot user feedback, cleared once the viewer has shown it. */
    val message: ViewerMessage? = null,
    /** The platform's own deletion question, for the viewer to put on screen. */
    val consent: ViewerConsent? = null,
) {
    /** The image being shown, or null while the list is empty. */
    public val current: MediaItem? get() = items.getOrNull(index)
    public val count: Int get() = items.size
    public val isEmpty: Boolean get() = !loading && items.isEmpty()
}

/** What the viewer tells the user after a deletion, once, and then clears. */
public sealed interface ViewerMessage {
    /** The platform deleted the file. */
    public data object Deleted : ViewerMessage

    /** The platform refused, or deleted nothing. */
    public data object DeleteFailed : ViewerMessage

    /** The user declined the platform's own confirmation. */
    public data object DeleteCancelled : ViewerMessage
}

/**
 * A consent question from the platform, which the viewer launches and answers back.
 *
 * [deleteAfterGrant] is what makes API 29 different: there the grant is a permission
 * and the row is still on the device until the app deletes it again.
 */
public data class ViewerConsent(
    public val sender: IntentSender,
    public val deleteAfterGrant: Boolean,
    public val uriString: String,
)

/**
 * The image viewer (master prompt §4.5).
 *
 * The viewer shows the same list Files was showing, in the same order: the route
 * carries the tapped item's id *and* the sort token the grid was using, so "3 of 15"
 * here is the same 3 of 15 the user counted on screen rather than a second list in a
 * different order.
 *
 * Which image is showing is state, not a page index owned by the pager, so it
 * survives recomposition and is written to [SavedStateHandle] as it moves — a viewer
 * restored after process death comes back to the photograph that was on screen.
 */
@HiltViewModel
public class ViewerViewModel @Inject constructor(
    private val media: MediaRepository,
    private val formatters: MorseFormatters,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val requestedId: String = Routes.decodeViewerArg(savedStateHandle.get<String>(Routes.VIEWER_ARG))
    private val sort = Routes.parseSortToken(savedStateHandle.get<String>(Routes.VIEWER_SORT_ARG))

    private val items = MutableStateFlow<List<MediaItem>?>(null)
    private val index = MutableStateFlow(0)
    private val info = MutableStateFlow(false)
    private val confirmDelete = MutableStateFlow(false)
    private val message = MutableStateFlow<ViewerMessage?>(null)
    private val consent = MutableStateFlow<ViewerConsent?>(null)

    /** False until the first list has been matched against the id the viewer was opened with. */
    private var resolved = false

    public val state: StateFlow<ViewerUiState> = combine(
        items,
        index,
        info,
        confirmDelete,
        message,
    ) { list, shown, isInfo, isConfirm, notice ->
        ViewerUiState(
            items = list.orEmpty(),
            index = shown,
            loading = list == null,
            infoVisible = isInfo,
            deleteConfirmVisible = isConfirm,
            message = notice,
        )
    }.combine(consent) { shown, pending ->
        shown.copy(consent = pending)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = ViewerUiState(),
    )

    init {
        viewModelScope.launch {
            media.observeImages()
                .map { list -> list.applySortOrder(sort) { item -> item } }
                .collect { list -> publish(list) }
        }
    }

    private fun publish(list: List<MediaItem>) {
        items.value = list
        val restored = savedStateHandle.get<Int>(SAVED_INDEX)
        index.value = when {
            // A restored viewer comes back to the photograph it was showing.
            !resolved && restored != null && restored in list.indices -> restored
            // Otherwise to the one that was tapped, or to the first if it has gone.
            !resolved -> list.indexOfFirst { it.id == requestedId }.takeIf { it >= 0 } ?: 0
            // After that the list can change under the viewer (a delete, a new
            // screenshot): keep the position, but never past the end.
            else -> index.value.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        }
        resolved = true
    }

    /** The deck settled on a page: this is the photograph being shown from now on. */
    public fun showPage(page: Int) {
        val count = items.value?.size ?: 0
        if (count == 0) return
        val next = page.coerceIn(0, count - 1)
        if (next == index.value) return
        index.value = next
        savedStateHandle[SAVED_INDEX] = next
    }

    /**
     * Asks the platform to delete the photograph being shown.
     *
     * The answer is whatever the platform said — deleted, a consent question, or
     * refused — and nothing is reported to the user before it is known.
     */
    public fun deleteCurrent() {
        val uriString = state.value.current?.uriString.orEmpty()
        confirmDelete.value = false
        viewModelScope.launch {
            when (val outcome = media.delete(uriString)) {
                DeleteOutcome.Deleted -> message.value = ViewerMessage.Deleted
                is DeleteOutcome.Consent -> consent.value =
                    ViewerConsent(outcome.sender, outcome.deleteAfterGrant, uriString)

                DeleteOutcome.Refused -> message.value = ViewerMessage.DeleteFailed
            }
        }
    }

    /** The platform's own confirmation came back; [granted] is whether the user agreed. */
    public fun consentResult(granted: Boolean) {
        val pending = consent.value ?: return
        consent.value = null
        when {
            !granted -> message.value = ViewerMessage.DeleteCancelled

            // From API 30 the platform deleted the row itself while asking.
            !pending.deleteAfterGrant -> message.value = ViewerMessage.Deleted

            // On API 29 the grant was a permission: the deletion happens now, and only
            // its own answer is reported.
            else -> viewModelScope.launch {
                when (val outcome = media.deleteAfterConsent(pending.uriString)) {
                    DeleteOutcome.Deleted -> message.value = ViewerMessage.Deleted
                    is DeleteOutcome.Consent -> consent.value =
                        ViewerConsent(outcome.sender, outcome.deleteAfterGrant, pending.uriString)

                    DeleteOutcome.Refused -> message.value = ViewerMessage.DeleteFailed
                }
            }
        }
    }

    /** The viewer has shown [ViewerUiState.message]. */
    public fun consumeMessage() {
        message.value = null
    }

    public fun showInfo(visible: Boolean) {
        info.value = visible
    }

    public fun confirmDelete(visible: Boolean) {
        confirmDelete.value = visible
    }

    public fun formatBytes(bytes: Long): String = formatters.bytes(bytes)

    /** When the file last changed, to the minute: a date alone hides a same-day edit. */
    public fun formatModified(millis: Long): String = formatters.dateTime(millis)

    /** "4032 × 3024 · 2.4 MB", leaving out whatever the platform did not report. */
    public fun metadataFor(item: MediaItem): String = buildList {
        dimensionsFor(item)?.let { add(it) }
        if (item.sizeBytes > 0L) add(formatters.bytes(item.sizeBytes))
    }.joinToString(META_SEPARATOR)

    /** The frame size, or null when the platform never reported one. */
    public fun dimensionsFor(item: MediaItem): String? =
        if (item.widthPixels > 0 && item.heightPixels > 0) {
            "${item.widthPixels} × ${item.heightPixels}"
        } else {
            null
        }

    public companion object {
        /** Saved-state key for the photograph being shown. */
        public const val SAVED_INDEX: String = "app.morsecode.viewer.index"

        /**
         * The reference's metadata separator: `${n} of 15 · 4032 × 3024 · 2.4 MB`.
         * The header joins the position with this line using the same character.
         */
        public const val META_SEPARATOR: String = " · "

        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
