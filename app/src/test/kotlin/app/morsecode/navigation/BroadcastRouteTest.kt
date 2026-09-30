package app.morsecode.navigation

import app.morsecode.ui.broadcast.BroadcastFixtures
import app.morsecode.ui.broadcast.BroadcastSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The broadcast flow's five routes.
 *
 * Two decisions are asserted here rather than only written down. The choice of literal route
 * segments — one per screen — keeps every broadcast destination addressable on its own, which
 * is what lets one receiving screen stand in for the reference's three phone frames; and the
 * chosen-phones argument is the selection model's own token, so the sender cannot be handed a
 * set of phones the picker could not have offered.
 */
class BroadcastRouteTest {

    @Test
    fun `every screen in the flow has its own route`() {
        assertEquals("broadcast/pick", Routes.BROADCAST_PICK)
        assertEquals("broadcast/sender?chosen={chosen}", Routes.BROADCAST_SENDER)
        assertEquals("broadcast/sender/complete", Routes.BROADCAST_SENT)
        assertEquals("broadcast/receiver/{recipient}", Routes.BROADCAST_RECEIVER)
        assertEquals("broadcast/receiver/{recipient}/complete", Routes.BROADCAST_RECEIVED)
    }

    @Test
    fun `the sender's route carries the chosen phones`() {
        assertEquals("broadcast/sender?chosen=r,p", Routes.broadcastSender("r,p"))
        assertEquals("broadcast/sender?chosen=", Routes.broadcastSender(""))
        assertEquals(
            "the argument is named where the pattern names it",
            "chosen",
            Routes.BROADCAST_CHOSEN_ARG,
        )
        assertTrue(Routes.BROADCAST_SENDER.contains("{${Routes.BROADCAST_CHOSEN_ARG}}"))
    }

    @Test
    fun `a phone's receiving route names that phone`() {
        assertEquals("broadcast/receiver/r", Routes.broadcastReceiver("r"))
        assertEquals("recipient", Routes.BROADCAST_RECIPIENT_ARG)
        assertTrue(Routes.BROADCAST_RECEIVER.contains("{${Routes.BROADCAST_RECIPIENT_ARG}}"))
    }

    @Test
    fun `a phone's completion is that phone's receiving route plus one segment`() {
        // Derived rather than written twice, so the two cannot drift into two different phones.
        listOf("r", "p", "s").forEach { phone ->
            val receiving = Routes.broadcastReceiver(phone)
            val completion = Routes.broadcastReceived(phone)

            assertEquals("$receiving/complete", completion)
            assertEquals("the same phone is named in both", phone, completion.substringAfter("receiver/").substringBefore('/'))
        }
    }

    @Test
    fun `the chosen token the route carries is the selection's own`() {
        val selection = BroadcastSelection(peers = BroadcastFixtures.discovered, chosen = setOf("p", "r"))
        val route = Routes.broadcastSender(selection.chosenToken)
        val token = route.substringAfter("chosen=")

        assertEquals("the token is written in one order however it was chosen", "p,r", token)
        assertEquals(
            "and reading it back offers the same phones, in the discovery's order",
            listOf("r", "p"),
            BroadcastSelection.fromToken(BroadcastFixtures.discovered, token).selected.map { it.peerId },
        )
    }

    @Test
    fun `a token naming a device the picker cannot offer is not obeyed`() {
        val route = Routes.broadcastSender("r,o")
        val token = route.substringAfter("chosen=")

        assertEquals(
            "the browser in the discovered list is not a recipient, whatever the route says",
            listOf("r"),
            BroadcastSelection.fromToken(BroadcastFixtures.discovered, token).selected.map { it.peerId },
        )
    }

    @Test
    fun `no broadcast route is a queue, a manual address or a pairing code`() {
        val routes = listOf(
            Routes.BROADCAST_PICK,
            Routes.BROADCAST_SENDER,
            Routes.BROADCAST_SENT,
            Routes.BROADCAST_RECEIVER,
            Routes.BROADCAST_RECEIVED,
        )

        routes.forEach { route ->
            assertFalse("$route must not be a queue screen", route.contains("queue"))
            assertFalse("$route must not offer a manual address", route.contains("manual") || route.contains("address"))
            assertFalse("$route must not be a pairing screen", route.contains("pair") || route.contains("qr"))
        }
        assertFalse(Routes.reserved.any { it.contains("broadcast") })
    }

    @Test
    fun `the flow starts where the app can reach it`() {
        // The picker is a top-level-style destination rather than an argument of another
        // screen, so Connect can open it without first choosing a layout or a phone.
        assertFalse(Routes.BROADCAST_PICK.contains("{"))
        assertEquals("broadcast", Routes.BROADCAST_PICK.substringBefore('/'))
    }
}
