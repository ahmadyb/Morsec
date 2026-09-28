package app.morsecode.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsecode.core.data.repository.HistoryRepository
import app.morsecode.core.model.HistoryEntry
import app.morsecode.core.model.MorseFormatters
import app.morsecode.core.model.SessionDirection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One day group in the History list. */
public data class HistorySection(val label: String, val entries: List<HistoryEntry>)

public data class HistoryUiState(
    val direction: SessionDirection = SessionDirection.INBOUND,
    val sections: List<HistorySection> = emptyList(),
    val searchVisible: Boolean = false,
    val query: String = "",
    val confirmClearVisible: Boolean = false,
    val itemCount: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
public class HistoryViewModel @Inject constructor(
    private val history: HistoryRepository,
    private val formatters: MorseFormatters,
) : ViewModel() {

    private val direction = MutableStateFlow(SessionDirection.INBOUND)
    private val query = MutableStateFlow("")
    private val searchVisible = MutableStateFlow(false)
    private val confirmClear = MutableStateFlow(false)

    private val entries = combine(direction, query) { dir, text -> dir to text }
        .flatMapLatest { (dir, text) ->
            if (text.isBlank()) history.observe(dir) else history.search(dir, text)
        }

    public val state: StateFlow<HistoryUiState> = combine(
        entries,
        direction,
        query,
        searchVisible,
        confirmClear,
    ) { list, dir, text, searching, confirming ->
        val now = System.currentTimeMillis()
        HistoryUiState(
            direction = dir,
            sections = list.groupBy { formatters.dayLabel(it.finishedEpochMillis, now) }
                .map { (label, group) -> HistorySection(label, group) },
            searchVisible = searching,
            query = text,
            confirmClearVisible = confirming,
            itemCount = list.size,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = HistoryUiState(),
    )

    public fun setDirection(id: String) {
        direction.value = if (id == SessionDirection.OUTBOUND.id) SessionDirection.OUTBOUND else SessionDirection.INBOUND
    }

    public fun toggleSearch() {
        val next = !searchVisible.value
        searchVisible.value = next
        if (!next) query.value = ""
    }

    public fun setQuery(value: String) {
        query.value = value
    }

    public fun requestClear() {
        confirmClear.value = true
    }

    public fun dismissClear() {
        confirmClear.value = false
    }

    /** Clears only the visible tab, as the reference does. */
    public fun clearVisibleTab() {
        val current = direction.value
        confirmClear.value = false
        viewModelScope.launch { history.clear(current) }
    }

    public fun remove(entry: HistoryEntry) {
        viewModelScope.launch { history.delete(entry.historyId) }
    }

    public fun formatBytes(bytes: Long): String = formatters.bytes(bytes)

    public fun formatClock(epochMillis: Long): String = formatters.time(epochMillis)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
