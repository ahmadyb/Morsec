package app.morsecode.navigation

import androidx.annotation.StringRes
import app.morsecode.R
import app.morsecode.core.design.icon.MorseIcons

/**
 * The four bottom-navigation destinations, in the reference's order, with its
 * icons: target, folder, clock, sliders.
 */
public enum class MorseDestination(
    public val route: String,
    @StringRes public val labelRes: Int,
    public val iconRes: Int,
) {
    CONNECT(Routes.CONNECT, R.string.nav_connect, MorseIcons.target),
    FILES(Routes.FILES, R.string.nav_files, MorseIcons.folder),
    HISTORY(Routes.HISTORY, R.string.nav_history, MorseIcons.clock),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, MorseIcons.sliders),
    ;

    public companion object {
        public val ordered: List<MorseDestination> = entries.toList()

        public fun forRoute(route: String?): MorseDestination? =
            entries.firstOrNull { it.route == route }
    }
}
