package app.morsecode.navigation

import android.net.Uri
import app.morsecode.core.model.SortDirection
import app.morsecode.core.model.SortKey
import app.morsecode.core.model.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The music player's route, which carries two things: the track that was tapped and
 * the order the Music tab was showing it in.
 *
 * The second matters as much as the first. The player's queue is the same list Files
 * was showing, so "Up next · 9 songs" has to count the nine the user was looking at
 * rather than a second query in a second order. Robolectric is here for `Uri.decode`,
 * which is what lets an id with colons in it — the shape MediaStore really produces —
 * survive the trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicPlayerRouteTest {

    @Test
    fun `the route names both arguments`() {
        assertEquals("music/{itemId}?sort={sort}", Routes.MUSIC)
        assertEquals("itemId", Routes.MUSIC_ARG)
        assertEquals("sort", Routes.MUSIC_SORT_ARG)
    }

    @Test
    fun `an id with colons survives the route`() {
        val id = "content://media/external/audio/media/4217"

        val route = Routes.music(Uri.encode(id), SortOrder(SortKey.NAME, SortDirection.ASC))

        assertTrue(route.startsWith("music/"))
        // A uri is handed back by the platform exactly as it was given, so it is not
        // decoded a second time on the way in.
        assertEquals(id, Routes.decodeMusicArg(route.substringAfter("music/").substringBefore("?")))
    }

    @Test
    fun `the order the list was showing travels with the track`() {
        SortKey.entries.forEach { key ->
            SortDirection.entries.forEach { direction ->
                val order = SortOrder(key, direction)

                val route = Routes.music(Uri.encode("audio:1"), order)
                val token = route.substringAfter("${Routes.MUSIC_SORT_ARG}=")

                assertEquals(Routes.sortToken(order), token)
                assertEquals(order, Routes.parseSortToken(token))
            }
        }
    }

    @Test
    fun `a missing or malformed sort token means the app's default order`() {
        assertEquals(SortOrder(), Routes.parseSortToken(null))
        assertEquals(SortOrder(), Routes.parseSortToken(""))
        assertEquals(SortOrder(), Routes.parseSortToken("name"))
        assertEquals(SortOrder(), Routes.parseSortToken("nonsense.up"))
        assertEquals(SortOrder(), Routes.parseSortToken("name.asc.extra"))
    }

    @Test
    fun `an absent track id decodes to nothing rather than to a guess`() {
        assertEquals("", Routes.decodeMusicArg(null))
        assertEquals("", Routes.decodeMusicArg(""))
    }

    @Test
    fun `the player and the viewer are different destinations over the same argument names`() {
        // Both take an item id and a sort token, and they must not be able to answer
        // for each other: a photograph opened in the player would be a silent bug.
        assertEquals("viewer/{itemId}?sort={sort}", Routes.VIEWER)
        assertEquals("music/{itemId}?sort={sort}", Routes.MUSIC)
        assertTrue(Routes.viewer(Uri.encode("1"), SortOrder()) != Routes.music(Uri.encode("1"), SortOrder()))
    }
}
