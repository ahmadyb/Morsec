package app.morsecode.core.data.db

import app.morsecode.core.model.Accent
import app.morsecode.core.model.BrowserSession
import app.morsecode.core.model.BrowserSessionState
import app.morsecode.core.model.CrashReport
import app.morsecode.core.model.DeviceKind
import app.morsecode.core.model.HistoryEntry
import app.morsecode.core.model.LogEntry
import app.morsecode.core.model.LogLevel
import app.morsecode.core.model.MediaKind
import app.morsecode.core.model.Peer
import app.morsecode.core.model.RecentDevice
import app.morsecode.core.model.SafGrant
import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.SessionPhase
import app.morsecode.core.model.TransferItem
import app.morsecode.core.model.TransferSession
import app.morsecode.core.model.TransferState
import app.morsecode.core.model.TransportKind
import app.morsecode.core.model.WebTransferRecord
import app.morsecode.core.model.WebTransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for entity mapping.
 *
 * They pin the two properties that a schema change could silently break: enum
 * values are stored by their stable id, and no field is dropped on the way
 * through the database.
 */
class MappersTest {

    private val peer = Peer(
        peerId = "peer-ravi",
        displayName = "Ravi's Redmi",
        endpointId = "192.168.1.42:33456",
        transport = TransportKind.LAN,
        deviceKind = DeviceKind.PHONE,
        detail = "192.168.1.42 · Phone · LAN",
        appVersion = "1.0.0",
        supportsResume = true,
        supportsEncryption = true,
    )

    @Test
    fun `session round trips with its flattened peer`() {
        val session = TransferSession(
            sessionId = "s-1",
            peer = peer,
            phase = SessionPhase.ACTIVE,
            broadcastId = "b-7",
            startedEpochMillis = 1_700_000_000_000L,
            endedEpochMillis = 0L,
            pauseAll = true,
            failureReason = null,
        )
        val back = session.toEntity().toDomain()
        assertEquals(session.sessionId, back.sessionId)
        assertEquals(session.phase, back.phase)
        assertEquals(session.broadcastId, back.broadcastId)
        assertEquals(session.pauseAll, back.pauseAll)
        assertEquals(peer.peerId, back.peer.peerId)
        assertEquals(peer.displayName, back.peer.displayName)
        assertEquals(peer.endpointId, back.peer.endpointId)
        assertEquals(peer.transport, back.peer.transport)
        assertEquals(peer.deviceKind, back.peer.deviceKind)
        assertEquals(peer.detail, back.peer.detail)
        assertEquals(peer.appVersion, back.peer.appVersion)
        assertEquals(peer.supportsResume, back.peer.supportsResume)
        assertEquals(peer.supportsEncryption, back.peer.supportsEncryption)
    }

    @Test
    fun `phase and state are stored by stable id not by kotlin name`() {
        val session = TransferSession(sessionId = "s-2", peer = peer, phase = SessionPhase.PENDING_CONSENT)
        assertEquals("pending_consent", session.toEntity().phase)

        val item = item(state = TransferState.PAUSED_LOCAL)
        assertEquals("paused_local", item.toEntity().state)
        assertEquals("paused_local", item.toEntity().state)
        // Every state id must round trip.
        TransferState.entries.forEach { state ->
            assertEquals(state, item(state = state).toEntity().let { it.toDomain() }.state)
        }
    }

    @Test
    fun `item round trip preserves offsets and identity`() {
        val item = TransferItem(
            transferId = "t-1",
            sessionId = "s-1",
            batchId = "b-1",
            direction = SessionDirection.INBOUND,
            displayName = "sunset.jpg",
            relativePath = "Photos/sunset.jpg",
            mimeType = "image/jpeg",
            kind = MediaKind.IMAGE,
            totalBytes = 4_120_000L,
            lastModifiedEpochMillis = 1_699_000_000_000L,
            isFolderArchive = false,
            state = TransferState.RECEIVING,
            confirmedBytes = 1_024_000L,
            bytesPerSecond = 8_400_000L,
            sha256Hex = "f".repeat(64),
            failureReason = null,
            retryCount = 2,
            queuedEpochMillis = 1_700_000_000_000L,
            finishedEpochMillis = 0L,
            resultUriString = null,
            recipientPeerId = null,
        )
        val entity = item.toEntity(
            sourceUriString = "content://media/external/images/media/12",
            targetDirectory = "Download/Morsecode",
            queuePosition = 3,
        )
        val back = entity.toDomain()
        assertEquals(item, back)
        assertEquals("content://media/external/images/media/12", entity.sourceUriString)
        assertEquals("Download/Morsecode", entity.targetDirectory)
        assertEquals(3, entity.queuePosition)
        assertEquals(1_024_000L, entity.confirmedBytes)
    }

    @Test
    fun `unknown persisted enum values fall back instead of throwing`() {
        val entity = item(state = TransferState.QUEUED).toEntity().copy(state = "state_from_the_future")
        assertEquals(TransferState.FAILED_RETRYABLE, entity.toDomain().state)

        val mediaEntity = item().toEntity().copy(kind = "hologram")
        assertEquals(MediaKind.OTHER, mediaEntity.toDomain().kind)

        val sessionEntity = TransferSession(sessionId = "s", peer = peer).toEntity().copy(phase = "teleporting")
        assertEquals(SessionPhase.PENDING_CONSENT, sessionEntity.toDomain().phase)
    }

