package app.morsecode.core.design.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The volume control every player uses (§4.7 "volume and mute controls").
 *
 * The mapping is asserted as arithmetic first, because that is all a bar is: a
 * position across the strip becomes a whole bar, clamped at both ends, and a strip
 * nobody has measured yet answers nothing rather than guessing a width. The gesture
 * is then asserted on the control itself — a tap sets the bar it landed on, a drag
 * keeps reporting the bar under the finger — which is where the gesture lives, so
 * that a screen embedding this control in a scrolling layout does not have to prove
 * the injection framework can reach it.
 *
 * What a screen reader hears is asserted too: the strip is a range, the range carries
 * the level in force, the level in force is nothing while muted, and the adjustment
 * action is wired to the same callback a finger uses, so the accessible path and the
 * touch path cannot disagree.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class MorseVolumeControlTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var levels = emptyList<Int>()
    private var muteClicks = 0

    @Test
    fun `a position across the strip is the bar it is over`() {
        assertEquals(0, VolumeMath.levelFor(0f, WIDTH, STEPS))
        // The fifth bar's centre is 45% across ten bars, and rounds to five.
        assertEquals(5, VolumeMath.levelFor(WIDTH * 0.45f, WIDTH, STEPS))
        assertEquals(1, VolumeMath.levelFor(WIDTH * 0.05f, WIDTH, STEPS))
        assertEquals(9, VolumeMath.levelFor(WIDTH * 0.85f, WIDTH, STEPS))
        assertEquals(STEPS, VolumeMath.levelFor(WIDTH.toFloat(), WIDTH, STEPS))
    }

    @Test
    fun `a position outside the strip is clamped, in both directions`() {
        assertEquals(0, VolumeMath.levelFor(-40f, WIDTH, STEPS))
        assertEquals(STEPS, VolumeMath.levelFor(WIDTH * 4f, WIDTH, STEPS))
    }

    @Test
    fun `a strip nobody has measured and a control with no bars answer nothing`() {
        assertEquals(0, VolumeMath.levelFor(30f, 0, STEPS))
        assertEquals(0, VolumeMath.levelFor(30f, WIDTH, 0))
    }

    @Test
    fun `a level lights the bars the reference would light`() {
        assertEquals(0, VolumeMath.levelForVolume(0f, STEPS))
        assertEquals(1, VolumeMath.levelForVolume(0.1f, STEPS))
        assertEquals(7, VolumeMath.levelForVolume(0.7f, STEPS))
        assertEquals(STEPS, VolumeMath.levelForVolume(1f, STEPS))
        // Four and a half tenths rounds up, which is how the reference rounds it.
        assertEquals(5, VolumeMath.levelForVolume(0.45f, STEPS))
        assertEquals(0, VolumeMath.levelForVolume(0.04f, STEPS))
        // A level outside nothing-to-full is pulled to an end, never wrapped.
        assertEquals(0, VolumeMath.levelForVolume(-0.3f, STEPS))
        assertEquals(STEPS, VolumeMath.levelForVolume(1.4f, STEPS))
        assertEquals(0, VolumeMath.levelForVolume(0.7f, 0))
    }

    @Test
    fun `the speaker wears the icon of the level in force`() {
        assertEquals(MorseIcons.volMute, volumeIconFor(0f))
        assertEquals(MorseIcons.volMute, volumeIconFor(-0.2f))
        assertEquals(MorseIcons.volLow, volumeIconFor(0.1f))
        assertEquals(MorseIcons.volLow, volumeIconFor(0.49f))
        assertEquals(MorseIcons.vol, volumeIconFor(0.5f))
        assertEquals(MorseIcons.vol, volumeIconFor(1f))
    }

    @Test
    fun `a tap in the middle of the strip sets the middle bar`() {
        show()

        composeTestRule.onNodeWithContentDescription(VOLUME).performClick()

        assertEquals(5, levels.last())
        assertEquals("a tap is one level, not a stream of them", 1, levels.size)
    }

    @Test
    fun `a tap sets the bar it landed on, near either end`() {
        show()
        val strip = composeTestRule.onNodeWithContentDescription(VOLUME)

        strip.performTouchInput { click(Offset(center.x * 0.1f, center.y)) }
        assertEquals(1, levels.last())

        strip.performTouchInput { click(Offset(center.x * 0.9f, center.y)) }
        assertEquals(9, levels.last())
    }

    @Test
    fun `a tap past the end of the strip is the loudest bar and never wraps`() {
        show()

        composeTestRule.onNodeWithContentDescription(VOLUME)
            .performTouchInput { click(Offset(center.x * 1.9f, center.y)) }

        assertEquals(STEPS, levels.last())
        assertTrue(levels.none { it < STEPS })
    }

    @Test
    fun `dragging up the strip keeps reporting the bar under the finger`() {
        show()

        composeTestRule.onNodeWithContentDescription(VOLUME)
            .performTouchInput { swipeRight(startX = 0.2f, endX = 0.8f) }

        assertTrue("a drag reports more than one level", levels.size > 1)
        assertEquals(8, levels.last())
        assertTrue(
            "the finger moved right, so the levels must not have moved left: $levels",
            levels.zipWithNext().all { (before, after) -> after >= before },
        )
    }

    @Test
    fun `dragging back down the strip lowers the level`() {
        show()

        composeTestRule.onNodeWithContentDescription(VOLUME)
            .performTouchInput { swipeLeft(startX = 0.8f, endX = 0.2f) }

        assertEquals(2, levels.last())
        assertTrue(
            "the finger moved left, so the levels must not have moved right: $levels",
            levels.zipWithNext().all { (before, after) -> after <= before },
        )
    }

    @Test
    fun `the speaker button reports its own click and leaves the level alone`() {
        show()

        composeTestRule.onNodeWithContentDescription(MUTE).performClick()

        assertEquals(1, muteClicks)
        assertTrue("mute is the speaker's job, not the bars'", levels.isEmpty())
    }

    @Test
    fun `the button says which of mute and unmute it is`() {
        show()
        composeTestRule.onNodeWithContentDescription(MUTE).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(UNMUTE).assertDoesNotExist()

        show(volumeInForce = 0f, label = "mute")
        composeTestRule.onNodeWithContentDescription(UNMUTE).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(MUTE).assertDoesNotExist()
    }

    @Test
    fun `the strip is a range a screen reader can adjust, carrying the level in force`() {
        show(volumeInForce = 0.7f, label = "70%")

        val config = composeTestRule.onNodeWithContentDescription(VOLUME).fetchSemanticsNode().config
        val range = config[SemanticsProperties.ProgressBarRangeInfo]

        assertEquals(7f, range.current, 0f)
        assertEquals(0f, range.range.start, 0f)
        assertEquals(STEPS.toFloat(), range.range.endInclusive, 0f)
        assertEquals("70%", config[SemanticsProperties.StateDescription])
        assertTrue(
            "the accessible adjustment must be wired, or TalkBack's gesture does nothing",
            config.contains(SemanticsActions.SetProgress),
        )
    }

    @Test
    fun `a muted control announces silence, not the level it is holding`() {
        show(volumeInForce = 0f, label = "mute")

        val config = composeTestRule.onNodeWithContentDescription(VOLUME).fetchSemanticsNode().config

        assertEquals(0f, config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
        assertEquals("mute", config[SemanticsProperties.StateDescription])
    }

    @Test
    fun `the printed label is optional and the strip still answers without it`() {
        show(volumeInForce = 0.7f, label = null)

        composeTestRule.onNodeWithText("70%").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(VOLUME).performClick()
        assertEquals(5, levels.last())
    }

    @Test
    fun `the label the caller prints is the label announced`() {
        show(volumeInForce = 0.3f, label = "30%")

        composeTestRule.onNodeWithText("30%").assertIsDisplayed()
        // The word "mute" is the caller's, printed only when the caller says so: this
        // control is at three tenths, and nothing here invents a different label for it.
        composeTestRule.onNodeWithText("mute").assertDoesNotExist()
    }

    private fun show(
        volumeInForce: Float = 0.7f,
        label: String? = "70%",
    ) {
        levels = emptyList()
        muteClicks = 0
        composeTestRule.setContent {
            MorseTheme {
                MorseVolumeControl(
                    volumeInForce = volumeInForce,
                    onMuteClick = { muteClicks += 1 },
                    onLevelChange = { level -> levels = levels + level },
                    contentDescription = VOLUME,
                    // The reference titles the speaker from its own mute flag. Here the
                    // caller does, and the control is told only the level in force.
                    muteDescription = if (label == "mute") UNMUTE else MUTE,
                    levelDescription = label,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private companion object {
        const val STEPS = 10

        /** A strip width in pixels; only its ratios matter to the mapping. */
        const val WIDTH = 200

        const val VOLUME = "Volume"
        const val MUTE = "Mute"
        const val UNMUTE = "Unmute"
    }
}
