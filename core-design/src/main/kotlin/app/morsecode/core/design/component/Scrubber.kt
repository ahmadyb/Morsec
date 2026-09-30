package app.morsecode.core.design.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.morsecode.core.design.theme.MorseTheme
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The seek arithmetic every player shares, in one place.
 *
 * A scrubber is a fraction of a track and a track is a duration, and the reference
 * clamps both ends of that conversion (`pct` clamps to 0..100, `seekTo` clamps the
 * fraction to 0..1 before multiplying). Keeping the three conversions together is
 * what lets them be asserted as arithmetic rather than as whichever gesture a test
 * happened to perform.
 */
public object ScrubberMath {

    /** How far through a track a position is, clamped to it; 0 when there is no duration. */
    public fun fraction(positionMillis: Long, durationMillis: Long): Float =
        if (durationMillis <= 0L) {
            0f
        } else {
            (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
        }

    /** The position a fraction of a track means, clamped to 0..1 before it is scaled. */
    public fun position(fraction: Float, durationMillis: Long): Long =
        (fraction.coerceIn(0f, 1f) * durationMillis.coerceAtLeast(0L)).roundToLong()

    /** A seek: never before the start, never past the end, whatever was asked for. */
    public fun clamp(positionMillis: Long, durationMillis: Long): Long =
        positionMillis.coerceIn(0L, durationMillis.coerceAtLeast(0L))
}

/**
 * The one scrubber every player uses (`.scrub`): tap anywhere on the track or drag
 * the knob to seek.
 *
 * Transcribed from the reference's own comment — `/* scrubber: click anywhere on the
 * track or drag the knob to seek */` — and its CSS: a 4 px bar (`.bar`, 5 px for
 * `.scrub.big`) on `--pressed` with the accent→lime fill, `padding:7px 0` for the
 * finger (10 dp here, the same touch-floor call the rest of the app makes), and a
 * 13 px knob with a 3 px ring of accent at 26% that is invisible until interaction.
 *
 * Two differences, both deliberate:
 *
 * - The reference reveals the knob on `:hover` as well as while dragging. A phone has
 *   no cursor, so the knob appears under the finger and not before it.
 * - The fill's `transition:width .25s linear` is switched off while dragging
 *   (`.scrub.drag .bar>i{transition:none}`) and under reduced motion. It is the
 *   transition the reference uses to smooth its own clock, which this app does not
 *   run: nothing here advances the position on a timer.
 */
@Composable
public fun MorseScrubber(
    positionMillis: Long,
    durationMillis: Long,
    onSeek: (Long) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    /** `.scrub.big` — the music player's taller bar. */
    big: Boolean = false,
    /** `.scrub.dark` — a track of white at 20%, for a player on true black. */
    dark: Boolean = false,
    /** What a screen reader hears for the position, e.g. "1:44 of 4:08". */
    positionDescription: String? = null,
) {
    val metrics = MorseTheme.metrics
    val colors = MorseTheme.colors
    val motion = MorseTheme.motion
    val density = LocalDensity.current

    // The fraction under the finger, or null when the finger is up: while dragging,
    // the bar follows the finger rather than the position the player reported.
    var dragging by remember { mutableStateOf<Float?>(null) }
    var trackWidth by remember { mutableStateOf(0) }

    val fraction = dragging ?: ScrubberMath.fraction(positionMillis, durationMillis)

    /** Moves the finger's fraction and reports the position it means, clamped. */
    fun seek(fractionOfTrack: Float) {
        val clamped = fractionOfTrack.coerceIn(0f, 1f)
        dragging = clamped
        onSeek(ScrubberMath.position(clamped, durationMillis))
    }

    val dragState = rememberDraggableState { delta ->
        if (trackWidth > 0) seek(fraction + delta / trackWidth)
    }

    val drawn by animateFloatAsState(
        targetValue = fraction,
        animationSpec = if (dragging != null || motion.reduced) {
            tween(durationMillis = 0)
        } else {
            tween(durationMillis = motion.scrubMillis, easing = LinearEasing)
        },
        label = "scrubber",
    )

    val knob = metrics.scrubberKnob
    val ring = KNOB_RING
    val knobTotalPx = with(density) { (knob + ring + ring).roundToPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = metrics.scrubberPaddingVertical)
            .onSizeChanged { trackWidth = it.width }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = { offset -> seek(offset.x / trackWidth.coerceAtLeast(1)) },
                onDragStopped = { dragging = null },
            )
            .semantics {
                this.contentDescription = contentDescription
                if (positionDescription != null) this.stateDescription = positionDescription
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (big) BIG_TRACK_HEIGHT else metrics.scrubberHeight)
                .clip(RoundedCornerShape(metrics.progressRadius))
                .background(if (dark) DarkTrack else colors.progressTrack),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(drawn)
                    .fillMaxHeight()
                    .background(
                        brush = Brush.linearGradient(listOf(colors.progressStart, colors.progressEnd)),
                        shape = RoundedCornerShape(metrics.progressRadius),
                    ),
            )
        }

        if (dragging != null) {
            // `.scrub .knob{transform:translate(-50%,-50%)}` — centred on the position,
            // so it overhangs the track at both ends exactly as the reference does.
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(x = (trackWidth * drawn).roundToInt() - knobTotalPx / 2, y = 0)
                    }
                    .size(knob + ring + ring)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = KNOB_RING_ALPHA)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(knob)
                        .clip(CircleShape)
                        .background(colors.accent),
                )
            }
        }
    }
}

/** `.scrub.big .bar{height:5px}` — one pixel taller than the base bar. */
private val BIG_TRACK_HEIGHT = 5.dp

/** `.scrub .knob{box-shadow:0 0 0 3px color-mix(in srgb,var(--acc) 26%,transparent)}`. */
private val KNOB_RING = 3.dp
private const val KNOB_RING_ALPHA = 0.26f

/** `.scrub.dark .bar{background:rgba(255,255,255,.2)}`. */
private val DarkTrack = Color.White.copy(alpha = 0.2f)
