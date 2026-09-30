package app.morsecode.ui.video

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MediaKind
import app.morsecode.core.model.MorseFormatters
import app.morsecode.navigation.Routes
import app.morsecode.ui.FakeMediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The video player's controller, asked what it holds and what it writes down (§4.7).
 *
 * Built over a fake storage layer carrying the reference's own nine clips — the names,
 * lengths and sizes its video list prints — and the id of the one that was "tapped", so
 * what is asserted is the state a screen would draw: which clip, what its metadata line
 * says, where inside it the user has got to, and which of play and pause the transport
 * is wearing.
 *
 * Two things this test is careful about. There is no clock: every position below was put
 * there by a call, and nothing in the player advances on its own, so a test that idles
 * the looper and reads the same position twice is reading a player that is honest about
 * not playing. And the state is collected rather than read straight off the flow, because
 * the controller publishes through `viewModelScope` and a combined flow that nobody
 * subscribes to has nothing to say.
 *
 * Restoration is the reason most of these tests exist. A video player is the screen most
 * likely to be killed mid-clip — it is what a user leaves to answer a message — so every
 * control is written down as it moves and comes back as it was left.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi", application = Application::class)
class VideoPlayerViewModelTest {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private var collector: Job? = null

    private lateinit var viewModel: VideoPlayerViewModel
    private lateinit var handle: SavedStateHandle
    private var latest = VideoPlayerUiState()

    /**
     * The reference's nine clips with the lengths and sizes its video list prints.
     *
     * The heights are not the reference's — its phone screen hardcodes "1080p" for every
     * clip because its data is a caption, not a file. Here each clip carries the height a
     * device would have reported, one of them carries none at all, and the metadata line
     * says only what is known.
     */
    private val clips = listOf(
        clip("clip_01.mp4", 48_000L, 12, heightPixels = 1080),
        clip("clip_02.mp4", 177_000L, 88, heightPixels = 720),
        clip("clip_03.mp4", 152_000L, 41, heightPixels = 1080),
        clip("clip_04.mp4", 61_000L, 6, heightPixels = 480),
        clip("clip_05.mp4", 15_000L, 3, heightPixels = 0),
        clip("clip_06.mp4", 104_000L, 22, heightPixels = 720),
        clip("clip_07.mp4", 192_000L, 64, heightPixels = 1080),
        clip("clip_08.mp4", 36_000L, 9, heightPixels = 480),
        clip("clip_09.mp4", 125_000L, 31, heightPixels = 720),
    )

    /** The clip most of these tests open: three minutes twelve, 64 MB, 1080 lines tall. */
    private val opened = clips[6]

    @After
    fun stopCollecting() {
        collector?.cancel()
        scope.cancel()
    }

    @Test
    fun `the clip the route names is the one the player shows`() {
        show()

        assertFalse(latest.loading)
        assertEquals(opened, latest.item)
        assertEquals("clip_07.mp4", latest.fileName)
        assertEquals("1080p · 64 MB · 3:12", latest.metadata)
        assertEquals(192_000L, latest.durationMillis)
        assertEquals("3:12", latest.total)
    }

    @Test
    fun `the player opens at the start, paused, at the level the reference starts at`() {
        show()

        // The reference opens a clip already playing at 28% of its length, because its
        // clock is a simulation with something to show for it. Nothing here advances on
        // its own, so the honest opening is the start of the clip, paused.
        assertFalse(latest.playing)
        assertEquals(0L, latest.positionMillis)
        assertEquals("0:00", latest.elapsed)
        assertEquals(VideoTransport.DEFAULT_VOLUME, latest.volume, 0f)
        assertEquals(7, latest.levelOfTen)
        assertEquals("70%", latest.volumePercent)
        assertFalse(latest.muted)
        assertFalse(latest.fullscreen)
        assertEquals(SubtitleState.UNAVAILABLE, latest.subtitles)
    }

    @Test
    fun `each clip gets its own metadata line, in the reference's own order`() {
        listOf(clips[0], clips[1], clips[4], clips[7]).forEach { video ->
            show(opened = video)
            val expected = listOfNotNull(
                video.heightPixels.takeIf { it > 0 }?.let { "${it}p" },
                MorseFormatters.forDefaultLocale().bytes(video.sizeBytes),
                MorseFormatters.forDefaultLocale().durationMillis(video.durationMillis),
            ).joinToString(" · ")

            assertEquals("${video.displayName}: $expected", expected, latest.metadata)
        }
    }

    @Test
    fun `a clip whose height nobody reported gets no invented resolution`() {
        show(opened = clips[4])

        assertEquals(0, clips[4].heightPixels)
        // Two parts, not three: the resolution is not known, so it is not printed, and
        // nothing plausible-sounding is put in its place.
        assertEquals("3 MB · 0:15", latest.metadata)
        assertFalse(latest.metadata.contains("p ·"))
    }

    @Test
    fun `an id that is not on this device leaves the player with nothing to show`() {
        show(openedId = "content://media/external/video/media/gone")

        assertFalse("the lookup answered, so the screen is not waiting any more", latest.loading)
        assertNull(latest.item)
        assertEquals("", latest.fileName)
        assertEquals("", latest.metadata)
        assertEquals(0L, latest.durationMillis)
        assertEquals("0:00", latest.elapsed)
    }

