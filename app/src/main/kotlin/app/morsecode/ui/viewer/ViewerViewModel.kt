package app.morsecode.ui.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MorseFormatters
import app.morsecode.core.model.applySortOrder
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
) {
    /** The image being shown, or null while the list is empty. */
    public val current: MediaItem? get() = items.getOrNull(index)
    public val count: Int get() = items.size
    public val isEmpty: Boolean get() = !loading && items.isEmpty()
}

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

    /** False until the first list has been matched against the id the viewer was opened with. */
    private var resolved = false

    public val state: StateFlow<ViewerUiState> = combine(
        items,
        index,
        info,
        confirmDelete,
    ) { list, shown, isInfo, isConfirm ->
        ViewerUiState(
            items = list.orEmpty(),
            index = shown,
            loading = list == null,
            infoVisible = isInfo,
            deleteConfirmVisible = isConfirm,
        )
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
