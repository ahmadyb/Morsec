package app.morsecode.ui.broadcast

import app.morsecode.core.model.DeviceKind
import app.morsecode.core.model.MediaKind
import app.morsecode.core.model.Peer
import app.morsecode.core.model.TransportKind
import app.morsecode.ui.transfer.TransferDirection
import app.morsecode.ui.transfer.TransferState
import app.morsecode.ui.transfer.VerificationOutcome

/**
 * The broadcast screens' sample data, standing in for discovery and the fan-out until
 * milestones 6 to 9.
 *
 * **These are fixtures and not a simulated engine.** Nothing here ticks, advances, retries
 * itself or produces a byte: they are fixed values written down once, so the picker, the
 * sender, the receiver and both completion screens can be built, reviewed and tested now.
 * Nothing in the app reads them except the broadcast view models, which the real transports
 * and the delivery engine will replace.
 *
 * The devices are the reference document's own four (`PEERS`), including the laptop: it is in
 * the discovered list precisely so the picker's rule can be seen to exclude it — a broadcast
 * goes to phones and tablets, and a WebShare laptop is neither.
 *
 * The batch and its deliveries are the reference's three files, with the per-phone states
 * spread deliberately rather than uniformly: one phone has finished and verified everything,
 * one is mid-way through the last file, and one is held on a file it had started and has a
 * failed delivery behind it. That spread is the point of the screen — no two phones are in the
 * same position — and it is what makes "one phone's trouble does not touch another's rows"
 * something a reviewer can see rather than read in a test.
 */
public object BroadcastFixtures {

    private const val MEGABYTE: Long = 1_000_000L

    /** Ravi's Redmi (`PEERS.r`), on the local network. */
    public val ravi: Peer = Peer(
        peerId = "r",
        displayName = "Ravi's Redmi",
        endpointId = "192.168.1.42:45454",
        transport = TransportKind.LAN,
        deviceKind = DeviceKind.PHONE,
        avatarLetter = 'R',
        colorSeed = "r",
        detail = "192.168.1.42 · Phone · LAN",
    )

    /** Pixel 7X (`PEERS.p`), within Nearby range. */
    public val pixel: Peer = Peer(
        peerId = "p",
        displayName = "Pixel 7X",
        endpointId = "NB-PIXEL7X",
        transport = TransportKind.NEARBY,
        deviceKind = DeviceKind.PHONE,
        avatarLetter = 'P',
        colorSeed = "p",
        detail = "Bluetooth 5.2 · Phone · Nearby",
    )

    /** Samsung A14 (`PEERS.s`), on the local network. */
    public val samsung: Peer = Peer(
        peerId = "s",
        displayName = "Samsung A14",
        endpointId = "192.168.1.51:45454",
        transport = TransportKind.LAN,
        deviceKind = DeviceKind.PHONE,
        avatarLetter = 'S',
        colorSeed = "s",
        detail = "192.168.1.51 · Phone · LAN",
    )

    /**
     * The Office Laptop (`PEERS.o`), on WebShare.
     *
     * Discovered, and deliberately not a broadcast recipient: it is here so the picker's own
     * rule has something to refuse and a test has something to assert is absent.
     */
    public val laptop: Peer = Peer(
        peerId = "o",
        displayName = "Office Laptop",
        endpointId = "webshare-office",
        transport = TransportKind.WEB,
        deviceKind = DeviceKind.DESKTOP,
        avatarLetter = 'O',
        colorSeed = "o",
        detail = "192.168.1.88 · WebShare",
    )

    /** This phone (`SELF`), the identity a receiver's screen names as the source. */
    public val self: Peer = Peer(
        peerId = "self",
        displayName = "MYA-L10",
        endpointId = "192.168.1.10:45454",
        transport = TransportKind.LAN,
        deviceKind = DeviceKind.PHONE,
        avatarLetter = 'M',
        colorSeed = "self",
        detail = "192.168.1.10 · Phone · LAN",
    )

    /** What discovery has found: three phones, and one device that is not a recipient. */
    public val discovered: List<Peer> = listOf(ravi, pixel, samsung, laptop)

