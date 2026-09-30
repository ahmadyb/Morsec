package app.morsecode.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.design.tokens.MorseType
import kotlin.math.roundToInt

/**
 * Which bar a position across the strip means.
 *
 * The reference's volume is ten bars and a click on any one of them sets the level
 * (`vol(v){S.vol=(+v)/10;S.muted=false;}`), so the mapping is a fraction of the
 * strip's width rounded to a whole bar: the middle of the fifth bar is level five,
 * the left edge is nothing, the right edge is all ten. Kept beside [ScrubberMath]
 * for the same reason — it is arithmetic a screen should not re-derive, and a
 * position outside the strip is clamped rather than wrapped.
 */
public object VolumeMath {
    /** The bar [x] pixels into a strip [width] pixels wide is asking for, 0 to [steps]. */
    public fun levelFor(x: Float, width: Int, steps: Int): Int {
        if (width <= 0 || steps <= 0) return 0
        return levelForVolume(x / width.toFloat(), steps)
    }

    /**
     * How many of [steps] bars a level lights, rounded the way the reference rounds
     * (`Math.round(v*10)`): four and a half tenths lights five bars, and a level
     * outside nothing-to-full is pulled to the nearest end rather than wrapped.
     *
     * One rounding rule, so the bar a finger asks for, the bar a screen reader's
     * adjustment asks for and the bar a player's own state reports are the same bar.
     */
    public fun levelForVolume(volumeInForce: Float, steps: Int): Int {
        if (steps <= 0) return 0
        return (volumeInForce.coerceIn(0f, 1f) * steps).roundToInt().coerceIn(0, steps)
    }
}

/**
 * The speaker the volume control wears.
 *
 * The reference's own ternary, transcribed: `v===0?'volMute':v<0.5?'volLow':'vol'`,
 * where `v` is the level *in force* — the stored level, or nothing while muted. A
 * control that showed the full speaker while muted would be saying the opposite of
 * what it is doing.
 */
public fun volumeIconFor(volumeInForce: Float): Int = when {
    volumeInForce <= 0f -> MorseIcons.volMute
    volumeInForce < 0.5f -> MorseIcons.volLow
    else -> MorseIcons.vol
}

/**
 * The volume control every player uses: `.volc` in the reference document.
 *
 * A speaker button, ten bars, and the level in monospaced type — the reference's
 * note on its own video screen says what it is meant to be: "Volume is a real
 * slider — click any bar to set the level, click the speaker to mute and restore
 * it." So the strip is one gesture surface that answers wherever the finger goes
 * down and keeps answering while the finger moves, rather than ten separate small
 * targets a thumb has to land on exactly.
 *
 * Two differences, both deliberate:
 *
 * - The bars are 5 by 15 dp at this width, which is a visual size and not a touch
 *   size. The gesture surface is [MorseMetrics.touchTarget] tall with the bars
 *   centred inside it, so what a finger can hit is what a finger can see, and the
 *   padding is inside the pointer input and the semantics rather than outside them.
 * - The reference lights a bar at 75% opacity on `:hover`. A phone has no cursor,
 *   so there is nothing to hover and no state is drawn for one.
 *
 * The strip announces itself as a range a screen reader can adjust, and the
 * adjustment is wired to the same [onLevelChange] a finger uses: TalkBack's volume
 * gesture and a tap on a bar are the same instruction, so neither can be the one
 * that silently does nothing.
 *
 * @param volumeInForce the level being heard: the stored level, or nothing while muted.
 *   It is the only volume the control is told, because it is the only one that changes
 *   what a listener hears or what the speaker should be wearing — a separate mute flag
 *   would be an input nothing reads, and an unread input is how a control comes to
 *   disagree with itself.
 * @param levelDescription what the label prints and the state announces — "70%", or
 *   the word the reference prints while muted. Omit it and the label is not drawn.
 */
@Composable
public fun MorseVolumeControl(
    volumeInForce: Float,
    onMuteClick: () -> Unit,
    onLevelChange: (Int) -> Unit,
    contentDescription: String,
    muteDescription: String,
    modifier: Modifier = Modifier,
    levelDescription: String? = null,
    tint: Color = MorseTheme.colors.viewerIconContent,
) {
    val metrics = MorseTheme.metrics
    val colors = MorseTheme.colors
    val steps = metrics.volumeSteps

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.volumeControlGap),
    ) {
        MorseIconButton(
            iconRes = volumeIconFor(volumeInForce),
            contentDescription = muteDescription,
            onClick = onMuteClick,
            tint = tint,
            size = metrics.volumeMuteButton,
            glyph = metrics.iconButtonGlyph,
            pressedBackground = colors.pressed,
        )

        VolumeBars(
            level = VolumeMath.levelForVolume(volumeInForce, steps),
            steps = steps,
            onLevelChange = onLevelChange,
            contentDescription = contentDescription,
            levelDescription = levelDescription,
            modifier = Modifier.height(metrics.touchTarget),
        )

        if (levelDescription != null) {
            Text(
                text = levelDescription,
                style = MorseTextStyles.monospacedMeta.copy(fontSize = MorseType.volumeLabelSize),
                color = colors.textTertiary,
                modifier = Modifier.width(metrics.volumeLabelWidth),
            )
        }
    }
}

/**
 * The ten bars, as one gesture surface.
 *
 * The level is reported on the way down — a tap is a level, not a near-miss of one —
 * and then for every move of the same pointer until it lifts, which is what makes the
 * control the slider the reference calls it rather than a row of buttons.
 */
@Composable
private fun VolumeBars(
    level: Int,
    steps: Int,
    onLevelChange: (Int) -> Unit,
    contentDescription: String,
    levelDescription: String?,
    modifier: Modifier = Modifier,
) {
    val metrics = MorseTheme.metrics
    val colors = MorseTheme.colors
    var stripWidth by remember { mutableIntStateOf(0) }
    val levelChange by rememberUpdatedState(onLevelChange)

    /** Reports the bar under [x], or nothing at all until the strip has been measured. */
    fun report(x: Float) {
        if (stripWidth <= 0) return
        levelChange(VolumeMath.levelFor(x, stripWidth, steps))
    }

    Box(
        modifier = modifier
            .pointerInput(steps) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pointerId = down.id
                    report(down.position.x)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (!change.pressed) break
                        report(change.position.x)
                        change.consume()
                    }
                }
            }
            .semantics {
                this.contentDescription = contentDescription
                if (levelDescription != null) stateDescription = levelDescription
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = level.toFloat(),
                    range = 0f..steps.toFloat(),
                )
                setProgress { target ->
                    levelChange(target.roundToInt().coerceIn(0, steps))
                    true
                }
            }
            .onSizeChanged { stripWidth = it.width },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.volumeGap),
        ) {
            repeat(steps) { index ->
                Box(
                    modifier = Modifier
                        .width(metrics.volumeStepWidth)
                        .height(metrics.volumeStepHeight)
                        .clip(RoundedCornerShape(metrics.volumeStepRadius))
                        .background(if (index < level) colors.accent else colors.pressed),
                )
            }
        }
    }
}
