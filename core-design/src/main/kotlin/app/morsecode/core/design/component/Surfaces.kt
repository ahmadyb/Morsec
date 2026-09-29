package app.morsecode.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme

/**
 * Root of every phone screen: the approved surface colour, edge-to-edge insets
 * and an optional content column. Content is padded for the system bars so the
 * sticky headers and bottom bars sit inside the safe area.
 */
@Composable
public fun MorseScreen(
    modifier: Modifier = Modifier,
    background: Color = MorseTheme.colors.background,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding), content = content)
    }
}

/** Horizontal inset used by scrolling screen content: 18 dp, 16 dp when compact. */
@Composable
public fun screenHorizontalPadding(compact: Boolean = false): Dp =
    if (compact) MorseTheme.metrics.screenPaddingHorizontalCompact else MorseTheme.metrics.screenPaddingHorizontal

/**
 * The `.hd` header row: a title plus trailing icon actions.
 *
 * @param nested renders the smaller 22 sp title used by screens reached from
 *   another screen (the mockup forces `font-size:17px` headers up to 22px).
 * @param leading shown before the title, typically a back icon button.
 */
@Composable
public fun MorseScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    nested: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val metrics = MorseTheme.metrics
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = metrics.headerMinHeight)
            .padding(
                start = 0.dp,
                end = 0.dp,
                top = metrics.headerPaddingTop,
                bottom = metrics.headerPaddingBottom,
            )
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.headerGap),
    ) {
        if (leading != null) leading()
        Text(
            text = title,
            style = if (nested) MorseTextStyles.nestedTitle else MorseTextStyles.screenTitle,
            color = MorseTheme.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Zero spacing: each icon button already reports a 48 dp touch target, so
        // the visible gap between 40 dp circles is exactly the mockup's 8 dp.
        if (actions != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp), content = actions)
        }
    }
}

/**
 * The `.iconbtn` control: 40 dp circle-ish square, 20 dp glyph, secondary tint,
 * pressed wash and the mockup's 0.97 press scale.
 */
@Composable
public fun MorseIconButton(
    iconRes: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MorseTheme.colors.textSecondary,
    size: Dp = MorseTheme.metrics.iconButtonSize,
    glyph: Dp = MorseTheme.metrics.iconButtonGlyph,
    pressedBackground: Color = MorseTheme.colors.pressed,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Read the motion token in composition: graphicsLayer's lambda is not a
    // composable scope, so tokens must be hoisted before it.
    val pressedScale = MorseTheme.motion.pressedScale
    Box(
        modifier = modifier
            .touchTarget()
            .graphicsLayer {
                val scale = if (pressed) pressedScale else 1f
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(size / 2))
            .background(if (pressed) pressedBackground else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .size(size),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(glyph),
        )
    }
}

/** The `.card` surface: 16 dp radius, container colour, no border in the M3 layer. */
@Composable
public fun MorseCard(
    modifier: Modifier = Modifier,
    padding: Dp = MorseTheme.metrics.cardPadding,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MorseTheme.colors
    val shape = RoundedCornerShape(MorseTheme.metrics.radiusMd)
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(colors.card)
    val clickable = if (onClick != null) {
        base.clickable(onClick = onClick)
    } else {
        base
    }
    Column(modifier = clickable.padding(padding), content = content)
}

/**
 * The `.wash` surface: the accent-tinted container used for peer cards, summary
 * cards and callouts. In the M3 layer this is `--m3-primary-container`.
 */
@Composable
public fun MorseWash(
    modifier: Modifier = Modifier,
    padding: Dp = MorseTheme.metrics.washPadding,
    background: Color = MorseTheme.colors.primaryContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MorseTheme.metrics.radiusLg))
            .background(background)
            .padding(padding),
        content = content,
    )
}

/**
 * The `.sec` section heading with an optional trailing text action (for example
 * "Refresh" on Discovered) and an optional icon action (the approved trash/clear
 * icon that removes only completed rows in that section).
 */
@Composable
public fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionIconRes: Int? = null,
    actionIconDescription: String? = null,
    onActionIcon: (() -> Unit)? = null,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = metrics.sectionMarginTop, bottom = metrics.sectionMarginBottom),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MorseTextStyles.sectionHeader,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (actionLabel != null && onAction != null) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .defaultMinSize(minHeight = metrics.sectionActionMinHeight)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = actionLabel,
                    style = MorseTextStyles.buttonSmall,
                    color = colors.accent,
                )
            }
        }
        if (actionIconRes != null && onActionIcon != null) {
            MorseIconButton(
                iconRes = actionIconRes,
                contentDescription = actionIconDescription,
                onClick = onActionIcon,
                size = metrics.sectionClearActionSize,
                glyph = metrics.iconButtonGlyphSmall,
                tint = colors.accent,
            )
        }
    }
}

/**
 * The `.li` row: 56 dp minimum height, leading slot, two-line text block and a
 * trailing slot. `divider` reproduces the 1 dp `--m3-outline-variant` rule that
 * separates rows and disappears after the last one.
 */
