package app.morsecode.ui.broadcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's rules, as a value rather than as a screen.
 *
 * Choosing two phones is the floor for a broadcast, a laptop is not a recipient however
 * discoverable it is, and a device that appears twice in a discovered list is one device. These
 * are the three ways a picker gets a broadcast wrong before anything has been sent, so each of
 * them is asserted here rather than only through a button's enabled state.
 */
class BroadcastSelectionTest {

    private val ravi = BroadcastFixtures.ravi
    private val pixel = BroadcastFixtures.pixel
    private val samsung = BroadcastFixtures.samsung
    private val laptop = BroadcastFixtures.laptop

    private val discovered = BroadcastFixtures.discovered

    @Test
    fun `an empty selection offers every phone and asks for two`() {
        val selection = BroadcastSelection(peers = discovered)

        assertEquals(listOf("r", "p", "s"), selection.selectable.map { it.peerId })
        assertTrue(selection.selected.isEmpty())
        assertEquals(0, selection.selectedCount)
        assertEquals(2, selection.shortfall)
        assertFalse(selection.canStart)
    }

    @Test
    fun `a laptop on WebShare is not offered at all`() {
        val selection = BroadcastSelection(peers = discovered)

        assertFalse(
            "the browser is discoverable but cannot receive into an app it does not run",
            selection.selectable.any { it.peerId == laptop.peerId },
        )
        assertSame(
            "and tapping it changes nothing, wherever the tap came from",
            selection,
            selection.toggled(laptop.peerId),
        )
    }

    @Test
    fun `tapping a phone selects it and tapping it again lets it go`() {
        val selected = BroadcastSelection(peers = discovered).toggled(ravi.peerId)

        assertTrue(selected.isSelected(ravi.peerId))
        assertEquals(1, selected.selectedCount)
        assertEquals(listOf(ravi), selected.selected)

        val deselected = selected.toggled(ravi.peerId)
        assertFalse(deselected.isSelected(ravi.peerId))
        assertEquals(0, deselected.selectedCount)
    }

    @Test
    fun `one phone is not enough to start and the screen can say how many more`() {
        val one = BroadcastSelection(peers = discovered).toggled(pixel.peerId)

        assertFalse(one.canStart)
        assertEquals(1, one.shortfall)
        assertEquals("the chosen phone is the one that was tapped", listOf(pixel), one.selected)
    }

    @Test
    fun `two phones start a broadcast and nothing is missing from it`() {
        val two = BroadcastSelection(peers = discovered)
            .toggled(ravi.peerId)
            .toggled(samsung.peerId)

        assertTrue(two.canStart)
        assertEquals(0, two.shortfall)
        assertEquals(2, two.selectedCount)
    }

    @Test
    fun `the chosen phones keep the order discovery offered them in`() {
        val selection = BroadcastSelection(peers = discovered)
            .toggled(samsung.peerId)
            .toggled(ravi.peerId)

        assertEquals(
            "the list reads the way the screen reads, not the way taps arrived",
            listOf("r", "s"),
            selection.selected.map { it.peerId },
        )
    }

    @Test
    fun `a phone discovered twice is one phone to choose`() {
        val twice = BroadcastSelection(peers = listOf(ravi, ravi, pixel, pixel))
            .toggled(ravi.peerId)
            .toggled(pixel.peerId)

        assertEquals(listOf("r", "p"), twice.selectable.map { it.peerId })
        assertEquals("and it cannot count as the second phone by itself", 2, twice.selectedCount)
    }

    // ---------------------------------------------------------------- restoration

    @Test
    fun `a selection survives being written down and read back`() {
        val selection = BroadcastSelection(peers = discovered)
            .toggled(ravi.peerId)
            .toggled(samsung.peerId)

        val restored = BroadcastSelection.fromToken(discovered, selection.chosenToken)

        assertEquals(selection.chosen, restored.chosen)
        assertEquals(selection.selectedCount, restored.selectedCount)
    }

    @Test
    fun `a token names the chosen phones in one order, whatever order they were tapped`() {
        val forwards = BroadcastSelection(peers = discovered).toggled(ravi.peerId).toggled(pixel.peerId)
        val backwards = BroadcastSelection(peers = discovered).toggled(pixel.peerId).toggled(ravi.peerId)

        assertEquals(forwards.chosenToken, backwards.chosenToken)
        assertEquals("p,r", forwards.chosenToken)
    }

    @Test
    fun `a token naming a device that is no longer offered is not trusted`() {
        val restored = BroadcastSelection.fromToken(discovered, "r,gone,p,laptop")

        assertEquals(
            "a phone that has gone away, and a device that was never a recipient, are dropped",
            setOf("r", "p"),
            restored.chosen,
        )
    }

    @Test
    fun `an empty or missing token restores an empty selection rather than a stray one`() {
        assertEquals(emptySet<String>(), BroadcastSelection.fromToken(discovered, null).chosen)
        assertEquals(emptySet<String>(), BroadcastSelection.fromToken(discovered, "").chosen)
        assertEquals(emptySet<String>(), BroadcastSelection.fromToken(discovered, ",,").chosen)
    }
}
