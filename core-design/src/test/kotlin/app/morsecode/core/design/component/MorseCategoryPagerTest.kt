package app.morsecode.core.design.component

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import app.morsecode.core.design.theme.MorseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Master prompt §4.3: Files categories switch by swiping the content sideways
 * *and* by tapping the pinned strip, and the two must never disagree.
 *
 * These run on the JVM through Robolectric because CI has no emulator. Native
 * graphics mode is required for real Compose layout and gesture handling, SDK 34
 * is the newest Robolectric models (the module targets 36), and the qualifiers
 * pin the reference phone width of 411 dp so the strip is measured as designed
 * rather than squeezed into Robolectric's 320 dp default.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class MorseCategoryPagerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val tabs = listOf(
        CategoryTab("photos", "Photos"),
        CategoryTab("videos", "Videos"),
        CategoryTab("music", "Music"),
        CategoryTab("apps", "Apps"),
        CategoryTab("files", "Files"),
    )

    /** Every id the pager reported to its host, in order. */
    private val selections = mutableListOf<String>()

    /** The tab a category strip marks as selected. */
    private val selectedTab = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)

    /**
     * A pager driven exactly like the Files screen drives it: the host owns the
     * selection, so a tap and a swipe both end in the same place. Pages are tall
     * vertical lists, which is what makes the vertical-scroll case meaningful.
     */
    private fun showPager() {
        selections.clear()
        composeTestRule.setContent {
            var selected by remember { mutableStateOf(tabs.first().id) }
            MorseTheme(reducedMotion = true) {
                MorseCategoryPager(
                    tabs = tabs,
                    selectedId = selected,
                    onSelect = { id ->
                        selections.add(id)
                        selected = id
                    },
                    modifier = Modifier.fillMaxSize(),
                ) { tab ->
                    LazyColumn(modifier = Modifier.fillMaxSize().testTag("page:${tab.id}")) {
                        items(count = 40) { index ->
                            Text(text = "row:$index", modifier = Modifier.height(120.dp))
                        }
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    /** Exactly one tab is marked selected, and it is the one with [label]. */
    private fun assertSelectedTab(label: String) {
        composeTestRule.onAllNodes(selectedTab).assertCountEquals(1)
        composeTestRule
            .onNode(selectedTab.and(hasAnyDescendant(hasText(label))))
            .assertIsDisplayed()
    }

    @Test
    fun `tapping a category moves the pager to that category`() {
        showPager()
        assertSelectedTab("Photos")

        composeTestRule.onNodeWithText("Videos").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("videos"), selections)
        assertSelectedTab("Videos")
        composeTestRule.onNodeWithTag("page:videos").assertIsDisplayed()
    }

    @Test
    fun `tapping a distant category skips straight to it`() {
        showPager()

        composeTestRule.onNodeWithText("Files").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("files"), selections)
        assertSelectedTab("Files")
        composeTestRule.onNodeWithTag("page:files").assertIsDisplayed()
    }

    @Test
    fun `swiping left advances to the next category`() {
        showPager()

        composeTestRule.onNodeWithTag("page:photos").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        assertEquals(listOf("videos"), selections)
        assertSelectedTab("Videos")
        composeTestRule.onNodeWithTag("page:videos").assertIsDisplayed()
    }

    @Test
    fun `swiping right returns to the previous category`() {
        showPager()
        composeTestRule.onNodeWithText("Videos").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("page:videos").performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()

        assertEquals(listOf("videos", "photos"), selections)
        assertSelectedTab("Photos")
        composeTestRule.onNodeWithTag("page:photos").assertIsDisplayed()
    }

    @Test
    fun `swiping up scrolls the category without selecting another one`() {
        showPager()

        composeTestRule.onNodeWithTag("page:photos").performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        assertEquals(emptyList<String>(), selections)
        assertSelectedTab("Photos")
        composeTestRule.onNodeWithTag("page:photos").assertIsDisplayed()
    }

    @Test
    fun `all five category tabs stay on screen`() {
        showPager()

        tabs.forEach { tab ->
            composeTestRule.onNodeWithText(tab.label).assertIsDisplayed()
        }
        composeTestRule.onAllNodes(selectedTab).assertCountEquals(1)
    }
}
