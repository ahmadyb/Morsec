package app.morsecode.ui.files

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import app.morsecode.R
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MediaKind
import app.morsecode.core.model.MorseFormatters
import app.morsecode.core.model.SafGrant
import app.morsecode.core.storage.StorageAccess
import app.morsecode.ui.FakeMediaRepository
import app.morsecode.ui.TestLifecycleOwner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** The Files media categories keep SAF-backed rows visible without broad MediaStore permission. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi", application = Application::class)
class FilesSafAccessTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: android.content.Context = ApplicationProvider.getApplicationContext()

    private val grant = SafGrant(
        id = 9L,
        treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload",
        displayName = "Download",
    )

    private val photo = safItem(
        id = "saf:primary:Download/Camera/IMG_2043.jpg",
        name = "IMG_2043.jpg",
        kind = MediaKind.IMAGE,
    )
    private val video = safItem(
        id = "saf:primary:Download/Camera/clip.mp4",
        name = "clip.mp4",
        kind = MediaKind.VIDEO,
    )
    private val song = safItem(
        id = "saf:primary:Download/Music/song.mp3",
        name = "song.mp3",
        kind = MediaKind.AUDIO,
    )

    @Test
    fun `valid SAF media appears in Photos Videos and Music without MediaStore permission`() {
        showFiles(
            FakeMediaRepository(
                images = listOf(photo),
                videos = listOf(video),
                audio = listOf(song),
                access = StorageAccess(mediaReadGranted = false, grants = listOf(grant)),
            ),
        )

        selectTiles()
        composeTestRule.onNodeWithContentDescription(photo.displayName).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.files_permission_title)).assertDoesNotExist()

        composeTestRule.onNodeWithText("Videos").performClick()
        settle()
        selectTiles()
        composeTestRule.onNodeWithContentDescription(video.displayName).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.files_permission_title)).assertDoesNotExist()

        composeTestRule.onNodeWithText("Music").performClick()
        settle()
        composeTestRule.onNodeWithText(song.displayName).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.files_permission_title)).assertDoesNotExist()
    }

    @Test
    fun `an empty SAF media category still offers broader media permission`() {
        showFiles(
            FakeMediaRepository(
                access = StorageAccess(mediaReadGranted = false, grants = listOf(grant)),
            ),
        )

        composeTestRule.onNodeWithText("Videos").performClick()
        settle()

        composeTestRule.onNodeWithText(context.getString(R.string.files_empty_videos_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.files_permission_grant)).assertIsDisplayed()
    }

    private fun showFiles(repository: FakeMediaRepository) {
        val viewModel = FilesViewModel(repository, MorseFormatters.forDefaultLocale())
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycleOwner()) {
                MorseTheme(reducedMotion = true) {
                    FilesScreen(
                        onNavigate = { },
                        onOpenFolder = { },
                        onOpenViewer = { _, _ -> },
                        onOpenMusic = { _, _ -> },
                        onOpenVideo = { },
                        viewModel = viewModel,
                    )
                }
            }
        }
        settle()
    }

    private fun safItem(id: String, name: String, kind: MediaKind) = MediaItem(
        id = id,
        displayName = name,
        kind = kind,
        mimeType = when (kind) {
            MediaKind.IMAGE -> "image/jpeg"
            MediaKind.VIDEO -> "video/mp4"
            MediaKind.AUDIO -> "audio/mpeg"
            else -> null
        },
        sizeBytes = 1_024L,
        dateModifiedEpochMillis = 1_760_000_000_000L,
        uriString = "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/" +
            Uri.encode(id.removePrefix("saf:")),
    )

    private fun selectTiles() {
        composeTestRule.onAllNodesWithContentDescription(context.getString(R.string.files_select))[0]
            .performClick()
        settle()
    }

    private fun settle() {
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
    }

}
