package app.morsecode.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.morsecode.R
import app.morsecode.core.design.component.ChipStyle
import app.morsecode.core.design.component.FileKindIcon
import app.morsecode.core.design.component.MorseCheckbox
import app.morsecode.core.design.component.MorseChip
import app.morsecode.core.design.component.MorseIconButton
import app.morsecode.core.design.component.MorseListRow
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MediaKind

/**
 * One file or folder row: kind glyph, name, real metadata, and either a
 * selection checkbox or the chip that says what the item is.
 *
 * Shared by the Files categories and the internal folder browser so a row means
 * the same thing in both places — including the selection rule from §5: while a
 * selection is open, tapping a row selects it instead of opening it.
 *
 * [onRemove] is the SAF grant's revoke affordance and only the Files category
 * passes one; [removeDescription] is its accessibility label, resolved by the
 * caller because only a composable scope may read resources.
 */
@Composable
public fun MorseMediaRow(
    item: MediaItem,
    selected: Boolean,
    selecting: Boolean,
    subtitle: String,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onRemove: (() -> Unit)?,
    removeDescription: String?,
    modifier: Modifier = Modifier,
) {
    val colors = MorseTheme.colors
    val accent = colors.accent

    MorseListRow(
        title = item.displayName,
        meta = subtitle,
        selected = selected,
        onClick = onClick,
        minHeight = 64.dp,
        modifier = modifier,
        leading = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting) {
                    MorseCheckbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelect() },
                        round = item.kind == MediaKind.AUDIO,
                        contentDescription = item.displayName,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = if (item.kind == MediaKind.AUDIO) accent else colors.kindWash(item.kind),
                            shape = RoundedCornerShape(MorseTheme.metrics.fileIconRadius),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.kind == MediaKind.AUDIO) {
                        Icon(
                            painter = painterResource(MorseIcons.music),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        FileKindIcon(kind = item.kind, size = 20.dp, transparentBackground = true)
                    }
                }
            }
        },
        trailing = {
            if (onRemove != null) {
                MorseIconButton(
                    iconRes = MorseIcons.trash,
                    contentDescription = removeDescription,
                    onClick = onRemove,
                    size = 40.dp,
                    glyph = 18.dp,
                )
            } else if (item.splitCount > 1 && item.isFolder) {
                MorseChip(text = stringResource(R.string.files_folder_children, item.splitCount))
            } else {
                MorseChip(
                    text = item.displayName.substringAfterLast('.', "")
                        .uppercase()
                        .ifEmpty { item.kind.name },
                    style = ChipStyle.NEUTRAL,
                )
            }
        },
    )
}
