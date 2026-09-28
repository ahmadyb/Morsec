package app.morsecode.core.design.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.MediaKind
import kotlin.math.abs

/** The mockup's `.btn` flavours. */
public enum class MorseButtonVariant {
    /** Solid accent, dark ink: `.btn`. */
    FILLED,

    /** Transparent with an outline border: `.btn.ghost`. */
    GHOST,

    /** Accent container wash: `.btn.wash`. */
    WASH,

    /** Success green: `.btn.ok`. */
    OK,

    /** Error-tinted text button, used by the transfer action bar's End. */
    DANGER,
}

/**
 * The `.btn` control: pill shape, 44 dp minimum height (36 dp when [small]),
 * weight 600 label and the mockup's 0.97 press scale.
 */
@Composable
public fun MorseButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: MorseButtonVariant = MorseButtonVariant.FILLED,
    iconRes: Int? = null,
    enabled: Boolean = true,
    small: Boolean = false,
    fillWidth: Boolean = false,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedScale = MorseTheme.motion.pressedScale

    val background = when (variant) {
        MorseButtonVariant.FILLED -> colors.accent
        MorseButtonVariant.GHOST -> Color.Transparent
        MorseButtonVariant.WASH -> colors.primaryContainer
        MorseButtonVariant.OK -> colors.ok
        MorseButtonVariant.DANGER -> Color.Transparent
    }
    val content = when (variant) {
        MorseButtonVariant.FILLED -> colors.onAccent
        MorseButtonVariant.GHOST -> colors.textPrimary
        MorseButtonVariant.WASH -> colors.accent
        MorseButtonVariant.OK -> colors.onOk
        MorseButtonVariant.DANGER -> colors.error
    }
    val borderColor = when (variant) {
        MorseButtonVariant.GHOST -> colors.outline
        else -> Color.Transparent
    }
    val minHeight = if (small) metrics.buttonSmallMinHeight else metrics.buttonMinHeight
    val horizontal = if (small) metrics.buttonSmallPaddingHorizontal else metrics.buttonPaddingHorizontal
    val vertical = if (small) metrics.buttonSmallPaddingVertical else metrics.buttonPaddingVertical
    val shape = RoundedCornerShape(percent = 50)

    Row(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = minHeight)
            .graphicsLayer {
                val scale = if (pressed) pressedScale else 1f
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(background)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(width = 1.dp, color = borderColor, shape = shape)
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = horizontal, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = if (enabled) content else content.copy(alpha = 0.38f),
                modifier = Modifier.size(if (small) 12.dp else 14.dp),
            )
        }
        Text(
            text = text,
            style = if (small) MorseTextStyles.buttonSmall else MorseTextStyles.button,
            color = if (enabled) content else content.copy(alpha = 0.38f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** The `.link` inline text action. */
@Composable
public fun MorseLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MorseTheme.colors.accent,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(MorseTheme.metrics.radiusXs))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MorseTextStyles.buttonSmall, color = color)
    }
}

/** The `.chip` flavours used across transfer rows, peers and sessions. */
public enum class ChipStyle { NEUTRAL, ACCENT, OK, WARN, ERROR, RECEIVE, OUTLINE }

/**
 * The `.chip` label: 24 dp minimum height, 9 sp weight 700 uppercase text on a
 * tinted container. Status is never carried by colour alone — the label text is
 * always present (§11).
 */
@Composable
public fun MorseChip(
    text: String,
    modifier: Modifier = Modifier,
    style: ChipStyle = ChipStyle.NEUTRAL,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val background = when (style) {
        ChipStyle.NEUTRAL -> colors.raised
        ChipStyle.ACCENT -> colors.chipAccentBackground
        ChipStyle.OK -> colors.chipOkBackground
        ChipStyle.WARN -> colors.chipWarnBackground
        ChipStyle.ERROR -> colors.chipErrorBackground
        ChipStyle.RECEIVE -> colors.receiveBackground
        ChipStyle.OUTLINE -> Color.Transparent
    }
    val content = when (style) {
        ChipStyle.NEUTRAL -> colors.textSecondary
        ChipStyle.ACCENT, ChipStyle.OUTLINE -> colors.accent
        ChipStyle.OK -> colors.ok
        ChipStyle.WARN -> colors.warn
        ChipStyle.ERROR -> colors.error
        ChipStyle.RECEIVE -> colors.receive
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = metrics.chipMinHeight)
            .clip(RoundedCornerShape(metrics.chipRadius))
            .background(background)
            .then(
                if (style == ChipStyle.OUTLINE) {
                    Modifier.border(1.dp, colors.chipOutlineBorder, RoundedCornerShape(metrics.chipRadius))
                } else {
                    Modifier
                },
            )
            .padding(
                horizontal = metrics.chipPaddingHorizontal,
                vertical = metrics.chipPaddingVertical,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MorseTextStyles.chip,
            color = content,
            maxLines = 1,
        )
    }
}

