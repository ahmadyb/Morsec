package app.morsecode.ui.video

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import app.morsecode.R
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MediaKind
import app.morsecode.core.model.MorseFormatters
import app.morsecode.navigation.Routes
import app.morsecode.ui.FakeMediaRepository
import app.morsecode.ui.TestLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/**
 * The video player, rendered for real on the JVM (§4.7).
 *
 * The view model is built over a fake storage layer holding the reference's own nine
 * clips and the id of the one that was "tapped", so what is asserted is the screen a user
 * would see: that clip's name over its resolution, size and length, the play control in
 * the middle of the stage and the one in the control bar moving together because they are
 * one action in two places, a scrubber that seeks when it is tapped, ±10 second nudges
 * that stop at the ends of the clip, a speaker that mutes and gives the level back, a
 * volume strip that announces itself as a range, subtitles that say plainly when a file
 * has none, fullscreen taking the title block away and giving it back, and the way out
 * of the screen.
 *
 * There is no clock in the player and none is faked here: a position moves because a test
 * moved it, and the only thing that "plays" is the label on two buttons.
 *
 * Dragging the scrubber is deliberately not asserted here. That gesture belongs to the
 * shared component and is asserted there, in isolation, in both its variants — which is
 * what makes it reliable, a synthetic drag inside a full screen being a statement about
 * the injection framework rather than about the player. What this screen owes is that a
 * seek reaches the paper, and that is what the seek tests below check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi", application = Application::class)
class VideoPlayerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var owner: TestLifecycleOwner
    private lateinit var viewModel: VideoPlayerViewModel
    private var backPresses = 0

    /** The reference's nine clips with the lengths and sizes its video list prints. */
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

    @Test
    fun `opening a clip shows its name over its resolution, size and length`() {
        showPlayer()

        composeTestRule.onNodeWithText("clip_07.mp4").assertIsDisplayed()
        composeTestRule.onNodeWithText("1080p · 64 MB · 3:12").assertIsDisplayed()
    }

    @Test
    fun `a clip whose height nobody reported is shown without one`() {
        showPlayer(opened = clips[4])

        composeTestRule.onNodeWithText("clip_05.mp4").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 MB · 0:15").assertIsDisplayed()
        composeTestRule.onNodeWithText("0p · 3 MB · 0:15").assertDoesNotExist()
    }

    @Test
    fun `the times are the position on the left and the whole length on the right`() {
        showPlayer()

        composeTestRule.onNodeWithText("0:00").assertIsDisplayed()
        composeTestRule.onNodeWithText("3:12").assertIsDisplayed()
    }

    @Test
    fun `the two play controls are one action and they move together`() {
        showPlayer()

        // The reference puts `data-act="play"` on both the stage and the control bar.
        composeTestRule.onAllNodesWithContentDescription(string(R.string.video_play)).assertCountEquals(2)

        composeTestRule.onAllNodesWithContentDescription(string(R.string.video_play))[0].performClick()
        settle()

        assertTrue(viewModel.state.value.playing)
        composeTestRule.onAllNodesWithContentDescription(string(R.string.video_pause)).assertCountEquals(2)
        composeTestRule.onAllNodesWithContentDescription(string(R.string.video_play)).assertCountEquals(0)

        // And the other one is the same switch, not a second opinion about it.
        composeTestRule.onAllNodesWithContentDescription(string(R.string.video_pause))[1].performClick()
        settle()

        assertFalse(viewModel.state.value.playing)
        composeTestRule.onAllNodesWithContentDescription(string(R.string.video_play)).assertCountEquals(2)
    }

    @Test
    fun `tapping the scrubber seeks to where the tap landed`() {
        showPlayer()

        composeTestRule.onNodeWithContentDescription(string(R.string.video_seek)).performClick()
        settle()

        // A tap is the middle of the clip, and the left-hand time is the answer.
        assertEquals(96_000L, viewModel.state.value.positionMillis)
        composeTestRule.onNodeWithText("1:36").assertIsDisplayed()
    }

    @Test
    fun `a position the controller is given is a position the screen prints`() {
        showPlayer()

        listOf(0L, 48_000L, 96_000L, 191_000L).forEach { position ->
            viewModel.seekTo(position)
            settle()
            composeTestRule.onNodeWithText(elapsed(position)).assertIsDisplayed()
        }

        // At the very end the two times are the same time, and both are printed: the
        // clip is over rather than one second short of over.
        viewModel.seekTo(192_000L)
        settle()
        composeTestRule.onAllNodesWithText("3:12").assertCountEquals(2)
        assertEquals(192_000L, viewModel.state.value.positionMillis)
    }

    @Test
    fun `the nudge buttons move by ten seconds and the times follow`() {
        showPlayer()
        viewModel.seekTo(96_000L)
        settle()

        composeTestRule.onNodeWithContentDescription(string(R.string.video_skip_forward)).performClick()
        settle()
        assertEquals(106_000L, viewModel.state.value.positionMillis)
        composeTestRule.onNodeWithText("1:46").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(string(R.string.video_skip_back)).performClick()
        settle()
        assertEquals(96_000L, viewModel.state.value.positionMillis)
        composeTestRule.onNodeWithText("1:36").assertIsDisplayed()
    }

    @Test
    fun `the nudge buttons stop at the ends of the clip`() {
        showPlayer()

        composeTestRule.onNodeWithContentDescription(string(R.string.video_skip_back)).performClick()
        settle()
        assertEquals(0L, viewModel.state.value.positionMillis)

        viewModel.seekTo(190_000L)
        settle()
        composeTestRule.onNodeWithContentDescription(string(R.string.video_skip_forward)).performClick()
        settle()
        assertEquals(192_000L, viewModel.state.value.positionMillis)
        composeTestRule.onAllNodesWithText("3:12").assertCountEquals(2)
    }

    @Test
    fun `the speaker mutes, says so, and gives the level back`() {
        showPlayer()
        composeTestRule.onNodeWithText("70%").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(string(R.string.video_mute)).performClick()
        settle()

        assertTrue(viewModel.state.value.muted)
        composeTestRule.onNodeWithContentDescription(string(R.string.video_unmute)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.video_mute)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.video_volume_muted)).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(string(R.string.video_unmute)).performClick()
        settle()

        assertFalse(viewModel.state.value.muted)
        composeTestRule.onNodeWithText("70%").assertIsDisplayed()
        assertEquals("a mute that came back empty would be a lost level", 7, viewModel.state.value.levelOfTen)
    }

    @Test
    fun `the volume strip is a range, and a tap on it sets the level it prints`() {
        showPlayer()

        val strip = composeTestRule.onNodeWithContentDescription(string(R.string.video_volume))
        val range = strip.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(7f, range.current, 0f)
        assertEquals(0f, range.range.start, 0f)
        assertEquals(10f, range.range.endInclusive, 0f)

        strip.performClick()
        settle()

        assertEquals(5, viewModel.state.value.levelOfTen)
        composeTestRule.onNodeWithText("50%").assertIsDisplayed()
    }

    @Test
    fun `subtitles say plainly that this file has none, and the answer is given`() {
        showPlayer()

        composeTestRule.onNodeWithContentDescription(string(R.string.video_subtitles_unavailable))
            .assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(string(R.string.video_subtitles_unavailable))
            .performClick()
        settle()

        // Pressing a control with no track behind it must not silently do nothing.
        assertEquals(SubtitleState.UNAVAILABLE, viewModel.state.value.subtitles)
        assertEquals(string(R.string.video_no_subtitles), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `subtitles are a switch when the file has a track`() {
        showPlayer(restored = mapOf(VideoPlayerViewModel.SAVED_SUBTITLES to SubtitleState.OFF.name))

        composeTestRule.onNodeWithContentDescription(string(R.string.video_subtitles_off)).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(string(R.string.video_subtitles_off)).performClick()
        settle()
        assertEquals(SubtitleState.ON, viewModel.state.value.subtitles)
        composeTestRule.onNodeWithContentDescription(string(R.string.video_subtitles_on)).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(string(R.string.video_subtitles_on)).performClick()
        settle()
        assertEquals(SubtitleState.OFF, viewModel.state.value.subtitles)
    }

    @Test
    fun `fullscreen gives the title block to the picture and gives it back`() {
        showPlayer()
        composeTestRule.onNodeWithText("clip_07.mp4").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(string(R.string.video_fullscreen)).performClick()
        settle()

        assertTrue(viewModel.state.value.fullscreen)
        composeTestRule.onNodeWithText("clip_07.mp4").assertDoesNotExist()
        composeTestRule.onNodeWithText("1080p · 64 MB · 3:12").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(string(R.string.video_exit_fullscreen)).assertIsDisplayed()
        // A player that hides its own way out is a trap, so the way out stays.
        composeTestRule.onNodeWithContentDescription(string(R.string.action_back)).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(string(R.string.video_exit_fullscreen)).performClick()
        settle()

        assertFalse(viewModel.state.value.fullscreen)
        composeTestRule.onNodeWithText("clip_07.mp4").assertIsDisplayed()
    }

    @Test
    fun `there is no overflow button on this player`() {
        showPlayer()

        // §4.7 forbids the button the reference removed, the way §4.5 does for the viewer.
        composeTestRule.onNodeWithContentDescription(string(R.string.action_more)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.action_more)).assertDoesNotExist()
    }

    @Test
    fun `every icon-only action on the screen carries a description`() {
        showPlayer()

        listOf(
            R.string.action_back,
            R.string.video_skip_back,
            R.string.video_skip_forward,
            R.string.video_mute,
            R.string.video_volume,
            R.string.video_subtitles_unavailable,
            R.string.video_fullscreen,
            R.string.video_playback_info,
        ).forEach { description ->
            composeTestRule.onNodeWithContentDescription(string(description)).assertIsDisplayed()
        }
        composeTestRule.onAllNodesWithContentDescription(string(R.string.video_play)).assertCountEquals(2)
    }

    @Test
    fun `back leaves the player`() {
        showPlayer()

        composeTestRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        settle()

        assertEquals(1, backPresses)
    }

    @Test
    fun `a clip that is not on this device shows the empty state, not an empty player`() {
        showPlayer(openedId = "video:gone")

        composeTestRule.onNodeWithText(string(R.string.video_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.video_empty_body)).assertIsDisplayed()
        // No transport for a clip that is not there: a play button that plays nothing is
        // the placeholder this product forbids.
        composeTestRule.onNodeWithContentDescription(string(R.string.video_play)).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(string(R.string.video_seek)).assertDoesNotExist()
        // The way back is still the way back.
        composeTestRule.onNodeWithContentDescription(string(R.string.action_back)).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi", application = Application::class)
    fun `a phone too narrow for the row keeps every control on screen`() {
        showPlayer()

        // At 360 dp the five controls need more width than they have once every icon
        // button reports its 48 dp touch box, so the volume control takes the line below
        // the transport rather than pushing the subtitles button off the screen.
        listOf(
            R.string.video_skip_back,
            R.string.video_skip_forward,
            R.string.video_mute,
            R.string.video_volume,
            R.string.video_subtitles_unavailable,
        ).forEach { description ->
            composeTestRule.onNodeWithContentDescription(string(description)).assertIsDisplayed()
        }
        composeTestRule.onAllNodesWithContentDescription(string(R.string.video_play)).assertCountEquals(2)
        // Nothing is dropped to make room: the readout is still printed beside the bars.
        composeTestRule.onNodeWithText("70%").assertIsDisplayed()
    }

    private fun showPlayer(
        opened: MediaItem = this.opened,
        openedId: String = opened.id,
        library: List<MediaItem> = clips,
        restored: Map<String, Any?> = emptyMap(),
    ) {
        backPresses = 0
        owner = TestLifecycleOwner()
        val handle = SavedStateHandle(
            buildMap<String, Any?> {
                put(Routes.VIDEO_ARG, openedId)
                putAll(restored)
            },
        )
        viewModel = VideoPlayerViewModel(
            media = FakeMediaRepository(videos = library),
            formatters = MorseFormatters.forDefaultLocale(),
            savedStateHandle = handle,
        )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MorseTheme(reducedMotion = true) {
                    VideoPlayerScreen(
                        onBack = { backPresses += 1 },
                        viewModel = viewModel,
                    )
                }
            }
        }
        settle()
    }

    /** "1:36" for 96 000 ms, printed the way the player prints it. */
    private fun elapsed(millis: Long): String = MorseFormatters.forDefaultLocale().durationMillis(millis)

    private fun string(id: Int, vararg args: Any?): String = context.getString(id, *args)

    private fun settle() {
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
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
