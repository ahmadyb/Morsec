package app.morsecode.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.morsecode.core.design.component.MorseDivider
import app.morsecode.core.design.component.touchTarget
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.design.tokens.MorseType

/**
 * How loud one action in the bar is.
 *
 * The reference's own three: the leading action carries the accent, the ordinary ones sit
 * in the secondary text colour, and the destructive one is the error colour.
 */
public enum class MorseActionBarTone {
    PRIMARY,
    NEUTRAL,
    DANGER,
}

/**
 * One action in [MorseActionBar]: a glyph over a word, exactly as the reference draws it.
 *
 * The label is the accessible name, so the icon is decorative and carries none of its own —
 * a screen reader hears "Add files", not "plus icon, Add files".
 */
public data class MorseActionBarItem(
    public val id: String,
    public val label: String,
    public val iconRes: Int,
    public val onClick: () -> Unit,
    public val tone: MorseActionBarTone = MorseActionBarTone.NEUTRAL,
    public val enabled: Boolean = true,
)

/**
 * The bottom transfer action bar (`.ab`): equal cells across the width, a hairline above
 * them, and the bottom navigation below.
 *
 * Equal cells matter more than they look: the master prompt fixes this bar at four actions
 * — Add files, Pause all / Resume all, Background, End — and a bar whose cells changed
 * width as its labels changed would move the destructive action out from under a thumb
 * that is used to where it is. The pause cell's label is the one thing that changes, and it
 * changes within its own cell.
 *
 * A cell that cannot act is disabled rather than removed, for the same reason: the other
 * three stay where they were.
 */
@Composable
public fun MorseActionBar(
    items: List<MorseActionBarItem>,
    modifier: Modifier = Modifier,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background),
    ) {
        MorseDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = metrics.actionBarMinHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item -> ActionBarCell(item, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun RowScope.ActionBarCell(item: MorseActionBarItem, modifier: Modifier) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val interaction = remember { MutableInteractionSource() }

    val tint = when (item.tone) {
        MorseActionBarTone.PRIMARY -> colors.accent
        MorseActionBarTone.NEUTRAL -> colors.textSecondary
        MorseActionBarTone.DANGER -> colors.error
    }.let { if (item.enabled) it else it.copy(alpha = DisabledAlpha) }

    Column(
        modifier = modifier
            .touchTarget()
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = item.enabled,
                onClick = item.onClick,
            )
            .padding(horizontal = metrics.actionBarPaddingHorizontal, vertical = BarPaddingVertical),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GlyphGap),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(metrics.radiusSm))
                .background(
                    if (item.tone == MorseActionBarTone.PRIMARY && item.enabled) {
                        colors.actionBarPrimaryPill
                    } else {
                        Color.Transparent
                    },
                )
                .padding(horizontal = PillPaddingHorizontal, vertical = PillPaddingVertical),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = item.iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(metrics.actionBarGlyph),
            )
        }
        Text(
            text = item.label,
            style = MorseTextStyles.meta.copy(fontSize = MorseType.actionBarLabelSize),
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** `.ab button{padding:10px 0 8px}`, split so the glyph keeps its own pill padding. */
private val BarPaddingVertical = 10.dp

/** `.ab .pill{padding:4px 16px}`. */
private val PillPaddingHorizontal = 16.dp
private val PillPaddingVertical = 4.dp

/** `.ab button{gap:5px}` between the glyph and its word. */
private val GlyphGap = 5.dp

/** What a disabled action looks like: drawn, but plainly not available. */
private const val DisabledAlpha = 0.38f