    @Test
    fun `history entry round trips`() {
        val entry = HistoryEntry(
            historyId = "h-1",
            transferId = "t-1",
            sessionId = "s-1",
            direction = SessionDirection.INBOUND,
            displayName = "report.pdf",
            mimeType = "application/pdf",
            kind = MediaKind.DOC,
            totalBytes = 18_200_000L,
            peerId = peer.peerId,
            peerName = peer.displayName,
            state = TransferState.COMPLETED,
            sha256Hex = "e".repeat(64),
            finishedEpochMillis = 1_700_000_500_000L,
            resultUriString = "content://media/external/file/9",
            broadcastId = "b-7",
        )
        assertEquals(entry, entry.toEntity().toDomain())
    }

    @Test
    fun `recent device, grant, log, crash, browser session and web transfer round trip`() {
        val device = RecentDevice(
            peerId = "peer-pixel",
            displayName = "Pixel 7X",
            transport = TransportKind.NEARBY,
            deviceKind = DeviceKind.PHONE,
            detail = "Nearby",
            lastSeenEpochMillis = 1_700_000_000_000L,
            lastSummary = "3 files · 18 MB",
            interactionCount = 4,
        )
        assertEquals(device, device.toEntity().toDomain())

        val grant = SafGrant(
            id = 2L,
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload",
            displayName = "Download",
            grantedEpochMillis = 1_700_000_000_000L,
            readWrite = true,
        )
        assertEquals(grant, grant.toEntity().toDomain())

        val log = LogEntry(
            id = 11L,
            timestampEpochMillis = 1_700_000_000_123L,
            level = LogLevel.WARN,
            tag = "transfer",
            message = "socket stalled, retrying",
            errorId = "E_SOCKET_STALL",
        )
        assertEquals(log, log.toEntity().toDomain())

        val crash = CrashReport(
            id = 3L,
            occurredEpochMillis = 1_700_000_000_000L,
            component = "TransferService",
            exceptionType = "java.io.IOException",
            message = "connection reset by peer",
            stackTrace = "java.io.IOException: connection reset by peer\n\tat app.morsecode.core.transfer.Pump.read",
            appVersion = "1.0.0",
            versionCode = 1,
            androidSdkInt = 23,
            deviceModel = "Huawei MYA-L10",
            recoveryNote = "Confirmed offsets were persisted; the transfer can resume.",
        )
        assertEquals(crash, crash.toEntity().toDomain())

        val browser = BrowserSession(
            sessionId = "web-1",
            tokenDigest = "d".repeat(64),
            userAgent = "Mozilla/5.0 (Windows NT 10.0) Chrome/126.0 Safari/537.36",
            remoteAddress = "192.168.1.77",
            state = BrowserSessionState.ACTIVE,
            requestedEpochMillis = 1_700_000_000_000L,
            acceptedEpochMillis = 1_700_000_010_000L,
            lastActivityEpochMillis = 1_700_000_900_000L,
            bytesDownloaded = 4_120_000L,
            bytesUploaded = 1_024L,
            requestCount = 7,
        )
        assertEquals(browser, browser.toEntity().toDomain())
        assertEquals("Chrome", browser.browserLabel)

        val webTransfer = WebTransferRecord(
            uploadId = "up-1",
            browserSessionId = "web-1",
            fileName = "notes.pdf",
            targetDirectory = "Documents",
            totalBytes = 2_000_000L,
            receivedBytes = 1_500_000L,
            partialPath = "<app-data>/incoming/up-1.part",
            state = WebTransferState.PARTIAL,
            updatedEpochMillis = 1_700_000_500_000L,
        )
        assertEquals(webTransfer, webTransfer.toEntity().toDomain())
        assertEquals(0.75f, webTransfer.progressFraction, 0.0001f)
        assertEquals(1_500_000L, webTransfer.resumeOffset)
    }

    @Test
    fun `nullable peer fields survive a session round trip`() {
        val session = TransferSession(
            sessionId = "s-3",
            peer = Peer(
                peerId = "peer-laptop",
                displayName = "Office Laptop",
                endpointId = "192.168.1.9:33456",
                transport = TransportKind.LAN,
                deviceKind = DeviceKind.DESKTOP,
            ),
            phase = SessionPhase.ENDED,
            failureReason = "peer_left_network",
        )
        val back = session.toEntity().toDomain()
        assertNull(back.peer.appVersion)
        assertTrue(!back.peer.supportsResume)
        assertEquals("peer_left_network", back.failureReason)
        assertEquals(Accent.default, Accent.fromId("sunflower"))
    }

    private fun item(state: TransferState = TransferState.QUEUED) = TransferItem(
        transferId = "t-${state.id}",
        sessionId = "s-1",
        batchId = "b-1",
        direction = SessionDirection.OUTBOUND,
        displayName = "file.bin",
        state = state,
    )
}
