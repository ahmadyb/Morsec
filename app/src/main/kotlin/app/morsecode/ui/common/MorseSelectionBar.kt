package app.morsecode.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.morsecode.R
import app.morsecode.core.design.component.MorseButton
import app.morsecode.core.design.component.MorseButtonVariant
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme

/**
 * The bar that appears while items are selected: what is selected, then the
 * actions that apply to exactly that set.
 *
 * One component rather than one per screen because the rules are the same
 * everywhere (master prompt §5): a real system share, a Send that is gated until
 * the transfer engine exists, and a clear. Nothing here is per-file — the
 * per-file matrix lives on the rows and in the transfer sections.
 */
@Composable
public fun MorseSelectionBar(
    summary: String,
    onShare: () -> Unit,
    onSend: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = MorseTheme.colors.card,
    borderColor: Color = MorseTheme.colors.line,
    padding: Dp = MorseTheme.metrics.screenPaddingHorizontal,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .border(BorderStroke(1.dp, borderColor))
            .padding(horizontal = padding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = summary,
            style = MorseTextStyles.listTitle,
            color = MorseTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        MorseButton(
            text = stringResource(R.string.action_share),
            onClick = onShare,
            iconRes = MorseIcons.share,
            variant = MorseButtonVariant.WASH,
            small = true,
        )
        MorseButton(
            text = stringResource(R.string.action_send),
            onClick = onSend,
            iconRes = MorseIcons.send,
            small = true,
        )
        MorseButton(
            text = stringResource(R.string.action_clear),
            onClick = onClear,
            variant = MorseButtonVariant.GHOST,
            small = true,
        )
    }
}
