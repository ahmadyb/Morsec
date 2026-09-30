package app.morsecode.ui.music

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsecode.R
import app.morsecode.core.design.component.MorseDivider
import app.morsecode.core.design.component.MorseEmptyState
import app.morsecode.core.design.component.MorseIconButton
import app.morsecode.core.design.component.MorseListRow
import app.morsecode.core.design.component.MorseLoading
import app.morsecode.core.design.component.MorseScrubber
import app.morsecode.core.design.component.SectionHeader
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.design.tokens.MorseType
import app.morsecode.core.model.FeatureArea
import app.morsecode.navigation.MorseDestination
import app.morsecode.ui.common.MorseTabScaffold
import app.morsecode.ui.common.ShareFiles
import app.morsecode.ui.common.rememberFeatureGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The music player (master prompt §4.6).
 *
 * Cell for cell, this is the reference's screen: a header whose only jobs are to say
 * NOW PLAYING and to get back to Files, the artwork, the title over its
 * "artist · album" line, the big scrubber with elapsed and remaining either side, the
 * five-cell transport, the four-cell strip, and the queue under "Up next · N songs" —
 * all of it above the bottom nav, with Files still the selected tab, because a track
 * is opened from Files and belongs to it.
 *
 * Three deliberate differences, each because a dead control is worse than a different
 * one:
 *
 * - The reference's header ends in an overflow button with no action behind it. This
 *   one ends in the app's own gate for [FeatureArea.MEDIA_PLAYBACK] while playback is
 *   gated — the honest sentence about milestone 10 rather than a menu with nothing in
 *   it — and becomes a spacer of the same size, so the label stays centred, once the
 *   player can actually play.
 * - The reference's shuffle, previous, next, repeat and strip cells are toasts. Here
 *   they are state: they move the queue, the position and the like, and every queue
 *   row plays the track it names.
 * - The reference runs a clock that advances the position while it is "playing". This
 *   build has no audio engine to keep time, so nothing here advances on its own: the
 *   position moves when the user seeks it, and [MusicPlayerViewModel.trackEnded] is
 *   the transition the real engine will call. Media3, MediaSession, audio focus and
 *   the notification are milestone 10.
 */
