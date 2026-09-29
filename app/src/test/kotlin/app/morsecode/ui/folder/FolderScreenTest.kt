package app.morsecode.ui.folder

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.morsecode.R
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MediaKind
import app.morsecode.core.model.MorseFormatters
import app.morsecode.core.model.SafGrant
import app.morsecode.core.storage.MediaRepository
import app.morsecode.core.storage.StorageAccess
import app.morsecode.core.storage.saf.SafPaths
import app.morsecode.navigation.MorseDestination
import app.morsecode.navigation.Routes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/**
 * The internal folder browser, rendered for real on the JVM.
 *
 * The view model is constructed with a fake repository over one granted SAF tree,
 * so what is asserted is the screen a user would see: the breadcrumb under the
 * sticky header, the folder's real rows, no upward-arrow control, descent into a
 * subfolder, breadcrumb navigation, selection, and a typed path that is only
 * honoured when it lands inside the grant.
 *
 * Robolectric runs the app module's own manifest, so the Hilt application is
 * replaced with a plain one — nothing here needs injection, and the point of the
 * test is the screen, not the graph.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi", application = Application::class)
class FolderScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val authority = "com.android.externalstorage.documents"
    private val tree = "content://$authority/tree/primary%3ADownload"
    private val rootLabel = "Internal storage"

    /** Levels this fake grant really has, so a typed path can be checked. */
    private val marchUri = level("primary:Download/2026/March")
    private val yearUri = level("primary:Download/2026")

    private val opened = mutableListOf<String>()
    private var backPresses = 0

    private fun string(@StringRes id: Int) = context.getString(id)

    private fun level(documentId: String): String = SafPaths.uriFor(tree, documentId)!!

    private fun fileItem(name: String, sizeBytes: Long, documentId: String) = MediaItem(
        id = "saf:$documentId",
        displayName = name,
        kind = MediaKind.of(name, null),
        mimeType = null,
        sizeBytes = sizeBytes,
        dateModifiedEpochMillis = 1_760_000_000_000L,
        uriString = level(documentId),
    )

    private fun folderItem(name: String, documentId: String) = MediaItem(
        id = "saf:$documentId",
        displayName = name,
        kind = MediaKind.FOLDER,
        mimeType = "vnd.android.document/directory",
        sizeBytes = 0L,
        dateModifiedEpochMillis = 1_760_000_000_000L,
        uriString = level(documentId),
        isFolder = true,
    )

    private val downloadChildren = listOf(
        folderItem("2026", "primary:Download/2026"),
        fileItem("notes.txt", 2_048L, "primary:Download/notes.txt"),
        fileItem("photo.jpg", 1_048_576L, "primary:Download/photo.jpg"),
    )

    /**
     * Shows the browser for [tree]. Everything the platform would answer with is
     * supplied up front, so the first frame is already the loaded one.
     */
    private fun showFolder(
        children: List<MediaItem> = downloadChildren,
        folders: Map<String, MediaItem?> = mapOf(
            tree to folderItem("Download", "primary:Download"),
            yearUri to folderItem("2026", "primary:Download/2026"),
            marchUri to folderItem("March", "primary:Download/2026/March"),
        ),
    ) {
        opened.clear()
        backPresses = 0
        val viewModel = FolderViewModel(
            media = FakeMediaRepository(children = mapOf(tree to children), folders = folders),
            formatters = MorseFormatters.forDefaultLocale(),
            savedStateHandle = SavedStateHandle(mapOf(Routes.FOLDER_ARG to tree)),
        )
        composeTestRule.setContent {
            MorseTheme(reducedMotion = true) {
                FolderScreen(
                    onBack = { backPresses += 1 },
                    onNavigate = { _: MorseDestination -> },
                    onOpenFolder = { opened += it },
                    viewModel = viewModel,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the breadcrumb sits under the header and the folder's rows under it`() {
        showFolder()

        // Breadcrumb: the volume level is named locally, the folder by its real name.
        composeTestRule.onNodeWithText(rootLabel).assertIsDisplayed()
        composeTestRule.onNodeWithText("Download").assertIsDisplayed()
        // The header carries exactly one back control, and it is the only one.
        composeTestRule.onAllNodesWithContentDescription(string(R.string.action_back))
            .assertCountEquals(1)
        // "Download" is both the header's title and the breadcrumb's last level.
        composeTestRule.onAllNodesWithText("Download").assertCountEquals(2)
        // Real contents.
        composeTestRule.onNodeWithText("2026").assertIsDisplayed()
        composeTestRule.onNodeWithText("notes.txt").assertIsDisplayed()
        composeTestRule.onNodeWithText("photo.jpg").assertIsDisplayed()
        // No upward-arrow control: the breadcrumb is the only way up, so there is
        // no ".." row and exactly one back control, in the header.
        composeTestRule.onNodeWithText("..").assertDoesNotExist()
    }

    @Test
    fun `tapping a subfolder opens that level`() {
        showFolder()

        composeTestRule.onNodeWithText("2026").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(yearUri), opened)
    }

    @Test
    fun `tapping a file opens the file rather than the browser`() {
        showFolder()

        composeTestRule.onNodeWithText("notes.txt").performClick()
        composeTestRule.waitForIdle()

        assertTrue("a file must not be opened as a folder: $opened", opened.isEmpty())
        assertEquals(string(R.string.history_open_failed), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `tapping a breadcrumb level opens that level`() {
        showFolder()

        composeTestRule.onNodeWithText(rootLabel).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(level("primary:")), opened)
    }

    @Test
    fun `the back control leaves the browser`() {
        showFolder()

        composeTestRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, backPresses)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `selecting rows brings up the shared selection bar`() {
        showFolder()

        composeTestRule.onNodeWithContentDescription(string(R.string.files_select)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("notes.txt").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("1 selected", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_share)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_send)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_clear)).assertIsDisplayed()
    }

    @Test
    fun `sending a selection stays gated until the transfer engine exists`() {
        showFolder()
        composeTestRule.onNodeWithContentDescription(string(R.string.files_select)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("notes.txt").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(string(R.string.action_send)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(string(R.string.gated_title)).assertIsDisplayed()
        assertTrue("nothing may be sent in this milestone: $opened", opened.isEmpty())
    }

    @Test
    fun `select all takes the whole folder and clears again`() {
        showFolder()

        composeTestRule.onNodeWithContentDescription(string(R.string.folder_select_all)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("3 selected", substring = true).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(string(R.string.folder_select_all)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("selected", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a typed path inside the grant opens`() {
        showFolder()
        openPathEditor()

        composeTestRule.onNode(hasSetTextAction())
            .performTextClearance()
            .performTextInput("2026/March")
        composeTestRule.onNodeWithText(string(R.string.folder_open)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(marchUri), opened)
    }

    @Test
    fun `a typed path outside the grant is refused`() {
        showFolder()
        openPathEditor()

        composeTestRule.onNode(hasSetTextAction())
            .performTextClearance()
            .performTextInput("primary:Pictures")
        composeTestRule.onNodeWithText(string(R.string.folder_open)).performClick()
        composeTestRule.waitForIdle()

        assertTrue("the grant is the ceiling: $opened", opened.isEmpty())
        assertEquals(string(R.string.folder_path_outside), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a typed path that is not there is refused`() {
        showFolder()
        openPathEditor()

        composeTestRule.onNode(hasSetTextAction())
            .performTextClearance()
            .performTextInput("Nowhere")
        composeTestRule.onNodeWithText(string(R.string.folder_open)).performClick()
        composeTestRule.waitForIdle()

        assertTrue(opened.isEmpty())
        assertEquals(string(R.string.folder_path_not_found), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a copied path pastes back to the same level`() {
        showFolder()
        openPathEditor()

        // What "Copy path" puts on the clipboard is what the editor accepts.
        composeTestRule.onNode(hasSetTextAction())
            .performTextClearance()
            .performTextInput("$rootLabel/Download/2026")
        composeTestRule.onNodeWithText(string(R.string.folder_open)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(yearUri), opened)
    }

    @Test
    fun `a folder that can no longer be read says so instead of looking empty`() {
        showFolder(children = emptyList(), folders = emptyMap())

        composeTestRule.onNodeWithText(string(R.string.folder_unavailable_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.folder_unavailable)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.folder_empty_title)).assertDoesNotExist()
    }

    @Test
    fun `an empty folder says it is empty`() {
        showFolder(children = emptyList())

        composeTestRule.onNodeWithText(string(R.string.folder_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.folder_unavailable_title)).assertDoesNotExist()
    }

    private fun openPathEditor() {
        composeTestRule.onNodeWithContentDescription(string(R.string.folder_edit_path)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(string(R.string.folder_edit_path_title)).assertIsDisplayed()
    }

}

/**
 * The storage layer, narrowed to what the browser reads: the children of a level
 * and whether a level resolves at all. Nothing here simulates work — a level that
 * is not in the map is a level the platform would not open.
 */
private class FakeMediaRepository(
    private val children: Map<String, List<MediaItem>>,
    private val folders: Map<String, MediaItem?>,
) : MediaRepository {

    override fun observeAccess(): Flow<StorageAccess> =
        flowOf(StorageAccess(mediaReadGranted = true))

    override fun observeImages(): Flow<List<MediaItem>> = flowOf(emptyList())

    override fun observeVideos(): Flow<List<MediaItem>> = flowOf(emptyList())

    override fun observeAudio(): Flow<List<MediaItem>> = flowOf(emptyList())

    override fun observeApps(): Flow<List<MediaItem>> = flowOf(emptyList())

    override fun observeDocuments(): Flow<List<MediaItem>> = flowOf(emptyList())

    override fun observeFolders(): Flow<List<MediaItem>> = flowOf(emptyList())

    override suspend fun childrenOf(treeUri: String): List<MediaItem> =
        children[treeUri].orEmpty()

    override suspend fun folderAt(uriString: String): MediaItem? = folders[uriString]

    override suspend fun itemById(id: String): MediaItem? =
        children.values.flatten().firstOrNull { it.id == id }

    override suspend fun refresh() = Unit

    override suspend fun addFolder(treeUri: String): SafGrant? = null

    override suspend fun removeFolder(grantId: Long) = Unit

    override suspend fun pruneRevokedGrants(): Int = 0
}
