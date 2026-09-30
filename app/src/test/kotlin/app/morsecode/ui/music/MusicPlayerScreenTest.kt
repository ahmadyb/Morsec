package app.morsecode.ui.music

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import app.morsecode.R
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MediaKind
import app.morsecode.core.model.MorseFormatters
import app.morsecode.core.model.SortDirection
import app.morsecode.core.model.SortKey
import app.morsecode.core.model.SortOrder
import app.morsecode.navigation.MorseDestination
import app.morsecode.navigation.Routes
import app.morsecode.ui.FakeMediaRepository
import app.morsecode.ui.TestLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The music player, rendered for real on the JVM (§4.6).
 *
 * The view model is built over a fake storage layer holding the reference's own nine
 * tracks and the id of the one that was "tapped", so what is asserted is the screen a
 * user would see: that track's title and its "artist · album" line over the artwork, a
 * queue that counts all nine, a transport that really moves through them, a scrubber
 * that seeks when it is dragged, four strip cells that each do something, a like that
 * belongs to one track, and every switch and position coming back after restoration.
 *
 * There is no clock in the player and none is faked here: the position moves because a
 * test moved it, and a track ends because a test said it ended.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi", application = Application::class)
class MusicPlayerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var owner: TestLifecycleOwner
    private lateinit var viewModel: MusicPlayerViewModel
    private var backPresses = 0
    private var navigations = emptyList<MorseDestination>()

    /**
     * The reference's nine tracks, in the order its Music tab lists them, with the
     * lengths it prints. Only the second carries an album tag: "Harbour Lights ·
     * Midnight Sessions" is the one "artist · album" line the reference shows, and the
     * rest exercise the line with only an artist in it.
     */
    private val tracks = listOf(
        track("Midnight Drive", "The Wanderers", null, 222_000L),
        track("Ocean Eyes (Live)", "Harbour Lights", "Midnight Sessions", 248_000L),
        track("Neon Rain", "Kite & Co.", null, 175_000L),
        track("Paper Boats", "Nadia Qureshi", null, 195_000L),
        track("Slow Signal", "Bellwether", null, 302_000L),
        track("Dust & Gold", "Amara Diallo", null, 208_000L),
        track("Late Transmission", "The Wanderers", null, 281_000L),
        track("Harmattan", "Tunde Olaniyi", null, 186_000L),
        track("Low Orbit", "Bellwether", null, 374_000L),
    )

    @Test
    fun `the tapped track is the one shown, over a queue of all nine`() {
        showPlayer(opened = tracks[1])

        assertShowing(1)
        composeTestRule.onNodeWithText("Harbour Lights · Midnight Sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText(quantity(R.plurals.music_queue_header, 9, 9)).assertIsDisplayed()
    }

    @Test
    fun `a track with no album tag shows its artist alone`() {
        showPlayer(opened = tracks[0])

        // The artist is on screen twice — in the title block and in that track's own
        // queue row — so what this test is about is what is *not* there: no album tag
        // means no separator and nothing invented after it.
        assertTrue(composeTestRule.onAllNodes(hasText("The Wanderers")).fetchSemanticsNodes().isNotEmpty())
        composeTestRule.onNodeWithText("The Wanderers · ", substring = true).assertDoesNotExist()
    }

    @Test
    fun `play and pause are one control that says which of them it is`() {
        showPlayer(opened = tracks[1])

        composeTestRule.onNodeWithContentDescription(string(R.string.music_play)).performClick()
        settle()
        composeTestRule.onNodeWithContentDescription(string(R.string.music_pause)).assertIsDisplayed()
        assertTrue(viewModel.state.value.playing)

        composeTestRule.onNodeWithContentDescription(string(R.string.music_pause)).performClick()
        settle()
        composeTestRule.onNodeWithContentDescription(string(R.string.music_play)).assertIsDisplayed()
        assertFalse(viewModel.state.value.playing)
    }

    @Test
    fun `next and previous walk the queue and wrap at both ends`() {
        showPlayer(opened = tracks[1])

        click(R.string.music_next)
        assertShowing(2)

        click(R.string.music_previous)
        assertShowing(1)

        // The queue is a loop, not a dead end at either side.
        click(R.string.music_previous)
        assertShowing(0)
        click(R.string.music_previous)
        assertShowing(tracks.lastIndex)
        click(R.string.music_next)
        assertShowing(0)
    }

    @Test
    fun `dragging the scrubber seeks, and cannot leave the track`() {
        showPlayer(opened = tracks[1])
        val duration = tracks[1].durationMillis

        composeTestRule.onNodeWithContentDescription(string(R.string.music_seek))
            .performTouchInput { swipeLeft() }
        settle()

        val afterLeft = viewModel.state.value.positionMillis
        assertTrue("a drag left must move the position, was $afterLeft", afterLeft > 0L)
        assertTrue("a drag left must land before the middle, was $afterLeft", afterLeft < duration / 2)
        composeTestRule.onNodeWithText(elapsed(afterLeft)).assertIsDisplayed()
        composeTestRule.onNodeWithText("-${elapsed(duration - afterLeft)}").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(string(R.string.music_seek))
            .performTouchInput { swipeRight() }
        settle()
        assertTrue(
            "a drag right must end past the middle, was ${viewModel.state.value.positionMillis}",
            viewModel.state.value.positionMillis > duration / 2,
        )
    }

    @Test
    fun `a seek is clamped to the track, whatever it is asked for`() {
        showPlayer(opened = tracks[1])
        val duration = tracks[1].durationMillis

        viewModel.seekTo(-50_000L)
        settle()
        assertEquals(0L, viewModel.state.value.positionMillis)
        composeTestRule.onNodeWithText("0:00").assertIsDisplayed()
        composeTestRule.onNodeWithText("-4:08").assertIsDisplayed()

        viewModel.seekTo(duration * 4)
        settle()
        assertEquals(duration, viewModel.state.value.positionMillis)
        // No queue row is printed with a minus in front of it, so these two are the
        // scrubber's own labels and nothing else.
        composeTestRule.onNodeWithText("-0:00").assertIsDisplayed()
        assertEquals("4:08", viewModel.state.value.elapsed)
    }

    @Test
    fun `shuffle says it is on, and every next it gives is another track in the queue`() {
        showPlayer(opened = tracks[1])
        val shuffle = string(R.string.music_shuffle)

        composeTestRule.onNodeWithContentDescription("$shuffle, ${string(R.string.music_shuffle_off)}")
            .performClick()
        settle()
        composeTestRule.onNodeWithContentDescription("$shuffle, ${string(R.string.music_shuffle_on)}")
            .assertIsDisplayed()
        assertEquals(ShuffleMode.ON, viewModel.state.value.shuffle)

        val titles = tracks.mapNotNull { it.title }.toSet()
        repeat(40) {
            val before = viewModel.state.value.index
            viewModel.next()
            settle()
            val after = viewModel.state.value.index
            assertTrue("shuffle left the queue: $after", after in tracks.indices)
            assertTrue("shuffle gave the same track back: $after", after != before)
            assertTrue(viewModel.state.value.currentTitle in titles)
            assertEquals(0L, viewModel.state.value.positionMillis)
        }
    }

    @Test
    fun `repeat cycles its three answers`() {
        showPlayer(opened = tracks[1])
        val repeat = string(R.string.music_repeat)

        fun assertRepeat(@StringRes state: Int) {
            composeTestRule.onNodeWithContentDescription("$repeat, ${string(state)}").assertIsDisplayed()
        }

        assertRepeat(R.string.music_repeat_off)
        composeTestRule.onNodeWithContentDescription("$repeat, ${string(R.string.music_repeat_off)}")
            .performClick()
        settle()
        assertRepeat(R.string.music_repeat_all)
        composeTestRule.onNodeWithContentDescription("$repeat, ${string(R.string.music_repeat_all)}")
            .performClick()
        settle()
        assertRepeat(R.string.music_repeat_one)
        composeTestRule.onNodeWithContentDescription("$repeat, ${string(R.string.music_repeat_one)}")
            .performClick()
        settle()
        assertRepeat(R.string.music_repeat_off)
        assertEquals(RepeatMode.OFF, viewModel.state.value.repeat)
    }

    @Test
    fun `a track that ends is answered the way repeat and shuffle say`() {
        showPlayer(opened = tracks[1])

        // Repeat one: the same track again, from its start, still playing.
        viewModel.cycleRepeat()
        viewModel.cycleRepeat()
        viewModel.seekTo(120_000L)
        settle()
        viewModel.trackEnded()
        settle()
        assertEquals(1, viewModel.state.value.index)
        assertEquals(0L, viewModel.state.value.positionMillis)
        assertTrue(viewModel.state.value.playing)

        // Repeat the queue: two cycles from "repeat one" is "repeat the queue".
        viewModel.cycleRepeat()
        viewModel.cycleRepeat()
        assertEquals(RepeatMode.ALL, viewModel.state.value.repeat)
        viewModel.select(tracks.lastIndex)
        settle()
        viewModel.trackEnded()
        settle()
        assertEquals(0, viewModel.state.value.index)
        assertTrue(viewModel.state.value.playing)

        // Neither: the queue ends, and the player says so by stopping.
        viewModel.cycleRepeat()
        viewModel.cycleRepeat()
        assertEquals(RepeatMode.OFF, viewModel.state.value.repeat)
        assertEquals(ShuffleMode.OFF, viewModel.state.value.shuffle)
        viewModel.select(tracks.lastIndex)
        settle()
        viewModel.trackEnded()
        settle()
        assertEquals(tracks.lastIndex, viewModel.state.value.index)
        assertFalse(viewModel.state.value.playing)
        assertEquals(0L, viewModel.state.value.positionMillis)

        // And in the middle of a queue with repeat off, ending a track moves on.
        viewModel.select(3)
        settle()
        viewModel.trackEnded()
        settle()
        assertEquals(4, viewModel.state.value.index)
    }

    @Test
    fun `saving a track keeps it saved, and only that track`() {
        showPlayer(opened = tracks[1])
        val liked = string(R.string.music_liked)

        composeTestRule.onNodeWithContentDescription(string(R.string.music_save)).performClick()
        settle()
        composeTestRule.onNodeWithContentDescription(string(R.string.music_saved)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("$liked, $liked").assertIsDisplayed()

        // Another track is not liked just because this one is.
        click(R.string.music_next)
        composeTestRule.onNodeWithContentDescription(string(R.string.music_save)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("$liked, ${string(R.string.music_not_liked)}")
            .assertIsDisplayed()

        // And the first one is still liked when the player comes back to it.
        click(R.string.music_previous)
        composeTestRule.onNodeWithContentDescription(string(R.string.music_saved)).assertIsDisplayed()
    }

    @Test
    fun `sharing the track hands it to the platform`() {
        showPlayer(opened = tracks[1])

        composeTestRule.onNodeWithContentDescription(string(R.string.action_share)).performClick()
        settle()

        val intent = shadowOf(context.applicationContext as Application).nextStartedActivity
        assertNotNull("sharing must hand an intent to the platform", intent)
        // ShareFiles wraps a single file in a chooser, so look through it if it did.
        val shared = intent?.extras?.get(Intent.EXTRA_INTENT) as? Intent ?: intent
        assertEquals(Intent.ACTION_SEND, shared?.action)
    }

    @Test
    fun `a queue row plays the track it names, from its start`() {
        showPlayer(opened = tracks[1])
        viewModel.seekTo(90_000L)
        settle()

        // "Midnight Drive" is on screen only as its queue row — it is not the track
        // being shown — so this click cannot mean anything else.
        composeTestRule.onAllNodes(hasText("Midnight Drive") and hasClickAction()).assertCountEquals(1)
        composeTestRule.onAllNodes(hasText("Midnight Drive") and hasClickAction())[0].performClick()
        settle()

        assertShowing(0)
        assertEquals(0, viewModel.state.value.index)
        assertEquals(0L, viewModel.state.value.positionMillis)
    }

    @Test
    fun `the track being shown is exactly one row in the queue, and it is the selected one`() {
        // The third track, because the queue below the fold is not composed yet and a
        // row nobody has scrolled to cannot be asserted on.
        showPlayer(opened = tracks[2])

        composeTestRule.onAllNodes(hasText("Neon Rain") and hasClickAction()).assertCountEquals(1)
        assertEquals(2, viewModel.state.value.index)
        assertEquals(tracks[2], viewModel.state.value.current)
        assertShowing(2)
    }

    @Test
    fun `a restored player comes back on the same track, at the same place, with the same switches`() {
        showPlayer(
            opened = tracks[0],
            restored = buildMap<String, Any?> {
                put(MusicPlayerViewModel.SAVED_INDEX, 4)
                put(MusicPlayerViewModel.SAVED_PLAYING, true)
                put(MusicPlayerViewModel.SAVED_POSITION, 96_000L)
                put(MusicPlayerViewModel.SAVED_SHUFFLE, ShuffleMode.ON.name)
                put(MusicPlayerViewModel.SAVED_REPEAT, RepeatMode.ONE.name)
                put(MusicPlayerViewModel.SAVED_LIKED, arrayListOf(tracks[4].id, tracks[7].id))
            },
        )

        assertTrue(composeTestRule.onAllNodes(hasText("Slow Signal")).fetchSemanticsNodes().isNotEmpty())
        composeTestRule.onNodeWithText("1:36").assertIsDisplayed()
        composeTestRule.onNodeWithText("-3:26").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.music_pause)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(
            "${string(R.string.music_shuffle)}, ${string(R.string.music_shuffle_on)}",
        ).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(
            "${string(R.string.music_repeat)}, ${string(R.string.music_repeat_one)}",
        ).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.music_saved)).assertIsDisplayed()
    }

    @Test
    fun `a restored position longer than its track comes back clamped to it`() {
        showPlayer(
            opened = tracks[0],
            restored = buildMap<String, Any?> {
                put(MusicPlayerViewModel.SAVED_INDEX, 2)
                put(MusicPlayerViewModel.SAVED_POSITION, 999_999L)
            },
        )

        assertEquals(tracks[2].durationMillis, viewModel.state.value.positionMillis)
        assertEquals("2:55", viewModel.state.value.elapsed)
        composeTestRule.onNodeWithText("-0:00").assertIsDisplayed()
    }

    @Test
    fun `every control on the player is named for a screen reader`() {
        showPlayer(opened = tracks[1])

        listOf(
            string(R.string.action_back),
            string(R.string.music_playback_info),
            string(R.string.music_seek),
            string(R.string.music_play),
            string(R.string.music_previous),
            string(R.string.music_next),
            string(R.string.music_save),
            string(R.string.action_share),
            "${string(R.string.music_liked)}, ${string(R.string.music_not_liked)}",
            string(R.string.music_queue_jump),
            // The two switches name themselves and the way round they are.
            "${string(R.string.music_shuffle)}, ${string(R.string.music_shuffle_off)}",
            "${string(R.string.music_repeat)}, ${string(R.string.music_repeat_off)}",
        ).forEach { description ->
            composeTestRule.onNode(hasContentDescription(description), useUnmergedTree = true)
                .assertExists("no control may be unnamed: $description")
        }
    }

    @Test
    fun `the scrubber says where the track is, in words`() {
        showPlayer(opened = tracks[1])

        viewModel.seekTo(104_000L)
        settle()

        val scrubber = composeTestRule.onNodeWithContentDescription(string(R.string.music_seek))
        scrubber.assertIsDisplayed()
        assertEquals(
            string(R.string.music_position, "1:44", "4:08"),
            scrubber.fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
    }

    @Test
    fun `the header's third cell says plainly what this build cannot do`() {
        showPlayer(opened = tracks[1])

        composeTestRule.onNodeWithContentDescription(string(R.string.music_playback_info)).performClick()
        settle()

        composeTestRule.onNodeWithText(string(R.string.gated_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.gated_area_playback), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `the back cell leaves the player`() {
        showPlayer(opened = tracks[1])

        composeTestRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        settle()

        assertEquals(1, backPresses)
    }

    @Test
    fun `the bottom nav is still there, still on Files, and still works`() {
        showPlayer(opened = tracks[1])

        MorseDestination.ordered.forEach { destination ->
            composeTestRule.onNodeWithContentDescription(string(destination.labelRes)).assertExists()
        }

        composeTestRule.onNodeWithContentDescription(string(MorseDestination.CONNECT.labelRes)).performClick()
        settle()
        assertEquals(listOf(MorseDestination.CONNECT), navigations)
    }

    @Test
    fun `the whole player fits the phone, from the header down to the first queue row`() {
        showPlayer(opened = tracks[1])

        composeTestRule.onNodeWithText(string(R.string.music_now_playing)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Harbour Lights · Midnight Sessions").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.music_seek)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.music_play)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.music_save)).assertIsDisplayed()
        composeTestRule.onNodeWithText(quantity(R.plurals.music_queue_header, 9, 9)).assertIsDisplayed()
        composeTestRule.onAllNodes(hasText("Midnight Drive"))[0].assertIsDisplayed()
    }

    @Test
    fun `a device with no music says so instead of drawing an empty player`() {
        showPlayer(opened = tracks[1], library = emptyList())

        composeTestRule.onNodeWithText(string(R.string.music_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.music_empty_body)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.music_seek)).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(string(R.string.music_play)).assertDoesNotExist()
    }

    @Test
    fun `a track that is no longer on the device starts the queue at its first track`() {
        showPlayer(opened = track("Gone", "Nobody", null, 60_000L), library = tracks)

        assertShowing(0)
    }

    @Test
    fun `a queue of one song is announced as one song`() {
        showPlayer(opened = tracks[3], library = listOf(tracks[3]))

        composeTestRule.onNodeWithText(quantity(R.plurals.music_queue_header, 1, 1)).assertIsDisplayed()
        assertShowing(3)
    }

    private fun showPlayer(
        opened: MediaItem = tracks[1],
        library: List<MediaItem> = tracks,
        restored: Map<String, Any?> = emptyMap(),
    ) {
        backPresses = 0
        navigations = emptyList()
        owner = TestLifecycleOwner()
        val handle = SavedStateHandle(
            buildMap<String, Any?> {
                put(Routes.MUSIC_ARG, opened.id)
                put(Routes.MUSIC_SORT_ARG, Routes.sortToken(SortOrder(SortKey.NAME, SortDirection.ASC)))
                putAll(restored)
            },
        )
        viewModel = MusicPlayerViewModel(
            media = FakeMediaRepository(audio = library),
            formatters = MorseFormatters.forDefaultLocale(),
            savedStateHandle = handle,
        )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MorseTheme(reducedMotion = true) {
                    MusicPlayerScreen(
                        onBack = { backPresses += 1 },
                        onNavigate = { navigations = navigations + it },
                        viewModel = viewModel,
                    )
                }
            }
        }
        settle()
    }

    private fun click(@StringRes description: Int) {
        composeTestRule.onNodeWithContentDescription(string(description)).performClick()
        settle()
    }

    /**
     * Asserts the track at [index] is the one being shown.
     *
     * Its name can appear twice — once as the title over the artwork and once as its
     * own queue row — so the assertion is "at least once", and the identity of the
     * track is pinned by the time left on the right of the scrubber: every track here
     * is a different length, and no queue row is printed with a minus in front of it.
     */
    private fun assertShowing(index: Int) {
        val track = tracks[index]
        val shown = composeTestRule.onAllNodes(hasText(track.title!!)).fetchSemanticsNodes()
        assertTrue("${track.title} is not on screen", shown.isNotEmpty())
        composeTestRule.onNodeWithText("-${elapsed(track.durationMillis)}").assertIsDisplayed()
    }

    /** "4:08" for 248 000 ms, printed the way the player prints it. */
    private fun elapsed(millis: Long): String = MorseFormatters.forDefaultLocale().durationMillis(millis)

    private fun track(
        title: String,
        artist: String?,
        album: String?,
        durationMillis: Long,
    ) = MediaItem(
        id = "audio:$title",
        displayName = "$title.mp3",
        kind = MediaKind.AUDIO,
        mimeType = "audio/mpeg",
        sizeBytes = 8_400_000L,
        dateModifiedEpochMillis = 1_760_000_000_000L,
        uriString = "content://media/external/audio/media/${title.hashCode()}",
        durationMillis = durationMillis,
        title = title,
        artist = artist,
        album = album,
    )

    private fun string(@StringRes id: Int, vararg args: Any?) = context.getString(id, *args)

    private fun quantity(@PluralsRes id: Int, count: Int, vararg args: Any?) =
        context.resources.getQuantityString(id, count, *args)

    private fun settle() {
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
    }
}
