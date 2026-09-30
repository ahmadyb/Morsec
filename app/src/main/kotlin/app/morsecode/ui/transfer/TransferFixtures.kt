package app.morsecode.ui.transfer

import app.morsecode.core.model.MediaKind

/**
 * A sample duplex session, standing in for the transfer engine until milestone 5.
 *
 * **This is a fixture and not a simulated engine.** Nothing here advances, ticks, retries
 * itself or produces a byte: it is one fixed session with one row in each of the nine
 * states, so that the duplex views can be built, reviewed and tested against the whole
 * per-file matrix now, rather than after the engine exists. Every value below was chosen
 * once and is written down here; no value is generated, none is computed from the clock,
 * and nothing in the app reads this file except the session view model, which milestone 5
 * replaces with the engine's own session.
 *
 * The rows the reference document draws are kept as they are — `holiday_2019.mp4` moving
 * at 6.2 MB/s, `IMG_2043.jpg` queued behind it, `live_set_final.flac` held at 39.7 MB of
 * 64, `notes_backup.zip` arriving in the other direction at 8.4 MB/s — so this screen can
 * be held next to the mockup. The other states the master prompt's matrix names are here
 * too: a delivered file with its checksum answer, one being verified, one that failed, an
 * incoming file the user cancelled, and one skipped by policy (the reference's own summary
 * counts "1 skipped" without ever drawing such a row).
 *
 * The peer each view opens on is the reference's own: the LAN phone for the outbound view,
 * the Nearby phone for the inbound one, so the transport label reads "LAN" or "Nearby"
 * exactly as the app's copy does everywhere else.
 */
public object TransferFixtures {

    private const val MEGABYTE: Long = 1_000_000L

    /** Ravi's Redmi (`PEERS.r`), on the local network. */
    private val lanPeer = TransferPeer(
        id = "r",
        name = "Ravi's Redmi",
        letter = "R",
        transport = "LAN",
        address = "192.168.1.42",
    )

    /** Pixel 7X (`PEERS.p`), within Nearby range. */
    private val nearbyPeer = TransferPeer(
        id = "p",
        name = "Pixel 7X",
        letter = "P",
        transport = "Nearby",
        address = "Bluetooth 5.2",
    )

    /** 144 MB outbound, 48.9 MB so far, 6.2 MB/s. */
    private val holiday = TransferItem(
        id = "v1",
        sessionId = SESSION_ID,
        direction = TransferDirection.OUTGOING,
        fileName = "holiday_2019.mp4",
        kind = MediaKind.VIDEO,
        totalBytes = 144 * MEGABYTE,
        transferredBytes = 48_900_000L,
        speedBytesPerSecond = 6_200_000L,
        state = TransferState.SENDING,
    )

    /** Queued behind it, with nothing transferred yet. */
    private val photo = TransferItem(
        id = "i1",
        sessionId = SESSION_ID,
        direction = TransferDirection.OUTGOING,
        fileName = "IMG_2043.jpg",
        kind = MediaKind.IMAGE,
        totalBytes = 4 * MEGABYTE + 100_000L,
        transferredBytes = 0L,
        state = TransferState.QUEUED,
    )

    /** Held at 39.7 MB of 64, so the row has a resume offset worth printing. */
    private val liveSet = TransferItem(
        id = "a1",
        sessionId = SESSION_ID,
        direction = TransferDirection.OUTGOING,
        fileName = "live_set_final.flac",
        kind = MediaKind.AUDIO,
        totalBytes = 64 * MEGABYTE,
        transferredBytes = 39_700_000L,
        state = TransferState.PAUSED,
    )

    /** Delivered, with the checksum compared and agreed. */
    private val backup = TransferItem(
        id = "z1",
        sessionId = SESSION_ID,
        direction = TransferDirection.OUTGOING,
        fileName = "notes_backup.zip",
        kind = MediaKind.ZIP,
        totalBytes = 18 * MEGABYTE + 200_000L,
        transferredBytes = 18 * MEGABYTE + 200_000L,
        state = TransferState.DONE,
        verification = VerificationOutcome.VERIFIED,
    )

