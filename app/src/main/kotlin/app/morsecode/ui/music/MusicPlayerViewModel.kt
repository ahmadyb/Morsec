package app.morsecode.ui.music

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morsecode.core.design.component.ScrubberMath
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MorseFormatters
import app.morsecode.core.model.applySortOrder
import app.morsecode.core.storage.MediaRepository
import app.morsecode.navigation.Routes
import app.morsecode.ui.viewer.ViewerViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

/** How previous and next walk the queue. */
public enum class ShuffleMode {
    OFF,
    ON,
}

/** What the player does when a track ends. */
public enum class RepeatMode {
    OFF,
    ALL,
    ONE,
}

/**
 * The music player on screen (§4.6): one queue, one track being shown, and the
 * transport's own state.
 *
 * Everything here is state the user caused. Nothing advances on a clock: this build
 * has no audio engine (that is Media3, milestone 10, and [app.morsecode.core.model.FeatureArea.MEDIA_PLAYBACK]
 * is gated until then), so the position moves only when the user seeks, and a track
 * ends only when [MusicPlayerViewModel.trackEnded] says so.
 */
public data class MusicPlayerUiState(
    /** Every track the queue can play, in the order Files was showing them. */
    val queue: List<MediaItem> = emptyList(),
    /** The track being shown. */
    val index: Int = 0,
    val playing: Boolean = false,
    /** Where inside the track the user has seeked to. */
    val positionMillis: Long = 0L,
    val shuffle: ShuffleMode = ShuffleMode.OFF,
    val repeat: RepeatMode = RepeatMode.OFF,
    /** Tracks the user has liked, restored with the player. */
    val likedIds: Set<String> = emptySet(),
    /** True until the audio query has answered. */
    val loading: Boolean = true,
    /** "1:44", on the left of the scrubber. */
    val elapsed: String = "",
    /** "-2:24", on the right: the reference writes remaining time with a minus. */
    val remaining: String = "",
    /** "4:08": how long the track being shown is, formatted. */
    val total: String = "",
    /** The queue as the player draws it, in the same order as [queue]. */
    val rows: List<QueueRow> = emptyList(),
) {
    /** The track being shown, or null while the queue is empty. */
    public val current: MediaItem? get() = queue.getOrNull(index)

    /** How long the track being shown is; 0 when its length was never tagged. */
    public val durationMillis: Long get() = current?.durationMillis?.coerceAtLeast(0L) ?: 0L

    public val count: Int get() = queue.size
    public val isEmpty: Boolean get() = !loading && queue.isEmpty()

    /** The title as the reference shows it: the tag, or the file name when there is none. */
    public val currentTitle: String get() = current?.title?.takeIf { it.isNotBlank() } ?: current?.displayName ?: ""

    /** "Harbour Lights · Midnight Sessions", without the parts that were never tagged. */
    public val currentSubtitle: String
        get() = listOfNotNull(
            current?.artist?.takeIf { it.isNotBlank() },
            current?.album?.takeIf { it.isNotBlank() },
        ).joinToString(ViewerViewModel.META_SEPARATOR)

    public val currentLiked: Boolean get() = current?.let { it.id in likedIds } ?: false
}

/**
 * One row of the player's queue, already formatted: what the reference draws is a
 * title, its artist and the track's length, and a row that had to ask a formatter for
 * its length during composition would ask again on every scroll.
 */
public data class QueueRow(
    public val item: MediaItem,
    public val title: String,
    /** The tag's artist, or null when the track was never tagged with one. */
    public val artist: String?,
    /** "4:08". */
    public val duration: String,
)

/** The transport's own state, which moves together and is restored together. */
private data class Transport(
    val playing: Boolean = false,
    val positionMillis: Long = 0L,
    val shuffle: ShuffleMode = ShuffleMode.OFF,
    val repeat: RepeatMode = RepeatMode.OFF,
)

/**
 * The music player (master prompt §4.6) as an explicit UI-state controller.
 *
 * The queue is the same list Files was showing, in the same order: the route carries
 * the tapped track's id *and* the sort token, so "Up next · 9 songs" here counts the
 * songs the user was looking at rather than a second, differently ordered query.
 *
 * Which track is showing, whether it is playing, where it has been seeked to, and the
 * shuffle, repeat and like states are all written to [SavedStateHandle] as they move,
 * so a player restored after process death comes back mid-queue on the same track.
 *
 * There is no clock in here and nothing pretends to be one. [trackEnded] is the
 * transition the real engine will hand to in milestone 10; it exists now because
 * "repeat this track", "repeat the queue" and "stop at the end" are three different
 * answers, and the repeat control would otherwise be a switch that switches nothing.
 */