    /** The reference's starting selection: two phones chosen, which is the minimum. */
    public fun selection(): BroadcastSelection =
        BroadcastSelection(peers = discovered, chosen = setOf("r", "p"))

    /** 144 MB of video. */
    public val holiday: BroadcastFile = BroadcastFile(
        id = "v1",
        fileName = "holiday_2019.mp4",
        kind = MediaKind.VIDEO,
        totalBytes = 144 * MEGABYTE,
    )

    /** A 4.1 MB photo, the smallest file in the batch. */
    public val photo: BroadcastFile = BroadcastFile(
        id = "i1",
        fileName = "IMG_2043.jpg",
        kind = MediaKind.IMAGE,
        totalBytes = 4_100_000L,
    )

    /** 64 MB of audio, the file two of the three phones are still working on. */
    public val live: BroadcastFile = BroadcastFile(
        id = "a1",
        fileName = "live_set_final.flac",
        kind = MediaKind.AUDIO,
        totalBytes = 64 * MEGABYTE,
    )

    /** The batch the reference sends, in the order it lists it. */
    public val batch: List<BroadcastFile> = listOf(holiday, photo, live)

    private const val SESSION_ID: String = "broadcast-fixture-1"

    /** The reference's rate for a phone that is still moving. */
    private const val MOVING_SPEED: Long = 4_800_000L

    /**
     * A broadcast in flight: nine deliveries, no two phones in the same position.
     *
     * `Pixel 7X` has verified everything; `Ravi's Redmi` is two files in and sending the third;
     * `Samsung A14` is held on the first file and has a failed delivery on the third — the one
     * control-bearing row in the batch, since a failure is the only state here that permits a
     * retry.
     */
    public fun session(): BroadcastSession = session(
        holidayToRavi = delivered(ravi, holiday),
        holidayToPixel = delivered(pixel, holiday),
        holidayToSamsung = held(samsung, holiday, 83_500_000L),
        photoToRavi = delivered(ravi, photo),
        photoToPixel = delivered(pixel, photo),
        photoToSamsung = delivered(samsung, photo),
        liveToRavi = moving(ravi, live, 21_400_000L),
        liveToPixel = delivered(pixel, live),
        liveToSamsung = broken(samsung, live, 12_000_000L),
    )

    /**
     * The same fan-out, restricted to the phones a picker chose.
     *
     * The picker's token decides which phones a broadcast addresses, and this is how the
     * sender honours it while still opening on a fan-out worth looking at: choosing Ravi and
     * the Pixel shows the reference's own two-phone default, choosing all three shows the
     * whole grid, and choosing one cannot happen because the picker will not start a
     * broadcast to fewer than two phones. An empty or unknown selection falls back to the
     * reference's default pair rather than to an empty batch.
     */
    public fun sessionFor(recipients: List<Peer>): BroadcastSession {
        val wanted = BroadcastMath.distinctRecipients(recipients).map { it.peerId }.toSet()
        val base = session()
        if (wanted.isEmpty()) return base
        return base.copy(
            recipients = base.recipients.filter { it.peerId in wanted },
            deliveries = base.deliveries.filterKeys { it.recipientId in wanted },
        )
    }

    /**
     * A broadcast that finished and verified everywhere: every phone got every file.
     *
     * This is what the sender's completion screen is for, and the only shape of broadcast that
     * may claim "all phones verified". Reached in the app when a real fan-out finishes, which
     * is milestone 9's work; until then it is what that screen is built and tested against.
     */
    public fun completedSession(): BroadcastSession = session(
        holidayToRavi = delivered(ravi, holiday),
        holidayToPixel = delivered(pixel, holiday),
        holidayToSamsung = delivered(samsung, holiday),
        photoToRavi = delivered(ravi, photo),
        photoToPixel = delivered(pixel, photo),
        photoToSamsung = delivered(samsung, photo),
        liveToRavi = delivered(ravi, live),
        liveToPixel = delivered(pixel, live),
        liveToSamsung = delivered(samsung, live),
    )