    @Test
    fun `an id that names a track is not a clip this player will show`() {
        val song = MediaItem(
            id = "audio:ocean-eyes",
            displayName = "Ocean Eyes (Live).mp3",
            kind = MediaKind.AUDIO,
            mimeType = "audio/mpeg",
            sizeBytes = 8_400_000L,
            dateModifiedEpochMillis = 1_760_000_000_000L,
            durationMillis = 248_000L,
        )

        show(openedId = song.id, audio = listOf(song))

        assertFalse(latest.loading)
        assertNull("a track belongs to the music player, not to this one", latest.item)
        assertEquals("", latest.fileName)
    }

    @Test
    fun `a restored position longer than the clip is pulled back to the clip`() {
        show(restored = mapOf(VideoPlayerViewModel.SAVED_POSITION to 999_000L))

        assertEquals(192_000L, latest.positionMillis)
        assertEquals(latest.total, latest.elapsed)
    }

    @Test
    fun `every control comes back as it was left`() {
        show(
            restored = mapOf(
                VideoPlayerViewModel.SAVED_PLAYING to true,
                VideoPlayerViewModel.SAVED_POSITION to 65_000L,
                VideoPlayerViewModel.SAVED_VOLUME to 0.3f,
                VideoPlayerViewModel.SAVED_MUTED to true,
                VideoPlayerViewModel.SAVED_REMEMBERED to 0.9f,
                VideoPlayerViewModel.SAVED_SUBTITLES to SubtitleState.ON.name,
                VideoPlayerViewModel.SAVED_FULLSCREEN to true,
            ),
        )

        assertTrue(latest.playing)
        assertEquals(65_000L, latest.positionMillis)
        assertEquals("1:05", latest.elapsed)
        assertEquals(0.3f, latest.volume, 0f)
        assertTrue(latest.muted)
        assertEquals("a mute is in force, so nothing is being heard", 0f, latest.effectiveVolume, 0f)
        assertEquals(0, latest.levelOfTen)
        assertEquals("0%", latest.volumePercent)
        assertEquals("the level the unmute will give back", "90%", latest.rememberedVolumePercent)
        assertEquals(SubtitleState.ON, latest.subtitles)
        assertTrue(latest.fullscreen)
    }

    @Test
    fun `a subtitle state nobody wrote down is the one this build can honestly report`() {
        show(restored = mapOf(VideoPlayerViewModel.SAVED_SUBTITLES to "NOT_A_STATE"))

        assertEquals(SubtitleState.UNAVAILABLE, latest.subtitles)
    }

    @Test
    fun `moving a control writes it down where restoration reads it`() {
        show()

        viewModel.togglePlay()
        viewModel.seekTo(45_000L)
        viewModel.setVolumeLevel(4)
        viewModel.toggleMute()
        viewModel.toggleFullscreen()
        settle()

        assertEquals(true, handle.get<Boolean>(VideoPlayerViewModel.SAVED_PLAYING))
        assertEquals(45_000L, handle.get<Long>(VideoPlayerViewModel.SAVED_POSITION))
        assertEquals(0.4f, handle.get<Float>(VideoPlayerViewModel.SAVED_VOLUME)!!, 0f)
        assertEquals(true, handle.get<Boolean>(VideoPlayerViewModel.SAVED_MUTED))
        assertEquals(0.4f, handle.get<Float>(VideoPlayerViewModel.SAVED_REMEMBERED)!!, 0f)
        assertEquals(true, handle.get<Boolean>(VideoPlayerViewModel.SAVED_FULLSCREEN))
    }

    @Test
    fun `play and pause are one switch, and both are state the screen reads back`() {
        show()

        viewModel.togglePlay()
        settle()
        assertTrue(latest.playing)

        viewModel.togglePlay()
        settle()
        assertFalse(latest.playing)
        // No clock moved the position while it was "playing".
        assertEquals(0L, latest.positionMillis)
    }

    @Test
    fun `the nudge buttons stop at the ends of the clip`() {
        show()

        viewModel.seekTo(5_000L)
        viewModel.skipBackward()
        settle()
        assertEquals(0L, latest.positionMillis)
        assertEquals("0:00", latest.elapsed)

        viewModel.seekTo(187_000L)
        viewModel.skipForward()
        settle()
        assertEquals(192_000L, latest.positionMillis)
        assertEquals(latest.total, latest.elapsed)
    }

    @Test
    fun `a nudge in the middle moves by ten seconds and the label follows`() {
        show()

        viewModel.seekTo(96_000L)
        viewModel.skipForward()
        settle()
        assertEquals(106_000L, latest.positionMillis)
        assertEquals("1:46", latest.elapsed)

        viewModel.skipBackward()
        viewModel.skipBackward()
        settle()
        assertEquals(86_000L, latest.positionMillis)
        assertEquals("1:26", latest.elapsed)
    }

