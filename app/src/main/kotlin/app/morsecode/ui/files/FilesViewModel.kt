package app.morsecode.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MorseFormatters
import app.morsecode.core.model.SortDirection
import app.morsecode.core.model.SortKey
import app.morsecode.core.model.SortOrder
import app.morsecode.core.model.applySortOrder
import app.morsecode.core.storage.MediaRepository
import app.morsecode.core.storage.StorageAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The five Files categories, in the reference's tab order. */
public enum class FilesTab(public val id: String) {
    PHOTOS("photos"),
    VIDEOS("videos"),
    MUSIC("music"),
    APPS("apps"),
    FILES("files"),
    ;

    public companion object {
        public val ordered: List<FilesTab> = entries.toList()
        public fun fromId(id: String?): FilesTab = entries.firstOrNull { it.id == id } ?: PHOTOS
    }
}

/** One day-grouped section header plus its items. */
public data class MediaSection(
    val label: String,
    val items: List<MediaItem>,
)

public data class FilesUiState(
    val tab: FilesTab = FilesTab.PHOTOS,
    val sections: List<MediaSection> = emptyList(),
    val folders: List<MediaItem> = emptyList(),
    val access: StorageAccess = StorageAccess(),
    val sortOrder: SortOrder = SortOrder(),
    val selection: Set<String> = emptySet(),
    val selecting: Boolean = false,
    val sortSheetVisible: Boolean = false,
    val selectedBytes: Long = 0L,
    val itemCount: Int = 0,
    /** One-shot user feedback, cleared after the UI has shown it. */
    val message: FilesMessage? = null,
)

/** Feedback the Files screen shows as a toast. */
public sealed interface FilesMessage {
    public data class FolderAdded(val displayName: String) : FilesMessage
    public data object GrantFailed : FilesMessage
    public data object PermissionDeclined : FilesMessage
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
public class FilesViewModel @Inject constructor(
    private val media: MediaRepository,
    private val formatters: MorseFormatters,
) : ViewModel() {

    private val tab = MutableStateFlow(FilesTab.PHOTOS)
    private val sortOrder = MutableStateFlow(SortOrder())
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val selecting = MutableStateFlow(false)
    private val sortSheet = MutableStateFlow(false)
    private val message = MutableStateFlow<FilesMessage?>(null)

    private val items: Flow<List<MediaItem>> = tab.flatMapLatest { current ->
        when (current) {
            FilesTab.PHOTOS -> media.observeImages()
            FilesTab.VIDEOS -> media.observeVideos()
            FilesTab.MUSIC -> media.observeAudio()
            FilesTab.APPS -> media.observeApps()
            FilesTab.FILES -> media.observeDocuments()
        }
    }

    public val state: StateFlow<FilesUiState> = combine(
        items,
        media.observeFolders(),
        media.observeAccess(),
        tab,
        sortOrder,
    ) { list, folders, access, currentTab, order ->
        Quintet(list, folders, access, currentTab, order)
    }.combine(combine(selection, selecting, sortSheet, message) { sel, isSelecting, sheet, msg ->
        SelectionState(sel, isSelecting, sheet, msg)
    }) { data, ui ->
        val sorted = data.items.applySortOrder(data.order) { it }
        val all = if (data.tab == FilesTab.FILES) data.folders.applySortOrder(data.order) { it } + sorted else sorted
        val selectedItems = all.filter { it.id in ui.selection }
        FilesUiState(
            tab = data.tab,
            sections = groupByDay(all, data.tab),
            folders = if (data.tab == FilesTab.FILES) data.folders.applySortOrder(data.order) { it } else emptyList(),
            access = data.access,
            sortOrder = data.order,
            selection = ui.selection,
            selecting = ui.selecting,
            sortSheetVisible = ui.sortSheet,
            selectedBytes = selectedItems.sumOf { it.sizeBytes },
            itemCount = all.size,
            message = ui.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = FilesUiState(),
    )

    /** Photos and videos are grouped by day; the other tabs are one section. */
    private fun groupByDay(items: List<MediaItem>, tab: FilesTab): List<MediaSection> {
        if (items.isEmpty()) return emptyList()
        if (tab != FilesTab.PHOTOS && tab != FilesTab.VIDEOS) {
            return listOf(MediaSection(label = "", items = items))
        }
        val now = System.currentTimeMillis()
        return items
            .groupBy { formatters.dayLabel(it.dateModifiedEpochMillis, now) }
            .map { (label, group) -> MediaSection(label = label, items = group) }
    }

    public fun selectTab(id: String) {
        val next = FilesTab.fromId(id)
        if (next == tab.value) return
        tab.value = next
        selection.value = emptySet()
        selecting.value = false
    }

    public fun toggleSelecting() {
        selecting.value = !selecting.value
        if (!selecting.value) selection.value = emptySet()
    }

    public fun toggleItem(id: String) {
        selection.value = if (id in selection.value) selection.value - id else selection.value + id
        if (selection.value.isNotEmpty()) selecting.value = true
    }

    public fun selectGroup(ids: List<String>) {
        val allSelected = ids.all { it in selection.value }
        selection.value = if (allSelected) selection.value - ids.toSet() else selection.value + ids
        selecting.value = true
    }

    public fun clearSelection() {
        selection.value = emptySet()
        selecting.value = false
    }

    public fun openSortSheet(visible: Boolean) {
        sortSheet.value = visible
    }

    public fun setSortKey(key: SortKey) {
        sortOrder.value = if (sortOrder.value.key == key) {
            sortOrder.value.toggleDirection()
        } else {
            sortOrder.value.copy(key = key)
        }
    }

    public fun setSortDirection(direction: SortDirection) {
        sortOrder.value = sortOrder.value.copy(direction = direction)
    }

    /** Items the user has selected, resolved from the current list. */
    public suspend fun selectedItems(): List<MediaItem> {
        val ids = selection.value
        if (ids.isEmpty()) return emptyList()
        val current = state.value.sections.flatMap { it.items } + state.value.folders
        return current.filter { it.id in ids }
    }

    public fun addFolder(treeUri: String) {
        viewModelScope.launch {
            val grant = media.addFolder(treeUri)
            message.value = if (grant == null) FilesMessage.GrantFailed else FilesMessage.FolderAdded(grant.displayName)
        }
    }

    public fun removeFolder(grantId: Long) {
        viewModelScope.launch { media.removeFolder(grantId) }
    }

    /** Called after the user answers the contextual media permission request. */
    public fun onPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            media.refresh()
            if (!granted) message.value = FilesMessage.PermissionDeclined
        }
    }

    public fun refresh() {
        viewModelScope.launch { media.refresh() }
    }

    public fun consumeMessage() {
        message.value = null
    }

    public fun formatBytes(bytes: Long): String = formatters.bytes(bytes)

    public fun formatDuration(millis: Long): String = formatters.durationMillis(millis)

    public fun formatSelectedSummary(count: Int, bytes: Long): String =
        formatters.count(count.toLong()) + " · " + formatters.bytes(bytes)

    private data class Quintet(
        val items: List<MediaItem>,
        val folders: List<MediaItem>,
        val access: StorageAccess,
        val tab: FilesTab,
        val order: SortOrder,
    )

    private data class SelectionState(
        val selection: Set<String>,
        val selecting: Boolean,
        val sortSheet: Boolean,
        val message: FilesMessage?,
    )

    public companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
