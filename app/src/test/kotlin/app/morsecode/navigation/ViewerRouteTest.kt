package app.morsecode.navigation

import android.net.Uri
import app.morsecode.core.model.SortDirection
import app.morsecode.core.model.SortKey
import app.morsecode.core.model.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The viewer's route, which carries two things: the photograph that was tapped and
 * the order the grid was showing it in.
 *
 * The second one matters as much as the first. The Files sort is not persisted, so
 * the viewer is told what order to read the same list in — otherwise "3 of 15" would
 * be a different 3 of 15 from the one the user counted before tapping. Robolectric
 * is needed for `Uri.decode`, which is what makes an id with colons and slashes in
 * it survive the trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerRouteTest {

    @Test
    fun `the route names both arguments`() {
        assertEquals("viewer/{itemId}?sort={sort}", Routes.VIEWER)
        assertEquals("itemId", Routes.VIEWER_ARG)
        assertEquals("sort", Routes.VIEWER_SORT_ARG)
    }

    @Test
    fun `an id with colons and slashes survives the route`() {
        val id = "saf:primary:Download/Camera/IMG_2043.jpg"
        val encoded = Uri.encode(id)

        val route = Routes.viewer(encoded, SortOrder(SortKey.NAME, SortDirection.ASC))

        assertEquals("viewer/$encoded?sort=name.asc", route)
        assertEquals(id, Routes.decodeViewerArg(Uri.decode(encoded)))
    }

    @Test
    fun `a MediaStore id needs no decoding`() {
        assertEquals("4821", Routes.decodeViewerArg("4821"))
    }

    @Test
    fun `a uri that is already decoded is used as it is`() {
        val uri = "content://media/external/images/media/4821"

        assertEquals(uri, Routes.decodeViewerArg(uri))
    }

    @Test
    fun `a missing argument opens nothing rather than an invented id`() {
        assertEquals("", Routes.decodeViewerArg(null))
    }

    @Test
    fun `every sort order round trips through its token`() {
        SortKey.entries.forEach { key ->
            SortDirection.entries.forEach { direction ->
                val order = SortOrder(key, direction)
                assertEquals("${key.id}.${direction.id}", Routes.sortToken(order))
                assertEquals(order, Routes.parseSortToken(Routes.sortToken(order)))
            }
        }
    }

    @Test
    fun `an absent or malformed token falls back to the app's order`() {
        listOf(null, "", "nonsense", "name", "name.sideways", "date.desc.extra").forEach { token ->
            assertEquals("$token", SortOrder(), Routes.parseSortToken(token))
        }
    }
}
