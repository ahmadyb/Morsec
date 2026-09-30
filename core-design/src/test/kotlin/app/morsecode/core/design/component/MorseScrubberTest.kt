package app.morsecode.core.design.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
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
 * The scrubber every player uses (§4.6 "seek by tapping or dragging", §4.7 the same).
 *
 * The arithmetic is asserted as arithmetic, because that is what a seek is allowed to
 * do: a fraction of a track becomes a position inside it, never before the start and
 * never past the end, and a track with no duration has nothing to seek to. The gesture
 * is asserted for the direction it produces rather than for an exact position, since
 * where a synthetic drag ends is the test framework's business, not the player's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class MorseScrubberTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a position becomes a fraction of the track`() {
        assertEquals(0f, ScrubberMath.fraction(0L, TRACK), 0f)
        assertEquals(0.5f, ScrubberMath.fraction(TRACK / 2, TRACK), TOLERANCE)
        assertEquals(1f, ScrubberMath.fraction(TRACK, TRACK), 0f)
    }

    @Test
    fun `a position outside the track is clamped, in both directions`() {
        assertEquals(0f, ScrubberMath.fraction(-5_000L, TRACK), 0f)
        assertEquals(1f, ScrubberMath.fraction(TRACK * 4, TRACK), 0f)
        assertEquals(0L, ScrubberMath.clamp(-1L, TRACK))
        assertEquals(TRACK, ScrubberMath.clamp(TRACK * 4, TRACK))
        assertEquals(TRACK / 2, ScrubberMath.clamp(TRACK / 2, TRACK))
    }

    @Test
    fun `a fraction becomes a position, and the two conversions invert each other`() {
        assertEquals(TRACK / 2, ScrubberMath.position(0.5f, TRACK))
        assertEquals(TRACK, ScrubberMath.position(1f, TRACK))
        assertEquals(0L, ScrubberMath.position(0f, TRACK))
        // Asking for more than the track, or less than none of it, is the same as
        // asking for the ends.
        assertEquals(TRACK, ScrubberMath.position(4f, TRACK))
        assertEquals(0L, ScrubberMath.position(-4f, TRACK))

        (0..100).forEach { percent ->
            val position = ScrubberMath.position(percent / 100f, TRACK)
            assertEquals("$percent%", percent / 100f, ScrubberMath.fraction(position, TRACK), 0.005f)
        }
    }

    @Test
    fun `a track with no duration has nothing to seek to`() {
        assertEquals(0f, ScrubberMath.fraction(1_000L, 0L), 0f)
        assertEquals(0L, ScrubberMath.position(0.5f, 0L))
        assertEquals(0L, ScrubberMath.clamp(1_000L, 0L))
        assertEquals(0L, ScrubberMath.clamp(-1_000L, -5L))
    }

    @Test
    fun `a tap anywhere on the track seeks there, without the finger moving`() {
        val seeks = mutableListOf<Long>()
        showScrubber(positionMillis = 0L, onSeek = { seeks += it })

        composeTestRule.onNodeWithContentDescription(DESCRIPTION).performClick()
        composeTestRule.waitForIdle()

        // The tap lands in the middle of the track, so it means half of it. This is
        // the half of "click anywhere on the track or drag the knob to seek" that a
        // gesture detector waiting for touch slop would never answer at all.
        val sought = seeks.single()
        assertTrue(
            "a tap in the middle must seek to the middle, sought $sought",
            kotlin.math.abs(sought - TRACK / 2) < TRACK / 20,
        )
    }

    @Test
    fun `dragging left seeks backwards and stays inside the track`() {
        val seeks = mutableListOf<Long>()
        showScrubber(positionMillis = TRACK / 2, onSeek = { seeks += it })

        composeTestRule.onNodeWithContentDescription(DESCRIPTION).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        assertTrue("a drag left must seek, sought $seeks", seeks.isNotEmpty())
        assertTrue("a drag left must end before the middle, sought $seeks", seeks.last() < TRACK / 2)
        assertTrue("no seek may leave the track, sought $seeks", seeks.all { it in 0L..TRACK })
    }

    @Test
    fun `dragging right from the start cannot seek before the start`() {
        val seeks = mutableListOf<Long>()
        showScrubber(positionMillis = 0L, onSeek = { seeks += it })

        composeTestRule.onNodeWithContentDescription(DESCRIPTION).performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()

        assertTrue("no seek may leave the track, sought $seeks", seeks.all { it in 0L..TRACK })
    }

    @Test
    fun `the scrubber is named, and says where the track is`() {
        showScrubber(positionMillis = 104_000L, positionDescription = "1:44 of 4:08")

        val node = composeTestRule.onNodeWithContentDescription(DESCRIPTION)
        node.assertIsDisplayed()
        assertEquals(
            "1:44 of 4:08",
            node.fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
    }

    @Test
    fun `a track whose duration has not arrived yet still renders`() {
        showScrubber(positionMillis = 0L, durationMillis = 0L, onSeek = {})

        composeTestRule.onNodeWithContentDescription(DESCRIPTION).assertIsDisplayed()
    }

    private fun showScrubber(
        positionMillis: Long,
        durationMillis: Long = TRACK,
        onSeek: (Long) -> Unit = {},
        positionDescription: String? = null,
    ) {
        composeTestRule.setContent {
            // Reduced motion, so the fill is where the position says it is rather
            // than part-way through an animation the assertion did not wait for.
            MorseTheme(reducedMotion = true) {
                MorseScrubber(
                    positionMillis = positionMillis,
                    durationMillis = durationMillis,
                    onSeek = onSeek,
                    contentDescription = DESCRIPTION,
                    positionDescription = positionDescription,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private companion object {
        const val DESCRIPTION = "Seek position in track"

        /** The reference's own track length: 4:08, `S.music.dur = 248`. */
        const val TRACK = 248_000L
        const val TOLERANCE = 0.0001f
    }
}