/**
 * The `.av` peer avatar. Colour is derived from a stable seed so a peer keeps the
 * same identity colour between sessions.
 */
@Composable
public fun PeerAvatar(
    letter: Char,
    colorSeed: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    backgroundOverride: Color? = null,
    contentColorOverride: Color? = null,
) {
    val metrics = MorseTheme.metrics
    val size = if (large) metrics.avatarSizeLarge else metrics.avatarSize
    val textSize = if (large) metrics.avatarTextSizeLarge else metrics.avatarTextSize
    val background = backgroundOverride ?: peerColour(colorSeed)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter.toString(),
            color = contentColorOverride ?: Color(0xFF0B0B0B),
            fontSize = textSize,
            fontWeight = androidx.compose.ui.text.font.FontWeight.W700,
            maxLines = 1,
        )
    }
}

/** The approved peer palette; a seed maps onto it deterministically. */
public val peerPalette: List<Color> = listOf(
    Color(0xFF8B5CF6),
    Color(0xFF0EA5E9),
    Color(0xFF22C55E),
    Color(0xFFF59E0B),
    Color(0xFFEF4444),
    Color(0xFF84CC16),
    Color(0xFFEA580C),
    Color(0xFFA78BFA),
)

/** Stable hash (the mockup's `hsh`) mapped onto [peerPalette]. */
public fun peerColour(seed: String): Color {
    var hash = 0
    for (ch in seed) hash = (hash * 31 + ch.code) and 0x7FFFFFFF
    return peerPalette[abs(hash) % peerPalette.size]
}

/** The `.ico` file-kind badge: 34 dp rounded square, kind colour at 22% wash. */
@Composable
public fun FileKindIcon(
    kind: MediaKind,
    modifier: Modifier = Modifier,
    size: Dp = MorseTheme.metrics.fileIconSize,
    transparentBackground: Boolean = false,
) {
    val colors = MorseTheme.colors
    val glyph = if (size >= MorseTheme.metrics.fileIconSize) {
        MorseTheme.metrics.fileIconGlyph
    } else {
        MorseTheme.metrics.fileIconGlyph - 2.dp
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(MorseTheme.metrics.fileIconRadius))
            .background(if (transparentBackground) Color.Transparent else colors.kindWash(kind)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = kindIcon(kind)),
            contentDescription = null,
            tint = colors.kindForeground(kind),
            modifier = Modifier.size(glyph),
        )
    }
}

/** Maps a [MediaKind] to its approved glyph from the mockup's `KIND` table. */
public fun kindIcon(kind: MediaKind): Int = when (kind) {
    MediaKind.IMAGE -> MorseIcons.image
    MediaKind.VIDEO -> MorseIcons.video
    MediaKind.AUDIO -> MorseIcons.music
    MediaKind.DOC -> MorseIcons.doc
    MediaKind.ZIP -> MorseIcons.zip
    MediaKind.APK -> MorseIcons.apk
    MediaKind.FOLDER -> MorseIcons.folder
    MediaKind.OTHER -> MorseIcons.doc
}

/** Progress bar flavour, matching `.bar`, `.bar.done`, `.bar.paused`, `.bar.err`. */
public enum class ProgressFlavour { ACTIVE, DONE, PAUSED, ERROR, RECEIVE }

/**
 * The `.bar` transfer progress row: 5 dp track on `--pressed`, filled with the
 * accent→lime gradient while active and a flat status colour otherwise. Width is
 * animated over 250 ms with a linear curve, exactly like the CSS transition, and
 * collapses to a single frame when reduced motion is requested.
 */
@Composable
public fun TransferProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    flavour: ProgressFlavour = ProgressFlavour.ACTIVE,
    height: Dp = MorseTheme.metrics.progressHeight,
) {
    val colors = MorseTheme.colors
    val motion = MorseTheme.motion
    val target = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = if (motion.reduced) 1 else motion.scrubMillis,
            easing = LinearEasing,
        ),
        label = "transferProgress",
    )
    val fill = when (flavour) {
        ProgressFlavour.ACTIVE -> Brush.horizontalGradient(listOf(colors.progressStart, colors.progressEnd))
        ProgressFlavour.DONE -> Brush.horizontalGradient(listOf(colors.ok, colors.ok))
        ProgressFlavour.PAUSED -> Brush.horizontalGradient(listOf(colors.warn, colors.warn))
        ProgressFlavour.ERROR -> Brush.horizontalGradient(listOf(colors.error, colors.error))
        ProgressFlavour.RECEIVE -> Brush.horizontalGradient(listOf(colors.receive, colors.receive))
    }
    val shape = RoundedCornerShape(MorseTheme.metrics.progressRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(colors.progressTrack),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .height(height)
                .clip(shape)
                .background(fill),
        )
    }
}

