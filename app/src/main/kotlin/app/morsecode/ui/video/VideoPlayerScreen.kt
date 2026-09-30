package app.morsecode.ui.video

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsecode.R
import app.morsecode.core.design.component.MorseEmptyState
import app.morsecode.core.design.component.MorseIconButton
import app.morsecode.core.design.component.MorseLoading
import app.morsecode.core.design.component.MorseScrubber
import app.morsecode.core.design.component.MorseVolumeControl
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.ImmersiveBackdrop
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.design.theme.VideoSurfaceGlow
import kotlin.math.hypot

/**
 * The video player (master prompt §4.7).
 *
 * Cell for cell, the reference's screen: a true-black stage, a header whose only jobs
 * are the file name, its resolution, size and length, and the way back to Files; a play
 * control in the middle of the picture at 74 dp on white at 14%; and a control surface
 * of #0B0B0B carrying the elapsed time against the clip's whole length, the shared
 * scrubber in its tall dark variant, and one row of back ten seconds, play, forward ten
 * seconds, volume and subtitles. There is no bottom navigation and no overflow menu:
 * this screen is immersive the way the photo viewer is, and §4.7 forbids the button the
 * reference removed.
 *
 * The header is the approved one and nothing more: back, the file name, and the
 * resolution, size and length under it. No overflow menu, no fullscreen switch, no
 * standing feature badge — a gate says what this build cannot do when a user asks for it,
 * not while they are merely looking at the screen.
 *
 * Three deliberate differences, each because a dead control is worse than a different one:
 *
 * - The reference opens a clip already playing at 28% of its length, because its clock
 *   is a simulation with something to show for it. This build opens at 0:00 paused:
 *   nothing here advances on its own, so a position nobody put there would be a claim
 *   about playback that is not happening. Media3 is milestone 10.
 * - The reference toasts every volume change. Here the level is printed beside the bars
 *   and moves under a dragging finger, so a toast per bar would be a stream of toasts
 *   for one gesture; the nudge and mute toasts stay, because those are single answers.
 * - Subtitles are unavailable, and say so. Nothing in this build can open a container
 *   and count its tracks, so the control reports that plainly instead of offering a
 *   subtitle that cannot appear. The state behind it already knows off from on, so the
 *   control is a switch that switches something the day a track exists.
 */