@Composable
public fun MorseListRow(
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    title: String,
    meta: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    titleColor: Color = MorseTheme.colors.textPrimary,
    divider: Boolean = true,
    minHeight: Dp = MorseTheme.metrics.listItemMinHeight,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background = when {
        selected -> colors.fileTabSelected
        pressed && onClick != null -> colors.textPrimary.copy(alpha = 0.05f)
        else -> Color.Transparent
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = minHeight)
                .background(background)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = null,
                            enabled = enabled,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(
                    horizontal = metrics.listItemPaddingHorizontal,
                    vertical = metrics.listItemPaddingVertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.listItemGap),
        ) {
            if (leading != null) leading()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MorseTextStyles.listTitle,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta != null) {
                    Text(
                        text = meta,
                        style = MorseTextStyles.meta,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) Row(content = trailing)
        }
        if (divider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.outlineVariant),
            )
        }
    }
}

/** The `.mut` body paragraph. */
@Composable
public fun MorseBodyText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MorseTheme.colors.textSecondary,
    align: TextAlign = TextAlign.Start,
) {
    Text(text = text, style = MorseTextStyles.muted, color = color, textAlign = align, modifier = modifier)
}

/** The `.meta` monospace-free metadata line. */
@Composable
public fun MorseMetaText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MorseTheme.colors.textTertiary,
    maxLines: Int = 2,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text,
        style = MorseTextStyles.meta,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
        modifier = modifier,
    )
}

/** The `.emptyish` state used when a list has nothing real to show. */
@Composable
public fun MorseEmptyState(
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    iconRes: Int = MorseIcons.folder,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = MorseTheme.colors.textTertiary.copy(alpha = 0.4f),
            modifier = Modifier.size(34.dp),
        )
        Text(
            text = title,
            style = MorseTextStyles.listTitle,
            color = MorseTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            MorseBodyText(text = message, align = TextAlign.Center)
        }
        if (action != null) action()
    }
}

/**
 * Hairline scrollbar: a 2 dp accent-toned thumb on a transparent track, drawn
 * from real scroll geometry (`.phone .scr::-webkit-scrollbar` in the reference).
 *
 * Native Android scroll indicators are not used because they cannot be tinted or
 * narrowed to the approved 2 dp on API 23.
 *
 * The geometry is read at draw time from lambdas, so the same drawing code serves
 * both a [LazyListState] (rows) and a [LazyGridState] (the photo and video grids).
 */
@Composable
private fun Modifier.scrollbarOverlay(
    readTotal: () -> Int,
    readFirstIndex: () -> Int,
    readLastIndex: () -> Int,
    readViewportStart: () -> Int,
    readViewportEnd: () -> Int,
): Modifier {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val thickness = with(density) { metrics.scrollbarThickness.toPx() }
    val minThumb = with(density) { metrics.scrollbarMinThumbLength.toPx() }
    return this.drawWithContent {
        drawContent()
        val total = readTotal()
        val first = readFirstIndex()
        val last = readLastIndex()
        if (total <= 0 || first < 0 || last < first) return@drawWithContent
        val viewportStart = readViewportStart()
        val viewport = readViewportEnd() - viewportStart
        if (viewport <= 0) return@drawWithContent
        val visibleCount = last - first + 1f
        val span = visibleCount / total.toFloat()
        if (span >= 0.999f) return@drawWithContent
        val thumbLength = (viewport * span).coerceAtLeast(minThumb)
        val scrollFraction = if (total - visibleCount <= 0f) {
            0f
        } else {
            (first / (total - visibleCount)).coerceIn(0f, 1f)
        }
        val top = viewportStart + (viewport - thumbLength) * scrollFraction
        val left = if (isRtl) 0f else size.width - thickness
        drawRect(
            color = colors.scrollThumb,
            topLeft = androidx.compose.ui.geometry.Offset(left, top),
            size = androidx.compose.ui.geometry.Size(thickness, thumbLength),
        )
    }
}

/** Hairline scrollbar for a lazy list (`.li` rows). */
@Composable
public fun Modifier.morseScrollbar(state: LazyListState): Modifier = scrollbarOverlay(
    readTotal = { state.layoutInfo.totalItemsCount },
    readFirstIndex = { state.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1 },
    readLastIndex = { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 },
    readViewportStart = { state.layoutInfo.viewportStartOffset },
    readViewportEnd = { state.layoutInfo.viewportEndOffset },
)

/** Hairline scrollbar for a lazy grid (`.grid3` photos and videos). */
@Composable
public fun Modifier.morseScrollbar(state: LazyGridState): Modifier = scrollbarOverlay(
    readTotal = { state.layoutInfo.totalItemsCount },
    readFirstIndex = { state.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1 },
    readLastIndex = { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 },
    readViewportStart = { state.layoutInfo.viewportStartOffset },
    readViewportEnd = { state.layoutInfo.viewportEndOffset },
)

/** Provides [LocalContentColor] for a subtree, matching the CSS `color` cascade. */
@Composable
public fun MorseContentColor(
    color: Color,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides color, content = content)
}

/** Spacer with a fixed height, used to clear floating bars at the end of lists. */
@Composable
public fun VerticalGap(height: Dp) {
    Box(modifier = Modifier.height(height))
}

/** Spacer with a fixed width. */
@Composable
public fun HorizontalGap(width: Dp) {
    Box(modifier = Modifier.width(width))
}

/** Constrains content height for sheets (`.sheet{max-height:76%}`). */
@Composable
public fun Modifier.sheetHeightLimit(containerHeight: Dp): Modifier {
    val fraction = MorseTheme.metrics.sheetMaxHeightFraction
    return this.heightIn(max = containerHeight * fraction)
}
