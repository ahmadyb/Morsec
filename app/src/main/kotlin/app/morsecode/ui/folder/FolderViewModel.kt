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
import kotlinx.coroutines.Job
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
    /** The SAF uri of the level being shown. */
    val uri: String = "",
    /** Breadcrumb levels of [uri], volume first. */
    val levels: List<FolderLevel> = emptyList(),
    /** The first breadcrumb index the platform can open: the granted level's depth. */
    val navigableFrom: Int = 0,
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
 * The internal folder browser: which level is open, what is really in it, and what
 * is selected there.
 *
 * Levels are walked in place rather than by stacking destinations. A SAF grant
 * reaches *down* from the folder the user picked and never up, so that folder is
 * the browser's ceiling: descending changes the level, a breadcrumb tap changes it
 * to that level, and [goUp] climbs one — which is what the system back gesture does
 * until the ceiling is reached, where leaving the browser is the right answer and
 * [goUp] says so by returning false.
 *
 * Walking in place keeps the whole path in one piece of state, which is what makes
 * the level savable: it is written to [SavedStateHandle] as it moves, so a browser
 * restored after process death comes back to the level being read instead of the
 * one it started at. Selection belongs to the level being shown, so a level change
 * starts from nothing selected.
 */
@HiltViewModel
public class FolderViewModel @Inject constructor(
    private val media: MediaRepository,
    private val formatters: MorseFormatters,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The level the user granted: the ceiling of the browser, and the frame a typed path is read in. */
    private val grantUri: String = Routes.decodeFolderArg(savedStateHandle.get<String>(Routes.FOLDER_ARG))

    /** Where the granted level sits in a breadcrumb, so levels above it can be told apart. */
    private val ceiling: Int = levelsOf(grantUri).size - 1

    /** The level being shown, saved as it moves. */
    private val level = MutableStateFlow(
        savedStateHandle.get<String>(KEY_LEVEL)?.takeIf { it.isNotEmpty() } ?: grantUri,
    )

    private val folder = MutableStateFlow<MediaItem?>(null)
    private val children = MutableStateFlow<List<MediaItem>?>(null)
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val selecting = MutableStateFlow(false)
    private val editor = MutableStateFlow(false)
    private val draft = MutableStateFlow("")
    private val message = MutableStateFlow<FolderMessage?>(null)

    private var readJob: Job? = null

    public val state: StateFlow<FolderUiState> = combine(
        level,
        folder,
        children,
        selection,
        selecting,
    ) { shown, self, kids, selected, isSelecting ->
        Level(shown, self, kids, selected, isSelecting)
    }.combine(combine(editor, draft, message) { open, text, note ->
        Editing(open, text, note)
    }) { shown, editing ->
        val items = shown.children
        FolderUiState(
            uri = shown.uri,
            levels = levelsOf(shown.uri),
            navigableFrom = ceiling,
            title = shown.folder?.displayName.orEmpty(),
            items = items.orEmpty(),
            loading = items == null,
            // An empty child list is only an empty folder if the folder itself
            // still resolves; otherwise the grant is gone.
            accessible = shown.folder != null || !items.isNullOrEmpty(),
            selection = shown.selection,
            selecting = shown.isSelecting,
            selectedBytes = items.orEmpty()
                .filter { it.id in shown.selection }
                .sumOf { it.sizeBytes },
            pathEditorVisible = editing.open,
            pathDraft = editing.text,
            message = editing.note,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = FolderUiState(
            uri = level.value,
            levels = levelsOf(level.value),
            navigableFrom = ceiling,
        ),
    )

    init {
        read(level.value)
    }

    /**
     * Shows [target] — a folder row, a breadcrumb level, or a path that resolved.
     *
     * Tapping the level already open is not a navigation, so it is not one.
     */
    public fun openLevel(target: String) {
        if (target.isEmpty() || target == level.value) return
        read(target)
        level.value = target
        savedStateHandle[KEY_LEVEL] = target
    }

    /** Shows the breadcrumb level at [index], or nothing when the grant cannot reach it. */
    public fun openLevelAt(index: Int) {
        levelUri(index)?.let(::openLevel)
    }

    /**
     * The uri of one breadcrumb level, or null when the platform cannot open it.
     *
     * Levels above the granted folder are shown for orientation but are not
     * targets: the grant does not cover them, so opening one would be a dead end.
     */
    public fun levelUri(index: Int): String? {
        if (index < ceiling) return null
        val target = levelsOf(level.value).getOrNull(index) ?: return null
        return SafPaths.uriFor(level.value, target.documentId)
    }

    /**
     * Climbs one level, which is what the back gesture means inside the browser.
     *
     * @return false at the granted level, where the caller should leave the
     *   browser instead of pretending there is something above it.
     */
    public fun goUp(): Boolean {
        val levels = levelsOf(level.value)
        val parent = levels.getOrNull(levels.size - 2) ?: return false
        if (levels.size - 2 < ceiling) return false
        val target = SafPaths.uriFor(level.value, parent.documentId) ?: return false
        openLevel(target)
        return true
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
            draft.value = levelsOf(level.value).lastOrNull()?.name.orEmpty()
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
     * with it. Paths are read against the grant rather than the level being shown,
     * which is what makes a pasted path land where it says. The result is always
     * inside the grant, and it is checked against the platform before the browser
     * claims a folder exists.
     */
    public suspend fun submitPath(rootLabel: String): String? {
        val resolution = SafPaths.resolve(draft.value, grantUri, rootLabel)
        if (resolution !is PathResolution.Inside) {
            message.value = if (resolution == PathResolution.Outside) {
                FolderMessage.PathOutsideGrant
            } else {
                FolderMessage.PathNotFound
            }
            return null
        }
        val target = SafPaths.uriFor(grantUri, resolution.documentId)
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

    /** Breadcrumb levels of a uri, volume first. */
    private fun levelsOf(uri: String): List<FolderLevel> =
        SafPaths.levelsOf(SafPaths.documentIdOf(uri).orEmpty())

    /** Reads one level, dropping a read that is no longer wanted. */
    private fun read(uri: String) {
        readJob?.cancel()
        // Cleared before the read, not inside it: a level change must never be
        // composed with the rows of the level that was there before it.
        folder.value = null
        children.value = null
        selection.value = emptySet()
        selecting.value = false
        editor.value = false
        draft.value = ""
        message.value = null
        readJob = viewModelScope.launch {
            val self = media.folderAt(uri)
            val kids = media.childrenOf(uri)
            folder.value = self
            children.value = kids.applySortOrder(BROWSE_ORDER) { it }
            if (self == null && kids.isEmpty()) message.value = FolderMessage.Unavailable
        }
    }

    private data class Level(
        val uri: String,
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

        /** Saved-state key for the level being shown. */
        private const val KEY_LEVEL = "app.morsecode.folder.level"
    }
}
