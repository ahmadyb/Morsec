package app.morsecode.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsecode.core.data.logging.MorseLogger
import app.morsecode.core.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One card of the four-card tour. */
public data class OnboardingUiState(
    val slide: Int = 0,
    /** True once the storage request has been answered, granted or not. */
    val permissionsAnswered: Boolean = false,
    val permissionsGranted: Boolean = false,
) {
    public val isLast: Boolean get() = slide >= OnboardingViewModel.SLIDE_COUNT - 1
}

@HiltViewModel
public class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val logger: MorseLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    public val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    public fun next() {
        _state.update { current ->
            if (current.isLast) current else current.copy(slide = current.slide + 1)
        }
    }

    public fun replay() {
        _state.update { it.copy(slide = 0, permissionsAnswered = false) }
    }

    /** Records the answer to the contextual storage request on card 2. */
    public fun onPermissionsResult(granted: Map<String, Boolean>) {
        val all = granted.values.isNotEmpty() && granted.values.all { it }
        val any = granted.values.any { it }
        _state.update { it.copy(permissionsAnswered = true, permissionsGranted = all || any) }
        logger.i(
            TAG,
            "onboarding storage request answered: ${granted.count { it.value }}/${granted.size} granted",
        )
    }

    /** Persists completion so the tour is not shown again after a restart. */
    public fun complete() {
        viewModelScope.launch {
            settings.setOnboardingCompleted(true)
            logger.i(TAG, "onboarding completed")
        }
    }

    public companion object {
        public const val SLIDE_COUNT: Int = 4
        private const val TAG = "onboarding"
    }
}
