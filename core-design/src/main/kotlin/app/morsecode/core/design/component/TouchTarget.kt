package app.morsecode.core.design.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Grows the hit area of a control to [size] without changing what is drawn.
 *
 * The mockup draws 40 dp icon buttons with an 8 dp gap between them. Reporting a
 * 48 dp layout box and letting the parent use zero spacing keeps that exact
 * visual rhythm while satisfying the 48 x 48 dp minimum touch target (§11).
 */
public fun Modifier.touchTarget(size: Dp = 48.dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val minimum = size.roundToPx()
        val width = maxOf(placeable.width, minimum)
        val height = maxOf(placeable.height, minimum)
        layout(width, height) {
            placeable.placeRelative(
                (width - placeable.width) / 2,
                (height - placeable.height) / 2,
            )
        }
    },
)