@Composable
public fun MusicPlayerScreen(
    onBack: () -> Unit,
    onNavigate: (MorseDestination) -> Unit,
    viewModel: MusicPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate = rememberFeatureGate()
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val motion = MorseTheme.motion
    val listState = rememberLazyListState()

    // Resolved in composition: the click lambdas below must not read resources
    // through LocalContext, which is what lint's resource-in-lambda check rejects.
    val headerTitle = stringResource(R.string.music_now_playing)
    val backDescription = stringResource(R.string.action_back)
    val playbackDescription = stringResource(R.string.music_playback_info)
    val playDescription = stringResource(R.string.music_play)
    val pauseDescription = stringResource(R.string.music_pause)
    val previousDescription = stringResource(R.string.music_previous)
    val nextDescription = stringResource(R.string.music_next)
    val shuffleDescription = stringResource(R.string.music_shuffle)
    val shuffleState = stringResource(
        if (state.shuffle == ShuffleMode.ON) R.string.music_shuffle_on else R.string.music_shuffle_off,
    )
    val repeatDescription = stringResource(R.string.music_repeat)
    val repeatState = stringResource(
        when (state.repeat) {
            RepeatMode.OFF -> R.string.music_repeat_off
            RepeatMode.ALL -> R.string.music_repeat_all
            RepeatMode.ONE -> R.string.music_repeat_one
        },
    )
    val seekDescription = stringResource(R.string.music_seek)
    val saveLabel = stringResource(R.string.music_save)
    val savedLabel = stringResource(R.string.music_saved)
    val shareLabel = stringResource(R.string.action_share)
    val likedLabel = stringResource(R.string.music_liked)
    val likedState = stringResource(
        if (state.currentLiked) R.string.music_liked else R.string.music_not_liked,
    )
    val queueLabel = stringResource(R.string.music_queue)
    val queueJump = stringResource(R.string.music_queue_jump)
    val appName = stringResource(R.string.app_name)
    val noShareTarget = stringResource(R.string.error_share_no_app)
    val loadingDescription = stringResource(R.string.music_loading)
    val emptyTitle = stringResource(R.string.music_empty_title)
    val emptyBody = stringResource(R.string.music_empty_body)
    val queueHeader = pluralStringResource(R.plurals.music_queue_header, state.count, state.count)

    MorseTabScaffold(selected = MorseDestination.FILES, onNavigate = onNavigate) {
        // `.hd{padding:6px 0 14px}`: back, a centred mono label, and a third cell of
        // the same size as the first so the label really is centred.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = metrics.screenPaddingHorizontal,
                    end = metrics.screenPaddingHorizontal,
                    top = 6.dp,
                    bottom = 14.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MorseIconButton(
                iconRes = MorseIcons.chevD,
                contentDescription = backDescription,
                onClick = onBack,
            )
            Text(
                text = headerTitle,
                style = MorseTextStyles.nowPlayingLabel,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (gate.isAvailable(FeatureArea.MEDIA_PLAYBACK)) {
                Spacer(modifier = Modifier.size(metrics.iconButtonSize))
            } else {
                MorseIconButton(
                    iconRes = MorseIcons.info,
                    contentDescription = playbackDescription,
                    // The idiom the viewer's Send uses: run the real action when the
                    // area is live, otherwise say which milestone delivers it.
                    onClick = { gate.run(FeatureArea.MEDIA_PLAYBACK) { } },
                )
            }
        }

        when {
            state.loading -> MorseLoading(
                contentDescription = loadingDescription,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            state.isEmpty -> MorseEmptyState(
                title = emptyTitle,
                message = emptyBody,
                iconRes = MorseIcons.music,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(metrics.screenPaddingHorizontal),
            )

            else -> {
                val track = state.current
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = metrics.screenPaddingHorizontal),
                    contentPadding = PaddingValues(bottom = metrics.screenPaddingBottom),
                ) {
                    if (track == null) return@LazyColumn

                    item(key = "artwork") {
                        // `margin:6px auto 22px` on a 190 dp tile.
                        MusicArtwork(
                            item = track,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 6.dp, bottom = 22.dp),
                        )
                    }

                    item(key = "title") {
                        // `.center`: the title at 20 px bold, "artist · album" under it.
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = state.currentTitle,
                                style = MorseTextStyles.screenTitle.copy(
                                    fontSize = MorseType.musicTitleSize,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = colors.textPrimary,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.currentSubtitle.isNotEmpty()) {
                                Text(
                                    text = state.currentSubtitle,
                                    style = MorseTextStyles.muted,
                                    color = colors.textSecondary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    item(key = "scrubber") {
                        // `margin:16px 0 2px` above the big scrubber, then elapsed and
                        // remaining at the two ends of one `.meta` line.
                        MorseScrubber(
                            positionMillis = state.positionMillis,
                            durationMillis = state.durationMillis,
                            onSeek = viewModel::seekTo,
                            contentDescription = seekDescription,
                            positionDescription = stringResource(
                                R.string.music_position,
                                state.elapsed,
                                state.total,
                            ),
                            big = true,
                            modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = state.elapsed,
                                style = MorseTextStyles.meta,
                                color = colors.textTertiary,
                            )
                            Text(
                                text = state.remaining,
                                style = MorseTextStyles.meta,
                                color = colors.textTertiary,
                            )
                        }
                    }

                    item(key = "transport") {
                        TransportRow(
                            playing = state.playing,
                            shuffleOn = state.shuffle == ShuffleMode.ON,
                            repeatOn = state.repeat != RepeatMode.OFF,
                            playDescription = if (state.playing) pauseDescription else playDescription,
                            previousDescription = previousDescription,
                            nextDescription = nextDescription,
                            shuffleDescription = "$shuffleDescription, $shuffleState",
                            repeatDescription = "$repeatDescription, $repeatState",
                            onPlay = viewModel::togglePlay,
                            onPrevious = viewModel::previous,
                            onNext = viewModel::next,
                            onShuffle = viewModel::cycleShuffle,
                            onRepeat = viewModel::cycleRepeat,
                        )
                    }

                    item(key = "strip") {
                        // Four cells, one row, hairlines above and below, glyph over a
                        // 10 px label. Save and Liked are the reference's two drawings
                        // of one idea — this track is kept — so both answer one state,
                        // and Liked carries the accent while it is.
                        val liked = state.currentLiked
                        Column {
                            MorseDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(metrics.musicRowActionHeight),
                            ) {
                                StripCell(
                                    iconRes = MorseIcons.heart,
                                    label = if (liked) savedLabel else saveLabel,
                                    description = if (liked) savedLabel else saveLabel,
                                    accent = liked,
                                    onClick = viewModel::toggleLiked,
                                    modifier = Modifier.weight(1f),
                                )
                                StripCell(
                                    iconRes = MorseIcons.share,
                                    label = shareLabel,
                                    description = shareLabel,
                                    accent = false,
                                    onClick = {
                                        scope.launch(Dispatchers.Main.immediate) {
                                            val uris = ShareFiles.prepare(context, listOf(track))
                                            if (uris.isEmpty()) {
                                                Toast.makeText(
                                                    context,
                                                    noShareTarget,
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            } else {
                                                ShareFiles.share(context, uris, appName)
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                StripCell(
                                    iconRes = MorseIcons.heart,
                                    label = likedLabel,
                                    description = "$likedLabel, $likedState",
                                    accent = liked,
                                    onClick = viewModel::toggleLiked,
                                    modifier = Modifier.weight(1f),
                                )
                                StripCell(
                                    iconRes = MorseIcons.list,
                                    label = queueLabel,
                                    description = queueJump,
                                    accent = false,
                                    onClick = {
                                        scope.launch {
                                            if (motion.reduced) {
                                                listState.scrollToItem(QUEUE_HEADER_INDEX)
                                            } else {
                                                listState.animateScrollToItem(QUEUE_HEADER_INDEX)
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            MorseDivider()
                        }
                    }

                    item(key = "queue-header") {
                        SectionHeader(
                            text = queueHeader,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }

                    itemsIndexed(state.rows, key = { _, row -> "queue-${row.item.id}" }) { index, row ->
                        val playing = index == state.index
                        MorseListRow(
                            title = row.title,
                            meta = row.artist,
                            selected = playing,
                            titleColor = if (playing) colors.accent else colors.textPrimary,
                            onClick = { viewModel.select(index) },
                            minHeight = 56.dp,
                            divider = index != state.rows.lastIndex,
                            leading = { QueueTile(playing = playing) },
                            trailing = {
                                Text(
                                    text = row.duration,
                                    style = MorseTextStyles.meta,
                                    color = colors.textTertiary,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * `.scrub.big`, and the five-cell transport under it.
 *
 * `gap:22px;margin:18px 0 20px`, with the play cell grown to the reference's 64 dp
 * accent circle — `background:var(--acc)`, `color:var(--accInk)`, a 26 dp glyph and
 * `box-shadow:0 0 30px color-mix(in srgb,var(--acc) 45%,transparent)`, which the
 * design system already carries as [app.morsecode.core.design.tokens.MorseColorTokens.playButtonGlow].
 *
 * Shuffle and repeat show their state the way the reference's cells cannot: the
 * control that is on carries the accent, and its description says so.
 */
@Composable
private fun TransportRow(
    playing: Boolean,
    shuffleOn: Boolean,
    repeatOn: Boolean,
    playDescription: String,
    previousDescription: String,
    nextDescription: String,
    shuffleDescription: String,
    repeatDescription: String,
    onPlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MorseIconButton(
            iconRes = MorseIcons.shuffle,
            contentDescription = shuffleDescription,
            onClick = onShuffle,
            tint = if (shuffleOn) colors.accent else colors.textSecondary,
        )
        MorseIconButton(
            iconRes = MorseIcons.prev,
            contentDescription = previousDescription,
            onClick = onPrevious,
        )
        Box(
            modifier = Modifier
                .size(metrics.musicPlayButton)
                .shadow(
                    elevation = PLAY_GLOW_ELEVATION,
                    shape = CircleShape,
                    spotColor = colors.playButtonGlow,
                    ambientColor = colors.playButtonGlow,
                )
                .clip(CircleShape)
                .background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            MorseIconButton(
                iconRes = if (playing) MorseIcons.pause else MorseIcons.play,
                contentDescription = playDescription,
                onClick = onPlay,
                tint = colors.onAccent,
                size = metrics.musicPlayButton,
                glyph = metrics.musicPlayGlyph,
                pressedBackground = colors.onAccent.copy(alpha = PRESSED_ON_ACCENT),
            )
        }
        MorseIconButton(
            iconRes = MorseIcons.next,
            contentDescription = nextDescription,
            onClick = onNext,
        )
        MorseIconButton(
            iconRes = MorseIcons.repeat,
            contentDescription = repeatDescription,
            onClick = onRepeat,
            tint = if (repeatOn) colors.accent else colors.textSecondary,
        )
    }
}

/**
 * One cell of the strip under the transport.
 *
 * `flex:1;flex-direction:column;height:56px;gap:4px;border-radius:0;font-size:10px`
 * — a glyph over its label, filling the cell, in the accent colour when the cell's
 * state is on. The description carries the state as well as the name, so "Liked, not
 * liked" is what a screen reader hears instead of a word that means nothing alone.
 */
@Composable
private fun StripCell(
    iconRes: Int,
    label: String,
    description: String,
    accent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val tint = if (accent) colors.accent else colors.textSecondary

    Column(
        modifier = modifier
            .height(metrics.musicRowActionHeight)
            .clickable(onClickLabel = description, onClick = onClick)
            // Named as a control rather than left to its label alone: the
            // description carries the state with it, so "Liked, not liked" is what
            // a screen reader hears instead of a word that means nothing by itself.
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(metrics.iconButtonGlyph),
        )
        Text(
            text = label,
            // font-size:10px on the strip's cells.
            fontSize = 10.sp,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Where the queue's heading sits in the list, for the strip's Queue cell: artwork,
 * title, scrubber, transport, strip, then the heading.
 */
private const val QUEUE_HEADER_INDEX = 5

/** `box-shadow:0 0 30px …` on the play circle. */
private val PLAY_GLOW_ELEVATION = 30.dp

/** `.iconbtn:active` on an accent fill: the ink at 16%. */
private const val PRESSED_ON_ACCENT = 0.16f

/** `.li .ico`: the track's tile, gradient while it is the one being shown. */
@Composable
private fun QueueTile(playing: Boolean) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics

    Box(
        modifier = Modifier
            .size(metrics.fileIconSize)
            .clip(RoundedCornerShape(metrics.fileIconRadius))
            .background(if (playing) colors.accent else colors.raised),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(MorseIcons.music),
            contentDescription = null,
            tint = if (playing) colors.onAccent else colors.textTertiary,
            modifier = Modifier.size(metrics.fileIconGlyph),
        )
    }
}