    /** Stopped by the connection rather than by the user, and retryable. */
    private val budget = TransferItem(
        id = "d1",
        sessionId = SESSION_ID,
        direction = TransferDirection.OUTGOING,
        fileName = "budget.xlsx",
        kind = MediaKind.DOC,
        totalBytes = 412_000L,
        transferredBytes = 190_000L,
        state = TransferState.FAILED,
    )

    /** Every byte delivered; the checksum is being compared. */
    private val clip = TransferItem(
        id = "v2",
        sessionId = SESSION_ID,
        direction = TransferDirection.OUTGOING,
        fileName = "clip_07.mp4",
        kind = MediaKind.VIDEO,
        totalBytes = 64 * MEGABYTE,
        transferredBytes = 64 * MEGABYTE,
        state = TransferState.VERIFYING,
    )

    /** Arriving at 8.4 MB/s. */
    private val incomingBackup = TransferItem(
        id = "z2",
        sessionId = SESSION_ID,
        direction = TransferDirection.INCOMING,
        fileName = "notes_backup.zip",
        kind = MediaKind.ZIP,
        totalBytes = 18 * MEGABYTE + 200_000L,
        transferredBytes = 13_100_000L,
        speedBytesPerSecond = 8_400_000L,
        state = TransferState.RECEIVING,
    )

    /** Arrived and verified from the other side. */
    private val incomingPhoto = TransferItem(
        id = "i2",
        sessionId = SESSION_ID,
        direction = TransferDirection.INCOMING,
        fileName = "IMG_2043.jpg",
        kind = MediaKind.IMAGE,
        totalBytes = 4 * MEGABYTE + 100_000L,
        transferredBytes = 4 * MEGABYTE + 100_000L,
        state = TransferState.DONE,
        verification = VerificationOutcome.VERIFIED,
    )

    /** The duplicate the conflict policy skipped rather than copied again. */
    private val skippedPhoto = TransferItem(
        id = "i3",
        sessionId = SESSION_ID,
        direction = TransferDirection.INCOMING,
        fileName = "IMG_2044.jpg",
        kind = MediaKind.IMAGE,
        totalBytes = 4 * MEGABYTE + 300_000L,
        transferredBytes = 0L,
        state = TransferState.SKIPPED,
    )

    /** An incoming file the user stopped part way through. */
    private val cancelledNote = TransferItem(
        id = "a2",
        sessionId = SESSION_ID,
        direction = TransferDirection.INCOMING,
        fileName = "voice_note.m4a",
        kind = MediaKind.AUDIO,
        totalBytes = 1_800_000L,
        transferredBytes = 600_000L,
        state = TransferState.CANCELLED,
    )

    /** Everything going out, in the order the reference lists it plus the three it does not. */
    private val outbound = listOf(holiday, photo, liveSet, backup, budget, clip)

    /** Everything coming in. */
    private val inbound = listOf(incomingBackup, incomingPhoto, skippedPhoto, cancelledNote)

    /** The session's identity; the engine will mint this, the fixture writes it down once. */
    private const val SESSION_ID: String = "session-fixture-1"

    /**
     * The session a view opens on.
     *
     * One session, two readings: the outbound view is shown the LAN phone, the inbound view
     * the Nearby one, and both carry the same files, because they are the two ends of the
     * same conversation rather than two different ones.
     */
    public fun session(layout: TransferLayout): TransferSession = TransferSession(
        id = SESSION_ID,
        peer = when (layout) {
            TransferLayout.SENDING_FIRST -> lanPeer
            TransferLayout.RECEIVING_FIRST -> nearbyPeer
        },
        outbound = outbound,
        inbound = inbound,
    )
}