@Composable
public fun VideoPlayerScreen(
    onBack: () -> Unit,
    viewModel: VideoPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Resolved in composition: the click lambdas below must not read resources through
    // LocalContext, which is what lint's resource-in-lambda check rejects.
    val backDescription = stringResource(R.string.action_back)
    val playDescription = stringResource(R.string.video_play)
    val pauseDescription = stringResource(R.string.video_pause)
    val seekDescription = stringResource(R.string.video_seek)
    val skipBackDescription = stringResource(R.string.video_skip_back)
    val skipForwardDescription = stringResource(R.string.video_skip_forward)
    val volumeDescription = stringResource(R.string.video_volume)
    val muteDescription = stringResource(R.string.video_mute)
    val unmuteDescription = stringResource(R.string.video_unmute)
    val mutedWord = stringResource(R.string.video_volume_muted)
    val loadingDescription = stringResource(R.string.video_loading)
    val emptyTitle = stringResource(R.string.video_empty_title)
    val emptyBody = stringResource(R.string.video_empty_body)
    val noSubtitles = stringResource(R.string.video_no_subtitles)
    val mutedDone = stringResource(R.string.video_muted_done)
    // The level the unmute will give back, so the sentence can be composed now rather
    // than read out of a resource inside the click that needs it.
    val unmutedDone = stringResource(R.string.video_unmuted_done, state.rememberedVolumePercent)
    val positionDescription = stringResource(R.string.video_position, state.elapsed, state.total)
    val subtitleDescription = stringResource(
        when (state.subtitles) {
            SubtitleState.UNAVAILABLE -> R.string.video_subtitles_unavailable
            SubtitleState.OFF -> R.string.video_subtitles_off
            SubtitleState.ON -> R.string.video_subtitles_on
        },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ImmersiveBackdrop),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            VideoHeader(
                fileName = state.fileName,
                metadata = state.metadata,
                backDescription = backDescription,
                onBack = onBack,
            )

            when {
                state.loading -> MorseLoading(
                    contentDescription = loadingDescription,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )

                state.item == null -> MorseEmptyState(
                    title = emptyTitle,
                    message = emptyBody,
                    iconRes = MorseIcons.video,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )

                else -> {
                    VideoStage(
                        playing = state.playing,
                        playDescription = playDescription,
                        pauseDescription = pauseDescription,
                        onTogglePlay = viewModel::togglePlay,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )

                    VideoControlBar(
                        state = state,
                        playDescription = playDescription,
                        pauseDescription = pauseDescription,
                        seekDescription = seekDescription,
                        positionDescription = positionDescription,
                        skipBackDescription = skipBackDescription,
                        skipForwardDescription = skipForwardDescription,
                        volumeDescription = volumeDescription,
                        muteDescription = if (state.muted) unmuteDescription else muteDescription,
                        levelDescription = if (state.muted) mutedWord else state.volumePercent,
                        subtitleDescription = subtitleDescription,
                        onTogglePlay = viewModel::togglePlay,
                        onSeek = viewModel::seekTo,
                        onSkipBackward = {
                            viewModel.skipBackward()
                            Toast.makeText(context, skipBackDescription, Toast.LENGTH_SHORT).show()
                        },
                        onSkipForward = {
                            viewModel.skipForward()
                            Toast.makeText(context, skipForwardDescription, Toast.LENGTH_SHORT).show()
                        },
                        onToggleMute = {
                            val wasMuted = state.muted
                            viewModel.toggleMute()
                            Toast.makeText(
                                context,
                                if (wasMuted) unmutedDone else mutedDone,
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        onVolumeLevel = viewModel::setVolumeLevel,
                        onSubtitles = {
                            if (state.subtitles == SubtitleState.UNAVAILABLE) {
                                // A file with no track gets the plain answer the reference
                                // gives, rather than a switch that switches nothing.
                                Toast.makeText(context, noSubtitles, Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.cycleSubtitles()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

}

/**
 * The header: back, and the file name over its resolution, size and length.
 *
 * Nothing else, because that is what the approved screen has. It carries no overflow
 * menu (§4.7 forbids the button the reference removed), no fullscreen switch — fullscreen
 * is drawn on the reference's WebShare player, and the phone header has no such action —
 * and no standing feature badge: a gate says what this build cannot do when the user asks
 * for it, not before they ask.
 */
@Composable
private fun VideoHeader(
    fileName: String,
    metadata: String,
    backDescription: String,
    onBack: () -> Unit,
) {
    val colors = MorseTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MorseIconButton(
            iconRes = MorseIcons.back,
            contentDescription = backDescription,
            onClick = onBack,
            tint = Color.White,
            pressedBackground = VideoOverlayWash,
        )

        Column(modifier = Modifier.weight(1f)) {
            if (fileName.isNotEmpty()) {
                Text(
                    text = fileName,
                    style = MorseTextStyles.listTitle,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata,
                    style = MorseTextStyles.meta,
                    color = colors.viewerMetaText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The picture area, with the play control the reference puts in the middle of it.
 *
 * The stage is painted with the reference's own gradient — `radial-gradient(circle at
 * 60% 60%,#1b1a10,#000 70%)`, a warm glow below and right of centre falling away to the
 * true black of the screen — which in this build is what separates the stage from the
 * control bar under it, there being no picture to put on it until milestone 10. The 70%
 * stop is measured the way CSS measures a circle gradient of unspecified size: along the
 * ray to the farthest corner.
 */
@Composable
private fun VideoStage(
    playing: Boolean,
    playDescription: String,
    pauseDescription: String,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = MorseTheme.metrics

    Box(
        modifier = modifier.drawBehind {
            val centre = Offset(size.width * GLOW_CENTRE_FRACTION, size.height * GLOW_CENTRE_FRACTION)
            val farthestCorner = listOf(
                Offset.Zero,
                Offset(size.width, 0f),
                Offset(0f, size.height),
                Offset(size.width, size.height),
            ).maxOf { hypot(it.x - centre.x, it.y - centre.y) }
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(VideoSurfaceGlow, ImmersiveBackdrop),
                    center = centre,
                    radius = (farthestCorner * GLOW_STOP_FRACTION).coerceAtLeast(1f),
                ),
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(metrics.videoPlayOverlay)
                .clip(CircleShape)
                .background(VideoOverlayWash),
            contentAlignment = Alignment.Center,
        ) {
            MorseIconButton(
                iconRes = if (playing) MorseIcons.pause else MorseIcons.play,
                contentDescription = if (playing) pauseDescription else playDescription,
                onClick = onTogglePlay,
                tint = Color.White,
                size = metrics.videoPlayOverlay,
                glyph = metrics.videoPlayGlyph,
                pressedBackground = Color.Transparent,
            )
        }
    }
}

/**
 * The control surface: #0B0B0B, the times, the shared scrubber, and the row of five.
 *
 * The row is the reference's own, in its own order and its own sizes — back ten, the
 * 52 dp accent play, forward ten, the volume control with its ten bars, subtitles — and
 * the two play controls on this screen are one action in two places, which is what the
 * reference does with `data-act="play"` on both.
 *
 * One width rule, and it is the reference's arithmetic rather than a guess about it.
 * Five controls at their drawn sizes fit the 411 dp phone this document was laid out
 * for, but this app reports a 48 dp layout box for every icon button (§11), and four of
 * them plus a volume control with ten bars and a readout need 397 dp of the 324 dp a
 * 360 dp phone has left after the bar's own padding. So the width the row needs is
 * computed from the same tokens it is drawn with, and when the device does not have it
 * the volume control takes a line of its own below the transport: every control stays
 * on screen at its full touch size, and on the reference's own width nothing moves.
 */
@Composable
private fun VideoControlBar(
    state: VideoPlayerUiState,
    playDescription: String,
    pauseDescription: String,
    seekDescription: String,
    positionDescription: String,
    skipBackDescription: String,
    skipForwardDescription: String,
    volumeDescription: String,
    muteDescription: String,
    levelDescription: String,
    subtitleDescription: String,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    onToggleMute: () -> Unit,
    onVolumeLevel: (Int) -> Unit,
    onSubtitles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val steps = metrics.volumeSteps
        val stripWidth = metrics.volumeStepWidth * steps + metrics.volumeGap * (steps - 1)
        val rowNeeds = metrics.touchTarget * TRANSPORT_TOUCH_TARGETS +
            metrics.videoControlPlay +
            metrics.volumeControlGap * 2 +
            metrics.volumeLabelWidth +
            stripWidth +
            CONTROL_BAR_PADDING_HORIZONTAL * 2
        val fitsOnOneRow = maxWidth >= rowNeeds

        // One control, one definition: it is called from the transport row when there is
        // room for it there, and from the line below when there is not.
        val volume: @Composable (Modifier) -> Unit = { volumeModifier ->
            MorseVolumeControl(
                volumeInForce = state.effectiveVolume,
                onMuteClick = onToggleMute,
                onLevelChange = onVolumeLevel,
                contentDescription = volumeDescription,
                muteDescription = muteDescription,
                levelDescription = levelDescription,
                tint = colors.viewerIconContent,
                modifier = volumeModifier,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .background(colors.videoControlBar)
                .padding(
                    start = CONTROL_BAR_PADDING_HORIZONTAL,
                    end = CONTROL_BAR_PADDING_HORIZONTAL,
                    top = CONTROL_BAR_PADDING_TOP,
                    bottom = CONTROL_BAR_PADDING_BOTTOM,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = state.elapsed,
                    style = MorseTextStyles.meta,
                    color = colors.viewerMetaText,
                )
                Text(
                    text = state.total,
                    style = MorseTextStyles.meta,
                    color = colors.viewerMetaText,
                )
            }

            MorseScrubber(
                positionMillis = state.positionMillis,
                durationMillis = state.durationMillis,
                onSeek = onSeek,
                contentDescription = seekDescription,
                positionDescription = positionDescription,
                // `.scrub.big.dark`: the taller bar, on a track of white at 20% for a
                // player sitting on true black. The same component the music player
                // uses, so a seek means the same thing in both.
                big = true,
                dark = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 16.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MorseIconButton(
                    iconRes = MorseIcons.back0,
                    contentDescription = skipBackDescription,
                    onClick = onSkipBackward,
                    tint = colors.viewerIconContent,
                    pressedBackground = VideoOverlayWash,
                )

                Box(
                    modifier = Modifier
                        .size(metrics.videoControlPlay)
                        .clip(CircleShape)
                        .background(colors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    MorseIconButton(
                        iconRes = if (state.playing) MorseIcons.pause else MorseIcons.play,
                        contentDescription = if (state.playing) pauseDescription else playDescription,
                        onClick = onTogglePlay,
                        tint = colors.onAccent,
                        size = metrics.videoControlPlay,
                        glyph = metrics.iconButtonGlyph,
                        pressedBackground = Color.Transparent,
                    )
                }

                MorseIconButton(
                    iconRes = MorseIcons.fwd0,
                    contentDescription = skipForwardDescription,
                    onClick = onSkipForward,
                    tint = colors.viewerIconContent,
                    pressedBackground = VideoOverlayWash,
                )

                if (fitsOnOneRow) volume(Modifier)

                MorseIconButton(
                    iconRes = MorseIcons.cc,
                    contentDescription = subtitleDescription,
                    onClick = onSubtitles,
                    tint = colors.viewerIconContent,
                    pressedBackground = VideoOverlayWash,
                )
            }

            if (!fitsOnOneRow) {
                volume(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = WRAPPED_VOLUME_GAP),
                )
            }
        }
    }
}

/**
 * The wash the reference paints its immersive controls on: `rgba(255,255,255,.14)` on
 * the central play button, and the press feedback for the icon buttons that sit on true
 * black, where the theme's own pressed colour would be invisible.
 */
private val VideoOverlayWash = Color.White.copy(alpha = 0.14f)

/** Where the stage's glow is centred: the reference puts it at 60% across and down. */
private const val GLOW_CENTRE_FRACTION = 0.60f

/** How far the glow reaches before it is the screen's own black: the CSS 70% stop. */
private const val GLOW_STOP_FRACTION = 0.70f

/** The control surface's own padding: `padding:14px 18px 22px` in the reference. */
private val CONTROL_BAR_PADDING_HORIZONTAL = 18.dp
private val CONTROL_BAR_PADDING_TOP = 14.dp
private val CONTROL_BAR_PADDING_BOTTOM = 22.dp

/**
 * How many of the row's controls carry a 48 dp touch box: back ten, forward ten, the
 * volume control's speaker and subtitles. The accent play is drawn at its own 52 dp.
 */
private const val TRANSPORT_TOUCH_TARGETS = 4

/** Breathing room between the transport row and the volume line it was moved to. */
private val WRAPPED_VOLUME_GAP = 8.dp
