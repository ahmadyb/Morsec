package app.morsecode.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.morsecode.core.design.component.BottomNavDestination
import app.morsecode.core.design.component.MorseBottomBar
import app.morsecode.core.design.component.MorseScreen
import app.morsecode.navigation.MorseDestination

/**
 * A screen with the bottom navigation bar.
 *
 * The reference builds each screen as `content + bottomNav(active)`, including
 * the nested Logs/Crashes/Doctor screens (which keep Settings lit), so the bar
 * is part of the screen rather than a shell around the navigation host. That
 * also keeps window insets in one place: [MorseScreen] applies them once.
 */
@Composable
public fun MorseTabScaffold(
    selected: MorseDestination?,
    onNavigate: (MorseDestination) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val destinations = MorseDestination.ordered.map { destination ->
        BottomNavDestination(
            id = destination.route,
            label = stringResource(destination.labelRes),
            iconRes = destination.iconRes,
            contentDescription = stringResource(destination.labelRes),
        )
    }
    MorseScreen {
        Column(modifier = Modifier.fillMaxWidth().weight(1f), content = content)
        MorseBottomBar(
            destinations = destinations,
            selectedId = selected?.route,
            onSelect = { route ->
                MorseDestination.forRoute(route)?.let(onNavigate)
            },
        )
    }
}