@HiltViewModel
public class MusicPlayerViewModel @Inject constructor(
    private val media: MediaRepository,
    private val formatters: MorseFormatters,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val requestedId: String = Routes.decodeMusicArg(savedStateHandle.get<String>(Routes.MUSIC_ARG))
    private val sort = Routes.parseSortToken(savedStateHandle.get<String>(Routes.MUSIC_SORT_ARG))

    private val queue = MutableStateFlow<List<MediaItem>?>(null)
    private val index = MutableStateFlow(savedStateHandle.get<Int>(SAVED_INDEX) ?: 0)
    private val transport = MutableStateFlow(
        Transport(
            playing = savedStateHandle.get<Boolean>(SAVED_PLAYING) ?: false,
            positionMillis = savedStateHandle.get<Long>(SAVED_POSITION) ?: 0L,
            shuffle = savedEnum(SAVED_SHUFFLE, ShuffleMode.OFF),
            repeat = savedEnum(SAVED_REPEAT, RepeatMode.OFF),
        ),
    )
    private val liked = MutableStateFlow(
        savedStateHandle.get<ArrayList<String>>(SAVED_LIKED)?.toSet().orEmpty(),
    )

    /** False until the first queue has been matched against the track the player was opened with. */
    private var resolved = false

    public val state: StateFlow<MusicPlayerUiState> = combine(
        queue,
        index,
        transport,
        liked,
    ) { list, shown, controls, likedIds ->
        val track = list?.getOrNull(shown)
        val duration = track?.durationMillis?.coerceAtLeast(0L) ?: 0L
        val position = ScrubberMath.clamp(controls.positionMillis, duration)
        MusicPlayerUiState(
            queue = list.orEmpty(),
            index = shown,
            playing = controls.playing,
            positionMillis = position,
            shuffle = controls.shuffle,
            repeat = controls.repeat,
            likedIds = likedIds,
            loading = list == null,
            elapsed = formatters.durationMillis(position),
            remaining = formatters.remaining(duration / MILLIS_PER_SECOND, position / MILLIS_PER_SECOND),
            total = formatters.durationMillis(duration),
            rows = list.orEmpty().map { track ->
                QueueRow(
                    item = track,
                    title = track.title?.takeIf { it.isNotBlank() } ?: track.displayName,
                    artist = listOf(track.artist, track.album).firstOrNull { !it.isNullOrBlank() },
                    duration = formatters.durationMillis(track.durationMillis),
                )
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = MusicPlayerUiState(
            playing = transport.value.playing,
            positionMillis = transport.value.positionMillis,
            shuffle = transport.value.shuffle,
            repeat = transport.value.repeat,
            likedIds = liked.value,
        ),
    )

    init {
        viewModelScope.launch {
            media.observeAudio()
                .map { list -> list.applySortOrder(sort) { item -> item } }
                .collect { list -> publish(list) }
        }
    }

    /** Play or pause. Which of the two it is, is state the transport buttons read back. */
    public fun togglePlay() {
        transport.update { it.copy(playing = !it.playing) }
        persist()
    }

    /** The next track in the queue, or another one when shuffle is on. */
    public fun next() = step(1)

    /** The previous track in the queue, or another one when shuffle is on. */
    public fun previous() = step(-1)

    /**
     * Shows the track the user picked in the queue, from its start.
     *
     * It deliberately does not start playing: the transport keeps whatever the user
     * last asked of it, and the app does not decide to make noise on their behalf.
     */
    public fun select(index: Int) {
        val list = queue.value ?: return
        if (index !in list.indices || index == this.index.value) return
        this.index.value = index
        transport.update { it.copy(positionMillis = 0L) }
        persist()
    }

    /** Seeks inside the track being shown: never before the start, never past the end. */
    public fun seekTo(positionMillis: Long) {
        val duration = currentDuration()
        transport.update { it.copy(positionMillis = ScrubberMath.clamp(positionMillis, duration)) }
        persist()
    }

    /** How long the track being shown is, read from the queue rather than from the combined state. */
    private fun currentDuration(): Long =
        queue.value?.getOrNull(index.value)?.durationMillis?.coerceAtLeast(0L) ?: 0L

    /** Shuffle is a switch, not a cycle: on, or off. */
    public fun cycleShuffle() {
        transport.update {
            it.copy(shuffle = if (it.shuffle == ShuffleMode.ON) ShuffleMode.OFF else ShuffleMode.ON)
        }
        persist()
    }

    /** Repeat cycles the three answers a player can give at the end of a track. */
    public fun cycleRepeat() {
        transport.update {
            it.copy(
                repeat = when (it.repeat) {
                    RepeatMode.OFF -> RepeatMode.ALL
                    RepeatMode.ALL -> RepeatMode.ONE
                    RepeatMode.ONE -> RepeatMode.OFF
                },
            )
        }
        persist()
    }

    /**
     * Likes or unlikes the track being shown.
     *
     * The reference draws both a Save cell and a Liked cell over one idea — this track
     * is kept — and the master prompt has no separate favourites store, so one state
     * answers both and is restored with the player. Persisting likes across launches
     * belongs with the milestone that owns saved tracks, not here.
     */
    public fun toggleLiked() {
        val id = state.value.current?.id ?: return
        liked.update { if (id in it) it - id else it + id }
        savedStateHandle[SAVED_LIKED] = ArrayList(liked.value)
    }

    /**
     * The track ended.
     *
     * Nothing in this build runs a clock that could end one — that is Media3, in
     * milestone 10 — but the answer the player gives is written down and tested,
     * because the repeat control has to mean something the moment it is pressed.
     */
    public fun trackEnded() {
        val list = queue.value ?: return
        if (list.isEmpty()) return
        val controls = transport.value
        when {
            controls.repeat == RepeatMode.ONE -> restart()
            controls.repeat == RepeatMode.ALL -> step(1)
            controls.shuffle == ShuffleMode.ON -> step(1)
            index.value < list.lastIndex -> step(1)
            else -> transport.update { it.copy(playing = false, positionMillis = 0L) }
        }
        persist()
    }

    private fun step(direction: Int) {
        val list = queue.value ?: return
        if (list.isEmpty()) return
        val current = index.value
        val next = when {
            list.size == 1 -> current
            transport.value.shuffle == ShuffleMode.ON -> otherThan(list.size, current)
            // Not Math.floorMod: that is API 24 and this app runs on 23.
            else -> ((current + direction) % list.size + list.size) % list.size
        }
        index.value = next
        transport.update { it.copy(positionMillis = 0L) }
        persist()
    }

    /**
     * Some other track, which is what shuffle means until the real player keeps the
     * shuffled order and its history (milestone 10). It is never the track already
     * being shown, and it is never outside the queue.
     */
    private fun otherThan(size: Int, current: Int): Int {
        var next = Random.nextInt(size)
        while (next == current) next = Random.nextInt(size)
        return next
    }

    private fun restart() {
        transport.update { it.copy(positionMillis = 0L, playing = true) }
    }

    private fun publish(list: List<MediaItem>) {
        queue.value = list
        if (!resolved) {
            val restored = savedStateHandle.get<Int>(SAVED_INDEX)
            index.value = when {
                // A restored player comes back to the track it was showing.
                restored != null && restored in list.indices -> restored
                // Otherwise to the one that was tapped, or to the first if it has gone.
                else -> list.indexOfFirst { it.id == requestedId }.takeIf { it >= 0 } ?: 0
            }
            resolved = true
        } else {
            // The library can change under the player: keep the track, never past the end.
            index.value = index.value.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        }
        // A restored position belongs to a track whose length was not known until now.
        transport.update { it.copy(positionMillis = ScrubberMath.clamp(it.positionMillis, currentDuration())) }
    }

    /** Whatever the controls do, the player comes back to it after process death. */
    private fun persist() {
        val controls = transport.value
        savedStateHandle[SAVED_INDEX] = index.value
        savedStateHandle[SAVED_PLAYING] = controls.playing
        savedStateHandle[SAVED_POSITION] = controls.positionMillis
        savedStateHandle[SAVED_SHUFFLE] = controls.shuffle.name
        savedStateHandle[SAVED_REPEAT] = controls.repeat.name
    }

    private inline fun <reified T : Enum<T>> savedEnum(key: String, default: T): T {
        val name = savedStateHandle.get<String>(key) ?: return default
        return enumValues<T>().firstOrNull { it.name == name } ?: default
    }

    public companion object {
        public const val SAVED_INDEX: String = "app.morsecode.music.index"
        public const val SAVED_PLAYING: String = "app.morsecode.music.playing"
        public const val SAVED_POSITION: String = "app.morsecode.music.position"
        public const val SAVED_SHUFFLE: String = "app.morsecode.music.shuffle"
        public const val SAVED_REPEAT: String = "app.morsecode.music.repeat"
        public const val SAVED_LIKED: String = "app.morsecode.music.liked"

        private const val MILLIS_PER_SECOND = 1_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