    /**
     * A broadcast that finished without succeeding everywhere: seven deliveries arrived and
     * verified, one failed, one was skipped by the conflict policy.
     *
     * Its purpose is the wording the screens must not get wrong — a partial broadcast may not
     * claim that every phone verified, and its summary has to say the failed and skipped
     * counts out loud rather than print zeros.
     */
    public fun partialSession(): BroadcastSession = session(
        holidayToRavi = delivered(ravi, holiday),
        holidayToPixel = delivered(pixel, holiday),
        holidayToSamsung = delivered(samsung, holiday),
        photoToRavi = delivered(ravi, photo),
        photoToPixel = delivered(pixel, photo),
        photoToSamsung = skipped(samsung, photo),
        liveToRavi = delivered(ravi, live),
        liveToPixel = delivered(pixel, live),
        liveToSamsung = broken(samsung, live, 12_000_000L),
    )

    /** The nine deliveries, written file by file, exactly as the reference's grid reads. */
    private fun session(
        holidayToRavi: BroadcastDelivery,
        holidayToPixel: BroadcastDelivery,
        holidayToSamsung: BroadcastDelivery,
        photoToRavi: BroadcastDelivery,
        photoToPixel: BroadcastDelivery,
        photoToSamsung: BroadcastDelivery,
        liveToRavi: BroadcastDelivery,
        liveToPixel: BroadcastDelivery,
        liveToSamsung: BroadcastDelivery,
    ): BroadcastSession = BroadcastSession(
        id = SESSION_ID,
        sender = self,
        files = batch,
        recipients = listOf(ravi, pixel, samsung),
        deliveries = listOf(
            holidayToRavi,
            holidayToPixel,
            holidayToSamsung,
            photoToRavi,
            photoToPixel,
            photoToSamsung,
            liveToRavi,
            liveToPixel,
            liveToSamsung,
        ).associateBy { it.key },
    )

    /** A delivery that arrived, with its checksum compared and agreed. */
    private fun delivered(peer: Peer, file: BroadcastFile): BroadcastDelivery =
        delivery(peer, file, TransferState.DONE)

    /** A delivery held where it stopped, with the bytes it reached. */
    private fun held(peer: Peer, file: BroadcastFile, transferredBytes: Long): BroadcastDelivery =
        delivery(peer, file, TransferState.PAUSED, transferredBytes)

    /** A delivery that is moving, at the reference's rate. */
    private fun moving(peer: Peer, file: BroadcastFile, transferredBytes: Long): BroadcastDelivery =
        delivery(peer, file, TransferState.SENDING, transferredBytes, MOVING_SPEED)

    /** A delivery stopped by something other than the user, and still retryable. */
    private fun broken(peer: Peer, file: BroadcastFile, transferredBytes: Long): BroadcastDelivery =
        delivery(peer, file, TransferState.FAILED, transferredBytes)

    /** A delivery the conflict policy skipped rather than copying again. */
    private fun skipped(peer: Peer, file: BroadcastFile): BroadcastDelivery =
        delivery(peer, file, TransferState.SKIPPED)

    /**
     * One delivery, with everything that follows from its state rather than typed twice: a
     * delivered file arrived in full with an agreed checksum unless a shortfall says otherwise,
     * anything stopped carries no speed, and the eligibility flags are left at their defaults
     * because the engine is the thing that will narrow them.
     */
    private fun delivery(
        peer: Peer,
        file: BroadcastFile,
        state: TransferState,
        transferredBytes: Long = if (state == TransferState.DONE) file.sizeBytes else 0L,
        speedBytesPerSecond: Long = 0L,
    ): BroadcastDelivery = BroadcastDelivery(
        recipientId = peer.peerId,
        fileId = file.id,
        totalBytes = file.sizeBytes,
        transferredBytes = transferredBytes,
        speedBytesPerSecond = if (state.isActive) speedBytesPerSecond else 0L,
        state = state,
        verification = when {
            state != TransferState.DONE -> VerificationOutcome.PENDING
            transferredBytes < file.sizeBytes -> VerificationOutcome.MISMATCH
            else -> VerificationOutcome.VERIFIED
        },
        direction = TransferDirection.OUTGOING,
    )
}