    @Test
    fun `a seek obeys the same rule as a nudge, at both ends and beyond`() {
        show()

        viewModel.seekTo(-5_000L)
        settle()
        assertEquals(0L, latest.positionMillis)

        viewModel.seekTo(96_000L)
        settle()
        assertEquals(96_000L, latest.positionMillis)
        assertEquals("1:36", latest.elapsed)

        viewModel.seekTo(1_000_000L)
        settle()
        assertEquals(192_000L, latest.positionMillis)
    }

    @Test
    fun `a mute holds the level, and the readout says which level it holds`() {
        show()
        viewModel.setVolumeLevel(4)
        settle()
        assertEquals("40%", latest.volumePercent)
        assertEquals("40%", latest.rememberedVolumePercent)

        viewModel.toggleMute()
        settle()
        assertTrue(latest.muted)
        assertEquals(0f, latest.effectiveVolume, 0f)
        assertEquals(0, latest.levelOfTen)
        assertEquals("the bars show what is being heard", "0%", latest.volumePercent)
        assertEquals("and the mute still holds what it will give back", "40%", latest.rememberedVolumePercent)
        assertEquals("the stored level is untouched, which is what unmute restores", 0.4f, latest.volume, 0f)

        viewModel.toggleMute()
        settle()
        assertFalse(latest.muted)
        assertEquals(4, latest.levelOfTen)
        assertEquals("40%", latest.volumePercent)
    }

    @Test
    fun `raising the level while muted ends the mute`() {
        show()
        viewModel.toggleMute()
        settle()
        assertTrue(latest.muted)

        viewModel.setVolumeLevel(9)
        settle()

        assertFalse(latest.muted)
        assertEquals(9, latest.levelOfTen)
        assertEquals(0.9f, latest.effectiveVolume, 0f)
        assertEquals("90%", latest.volumePercent)
    }

    @Test
    fun `subtitles cycle only when a track exists`() {
        show()
        viewModel.cycleSubtitles()
        settle()
        assertEquals(
            "no track means no next answer, so nothing changed",
            SubtitleState.UNAVAILABLE,
            latest.subtitles,
        )

        show(restored = mapOf(VideoPlayerViewModel.SAVED_SUBTITLES to SubtitleState.OFF.name))
        viewModel.cycleSubtitles()
        settle()
        assertEquals(SubtitleState.ON, latest.subtitles)

        viewModel.cycleSubtitles()
        settle()
        assertEquals(SubtitleState.OFF, latest.subtitles)
    }

    @Test
    fun `fullscreen is state that stays put and is written down`() {
        show()
        assertFalse(latest.fullscreen)

        viewModel.toggleFullscreen()
        settle()
        assertTrue(latest.fullscreen)
        // Reading the same state twice is what recomposition does.
        assertTrue(latest.fullscreen)
        assertEquals(true, handle.get<Boolean>(VideoPlayerViewModel.SAVED_FULLSCREEN))

        viewModel.toggleFullscreen()
        settle()
        assertFalse(latest.fullscreen)
    }

    @Test
    fun `a player restored from what another one wrote down shows the same thing`() {
        show()
        viewModel.seekTo(120_000L)
        viewModel.togglePlay()
        viewModel.setVolumeLevel(2)
        viewModel.toggleMute()
        viewModel.toggleFullscreen()
        settle()

        val written = handle.keys().associateWith { handle.get<Any?>(it) }
        show(restored = written)

        assertEquals(120_000L, latest.positionMillis)
        assertEquals("2:00", latest.elapsed)
        assertTrue(latest.playing)
        assertTrue(latest.muted)
        assertEquals(0, latest.levelOfTen)
        assertEquals("20%", latest.rememberedVolumePercent)
        assertTrue(latest.fullscreen)
    }

    private fun show(
        opened: MediaItem = this.opened,
        openedId: String = opened.id,
        library: List<MediaItem> = clips,
        audio: List<MediaItem> = emptyList(),
        restored: Map<String, Any?> = emptyMap(),
    ) {
        collector?.cancel()
        latest = VideoPlayerUiState()
        handle = SavedStateHandle(
            buildMap<String, Any?> {
                put(Routes.VIDEO_ARG, openedId)
                putAll(restored)
            },
        )
        viewModel = VideoPlayerViewModel(
            media = FakeMediaRepository(videos = library, audio = audio),
            formatters = MorseFormatters.forDefaultLocale(),
            savedStateHandle = handle,
        )
        collector = scope.launch { viewModel.state.collect { latest = it } }
        settle()
    }

    private fun settle() {
        ShadowLooper.idleMainLooper()
        ShadowLooper.idleMainLooper()
    }

    private fun clip(
        name: String,
        durationMillis: Long,
        sizeMb: Int,
        heightPixels: Int,
    ) = MediaItem(
        id = "video:$name",
        displayName = name,
        kind = MediaKind.VIDEO,
        mimeType = "video/mp4",
        sizeBytes = sizeMb * 1_000_000L,
        dateModifiedEpochMillis = 1_760_000_000_000L,
        uriString = "content://media/external/video/media/${name.hashCode()}",
        durationMillis = durationMillis,
        widthPixels = if (heightPixels == 0) 0 else heightPixels * 16 / 9,
        heightPixels = heightPixels,
    )
}
