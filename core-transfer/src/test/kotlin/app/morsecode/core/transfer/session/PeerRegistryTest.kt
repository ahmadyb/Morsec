package app.morsecode.core.transfer.session

import app.morsecode.core.model.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

public class PeerRegistryTest {
    @Test
    public fun snapshotsAreDeterministicallySortedAndIdentityUpdatesKeepFirstSeen() {
        val registry = PeerRegistry(capacity = 3, staleAfterMillis = 4_800L)
        registry.observe(advertisement("00000000000000000000000000000002", "Zulu"), 10L)
        registry.observe(advertisement("00000000000000000000000000000001", "alpha"), 12L)

        val updated = registry.observe(
            advertisement("00000000000000000000000000000002", "Beta"),
            30L,
        )

        assertEquals(listOf("alpha", "Beta"), updated.map { it.advertisement.displayName })
        val peer = registry.selected(id("00000000000000000000000000000002"), 30L)
        assertNotNull(peer)
        assertEquals(10L, peer?.firstSeenElapsedMillis)
        assertEquals(30L, peer?.lastSeenElapsedMillis)
        assertFalse(requireNotNull(peer).isTrustAnchor)
    }

    @Test
    public fun boundedCapacityEvictsOldestWithIdentityTieBreak() {
        val registry = PeerRegistry(capacity = 2, staleAfterMillis = 4_800L)
        val first = id("00000000000000000000000000000001")
        val second = id("00000000000000000000000000000002")
        val third = id("00000000000000000000000000000003")
        registry.observe(advertisement(first.value, "First"), 100L)
        registry.observe(advertisement(second.value, "Second"), 100L)
        registry.observe(advertisement(third.value, "Third"), 101L)

        assertNull(registry.selected(first, 101L))
        assertNotNull(registry.selected(second, 101L))
        assertNotNull(registry.selected(third, 101L))
        assertEquals(2, registry.size(101L))
    }

    @Test
    public fun stalePeersExpireAtTheInjectedMonotonicBoundary() {
        val registry = PeerRegistry(capacity = 2, staleAfterMillis = 100L)
        val peerId = id("00000000000000000000000000000001")
        registry.observe(advertisement(peerId.value, "Peer"), 500L)

        assertNotNull(registry.selected(peerId, 599L))
        assertNull(registry.selected(peerId, 600L))
        assertTrue(registry.snapshot(600L).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    public fun backwardClockIsRejected() {
        val registry = PeerRegistry()
        registry.snapshot(100L)
        registry.snapshot(99L)
    }

    @Test(expected = IllegalArgumentException::class)
    public fun unsafeEndpointIsRejectedBeforeItCanEnterTheRegistry() {
        TransportEndpoint("lan", "192.168.1.12/24")
    }

    private fun advertisement(peerId: String, name: String): PeerAdvertisement = PeerAdvertisement(
        peerInstanceId = id(peerId),
        displayName = name,
        appVersion = "1.0.0",
        transport = TransportKind.LAN,
        endpoint = TransportEndpoint("lan", "192.168.1.12"),
        protocolRange = ProtocolRange.CURRENT,
        capabilities = SessionCapabilities.controlOnly(TransportKind.LAN),
    )

    private fun id(value: String): PeerInstanceId = PeerInstanceId(value)
}
