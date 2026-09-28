package app.morsecode.ui.crashes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsecode.core.data.repository.DiagnosticsRepository
import app.morsecode.core.model.CrashReport
import app.morsecode.core.model.MorseFormatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
public class CrashesViewModel @Inject constructor(
    private val diagnostics: DiagnosticsRepository,
    private val formatters: MorseFormatters,
) : ViewModel() {

    public val reports: StateFlow<List<CrashReport>> = diagnostics.observeCrashReports()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList(),
        )

    public fun delete(id: Long) {
        viewModelScope.launch { diagnostics.deleteCrashReport(id) }
    }

    public fun clear() {
        viewModelScope.launch { diagnostics.clearCrashReports() }
    }

    public suspend fun exportText(): String = diagnostics.exportCrashReports()

    public fun whenLabel(report: CrashReport): String =
        formatters.dayLabel(report.occurredEpochMillis) + " · " + formatters.time(report.occurredEpochMillis)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
