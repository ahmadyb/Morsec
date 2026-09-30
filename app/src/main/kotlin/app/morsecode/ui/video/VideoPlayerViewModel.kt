package app.morsecode.ui.video

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsecode.core.design.component.ScrubberMath
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MorseFormatters
import app.morsecode.core.storage.MediaRepository
import app.morsecode.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The video player on screen (§4.7): one clip, and the controls the user has moved.
 *
 * Every field is state a user caused. Nothing here advances on a clock and no timer
 * pretends to be a decoder: this build has no video engine — Media3 is milestone 10,
 * and [app.morsecode.core.model.FeatureArea.MEDIA_PLAYBACK] is gated until then — so
 * the position moves when the user seeks or nudges it, [playing] records which of the
 * two labels the transport buttons wear, and the picture area is a stage with nothing
 * playing on it yet.
 */
public data class VideoPlayerUiState(
    /** The clip the player was opened with, or null once the lookup has answered with nothing. */
    val item: MediaItem? = null,
    /** The clip's file name, as the header prints it. */
    val fileName: String = "",
    /**
     * Resolution, size and length, joined the way the reference joins them
     * ("1080p · 144 MB · 24:12") — and only the parts this device actually reported.
     * A clip whose height nobody read gets no invented "1080p".
     */
    val metadata: String = "",
    val playing: Boolean = false,
    /** Where inside the clip the user has seeked or nudged to, clamped to its length. */
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
    /** "6:48" — the reference's left-hand time, printed from the position. */
    val elapsed: String = "",
    /** "24:12" — the right-hand time is the clip's whole length, not what is left of it. */
    val total: String = "",
    /** The level the user set, kept whether or not it is currently in force. */
    val volume: Float = VideoTransport.DEFAULT_VOLUME,
    /** The level being heard: nothing while muted. This is what the bars and speaker show. */
    val effectiveVolume: Float = VideoTransport.DEFAULT_VOLUME,
    val muted: Boolean = false,
    /** How many of the ten bars are lit. */
    val levelOfTen: Int = 0,
    /** "70%" — the level in force as a percentage, for the control's readout. */
    val volumePercent: String = "",
    /**
     * The level a mute is holding, as a percentage.
     *
     * Formatted here rather than assembled in a click lambda because the sentence that
     * prints it — "Unmuted · 70%" — has to be composed before the click happens, and the
     * level it names is this one: the level the unmute will give back.
     */
    val rememberedVolumePercent: String = "",
    val subtitles: SubtitleState = SubtitleState.UNAVAILABLE,
    val fullscreen: Boolean = false,
    /** True until the clip lookup has answered, so the screen does not flash an empty player. */
    val loading: Boolean = true,
)

/**
 * The video player (master prompt §4.7) as an explicit UI-state controller.
 *
 * The route carries the id of the clip that was tapped and this asks the storage layer
 * for that one clip — a video player has no queue, so there is no second list to keep
 * in step with the one Files was showing.
 *
 * What the controls do is decided by [VideoTransportRules], not here: this holds the
 * current [VideoTransport], writes it to [SavedStateHandle] as it moves, and formats it
 * for the screen. A player restored after process death therefore comes back on the
 * same clip at the same position, playing or paused as it was, at the level it was
 * hearing, muted or not, with its subtitles and its fullscreen state as they were left.
 *
 * A restored position is clamped to the clip's real length on the way in, which is what
 * the reference's own screen entry does (`S.video.pos=Math.min(S.video.pos,S.video.dur)`):
 * a position saved against one edit of a file is not a position inside another.
 */
