package app.morsecode.navigation

import app.morsecode.ui.transfer.TransferLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The duplex session's route: one destination, and the token that says which end is being read.
 *
 * There is no `/queue` here and no route that would become one, because the master prompt keeps
 * queue management inside these two views rather than in a screen of its own. The test says so
 * in the place the routes are defined, so a queue route cannot be added without a test failing
 * next to it.
 */
class TransferRouteTest {

    @Test
    fun `the route names its one argument`() {
        assertEquals("session/{layout}", Routes.TRANSFER)
        assertEquals("layout", Routes.TRANSFER_ARG)
    }

    @Test
    fun `both layouts have a route and each reads back as itself`() {
        TransferLayout.entries.forEach { layout ->
            val route = Routes.transfer(layout.id)

            assertEquals("session/${layout.id}", route)
            assertEquals(layout, TransferLayout.fromId(route.substringAfter("session/")))
        }
    }

    @Test
    fun `a token that names no layout gets the outbound view rather than a crash`() {
        assertEquals(TransferLayout.SENDING_FIRST, TransferLayout.fromId("nonsense"))
        assertEquals(TransferLayout.SENDING_FIRST, TransferLayout.fromId(null))
        assertEquals(TransferLayout.SENDING_FIRST, TransferLayout.fromId(""))
    }

    @Test
    fun `there is no queue destination`() {
        val routeConstants = listOf(
            Routes.ONBOARDING,
            Routes.CONNECT,
            Routes.FILES,
            Routes.HISTORY,
            Routes.SETTINGS,
            Routes.LOGS,
            Routes.CRASHES,
            Routes.DOCTOR,
            Routes.HELP,
            Routes.WEBSHARE,
            Routes.FOLDER,
            Routes.VIEWER,
            Routes.MUSIC,
            Routes.VIDEO,
            Routes.TRANSFER,
        )

        routeConstants.forEach { route ->
            assertFalse("$route must not be a queue screen", route.contains("queue"))
        }
        assertFalse(Routes.reserved.any { it.contains("queue") })
    }

    @Test
    fun `the two transfer views are one destination, not two routes that could drift`() {
        val sending = Routes.transfer(TransferLayout.SENDING_FIRST.id)
        val receiving = Routes.transfer(TransferLayout.RECEIVING_FIRST.id)

        assertTrue(sending.startsWith("session/"))
        assertTrue(receiving.startsWith("session/"))
        assertEquals(sending.substringBefore('/'), receiving.substringBefore('/'))
    }
}
