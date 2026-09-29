package app.morsecode.ui.folder

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MorseFormatters
import app.morsecode.core.model.SortDirection
import app.morsecode.core.model.SortKey
import app.morsecode.core.model.SortOrder
import app.morsecode.core.model.applySortOrder
import app.morsecode.core.storage.MediaRepository
import app.morsecode.core.storage.saf.FolderLevel
import app.morsecode.core.storage.saf.PathResolution
import app.morsecode.core.storage.saf.SafPaths
import app.morsecode.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-shot feedback the folder browser shows as a toast. */
public sealed interface FolderMessage {
    /** The grant no longer resolves, so this level cannot be read at all. */
    public data object Unavailable : FolderMessage

    /** A typed path is well formed but sits outside the grant. */
    public data object PathOutsideGrant : FolderMessage

    /** A typed path reads as a path, and nothing is there. */
    public data object PathNotFound : FolderMessage
}

public data class FolderUiState(
    /** The SAF uri this back-stack entry shows. */
    val uri: String = "",
    /** Breadcrumb levels of [uri], volume first. */
    val levels: List<FolderLevel> = emptyList(),
    /** The folder's real name, empty until the platform answers. */
    val title: String = "",
    val items: List<MediaItem> = emptyList(),
    /** True until the first read of this level has returned. */
    val loading: Boolean = true,
    /** False when neither the folder nor any child could be read. */
    val accessible: Boolean = true,
    val selection: Set<String> = emptySet(),
    val selecting: Boolean = false,
    val selectedBytes: Long = 0L,
    val pathEditorVisible: Boolean = false,
    val pathDraft: String = "",
    val message: FolderMessage? = null,
)

/**
 * One level of the internal folder browser.
 *
 * Going down pushes a destination rather than mutating this state, so the system
 * back gesture is the way up and every level keeps its own scroll position and
 * selection — the breadcrumb taps into the same back stack. What this view model
 * owns is the level it was created for: reading it, naming it, selecting inside
 * it, and turning a typed path into a uri that is provably inside the grant.
 */
@HiltViewModel
public class FolderViewModel @Inject constructor(
    private val media: MediaRepository,
    private val formatters: MorseFormatters,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The level being browsed, as the platform granted it. */
    public val uri: String = Routes.decodeFolderArg(savedStateHandle.get<String>(Routes.FOLDER_ARG))

    private val folder = MutableStateFlow<MediaItem?>(null)
    private val children = MutableStateFlow<List<MediaItem>?>(null)
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val selecting = MutableStateFlow(false)
    private val editor = MutableStateFlow(false)
    private val draft = MutableStateFlow("")
    private val message = MutableStateFlow<FolderMessage?>(null)

    public val state: StateFlow<FolderUiState> = combine(
        folder,
        children,
        selection,
        selecting,
    ) { self, kids, selected, isSelecting ->
        Level(self, kids, selected, isSelecting)
    }.combine(combine(editor, draft, message) { open, text, note ->
        Editing(open, text, note)
    }) { level, editing ->
        val items = level.children
        FolderUiState(
            uri = uri,
            levels = SafPaths.levelsOf(SafPaths.documentIdOf(uri).orEmpty()),
            title = level.folder?.displayName.orEmpty(),
            items = items.orEmpty(),
            loading = items == null,
            // An empty child list is only an empty folder if the folder itself
            // still resolves; otherwise the grant is gone.
            accessible = level.folder != null || !items.isNullOrEmpty(),
            selection = level.selection,
            selecting = level.isSelecting,
            selectedBytes = items.orEmpty()
                .filter { it.id in level.selection }
                .sumOf { it.sizeBytes },
            pathEditorVisible = editing.open,
            pathDraft = editing.text,
            message = editing.note,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = FolderUiState(
            uri = uri,
            levels = SafPaths.levelsOf(SafPaths.documentIdOf(uri).orEmpty()),
        ),
    )

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val self = media.folderAt(uri)
            val kids = media.childrenOf(uri)
            folder.value = self
            children.value = kids.applySortOrder(BROWSE_ORDER) { it }
            if (self == null && kids.isEmpty()) message.value = FolderMessage.Unavailable
        }
    }

    /** The uri of one breadcrumb level, or null if the index is not ours. */
    public fun levelUri(index: Int): String? {
        val levels = SafPaths.levelsOf(SafPaths.documentIdOf(uri).orEmpty())
        val level = levels.getOrNull(index) ?: return null
        return SafPaths.uriFor(uri, level.documentId)
    }

    public fun toggleSelecting() {
        selecting.value = !selecting.value
        if (!selecting.value) selection.value = emptySet()
    }

    public fun toggleItem(id: String) {
        selection.value = if (id in selection.value) selection.value - id else selection.value + id
        if (selection.value.isNotEmpty()) selecting.value = true
    }

    public fun selectAll() {
        val ids = children.value.orEmpty().map { it.id }
        selection.value = ids.toSet()
        selecting.value = true
    }

    public fun clearSelection() {
        selection.value = emptySet()
        selecting.value = false
    }

    /** The selected items, resolved from what this level actually contains. */
    public suspend fun selectedItems(): List<MediaItem> {
        val ids = selection.value
        if (ids.isEmpty()) return emptyList()
        return children.value.orEmpty().filter { it.id in ids }
    }

    public fun formatBytes(bytes: Long): String = formatters.bytes(bytes)

    /** Row metadata: real size and modification date, or null when unknown. */
    public fun subtitleFor(item: MediaItem): String? {
        val parts = buildList {
            if (!item.isFolder && item.sizeBytes > 0L) add(formatters.bytes(item.sizeBytes))
            if (item.dateModifiedEpochMillis > 0L) add(formatters.date(item.dateModifiedEpochMillis))
        }
        return parts.joinToString(" · ").ifEmpty { null }
    }

    public fun openPathEditor(visible: Boolean) {
        editor.value = visible
        if (visible && draft.value.isEmpty()) {
            val levels = SafPaths.levelsOf(SafPaths.documentIdOf(uri).orEmpty())
            draft.value = levels.lastOrNull()?.name.orEmpty()
        }
    }

    public fun setPathDraft(value: String) {
        draft.value = value
    }

    /**
     * Turns the typed path into a uri to open, or null after saying why not.
     *
     * [rootLabel] is the localised name of the volume level, supplied by the
     * screen because only the UI knows it; a pasted copy of the breadcrumb starts
     * with it. The result is always inside the grant, and it is checked against
     * the platform before the browser claims a folder exists.
     */
    public suspend fun submitPath(rootLabel: String): String? {
        val resolution = SafPaths.resolve(draft.value, uri, rootLabel)
        if (resolution !is PathResolution.Inside) {
            message.value = if (resolution == PathResolution.Outside) {
                FolderMessage.PathOutsideGrant
            } else {
                FolderMessage.PathNotFound
            }
            return null
        }
        val target = SafPaths.uriFor(uri, resolution.documentId)
        if (target == null || media.folderAt(target) == null) {
            message.value = FolderMessage.PathNotFound
            return null
        }
        editor.value = false
        return target
    }

    public fun consumeMessage() {
        message.value = null
    }

    private data class Level(
        val folder: MediaItem?,
        val children: List<MediaItem>?,
        val selection: Set<String>,
        val isSelecting: Boolean,
    )

    private data class Editing(
        val open: Boolean,
        val text: String,
        val note: FolderMessage?,
    )

    public companion object {
        /** Folders first, then names — the order a file browser is read in. */
        private val BROWSE_ORDER = SortOrder(key = SortKey.NAME, direction = SortDirection.ASC)
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
