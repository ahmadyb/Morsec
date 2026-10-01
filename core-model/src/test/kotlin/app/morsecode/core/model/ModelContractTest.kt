package app.morsecode.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelContractTest {

    @Test
    fun `file categories keep the mockup order and ids`() {
        assertEquals(
            listOf("photos", "videos", "music", "apps", "files"),
            FileCategory.ordered.map { it.id },
        )
        assertEquals(FileCategory.PHOTOS, FileCategory.fromId("photos"))
        assertEquals(FileCategory.FILES, FileCategory.fromId("files"))
        assertEquals(null, FileCategory.fromId("documents"))
        assertEquals(2, FileCategory.indexOf(FileCategory.MUSIC))
    }

    @Test
    fun `mime and extension mapping never crashes on junk`() {
        assertEquals(MediaKind.IMAGE, MediaKind.fromMimeType("image/jpeg"))
        assertEquals(MediaKind.VIDEO, MediaKind.fromMimeType("video/mp4"))
        assertEquals(MediaKind.AUDIO, MediaKind.fromMimeType("audio/flac"))
        assertEquals(MediaKind.APK, MediaKind.fromMimeType("application/vnd.android.package-archive"))
        assertEquals(MediaKind.ZIP, MediaKind.fromMimeType("application/zip"))
        assertEquals(MediaKind.DOC, MediaKind.fromMimeType("application/pdf"))
        assertEquals(MediaKind.OTHER, MediaKind.fromMimeType(null))
        assertEquals(MediaKind.OTHER, MediaKind.fromMimeType(""))
        assertEquals(MediaKind.OTHER, MediaKind.fromMimeType("x-y/z"))

        assertEquals(MediaKind.IMAGE, MediaKind.fromExtension("IMG_2043.JPG"))
        assertEquals(MediaKind.VIDEO, MediaKind.fromExtension("holiday_2019.mp4"))
        assertEquals(MediaKind.AUDIO, MediaKind.fromExtension("live_set_final.flac"))
        assertEquals(MediaKind.ZIP, MediaKind.fromExtension("notes_backup.zip"))
        assertEquals(MediaKind.APK, MediaKind.fromExtension("app-release.apk"))
        assertEquals(MediaKind.DOC, MediaKind.fromExtension("invoice_2024.pdf"))
        assertEquals(MediaKind.OTHER, MediaKind.fromExtension("README"))

        // A missing MIME type falls back to the extension.
        assertEquals(MediaKind.VIDEO, MediaKind.of("clip_01.mp4", null))
        // A lying extension does not override a real MIME type.
        assertEquals(MediaKind.IMAGE, MediaKind.of("photo.mp4", "image/png"))
    }

    @Test
    fun `sorting keeps folders first in both directions`() {
        val items = listOf(
            media("zeta.bin", size = 10, folder = false, date = 5),
            media("alpha", size = 900, folder = true, date = 1),
            media("beta.bin", size = 500, folder = false, date = 9),
        )
        val descending = items.applySortOrder(SortOrder(SortKey.DATE, SortDirection.DESC)) { it }
        assertEquals(listOf("alpha", "beta.bin", "zeta.bin"), descending.map { it.displayName })

        val bySizeAsc = items.applySortOrder(SortOrder(SortKey.SIZE, SortDirection.ASC)) { it }
        assertEquals(listOf("alpha", "zeta.bin", "beta.bin"), bySizeAsc.map { it.displayName })

        val byNameDesc = items.applySortOrder(SortOrder(SortKey.NAME, SortDirection.DESC)) { it }
        assertEquals(listOf("alpha", "zeta.bin", "beta.bin"), byNameDesc.map { it.displayName })

        val byType = items.applySortOrder(SortOrder(SortKey.TYPE, SortDirection.ASC)) { it }
        assertEquals("alpha", byType.first().displayName)
    }

    @Test
    fun `duplicate policy cycles through the four approved options`() {
        val cycle = generateSequence(DuplicatePolicy.RENAME) { it.next() }.take(5).toList()
        assertEquals(
            listOf(
                DuplicatePolicy.RENAME,
                DuplicatePolicy.OVERWRITE,
                DuplicatePolicy.SKIP,
                DuplicatePolicy.ASK,
                DuplicatePolicy.RENAME,
            ),
            cycle,
        )
        assertEquals(4, DuplicatePolicy.ordered.size)
    }

    @Test
    fun `broadcast cap cycles from two to eight phones`() {
        var settings = MorseSettings(broadcastPeerLimit = 2)
        val seen = mutableListOf(settings.broadcastPeerLimit)
        repeat(7) {
            settings = settings.withNextBroadcastLimit()
            seen += settings.broadcastPeerLimit
        }
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 8, 2), seen)
    }

    @Test
    fun `settings reject impossible values`() {
        val limitFailure = runCatching { MorseSettings(broadcastPeerLimit = 99) }
        assertTrue(limitFailure.isFailure)
        val portFailure = runCatching { MorseSettings(webSharePort = 80) }
        assertTrue(portFailure.isFailure)
        assertNotNull(MorseSettings(webSharePort = NetworkPorts.WEBSHARE_HTTP))
    }

    @Test
    fun `accents carry the approved hex triples`() {
        assertEquals(0xFFFACC15L, Accent.SUNFLOWER.accentArgb)
        assertEquals(0xFFEAB308L, Accent.SUNFLOWER.accentDarkArgb)
        assertEquals(0xFF1A1400L, Accent.SUNFLOWER.onAccentArgb)
        // Violet and ember draw white ink on the accent, everything else dark ink.
        assertEquals(0xFFFFFFFFL, Accent.VIOLET.onAccentArgb)
        assertEquals(0xFFFFFFFFL, Accent.EMBER.onAccentArgb)
        assertEquals(0xFF1A1400L, Accent.LEAF.onAccentArgb)
        assertEquals(0xFF1A1400L, Accent.SKY.onAccentArgb)
        assertEquals(
            listOf("sunflower", "leaf", "ember", "violet", "sky"),
            Accent.ordered.map { it.id },
        )
        assertEquals(Accent.SUNFLOWER, Accent.fromId("nonsense"))
    }

    @Test
    fun `transport badges match the mockup chips`() {
        assertEquals("LAN", TransportKind.LAN.badge)
        assertEquals("NEARBY", TransportKind.NEARBY.badge)
        assertEquals("WEB", TransportKind.WEB.badge)
        assertEquals(TransportKind.NEARBY, TransportKind.fromId("nearby"))
    }

    @Test
    fun `peer avatar letters fall back to the display name`() {
        val peer = Peer(
            peerId = "p1",
            displayName = "  ravi's redmi ",
            endpointId = "192.168.1.42:33456",
            transport = TransportKind.LAN,
            deviceKind = DeviceKind.PHONE,
        )
        assertEquals('R', peer.letter)
        assertTrue(peer.isBroadcastEligible)
        val browser = peer.copy(deviceKind = DeviceKind.BROWSER)
        assertFalse("browsers are not broadcast recipients", browser.isBroadcastEligible)
        val blank = peer.copy(displayName = "")
        assertEquals('?', blank.letter)
    }

    @Test
    fun `ports and timings are the documented defaults`() {
        assertEquals(33455, NetworkPorts.WEBSHARE_HTTP)
        assertEquals(33456, NetworkPorts.PEER_CONTROL)
        assertEquals(33457, NetworkPorts.DISCOVERY_BEACON)
        assertEquals(1200L, NetworkPorts.BEACON_INTERVAL_MILLIS)
        assertEquals(4800L, NetworkPorts.PEER_STALE_AFTER_MILLIS)
        assertTrue(NetworkPorts.isMorsecodePort(33455))
        assertFalse(NetworkPorts.isMorsecodePort(8080))
        assertEquals(listOf(33455, 33456, 33457), NetworkPorts.requiredPorts)
    }

    @Test
    fun `browser names are classified offline`() {
        assertEquals("Chrome", BrowserNames.fromUserAgent("Mozilla/5.0 (Windows NT 10.0) Chrome/124.0.0.0 Safari/537.36"))
        assertEquals("Firefox", BrowserNames.fromUserAgent("Mozilla/5.0 (X11; Linux x86_64; rv:126.0) Gecko/20100101 Firefox/126.0"))
        assertEquals("Edge", BrowserNames.fromUserAgent("Mozilla/5.0 Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0"))
        assertEquals("Safari", BrowserNames.fromUserAgent("Mozilla/5.0 (Macintosh) Version/17.4 Safari/605.1.15"))
        assertEquals("Browser", BrowserNames.fromUserAgent(""))
        assertEquals(DeviceKind.DESKTOP, BrowserNames.deviceClass("Mozilla/5.0 (X11; Linux x86_64)"))
        assertEquals(DeviceKind.PHONE, BrowserNames.deviceClass("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0)"))
        assertEquals(DeviceKind.TABLET, BrowserNames.deviceClass("Mozilla/5.0 (iPad)"))
    }

    @Test
    fun `webshare status derives its address from the bound interface`() {
        val stopped = WebShareStatus()
        assertEquals(null, stopped.displayAddress)
        assertEquals(null, stopped.displayUrl)

        val running = WebShareStatus(
            running = true,
            boundPort = 33455,
            interfaceName = "wlan0",
            hostAddress = "192.168.1.24",
            sessions = listOf(
                BrowserSession(
                    sessionId = "s1",
                    tokenDigest = "digest",
                    userAgent = "Mozilla/5.0 Chrome/124.0.0.0",
                    remoteAddress = "192.168.1.88",
                    state = BrowserSessionState.ACTIVE,
                ),
                BrowserSession(
                    sessionId = "s2",
                    tokenDigest = "digest2",
                    userAgent = null,
                    remoteAddress = "192.168.1.90",
                ),
            ),
        )
        assertEquals("192.168.1.24:33455", running.displayAddress)
        assertEquals("http://192.168.1.24:33455", running.displayUrl)
        assertEquals(1, running.liveSessionCount)
        assertEquals(1, running.pendingCount)
        assertEquals("Chrome", running.sessions[0].browserLabel)
        assertEquals("192.168.1.90", running.sessions[1].browserLabel)
    }

    @Test
    fun `the current milestone is two and transport areas stay gated`() {
        assertEquals(2, FeatureReadiness.CURRENT_MILESTONE)
        assertTrue(FeatureReadiness.isAvailable(FeatureArea.ONBOARDING))
        assertTrue(FeatureReadiness.isAvailable(FeatureArea.SETTINGS))
        assertTrue(FeatureReadiness.isAvailable(FeatureArea.FILE_BROWSING))
        assertTrue(FeatureReadiness.isAvailable(FeatureArea.DIAGNOSTICS))
        assertFalse(FeatureReadiness.isAvailable(FeatureArea.LAN_TRANSPORT))
        assertFalse(FeatureReadiness.isAvailable(FeatureArea.WEBSHARE_SERVER))
        assertFalse(FeatureReadiness.isAvailable(FeatureArea.MEDIA_PLAYBACK))
        assertFalse("the product is not complete at milestone two", FeatureReadiness.isComplete())
        assertTrue(FeatureReadiness.gated.isNotEmpty())
        // Gated areas must be listed in delivery order so the report is readable.
        val milestones = FeatureReadiness.gated.map { it.deliveredInMilestone }
        assertEquals(milestones.sorted(), milestones)
    }

    @Test
    fun `transfer item progress is clamped and derived from confirmed bytes`() {
        val item = TransferItem(
            transferId = "t1",
            sessionId = "s1",
            batchId = "b1",
            direction = SessionDirection.OUTBOUND,
            displayName = "holiday_2019.mp4",
            kind = MediaKind.VIDEO,
            totalBytes = 144_000_000L,
            confirmedBytes = 48_900_000L,
            state = TransferState.SENDING,
        )
        assertEquals(33, item.progressPercent)
        assertEquals(0.339583f, item.progressFraction, 0.0001f)
        assertTrue(item.actions.pause)
        assertFalse(item.isComplete)

        val overflow = item.copy(confirmedBytes = 999_999_999L)
        assertEquals(100, overflow.progressPercent)

        val unknownSize = item.copy(totalBytes = 0L)
        assertEquals(0f, unknownSize.progressFraction, 0.0f)
        assertEquals(0, unknownSize.progressPercent)
    }

    private fun media(
        name: String,
        size: Long,
        folder: Boolean,
        date: Long,
    ) = MediaItem(
        id = name,
        displayName = name,
        kind = if (folder) MediaKind.FOLDER else MediaKind.DOC,
        mimeType = null,
        sizeBytes = size,
        dateModifiedEpochMillis = date,
        isFolder = folder,
    )
}
