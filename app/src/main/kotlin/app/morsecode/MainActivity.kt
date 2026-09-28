package app.morsecode

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.navigation.MorseApp
import app.morsecode.navigation.Routes
import app.morsecode.ui.root.RootViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity. Everything else is Compose.
 *
 * The theme is driven by the persisted settings — accent, dark mode and reduced
 * motion — so a change on the Settings screen repaints the whole app without a
 * restart. Reduced motion also mirrors the platform animator scale, which is how
 * a user who disabled animations system-wide gets a static UI (§11).
 */
@AndroidEntryPoint
public class MainActivity : ComponentActivity() {

    private val rootViewModel: RootViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by rootViewModel.settings.collectAsStateWithLifecycle()
            MorseTheme(
                themeMode = settings.themeMode,
                accent = settings.accent,
                reducedMotion = settings.reducedMotion || animatorScaleDisabled(),
            ) {
                MorseApp(
                    startDestination = if (settings.onboardingCompleted) Routes.CONNECT else Routes.ONBOARDING,
                )
            }
        }
    }

    /** True when the system animator duration scale is 0 ("animations off"). */
    private fun animatorScaleDisabled(): Boolean = runCatching {
        Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            DEFAULT_ANIMATOR_SCALE,
        ) == 0f
    }.getOrDefault(false)

    private companion object {
        const val DEFAULT_ANIMATOR_SCALE = 1f
    }
}