@HiltViewModel
public class VideoPlayerViewModel @Inject constructor(
    private val media: MediaRepository,
    private val formatters: MorseFormatters,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val requestedId: String = Routes.decodeVideoArg(savedStateHandle.get<String>(Routes.VIDEO_ARG))

    private val item = MutableStateFlow<MediaItem?>(null)

    /** False until the lookup has answered, whether it found the clip or not. */
    private val resolved = MutableStateFlow(false)

    private val transport = MutableStateFlow(
        VideoTransport(
            playing = savedStateHandle.get<Boolean>(SAVED_PLAYING) ?: false,
            positionMillis = savedStateHandle.get<Long>(SAVED_POSITION) ?: 0L,
            volume = savedStateHandle.get<Float>(SAVED_VOLUME) ?: VideoTransport.DEFAULT_VOLUME,
            muted = savedStateHandle.get<Boolean>(SAVED_MUTED) ?: false,
            rememberedVolume = savedStateHandle.get<Float>(SAVED_REMEMBERED)
                ?: VideoTransport.DEFAULT_VOLUME,
            subtitles = savedEnum(SAVED_SUBTITLES, SubtitleState.UNAVAILABLE),
            fullscreen = savedStateHandle.get<Boolean>(SAVED_FULLSCREEN) ?: false,
        ),
    )

    public val state: StateFlow<VideoPlayerUiState> = combine(
        item,
        resolved,
        transport,
    ) { video, lookedUp, controls ->
        val duration = video?.durationMillis?.coerceAtLeast(0L) ?: 0L
        // Read from the clip and not from the combined state: a restored position can be
        // longer than the clip it was saved against, and clamping it is the point.
        val position = ScrubberMath.clamp(controls.positionMillis, duration)
        val parts = buildList {
            resolutionOf(video)?.let(::add)
            if (video != null && video.sizeBytes > 0L) add(formatters.bytes(video.sizeBytes))
            if (duration > 0L) add(formatters.durationMillis(duration))
        }
        VideoPlayerUiState(
            item = video,
            fileName = video?.displayName.orEmpty(),
            metadata = parts.joinToString(METADATA_SEPARATOR),
            playing = controls.playing,
            positionMillis = position,
            durationMillis = duration,
            elapsed = formatters.durationMillis(position),
            total = formatters.durationMillis(duration),
            volume = controls.volume,
            effectiveVolume = controls.effectiveVolume,
            muted = controls.muted,
            levelOfTen = controls.levelOfTen,
            volumePercent = formatters.percent(controls.effectiveVolume),
            rememberedVolumePercent = formatters.percent(controls.rememberedVolume),
            subtitles = controls.subtitles,
            fullscreen = controls.fullscreen,
            loading = !lookedUp,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = VideoPlayerUiState(
            playing = transport.value.playing,
            positionMillis = transport.value.positionMillis,
            volume = transport.value.volume,
            effectiveVolume = transport.value.effectiveVolume,
            muted = transport.value.muted,
            levelOfTen = transport.value.levelOfTen,
            subtitles = transport.value.subtitles,
            fullscreen = transport.value.fullscreen,
        ),
    )

    init {
        viewModelScope.launch {
            // A clip that is not a video — or is not on this device any more — leaves the
            // player with nothing to show, and the screen says so instead of inventing a file.
            item.value = media.itemById(requestedId)?.takeIf { it.isVideo }
            resolved.value = true
        }
    }

    /** Play or pause: which of the two it is, is state both transport buttons read back. */
    public fun togglePlay() = mutate { VideoTransportRules.togglePlay(it) }

    /** Seek, from the scrubber's finger or from anything else that knows a position. */
    public fun seekTo(positionMillis: Long) = mutate {
        VideoTransportRules.seek(it, positionMillis, currentDuration())
    }

    /** Ten seconds back, stopping at the start. */
    public fun skipBackward() = mutate {
        VideoTransportRules.skip(it, -VideoTransport.SKIP_MILLIS, currentDuration())
    }

    /** Ten seconds forward, stopping at the end. */
    public fun skipForward() = mutate {
        VideoTransportRules.skip(it, VideoTransport.SKIP_MILLIS, currentDuration())
    }

    /** Mute, or give back the level the mute stored. */
    public fun toggleMute() = mutate { VideoTransportRules.toggleMute(it) }

    /** Set the level from a bar, which also ends a mute when the level is above nothing. */
    public fun setVolumeLevel(levelOfTen: Int) = mutate {
        VideoTransportRules.setVolumeLevel(it, levelOfTen)
    }

    /** Subtitles: off and on trade places, and a file with no track has no next answer. */
    public fun cycleSubtitles() = mutate { VideoTransportRules.cycleSubtitles(it) }

    /** The player's own fullscreen state, which the header gives its height to. */
    public fun toggleFullscreen() = mutate { VideoTransportRules.toggleFullscreen(it) }

    /**
     * One place that moves the transport, so one place persists it.
     *
     * Named for what it does rather than `apply`: a member called `apply` taking a
     * lambda reads like the standard library's scope function and is not one.
     */
    private fun mutate(transition: (VideoTransport) -> VideoTransport) {
        transport.update(transition)
        persist()
    }

    /** How long the clip is, read from the item rather than from the combined state. */
    private fun currentDuration(): Long = item.value?.durationMillis?.coerceAtLeast(0L) ?: 0L

    /**
     * The clip's height as the resolution word the reference prints.
     *
     * Nothing is guessed: a clip whose height this device never reported gets no
     * resolution in its metadata line rather than a plausible-sounding one.
     */
    private fun resolutionOf(video: MediaItem?): String? {
        val height = video?.heightPixels ?: return null
        return if (height > 0) "${height}p" else null
    }

    private fun persist() {
        val controls = transport.value
        savedStateHandle[SAVED_PLAYING] = controls.playing
        savedStateHandle[SAVED_POSITION] = controls.positionMillis
        savedStateHandle[SAVED_VOLUME] = controls.volume
        savedStateHandle[SAVED_MUTED] = controls.muted
        savedStateHandle[SAVED_REMEMBERED] = controls.rememberedVolume
        savedStateHandle[SAVED_SUBTITLES] = controls.subtitles.name
        savedStateHandle[SAVED_FULLSCREEN] = controls.fullscreen
    }

    private inline fun <reified T : Enum<T>> savedEnum(key: String, default: T): T {
        val name = savedStateHandle.get<String>(key) ?: return default
        return enumValues<T>().firstOrNull { it.name == name } ?: default
    }

    public companion object {
        public const val SAVED_PLAYING: String = "app.morsecode.video.playing"
        public const val SAVED_POSITION: String = "app.morsecode.video.position"
        public const val SAVED_VOLUME: String = "app.morsecode.video.volume"
        public const val SAVED_MUTED: String = "app.morsecode.video.muted"
        public const val SAVED_REMEMBERED: String = "app.morsecode.video.rememberedVolume"
        public const val SAVED_SUBTITLES: String = "app.morsecode.video.subtitles"
        public const val SAVED_FULLSCREEN: String = "app.morsecode.video.fullscreen"

        /** The reference's metadata line joins its three parts with a middot. */
        private const val METADATA_SEPARATOR = " · "
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
