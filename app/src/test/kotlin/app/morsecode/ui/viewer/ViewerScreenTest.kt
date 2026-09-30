package app.morsecode.ui.viewer

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import app.morsecode.core.storage.DeleteOutcome
import app.morsecode.navigation.Routes
import app.morsecode.ui.FakeMediaRepository
import app.morsecode.ui.awaitStartedActivity
import app.morsecode.ui.TestLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/**
 * The image viewer, rendered for real on the JVM (§4.5).
 *
 * The view model is constructed over a fake storage layer with five photographs and
 * the id of the one that was "tapped", so what is asserted is the screen a user
 * would see: the tapped photograph first, its name and position in the header, a
 * deck that swipes both ways and wraps at both ends, five named actions and nothing
 * else drawn over the image, information that says what the platform reported,
 * deletion that asks first, and the photograph being shown surviving restoration.
 *
 * The theme is composed with reduced motion, which is one of the two ways the deck
 * moves: the other, animated scrolling, is the same code path with an animation in
 * front of it, and this way the assertions do not depend on frame timing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi", application = Application::class)
class ViewerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var owner: TestLifecycleOwner
    private lateinit var repository: FakeMediaRepository
    private lateinit var savedStateHandle: SavedStateHandle
    private var backPresses = 0

    /** Five photographs, in the order the grid was showing them. */
    private val photos = listOf(
        photo(0, "IMG_2041.jpg", 1_024L, 4032, 3024),
        photo(1, "IMG_2042.jpg", 2_048L, 4032, 3024),
        photo(2, "IMG_2043.jpg", 4_096L, 4032, 3024),
        photo(3, "IMG_2044.jpg", 8_192L, 1920, 1080),
        photo(4, "IMG_2045.jpg", 16_384L, 1920, 1080),
    )

    @Test
    fun `the tapped photograph is the one shown first`() {
        showViewer(opened = photos[2])

        assertShowing(2)
    }

    @Test
    fun `a swipe forward shows the next photograph and says so`() {
        showViewer(opened = photos[2])

        swipeDeck(forward = true)

        assertShowing(3)
        composeTestRule.onNodeWithText(photos[2].displayName).assertDoesNotExist()
    }

    @Test
    fun `a swipe backward shows the previous photograph`() {
        showViewer(opened = photos[2])

        swipeDeck(forward = false)

        assertShowing(1)
    }

    @Test
    fun `the deck wraps at both ends, as the reference does`() {
        showViewer(opened = photos.first())

        swipeDeck(forward = false)
        assertShowing(photos.lastIndex)

        swipeDeck(forward = true)
        assertShowing(0)
    }

    @Test
    fun `back leaves the viewer`() {
        showViewer()

        composeTestRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        settle()

        assertEquals(1, backPresses)
    }

    @Test
    fun `every action is named for a screen reader`() {
        showViewer()

        listOf(
            R.string.action_back,
            R.string.action_share,
            R.string.viewer_edit,
            R.string.viewer_delete,
            R.string.viewer_info,
            R.string.viewer_send,
        ).forEach { description ->
            composeTestRule.onNodeWithContentDescription(string(description)).assertIsDisplayed()
        }
    }

    @Test
    fun `there is no overflow button and nothing clickable over the image`() {
        showViewer()

        // §4.5 names the overflow button as something this screen must not have.
        composeTestRule.onNodeWithContentDescription(string(R.string.action_more)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.action_more)).assertDoesNotExist()

        // Nor arrows, nor any other control drawn on top of the photograph: the
        // page's own description is the ancestor of everything inside the deck.
        val page = pageDescription(2)
        composeTestRule.onAllNodes(
            hasClickAction() and hasAnyAncestor(hasContentDescription(page)),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    @Test
    fun `the photograph being shown is described with its position`() {
        showViewer(opened = photos[2])

        composeTestRule.onNodeWithContentDescription(pageDescription(2)).assertIsDisplayed()
    }

    @Test
    fun `the swipe hint is shown and stays out of the way of the deck`() {
        showViewer()

        composeTestRule.onNodeWithText(string(R.string.viewer_hint)).assertIsDisplayed()
    }

    @Test
    fun `information says what the platform reported`() {
        showViewer(opened = photos[2])

        composeTestRule.onNodeWithContentDescription(string(R.string.viewer_info)).performClick()
        settle()

        composeTestRule.onNodeWithText(string(R.string.viewer_info_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText("image/jpeg").assertIsDisplayed()
        composeTestRule.onNodeWithText("4032 × 3024").assertIsDisplayed()
        composeTestRule.onNodeWithText(MorseFormatters.forDefaultLocale().bytes(4_096L)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Camera").assertIsDisplayed()

        composeTestRule.onNodeWithText(string(R.string.action_close)).performClick()
        settle()
        composeTestRule.onNodeWithText(string(R.string.viewer_info_title)).assertDoesNotExist()
    }

    @Test
    fun `a photograph the platform gave no size for says unknown instead of guessing`() {
        val bare = photos[0].copy(widthPixels = 0, heightPixels = 0, bucket = null, mimeType = null)
        showViewer(opened = bare, images = listOf(bare))

        composeTestRule.onNodeWithContentDescription(string(R.string.viewer_info)).performClick()
        settle()

        composeTestRule.onNodeWithText(string(R.string.viewer_info_dimensions)).assertIsDisplayed()
        // Type, dimensions and location were never reported by the platform.
        composeTestRule.onAllNodesWithText(string(R.string.viewer_info_unknown)).assertCountEquals(3)
    }

    @Test
    fun `delete asks first, and cancelling deletes nothing`() {
        showViewer()

        composeTestRule.onNodeWithContentDescription(string(R.string.viewer_delete)).performClick()
        settle()

        composeTestRule.onNodeWithText(string(R.string.viewer_delete_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.viewer_delete_body, photos[2].displayName))
            .assertIsDisplayed()

        composeTestRule.onNodeWithText(string(R.string.action_cancel)).performClick()
        settle()

        composeTestRule.onNodeWithText(string(R.string.viewer_delete_title)).assertDoesNotExist()
        assertEquals(emptyList<String>(), repository.deletedUris)
        // The photograph is still the one being shown.
        assertShowing(2)
    }

    @Test
    fun `a deletion the platform refuses is reported as refused`() {
        showViewer()
        repository.deleteOutcome = DeleteOutcome.Refused

        confirmDelete()

        assertEquals(string(R.string.viewer_delete_failed), awaitToast())
        assertEquals(listOf(photos[2].uriString), repository.deletedUris)
        // A refusal is not a deletion, so the photograph is still the one shown.
        assertShowing(2)
    }

    @Test
    fun `a deletion the platform performs is reported as deleted`() {
        showViewer()
        repository.deleteOutcome = DeleteOutcome.Deleted

        confirmDelete()

        assertEquals(string(R.string.viewer_deleted), awaitToast())
        assertEquals(listOf(photos[2].uriString), repository.deletedUris)
    }

    @Test
    fun `edit hands the photograph to an external editor`() {
        showViewer(opened = photos[2])

        composeTestRule.onNodeWithContentDescription(string(R.string.viewer_edit)).performClick()
        settle()

        val intent = awaitStartedActivity(context) { settle() }
        assertNotNull("an edit intent must reach the platform", intent)
        assertEquals(Intent.ACTION_EDIT, intent!!.action)
        assertEquals(photos[2].uriString, intent.data.toString())
        assertEquals("image/jpeg", intent.type)
    }

    @Test
    fun `sending is gated until the transfer engine exists`() {
        showViewer()

        composeTestRule.onNodeWithContentDescription(string(R.string.viewer_send)).performClick()
        settle()

        composeTestRule.onNodeWithText(string(R.string.gated_title)).assertIsDisplayed()
    }

    @Test
    fun `a restored viewer comes back to the photograph it was showing`() {
        showViewer(opened = photos[0], restoredIndex = 4)

        assertShowing(4)
    }

    @Test
    fun `a restored position past the end of the list is not honoured`() {
        showViewer(opened = photos[0], restoredIndex = 99)

        assertShowing(0)
    }

    @Test
    fun `the photograph being shown is saved as the deck moves`() {
        showViewer(opened = photos[2])

        swipeDeck(forward = true)

        assertEquals(3, savedStateHandle.get<Int>(ViewerViewModel.SAVED_INDEX))
    }

    @Test
    fun `a viewer with no photographs says so instead of showing black`() {
        showViewer(images = emptyList())

        composeTestRule.onNodeWithText(string(R.string.viewer_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.viewer_empty_body)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.action_back)).assertIsDisplayed()
    }

    @Test
    fun `one photograph on the device has nothing to swipe to`() {
        showViewer(opened = photos.first(), images = listOf(photos.first()))

        composeTestRule.onNodeWithText(string(R.string.viewer_position, 1, 1), substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(photos.first().displayName).assertIsDisplayed()
    }

    /**
     * Renders the viewer over [images], opened at [opened], optionally with a
     * position already in saved state the way a restored process would have one.
     */
    private fun showViewer(
        opened: MediaItem = photos[2],
        images: List<MediaItem> = photos,
        restoredIndex: Int? = null,
    ) {
        backPresses = 0
        owner = TestLifecycleOwner()
        savedStateHandle = SavedStateHandle(
            buildMap<String, Any?> {
                put(Routes.VIEWER_ARG, opened.id)
                put(Routes.VIEWER_SORT_ARG, Routes.sortToken(SortOrder(SortKey.NAME, SortDirection.ASC)))
                if (restoredIndex != null) put(ViewerViewModel.SAVED_INDEX, restoredIndex)
            },
        )
        repository = FakeMediaRepository(images = images)
        val viewModel = ViewerViewModel(
            media = repository,
            formatters = MorseFormatters.forDefaultLocale(),
            savedStateHandle = savedStateHandle,
        )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MorseTheme(reducedMotion = true) {
                    ViewerScreen(onBack = { backPresses += 1 }, viewModel = viewModel)
                }
            }
        }
        settle()
    }

    /**
     * Drags across the deck: forward moves to the next photograph, backward to the
     * previous one. The gesture starts at the centre of the screen, which is the
     * deck — the header and the actions sit above and below it.
     */
    private fun swipeDeck(forward: Boolean) {
        composeTestRule.onRoot().performTouchInput {
            if (forward) swipeLeft() else swipeRight()
        }
        settle()
    }

    /** Asserts the header and the page description both name the photograph at [index]. */
    private fun assertShowing(index: Int) {
        val item = photos[index]
        composeTestRule.onNodeWithText(item.displayName).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.viewer_position, index + 1, photos.size), substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(pageDescription(index)).assertExists()
    }

    /** What the deck says about the photograph at [index], as a screen reader hears it. */
    private fun pageDescription(index: Int) =
        string(R.string.viewer_image_description, photos[index].displayName, index + 1, photos.size)

    /** Asks to delete the photograph being shown, and confirms it. */
    private fun confirmDelete() {
        composeTestRule.onNodeWithContentDescription(string(R.string.viewer_delete)).performClick()
        settle()
        composeTestRule.onNodeWithText(string(R.string.viewer_delete)).performClick()
        settle()
    }

    /**
     * Waits for the toast a background operation produced.
     *
     * Deletion really does leave the main thread, so this pumps the looper until the
     * answer lands rather than assuming it already has.
     */
    private fun awaitToast(): String? {
        val deadline = System.currentTimeMillis() + TOAST_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            settle()
            ShadowToast.getTextOfLatestToast()?.let { return it }
            Thread.sleep(20L)
        }
        return null
    }

    private fun string(@StringRes id: Int, vararg args: Any?) = context.getString(id, *args)

    private fun settle() {
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
    }

    private fun photo(index: Int, name: String, sizeBytes: Long, width: Int, height: Int) = MediaItem(
        id = "img:$index",
        displayName = name,
        kind = MediaKind.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = sizeBytes,
        dateModifiedEpochMillis = 1_760_000_000_000L,
        uriString = "content://media/external/images/media/${100 + index}",
        widthPixels = width,
        heightPixels = height,
        bucket = "Camera",
    )

    private companion object {
        /** Long enough for a background delete to answer, short enough to fail a test. */
        const val TOAST_TIMEOUT_MILLIS = 5_000L
    }
}