/** The `.tile` stat block inside a summary wash card. */
@Composable
public fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(metrics.tileRadius))
            .background(colors.raised)
            .padding(horizontal = metrics.tilePaddingHorizontal, vertical = metrics.tilePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MorseTextStyles.tileValue,
            color = colors.accent,
            maxLines = 1,
        )
        Text(
            text = label.uppercase(),
            style = MorseTextStyles.tileLabel,
            color = colors.textTertiary,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** The `.sw` switch: 52 x 32 dp, 2 dp outline, thumb grows when checked. */
@Composable
public fun MorseSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    stateLabel: String? = null,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val motion = MorseTheme.motion
    val duration = if (motion.reduced) 1 else motion.stateMillis

    val track by animateColorAsState(
        targetValue = if (checked) colors.accent else Color.Transparent,
        animationSpec = tween(duration),
        label = "switchTrack",
    )
    val border by animateColorAsState(
        targetValue = if (checked) colors.accent else colors.outline,
        animationSpec = tween(duration),
        label = "switchBorder",
    )
    val thumbSize by animateDpAsState(
        targetValue = if (checked) metrics.switchThumbOn else metrics.switchThumbOff,
        animationSpec = tween(duration),
        label = "switchThumbSize",
    )
    val thumbX by animateDpAsState(
        targetValue = if (checked) 25.dp else 5.dp,
        animationSpec = tween(duration),
        label = "switchThumbX",
    )
    val thumbColor = if (checked) colors.onAccent else colors.textTertiary

    Box(
        modifier = modifier
            .touchTarget()
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
                if (stateLabel != null) {
                    stateDescription = stateLabel
                    contentDescription = stateLabel
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            )
            .size(width = metrics.switchWidth, height = metrics.switchHeight)
            .clip(RoundedCornerShape(percent = 50))
            .background(track)
            .border(metrics.switchBorderWidth, border, RoundedCornerShape(percent = 50)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbX)
                .size(thumbSize)
                .clip(CircleShape)
                .background(thumbColor),
        )
    }
}

/** The `.ck` checkbox, square for rows and round for media tiles. */
@Composable
public fun MorseCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    round: Boolean = false,
    /** Tile overlay styling: translucent dark background, white ring. */
    overlay: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val shape = if (round) CircleShape else RoundedCornerShape(metrics.checkboxRadius)
    val background = when {
        checked -> colors.ok
        overlay -> Color.Black.copy(alpha = 0.45f)
        else -> Color.Transparent
    }
    val border = when {
        checked -> colors.ok
        overlay -> Color.White
        else -> colors.textTertiary
    }
    Box(
        modifier = modifier
            .touchTarget(44.dp)
            .semantics {
                role = Role.Checkbox
                toggleableState = ToggleableState(checked)
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Checkbox,
                onClick = { onCheckedChange(!checked) },
            )
            .size(metrics.checkboxSize)
            .clip(shape)
            .background(background)
            .border(1.5.dp, border, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                painter = painterResource(id = MorseIcons.check),
                contentDescription = null,
                tint = colors.onOk,
                modifier = Modifier.size(metrics.checkboxGlyph),
            )
        }
    }
}

/** The `.rad` radio used by the broadcast picker. */
@Composable
public fun MorseRadioButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val colors = MorseTheme.colors
    Box(
        modifier = modifier
            .touchTarget(44.dp)
            .semantics {
                role = Role.RadioButton
                toggleableState = ToggleableState(selected)
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .size(18.dp)
            .clip(CircleShape)
            .background(if (selected) colors.accent else Color.Transparent)
            .border(1.5.dp, if (selected) colors.accent else colors.textTertiary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                painter = painterResource(id = MorseIcons.check),
                contentDescription = null,
                tint = colors.onAccent,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/**
 * The `.segpill` two-option selector used by History (Received / Sent) and by
 * other segmented choices.
 */
@Composable
public fun SegmentedPill(
    options: List<SegmentedOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MorseTheme.colors
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.raised)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val selected = option.id == selectedId
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val pressedScale = MorseTheme.motion.pressedScale
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 40.dp)
                    .graphicsLayer {
                        val scale = if (pressed) pressedScale else 1f
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(shape)
                    .background(if (selected) colors.accent else Color.Transparent)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelect(option.id) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    style = if (selected) {
                        MorseTextStyles.button.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.W600)
                    } else {
                        MorseTextStyles.button.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.W400)
                    },
                    color = if (selected) colors.onAccent else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** One option inside a [SegmentedPill]. */
public data class SegmentedOption(val id: String, val label: String)

/** A thin 1 dp rule in `--m3-outline-variant`. */
@Composable
public fun MorseDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MorseTheme.colors.outlineVariant),
    )
}

/** Fixed-width spacer used inside rows. */
@Composable
public fun RowGap(width: Dp) {
    Box(modifier = Modifier.width(width))
}
