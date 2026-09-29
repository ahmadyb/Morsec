package app.morsecode.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme

/**
 * The `.crumb` path bar (master prompt §4.3): a pill that says where the user is
 * and lets any level be tapped to go back to it.
 *
 * It belongs *below* the sticky header and never scrolls away with the rows, so
 * the caller pins it: the bar is a sibling of the scrolling list, not part of it.
 * There is no upward-arrow control — the breadcrumb is the way up, which is why
 * every level is a target and the current one is named for screen readers.
 *
 * A path longer than the phone scrolls inside the pill and keeps its tail on
 * screen, because the level being browsed is the one worth reading. No horizontal
 * scrollbar is drawn.
 *
 * The reference draws the pill at about 40 dp with `padding:11px 16px`; it is
 * 48 dp here so a level meets the touch-target floor (§11), the same call the
 * category strip makes. Recorded in doc/fidelity-notes.md.
 */
@Composable
public fun MorseCrumb(
    levels: List<String>,
    onLevelClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int = MorseIcons.folder,
    iconDescription: String? = null,
    currentDescription: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val scrollState = rememberScrollState()
    // How far the path can scroll is only known once the strip has been laid out,
    // so the tail is chased rather than assumed: a long path always ends with the
    // level being browsed on screen, and a resize keeps it that way.
    LaunchedEffect(levels) {
        snapshotFlow { scrollState.maxValue }.collect { maxValue ->
            if (maxValue > 0) scrollState.scrollTo(maxValue)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = metrics.touchTarget)
            .clip(RoundedCornerShape(metrics.radiusPill))
            .background(colors.raised)
            // .crumb{padding:11px 16px} — the vertical half is the 48 dp height.
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        // .crumb{gap:6px}
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // .crumb svg — 13 dp folder glyph, tertiary.
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = iconDescription,
            tint = colors.textTertiary,
            modifier = Modifier.size(13.dp),
        )
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            levels.forEachIndexed { index, label ->
                if (index > 0) {
                    // .crumb i — a separator, not a control.
                    Text(
                        text = "/",
                        style = MorseTextStyles.monospacedAddress,
                        color = colors.textTertiary,
                    )
                }
                CrumbLevel(
                    label = label,
                    current = index == levels.lastIndex,
                    currentDescription = currentDescription,
                    onClick = { onLevelClick(index) },
                )
            }
        }
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

@Composable
private fun CrumbLevel(
    label: String,
    current: Boolean,
    currentDescription: String?,
    onClick: () -> Unit,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Text(
        text = label,
        // .crumb{font-family:var(--mono);font-size:12px}
        style = MorseTextStyles.monospacedAddress,
        color = when {
            // .crumb span:hover{color:var(--acc)}
            pressed -> colors.accent
            current -> colors.textPrimary
            else -> colors.textSecondary
        },
        maxLines = 1,
        overflow = TextOverflow.Visible,
        modifier = Modifier
            .defaultMinSize(minHeight = metrics.touchTarget)
            .clip(RoundedCornerShape(metrics.radiusXs))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .then(
                if (current && currentDescription != null) {
                    Modifier.semantics { stateDescription = currentDescription }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 4.dp),
    )
}
