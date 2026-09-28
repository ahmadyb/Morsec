package app.morsecode.ui.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsecode.core.data.settings.SettingsRepository
import app.morsecode.core.model.MorseSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Feeds the activity's theme; every other screen has its own view model. */
@HiltViewModel
public class RootViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {

    /**
     * Started eagerly: the first frame must not be drawn with default settings
     * and then visibly switch accent when the stored value arrives.
     */
    public val settings: StateFlow<MorseSettings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = MorseSettings(),
        )
}
