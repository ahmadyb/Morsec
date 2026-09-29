package app.morsecode.ui.folder

import android.app.Application
import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/**
 * The internal folder browser, rendered for real on the JVM.
 *
 * The view model is constructed with a fake repository over one granted SAF tree,
 * so what is asserted is the screen a user would see and the levels it walks
 * through: the breadcrumb under the sticky header, the folder's real rows, no
 * upward-arrow control, descent into a subfolder, breadcrumb navigation, the
 * grant's ceiling, both ways of going back, selection, and a typed path that is
 * only honoured when it lands inside the grant.
 *
 * Robolectric runs the app module's own manifest, so the Hilt application is
 * replaced with a plain one — nothing here needs injection, and the point of the
 * test is the screen, not the graph. No activity is launched either: the two things
 * this screen reads from one are supplied directly (see [TestBackOwner]), which is
 * also what makes the system back gesture reachable in both build variants.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi", application = Application::class)
class FolderScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val authority = "com.android.externalstorage.documents"

    /** The grant: everything the browser may open is at or below this level. */
    private val tree = "content://$authority/tree/primary%3ADownload"
    private val rootLabel = "Internal storage"

    /** Levels this fake grant really has, so a typed path can be checked against them. */
    private val yearUri = level("primary:Download/2026")
    private val marchUri = level("primary:Download/2026/March")

    private lateinit var backOwner: TestBackOwner
    private lateinit var savedStateHandle: SavedStateHandle

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

    private val yearChildren = listOf(
        folderItem("March", "primary:Download/2026/March"),
        fileItem("report.pdf", 4_096L, "primary:Download/2026/report.pdf"),
    )

    private val marchChildren = listOf(
        fileItem("signed.zip", 8_192L, "primary:Download/2026/March/signed.zip"),
    )

    /**
     * Shows the browser for [tree]. Everything the platform would answer with is
     * supplied up front, so a level is either readable or it is not — nothing here
     * stands in for work the storage layer has not done. [restoredLevel] is what a
     * browser coming back after process death would have saved.
     */
    private fun showFolder(
        restoredLevel: String? = null,
        children: Map<String, List<MediaItem>> = mapOf(
            tree to downloadChildren,
            yearUri to yearChildren,
            marchUri to marchChildren,
        ),
        folders: Map<String, MediaItem?> = mapOf(
            tree to folderItem("Download", "primary:Download"),
            yearUri to folderItem("2026", "primary:Download/2026"),
            marchUri to folderItem("March", "primary:Download/2026/March"),
        ),
    ) {
        backPresses = 0
        backOwner = TestBackOwner()
        savedStateHandle = SavedStateHandle(
            buildMap {
                put(Routes.FOLDER_ARG, tree)
                if (restoredLevel != null) put(FolderViewModel.SAVED_LEVEL, restoredLevel)
            },
        )
        val viewModel = FolderViewModel(
            media = FakeMediaRepository(children = children, folders = folders),
            formatters = MorseFormatters.forDefaultLocale(),
            savedStateHandle = savedStateHandle,
        )
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides backOwner,
                LocalOnBackPressedDispatcherOwner provides backOwner,
            ) {
                MorseTheme(reducedMotion = true) {
                    FolderScreen(
                        onBack = { backPresses += 1 },
                        onNavigate = { _: MorseDestination -> },
                        viewModel = viewModel,
                    )
                }
            }
        }
        settle()
    }

    /** Runs whatever the view model posted to the main looper, then the frame it causes. */
    private fun settle() {
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
    }

    /** The system back gesture, as the phone would deliver it. */
    private fun pressBack() {
        backOwner.onBackPressedDispatcher.onBackPressed()
        settle()
    }

    /** The granted level: its own rows, and a breadcrumb that ends at it. */
    private fun assertAtDownload() {
        composeTestRule.onNodeWithText("notes.txt").assertIsDisplayed()
        composeTestRule.onNodeWithText("photo.jpg").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("2026").assertCountEquals(1)
    }

    private fun assertAtYear() {
        composeTestRule.onNodeWithText("report.pdf").assertIsDisplayed()
        // "2026" is now the header's title and the breadcrumb's current level.
        composeTestRule.onAllNodesWithText("2026").assertCountEquals(2)
    }

    private fun assertAtMarch() {
        composeTestRule.onNodeWithText("signed.zip").assertIsDisplayed()
    }

    @Test
    fun `the breadcrumb sits under the header and the folder's rows under it`() {
        showFolder()

        // Breadcrumb: the volume level is named locally, the folder by its real name.
        composeTestRule.onNodeWithText(rootLabel).assertIsDisplayed()
        // "Download" is both the header's title and the breadcrumb's current level.
        composeTestRule.onAllNodesWithText("Download").assertCountEquals(2)
        // The header carries exactly one back control, and it is the only one.
        composeTestRule.onAllNodesWithContentDescription(string(R.string.action_back))
            .assertCountEquals(1)
        // Real contents.
        composeTestRule.onNodeWithText("2026").assertIsDisplayed()
        composeTestRule.onNodeWithText("notes.txt").assertIsDisplayed()
        composeTestRule.onNodeWithText("photo.jpg").assertIsDisplayed()
        // No upward-arrow control: the breadcrumb is the only way up, so there is
        // no ".." row and exactly one back control, in the header.
        composeTestRule.onNodeWithText("..").assertDoesNotExist()
    }

    @Test
    fun `tapping a subfolder shows that level and saves it`() {
        showFolder()

        composeTestRule.onNodeWithText("2026").performClick()
        settle()

        assertAtYear()
        composeTestRule.onNodeWithText("signed.zip").assertDoesNotExist()
        // Saved as it moves, so a restored browser comes back here.
        assertEquals(yearUri, savedStateHandle.get<String>(FolderViewModel.SAVED_LEVEL))
    }

    @Test
    fun `a restored browser comes back to the level it was showing`() {
        showFolder(restoredLevel = yearUri)

        assertAtYear()
        composeTestRule.onNodeWithText("notes.txt").assertDoesNotExist()
    }

    @Test
    fun `tapping a file does not descend into it`() {
        showFolder()

        composeTestRule.onNodeWithText("notes.txt").performClick()
        settle()

        // A file row is a file, not a level. What opening it hands off to depends on
        // what the platform has to open it with, which is not this test's subject.
        assertAtDownload()
        assertEquals(0, backPresses)
    }

    @Test
    fun `tapping a breadcrumb level shows that level`() {
        showFolder()
        composeTestRule.onNodeWithText("2026").performClick()
        settle()
        assertAtYear()

        composeTestRule.onNodeWithText("Download").performClick()
        settle()

        assertAtDownload()
        composeTestRule.onNodeWithText("report.pdf").assertDoesNotExist()
    }

    @Test
    fun `a level above the grant is shown but is not a target`() {
        showFolder()

        // The volume is part of an honest path, and it is not inside what the user
        // granted, so tapping it must not move the browser anywhere.
        composeTestRule.onNodeWithText(rootLabel).performClick()
        settle()

        assertAtDownload()
        assertEquals("nothing above the grant may be opened", 0, backPresses)
    }

    @Test
    fun `the back control climbs a level and then leaves the browser`() {
        showFolder()
        composeTestRule.onNodeWithText("2026").performClick()
        settle()
        assertAtYear()

        composeTestRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        settle()
        assertAtDownload()
        assertEquals("a level was still above this one", 0, backPresses)

        composeTestRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        settle()
        assertEquals("the grant is the ceiling, so back leaves the browser", 1, backPresses)
    }

    @Test
    fun `the system back gesture climbs a level before closing`() {
        showFolder()
        composeTestRule.onNodeWithText("2026").performClick()
        settle()

        pressBack()
        assertAtDownload()
        assertEquals(0, backPresses)

        pressBack()
        assertEquals(1, backPresses)
    }

    @Test
    fun `a selection belongs to the level it was made in`() {
        showFolder()
        composeTestRule.onNodeWithText("2026").performClick()
        settle()
        composeTestRule.onNodeWithContentDescription(string(R.string.files_select)).performClick()
        settle()
        composeTestRule.onNodeWithText("report.pdf").performClick()
        settle()
        composeTestRule.onNodeWithText("1 selected", substring = true).assertIsDisplayed()

        // The breadcrumb is not a selection control, so it navigates even while rows
        // are selected — and the selection does not follow the user down the path.
        composeTestRule.onNodeWithText("Download").performClick()
        settle()

        assertAtDownload()
        composeTestRule.onNodeWithText("selected", substring = true).assertDoesNotExist()
    }

    @Test
    fun `selecting rows brings up the shared selection bar`() {
        showFolder()

        composeTestRule.onNodeWithContentDescription(string(R.string.files_select)).performClick()
        settle()
        composeTestRule.onNodeWithText("notes.txt").performClick()
        settle()

        composeTestRule.onNodeWithText("1 selected", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_share)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_send)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_clear)).assertIsDisplayed()
    }

    @Test
    fun `sending a selection stays gated until the transfer engine exists`() {
        showFolder()
        composeTestRule.onNodeWithContentDescription(string(R.string.files_select)).performClick()
        settle()
        composeTestRule.onNodeWithText("notes.txt").performClick()
        settle()

        composeTestRule.onNodeWithText(string(R.string.action_send)).performClick()
        settle()

        composeTestRule.onNodeWithText(string(R.string.gated_title)).assertIsDisplayed()
        assertEquals("a gated action must not navigate anywhere", 0, backPresses)
    }

    @Test
    fun `select all takes the whole folder and clears again`() {
        showFolder()

        composeTestRule.onNodeWithContentDescription(string(R.string.folder_select_all)).performClick()
        settle()
        composeTestRule.onNodeWithText("3 selected", substring = true).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(string(R.string.folder_select_all)).performClick()
        settle()
        composeTestRule.onNodeWithText("selected", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a typed path inside the grant opens`() {
        showFolder()
        openPathEditor()

        typePath("2026/March")

        assertAtMarch()
    }

    @Test
    fun `a typed path outside the grant is refused`() {
        showFolder()
        openPathEditor()

        typePath("primary:Pictures")

        assertEquals(string(R.string.folder_path_outside), ShadowToast.getTextOfLatestToast())
        assertAtDownload()
    }

    @Test
    fun `a typed path that is not there is refused`() {
        showFolder()
        openPathEditor()

        typePath("Nowhere")

        assertEquals(string(R.string.folder_path_not_found), ShadowToast.getTextOfLatestToast())
        assertAtDownload()
    }

    @Test
    fun `a copied path pastes back to the same level`() {
        showFolder()
        openPathEditor()

        // What "Copy path" puts on the clipboard is what the editor accepts.
        typePath("$rootLabel/Download/2026")

        assertAtYear()
    }

    @Test
    fun `a folder that can no longer be read says so instead of looking empty`() {
        showFolder(children = emptyMap(), folders = emptyMap())

        composeTestRule.onNodeWithText(string(R.string.folder_unavailable_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.folder_unavailable)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.folder_empty_title)).assertDoesNotExist()
    }

    @Test
    fun `an empty folder says it is empty`() {
        showFolder(children = mapOf(tree to emptyList()))

        composeTestRule.onNodeWithText(string(R.string.folder_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.folder_unavailable_title)).assertDoesNotExist()
    }

    private fun openPathEditor() {
        composeTestRule.onNodeWithContentDescription(string(R.string.folder_edit_path)).performClick()
        settle()
        composeTestRule.onNodeWithText(string(R.string.folder_edit_path_title)).assertIsDisplayed()
    }

    /** Types into the path field and presses Open. */
    private fun typePath(path: String) {
        val field = composeTestRule.onNode(hasSetTextAction())
        // Cleared and typed as two statements: the editor opens showing the level
        // the user is standing in, and what is typed replaces it.
        field.performTextClearance()
        field.performTextInput(path)
        composeTestRule.onNodeWithText(string(R.string.folder_open)).performClick()
        settle()
    }
}

/**
 * The lifecycle and back-dispatcher owner the browser composes under.
 *
 * Robolectric resolves an activity through the merged manifest, and only the debug
 * variant carries the one `androidx.compose.ui.test.manifest` declares, so no
 * activity is launched here at all. These are the two things this screen reads from
 * one: a resumed lifecycle, to collect its state, and a back dispatcher, to turn the
 * system gesture into "up one level".
 */
private class TestBackOwner : OnBackPressedDispatcherOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)

    override val onBackPressedDispatcher: OnBackPressedDispatcher = OnBackPressedDispatcher()
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    init {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
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
