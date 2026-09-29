package app.morsecode.core.design.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.morsecode.core.design.theme.MorseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Master prompt §4.3: the internal folder browser's path bar supports breadcrumb
 * navigation, and it is the only way up — there is no upward-arrow control to
 * test because there is none to render.
 *
 * JVM/Robolectric for the same reasons as [MorseCategoryPagerTest]: no emulator
 * in CI, native graphics for real layout, SDK 34 because that is what Robolectric
 * models, and the reference phone width so the pill is measured as designed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class MorseCrumbTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val levels = listOf("Internal storage", "Download", "2026")

    /** Every level index the bar reported, in order. */
    private val taps = mutableListOf<Int>()

    private fun showCrumb(path: List<String> = levels, trailingLabel: String? = null) {
        taps.clear()
        composeTestRule.setContent {
            MorseTheme(reducedMotion = true) {
                MorseCrumb(
                    levels = path,
                    onLevelClick = taps::add,
                    currentDescription = "Current folder",
                    iconDescription = "Path",
                    modifier = Modifier.fillMaxWidth(),
                    trailing = if (trailingLabel == null) null else {
                        { Text(trailingLabel) }
                    },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `every level is shown with a separator between them`() {
        showCrumb()

        levels.forEach { level -> composeTestRule.onNodeWithText(level).assertIsDisplayed() }
        composeTestRule.onAllNodesWithText("/").assertCountEquals(levels.size - 1)
    }

    @Test
    fun `tapping a level reports its position`() {
        showCrumb()

        composeTestRule.onNodeWithText("Download").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Internal storage").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(1, 0), taps)
    }

    @Test
    fun `the level being browsed is named for screen readers`() {
        showCrumb()

        val current = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Current folder")
        composeTestRule.onAllNodes(current, useUnmergedTree = true).assertCountEquals(1)
        composeTestRule.onNodeWithText("2026").assertIsDisplayed()
    }

    @Test
    fun `a path longer than the phone keeps the current level on screen`() {
        val deep = listOf("Internal storage", "Download", "2026", "March", "Reports", "Final", "Signed", "Archive")
        showCrumb(path = deep)

        // The strip scrolls inside its pill and chases its tail, so the level the
        // user is standing in is the one still readable.
        composeTestRule.onNodeWithText(deep.last()).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("/").assertCountEquals(deep.size - 1)
    }

    @Test
    fun `the trailing actions sit beside the path`() {
        showCrumb(trailingLabel = "Copy path")

        composeTestRule.onNodeWithText("Copy path").assertIsDisplayed()
        composeTestRule.onNodeWithText("Internal storage").assertIsDisplayed()

        composeTestRule.onNodeWithText("Download").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(1), taps)
    }
}
