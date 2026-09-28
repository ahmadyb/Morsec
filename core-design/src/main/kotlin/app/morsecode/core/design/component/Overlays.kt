package app.morsecode.core.design.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import kotlin.math.roundToInt

/** A discovery blip, positioned as a fraction of the radar box. */
public data class RadarBlip(
    val xFraction: Float,
    val yFraction: Float,
    /** Stagger of the pulse animation, as a fraction of one pulse cycle. */
    val delayFraction: Float = 0f,
)

/**
 * The `.radar` discovery indicator.
 *
 * A radial accent wash, one inset ring, a conic sweep that turns once every
 * 2400 ms, pulsing blips for each discovered peer and a 44 dp accent core. When
 * reduced motion is requested the sweep and pulses are drawn in their resting
 * state instead of animating (§11).
 *
 * @param blips real discovered peers; the indicator never invents devices.
 * @param active when false the sweep stops, which is how the UI reports that
 *   discovery is not running.
 */
@Composable
public fun RadarIndicator(
    modifier: Modifier = Modifier,
    size: Dp = MorseTheme.metrics.radarSize,
    blips: List<RadarBlip> = emptyList(),
    active: Boolean = true,
    coreIconRes: Int = MorseIcons.target,
    coreContentDescription: String? = null,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val motion = MorseTheme.motion
    val density = LocalDensity.current
    val sizePx = with(density) { size.toPx() }

    val sweepAngle = if (active && !motion.reduced) {
        val transition = rememberInfiniteTransition(label = "radarSweep")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = motion.radarSweepMillis, easing = LinearEasing),
            ),
            label = "radarSweepAngle",
        )
        angle
    } else {
        0f
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(colors.primaryContainer, Color.Transparent),
                    radius = sizePx * 0.70f,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        // .radar::before — inset 14% ring.
        Box(
            modifier = Modifier
                .fillMaxSize(0.72f)
                .clip(CircleShape)
                .border(1.dp, colors.radarRing, CircleShape),
        )
        // .radar::after — conic sweep, rotated from 12 o'clock like CSS conic-gradient.
        if (active) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = sweepAngle - 90f
                        clip = true
                        shape = CircleShape
                    }
                    .background(
                        Brush.sweepGradient(
                            0.00f to colors.radarSweep,
                            0.28f to Color.Transparent,
                            1.00f to Color.Transparent,
                        ),
                    ),
            )
        }
        blips.forEach { blip ->
            RadarBlipDot(blip = blip, containerSize = size)
        }
        // .radar .core
        Box(
            modifier = Modifier
                .size(metrics.radarCoreSize)
                .clip(CircleShape)
                .background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = coreIconRes),
                contentDescription = coreContentDescription,
                tint = colors.onAccent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun RadarBlipDot(blip: RadarBlip, containerSize: Dp) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val motion = MorseTheme.motion
    val density = LocalDensity.current

    val (alpha, scale) = if (motion.reduced) {
        1f to 1f
    } else {
        val transition = rememberInfiniteTransition(label = "radarBlip")
        val alphaValue by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = motion.radarBlipMillis / 2,
                    easing = FastOutSlowInEasing,
                    initialStartOffset = StartOffset((motion.radarBlipMillis * blip.delayFraction).roundToInt()),
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "radarBlipAlpha",
        )
        val scaleValue by transition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = motion.radarBlipMillis / 2,
                    easing = FastOutSlowInEasing,
                    initialStartOffset = StartOffset((motion.radarBlipMillis * blip.delayFraction).roundToInt()),
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "radarBlipScale",
        )
        alphaValue to scaleValue
    }

    val boxSize = with(density) { containerSize.toPx() }
    val blipPx = with(density) { metrics.radarBlip.toPx() }
    val left = (boxSize * blip.xFraction - blipPx / 2f).roundToInt()
    val top = (boxSize * blip.yFraction - blipPx / 2f).roundToInt()

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        Box(
            modifier = Modifier
                .offset { IntOffset(left, top) }
                .size(metrics.radarBlip)
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(colors.ok),
        )
    }
}

/**
 * The `.sheet` bottom sheet: 28 dp top corners, container surface, a 32 x 4 dp
 * grab handle and a title/subtitle block above the caller's content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun MorseModalSheet(
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val sheetState = rememberModalBottomSheetState()
    val sheetShape = RoundedCornerShape(
        topStart = metrics.radiusXl,
        topEnd = metrics.radiusXl,
        bottomStart = 0.dp,
        bottomEnd = 0.dp,
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        shape = sheetShape,
        containerColor = colors.card,
        contentColor = colors.textPrimary,
        scrimColor = colors.scrim,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 12.dp)
                    .size(width = metrics.sheetGrabWidth, height = metrics.sheetGrabHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(colors.outline),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = metrics.sheetPaddingHorizontal,
                    end = metrics.sheetPaddingHorizontal,
                    bottom = metrics.sheetPaddingBottom,
                ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MorseTextStyles.listTitle.copy(fontSize = 15.sp),
                color = colors.textPrimary,
            )
            if (subtitle != null) {
                MorseMetaText(text = subtitle)
            }
            Box(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

/**
 * The `.dlgcard` dialog: 28 dp corners, centred content, an optional leading
 * badge and a button row. Rendered with [Dialog] so the platform handles focus,
 * dimming and the back gesture on every supported API level.
 */
@Composable
public fun MorseDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    Dialog(onDismissRequest = onDismiss, properties = properties) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(metrics.radiusXl))
                    .background(colors.card)
                    .padding(
                        start = metrics.dialogPaddingHorizontal,
                        end = metrics.dialogPaddingHorizontal,
                        top = metrics.dialogPaddingTop,
                        bottom = metrics.dialogPaddingHorizontal,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}

/** A circular badge used at the top of dialogs and onboarding slides. */
@Composable
public fun MorseBadge(
    iconRes: Int?,
    background: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 66.dp,
    glyph: Dp = 30.dp,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
    letter: Char? = null,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        when {
            iconRes != null -> Icon(
                painter = painterResource(id = iconRes),
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(glyph),
            )
            letter != null -> Text(
                text = letter.toString(),
                color = contentColor,
                fontSize = 24.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.W700,
                textAlign = TextAlign.Center,
            )
        }
    }
}

