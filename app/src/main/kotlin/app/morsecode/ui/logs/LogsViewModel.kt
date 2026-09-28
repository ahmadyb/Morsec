package app.morsecode.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsecode.core.data.repository.DiagnosticsRepository
import app.morsecode.core.model.LogEntry
import app.morsecode.core.model.MorseFormatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

public data class LogsUiState(
    val entries: List<LogEntry> = emptyList(),
    val errorsOnly: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
public class LogsViewModel @Inject constructor(
    private val diagnostics: DiagnosticsRepository,
    private val formatters: MorseFormatters,
) : ViewModel() {

    private val errorsOnly = MutableStateFlow(false)

    public val state: StateFlow<LogsUiState> = errorsOnly
        .flatMapLatest { only ->
            if (only) diagnostics.observeProblems() else diagnostics.observeLogs()
        }
        .map { entries -> LogsUiState(entries = entries, errorsOnly = errorsOnly.value) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = LogsUiState(),
        )

    public fun toggleErrorsOnly() {
        errorsOnly.value = !errorsOnly.value
    }

    public fun clear() {
        viewModelScope.launch { diagnostics.clearLogs() }
    }

    /** The exact text the Share action hands to the chooser. */
    public suspend fun exportText(): String = diagnostics.exportLogs()

    public fun stamp(entry: LogEntry): String = formatters.logTime(entry.timestampEpochMillis)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
