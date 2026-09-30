package app.morsecode.navigation

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The video player's route, which carries one thing: the clip that was tapped.
 *
 * It deliberately carries less than the two routes beside it. The viewer and the music
 * player both take a sort token because both show a list — a deck of photographs, a
 * queue of tracks — and that list has to be the one the user was looking at rather than
 * a second query in a second order. A video player shows one clip and keeps no queue,
 * so there is no list whose order could disagree, and a token nothing reads would be a
 * promise the destination never keeps.
 *
 * Robolectric is here for `Uri.decode`, which is what lets an id with colons in it — the
 * shape MediaStore really produces — survive the trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoPlayerRouteTest {

    @Test
    fun `the route names its one argument`() {
        assertEquals("video/{itemId}", Routes.VIDEO)
        assertEquals("itemId", Routes.VIDEO_ARG)
    }

    @Test
    fun `an id with colons survives the route`() {
        val id = "content://media/external/video/media/8842"

        val route = Routes.video(Uri.encode(id))

        assertTrue(route.startsWith("video/"))
        // A uri is handed back by the platform exactly as it was given, so it is not
        // decoded a second time on the way in.
        assertEquals(id, Routes.decodeVideoArg(route.substringAfter("video/")))
    }

    @Test
    fun `an encoded id that is not a uri is decoded once`() {
        val encoded = Uri.encode("video:holiday 2019")

        assertEquals("video:holiday 2019", Routes.decodeVideoArg(encoded))
    }

    @Test
    fun `the route carries no sort token, because the player keeps no queue`() {
        val route = Routes.video(Uri.encode("content://media/external/video/media/1"))

        assertFalse("nothing here would read a sort token: $route", route.contains("sort"))
        assertFalse(route.contains("?"))
    }

    @Test
    fun `a video route is not mistaken for the two player routes beside it`() {
        // Three destinations, three prefixes: a clip must not land in the music player
        // or the photo viewer because a route string collided.
        assertTrue(Routes.video("v1").startsWith("video/"))
        assertFalse(Routes.video("v1").startsWith(Routes.MUSIC.substringBefore("{")))
        assertFalse(Routes.video("v1").startsWith(Routes.VIEWER.substringBefore("{")))
        assertEquals("video/v1", Routes.video("v1"))
    }
}
