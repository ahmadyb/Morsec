package app.morsecode.ui.broadcast

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import app.morsecode.core.model.FeatureArea
import app.morsecode.core.model.FeatureReadiness
import app.morsecode.core.model.MorseFormatters
import app.morsecode.core.model.Peer
import app.morsecode.navigation.Routes
import app.morsecode.ui.transfer.TransferActions
import app.morsecode.ui.transfer.TransferDirection
import app.morsecode.ui.transfer.TransferPeer
import app.morsecode.ui.transfer.TransferRow
import app.morsecode.ui.transfer.TransferRowAction
import app.morsecode.ui.transfer.TransferState
import app.morsecode.ui.transfer.rowTo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * The broadcast flow's view models: one per screen, each an explicit controller over an
 * immutable session.
 *
 * Nothing here talks to a socket, a transport or a clock. The picker owns a selection, the
 * sender owns a batch fanning out, a receiver owns one phone's share of it, and the two
 * completions own a finished one — every transition is a pure function from
 * [BroadcastRules], and every screen is handed its state rather than reaching for it. That is
 * what keeps this milestone's work honest: the fan-out itself is milestone 9's, and until
 * then these values are what the UI is built and tested against.
 */

// ---------------------------------------------------------------- the picker

/**
 * What the picker draws.
 *
 * The numbers and the byte total are formatted here, as everywhere else in this app; the words
 * around them belong to resources, so the screen decides that a count of two reads "2 phones"
 * and this decides that 212 100 000 bytes read "212 MB".
 */
public data class BroadcastPickUiState(
    public val selection: BroadcastSelection,
    public val batchFileCount: Int,
    public val batchBytes: String,
    /** False while discovery is gated, which is when the list is the reference's sample. */
    public val discoveryLive: Boolean,
) {
    /** The devices the screen may offer: phones and tablets, once each. */
    public val peers: List<Peer> get() = selection.selectable

    public val selectedCount: Int get() = selection.selectedCount
    public val canStart: Boolean get() = selection.canStart
    public val shortfall: Int get() = selection.shortfall
    public val hasDevices: Boolean get() = peers.isNotEmpty()
}

/**
 * The phone picker (master prompt §5 "Connect": multi-device selection for broadcast).
 *
 * The selection is saved as a token, so a recreated screen comes back with the same phones
 * ticked rather than resetting under the user's hand — and the token only ever names devices
 * this screen could have offered.
 */
@HiltViewModel
public class BroadcastPickerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val formatters: MorseFormatters,
) : ViewModel() {

    private val peers: List<Peer> = BroadcastFixtures.discovered

    private val selection = MutableStateFlow(
        BroadcastSelection.fromToken(
            peers = peers,
            token = savedStateHandle.get<String>(Routes.BROADCAST_CHOSEN_ARG),
        ),
    )

    private val _state = MutableStateFlow(publish(selection.value))

    public val state: StateFlow<BroadcastPickUiState> = _state.asStateFlow()

    /** Tick a phone, or untick it. A device this screen may not address is left alone. */
    public fun toggle(peerId: String) {
        val next = selection.value.toggled(peerId)
        if (next == selection.value) return
        selection.value = next
        savedStateHandle[Routes.BROADCAST_CHOSEN_ARG] = next.chosenToken
        _state.value = publish(next)
    }

    /** For the screen's toast: what the user is about to broadcast to. */
    public fun chosenToken(): String = selection.value.chosenToken

    private fun publish(selection: BroadcastSelection): BroadcastPickUiState =
        broadcastPickState(selection, BroadcastFixtures.batch, formatters)
}

// ---------------------------------------------------------------- the sender

/** One phone's row beneath a file: who, how far, and what that delivery may offer. */
public data class BroadcastDeliveryRow(
    public val key: DeliveryKey,
    public val fileName: String,
    public val recipient: Peer,
    public val transferred: String,
    public val total: String,
    /** "33%", or the reference's dash when nothing has moved yet. */
    public val percent: String,
    public val fraction: Float,
    public val state: TransferState,
    public val actions: TransferActions,
)

/** One file of the batch: what it is, how far it has got, and where each phone is with it. */
public data class BroadcastFileRow(
    public val file: BroadcastFile,
    public val size: String,
    public val deliveredPhones: Int,
    public val totalPhones: Int,
    public val state: BroadcastGroupState,
    public val recipients: List<BroadcastDeliveryRow>,
)

/** The three numbers under the sender's summary, as the reference draws them. */
public data class BroadcastTiles(
    public val phones: String,
    public val filesEach: String,
    public val toSend: String,
    public val delivered: String,
)

/**
 * What the sender draws.
 *
 * The counts are handed over as the numbers they are, not as sentences: the screen writes them
 * in the app's own words, which is what lets the same card say "2 sending · 1 paused" while a
 * batch runs and "6 delivered · 1 failed" when it stops.
 */
public data class BroadcastSenderUiState(
    public val sessionId: String,
    public val files: List<BroadcastFileRow>,
    public val result: BroadcastResult,
    public val recipientCount: Int,
    public val combinedThroughput: String,
    public val hasThroughput: Boolean,
    public val tiles: BroadcastTiles,
    public val allAction: BroadcastAllAction,
    public val endConfirmationVisible: Boolean = false,
) {
    /** True when every expected delivery has stopped. Then — and only then — the batch is over. */
    public val complete: Boolean get() = result.complete

    /** Everything unfinished, for the End dialog's wording. */
    public val unfinishedCount: Int get() = result.pending
}

/**
 * The broadcast sender: one batch, and one independent delivery per phone.
 *
 * The view model holds the session and nothing else; every row, count and total is derived
 * when the state is published, so the screen cannot show a stale percentage beside a fresh
 * chip, and a delivery's trouble cannot be written onto its neighbours' rows — they are
 * different values, and only the one that changed is replaced.
 */
@HiltViewModel
public class BroadcastSenderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val formatters: MorseFormatters,
) : ViewModel() {

    private val session = MutableStateFlow(startSession(savedStateHandle))

    private val endConfirmation = MutableStateFlow(false)

    private val _state = MutableStateFlow(publish(session.value, endConfirmation.value))

    public val state: StateFlow<BroadcastSenderUiState> = _state.asStateFlow()

    /** Send one delivery to one phone again, after a failure the engine reported. */
    public fun retry(recipientId: String, fileId: String) {
        mutate { current ->
            BroadcastRules.apply(current, DeliveryKey(recipientId, fileId), TransferRowAction.RETRY)
        }
    }

    /** The bar's one whole-batch control: pause everything moving, or resume everything held. */
    public fun toggleAll() {
        mutate { current -> BroadcastRules.toggleAll(current) }
    }

    /** Ask to end the broadcast. Nothing changes until the question is answered. */
    public fun requestEnd() {
        endConfirmation.value = true
        publishCurrent()
    }

    public fun dismissEnd() {
        endConfirmation.value = false
        publishCurrent()
    }

    /** End it: whatever is still unfinished stops where it is. */
    public fun confirmEnd() {
        endConfirmation.value = false
        mutate { current -> BroadcastRules.end(current) }
    }

    private fun mutate(transition: (BroadcastSession) -> BroadcastSession) {
        session.value = transition(session.value)
        publishCurrent()
    }

    private fun publishCurrent() {
        _state.value = publish(session.value, endConfirmation.value)
    }

    private fun publish(current: BroadcastSession, endVisible: Boolean): BroadcastSenderUiState =
        broadcastSenderState(current, formatters, endVisible)

    private companion object {
        /**
         * The broadcast this screen opens on, fanned out to the phones the picker chose.
         *
         * The engine's own entry point is [BroadcastSession.start], which queues every
         * expected delivery — that is what a real fan-out begins as, and it is unit-tested.
         * Until the engine exists, this screen opens on the reference's fixed sample fan-out
         * restricted to the chosen phones, so the nesting the screen exists to draw — three
         * phones, three different positions — is what a reviewer actually sees. Choosing two
         * phones gives the reference's own default pair; choosing none falls back to it.
         */
        fun startSession(savedStateHandle: SavedStateHandle): BroadcastSession {
            val chosen = BroadcastSelection.fromToken(
                peers = BroadcastFixtures.discovered,
                token = savedStateHandle.get<String>(Routes.BROADCAST_CHOSEN_ARG),
            )
            val recipients = chosen.selected.ifEmpty {
                BroadcastSelection.fromToken(BroadcastFixtures.discovered, DEFAULT_CHOSEN).selected
            }
            return BroadcastFixtures.sessionFor(recipients)
        }

        /** The reference's initial selection: Ravi and the Pixel. */
        const val DEFAULT_CHOSEN: String = "r,p"
    }
}

// ---------------------------------------------------------------- one receiver

/** The peer card's model, from a discovered peer: the same card the duplex views draw. */
internal fun Peer.asTransferPeer(): TransferPeer = TransferPeer(
    id = peerId,
    name = displayName,
    letter = letter.toString(),
    transport = transport.badge,
    address = endpointId,
)

/**
 * What one phone's receiving screen draws.
 *
 * The rows are the duplex views' own row model, built from this phone's deliveries read as
 * incoming: a file arriving here is the same file, with the same bar, the same second line and
 * the same chip vocabulary, which is why the receiver reuses the row rather than drawing a
 * second kind of one.
 */
public data class BroadcastReceiverUiState(
    public val sessionId: String,
    public val recipient: Peer,
    public val sender: Peer,
    /** "212 MB" — the batch this phone is receiving, as the list heading prints it. */
    public val batchBytes: String,
    public val rows: List<TransferRow>,
    public val result: BroadcastResult,
    public val averageSpeed: String,
    public val hasAverageSpeed: Boolean,
    public val allAction: BroadcastAllAction,
    public val endConfirmationVisible: Boolean = false,
) {
    public val complete: Boolean get() = result.complete
    public val unfinishedCount: Int get() = result.pending
}

/**
 * One phone's receiving screen.
 *
 * The same screen draws every phone: the route says which one, and this hands the screen that
 * phone's deliveries alone. One phone's failure, hold or completion changes nothing here about
 * another phone, because another phone's deliveries are not in this state at all.
 */
@HiltViewModel
public class BroadcastReceiverViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val formatters: MorseFormatters,
) : ViewModel() {

    private val recipientId: String = savedStateHandle.get<String>(Routes.BROADCAST_RECIPIENT_ARG).orEmpty()

    private val session = MutableStateFlow(BroadcastFixtures.session())

    private val endConfirmation = MutableStateFlow(false)

    private val _state = MutableStateFlow(publish(session.value, endConfirmation.value))

    public val state: StateFlow<BroadcastReceiverUiState> = _state.asStateFlow()

    private val recipient: Peer =
        session.value.recipient(recipientId) ?: session.value.uniqueRecipients.first()

    /**
     * Clear what has arrived on this phone.
     *
     * Scoped to this phone, so clearing what landed here cannot reach another phone's rows —
     * and only the delivered rows go: a failed or held file is still something to act on.
     */
    public fun clearCompleted() {
        mutate { current -> BroadcastRules.clearCompleted(current, recipient.peerId) }
    }

    /** How many delivered rows the clear would remove, for the screen's own report. */
    public fun completedCount(): Int = session.value.deliveriesTo(recipient.peerId)
        .count { it.state == TransferState.DONE }

    public fun toggleAll() {
        mutate { current -> BroadcastRules.toggleAll(current, recipient.peerId) }
    }

    public fun requestEnd() {
        endConfirmation.value = true
        publishCurrent()
    }

    public fun dismissEnd() {
        endConfirmation.value = false
        publishCurrent()
    }

    public fun confirmEnd() {
        endConfirmation.value = false
        mutate { current -> BroadcastRules.end(current, recipient.peerId) }
    }

    private fun mutate(transition: (BroadcastSession) -> BroadcastSession) {
        session.value = transition(session.value)
        publishCurrent()
    }

    private fun publishCurrent() {
        _state.value = publish(session.value, endConfirmation.value)
    }

    private fun publish(current: BroadcastSession, endVisible: Boolean): BroadcastReceiverUiState =
        broadcastReceiverState(current, recipient.peerId, formatters, endVisible)
    }

// ---------------------------------------------------------------- completions

/** One phone's result on the sender's completion screen. */
public data class BroadcastRecipientResultRow(
    public val peer: Peer,
    public val delivered: Int,
    public val expected: Int,
    public val bytes: String,
    public val failed: Int,
    public val skipped: Int,
    public val cancelled: Int,
    public val mismatched: Int,
    public val state: BroadcastGroupState,
) {
    /** Nothing stopped badly: this phone's files all arrived and every checksum agreed. */
    public val clean: Boolean get() = failed == 0 && skipped == 0 && cancelled == 0 && mismatched == 0
}

/** What the sender's completion screen draws. */
public data class BroadcastSentUiState(
    public val result: BroadcastResult,
    public val recipients: List<BroadcastRecipientResultRow>,
    public val recipientCount: Int,
    public val filesEach: Int,
    public val deliveredBytes: String,
    /** Disabled once the batch is over: there is nothing left to hold. */
    public val allAction: BroadcastAllAction,
    public val cleared: Boolean,
)

/** The sender's completion: every phone, what each of them got, and what the batch added up to. */
@HiltViewModel
public class BroadcastSentViewModel @Inject constructor(
    private val formatters: MorseFormatters,
) : ViewModel() {

    private val session = MutableStateFlow(BroadcastFixtures.completedSession())

    private val cleared = MutableStateFlow(false)

    private val _state = MutableStateFlow(publish())

    public val state: StateFlow<BroadcastSentUiState> = _state.asStateFlow()

    /** Clear the delivered list: the rows go, and the report stays. */
    public fun clear() {
        cleared.value = true
        publishCurrent()
    }

    private fun publishCurrent() {
        _state.value = publish()
    }

    private fun publish(): BroadcastSentUiState =
        broadcastSentState(session.value, formatters, cleared.value)

}

/** What one phone's completion screen draws: its own files, and what became of them. */
public data class BroadcastReceivedUiState(
    public val sender: Peer,
    public val recipient: Peer,
    public val rows: List<TransferRow>,
    public val result: BroadcastResult,
    public val deliveredBytes: String,
    public val fileCount: Int,
    /** Disabled once nothing is unfinished, which is the whole point of a completion screen. */
    public val allAction: BroadcastAllAction,
) {
    public val complete: Boolean get() = result.complete
}

/** One phone's completion: what arrived, what the checksums said, and where the files are. */
@HiltViewModel
public class BroadcastReceivedViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val formatters: MorseFormatters,
) : ViewModel() {

    private val session: BroadcastSession = BroadcastFixtures.session()

    private val recipient: Peer = session
        .recipient(savedStateHandle.get<String>(Routes.BROADCAST_RECIPIENT_ARG).orEmpty())
        ?: session.uniqueRecipients.first()

    private val _state = MutableStateFlow(publish())

    public val state: StateFlow<BroadcastReceivedUiState> = _state.asStateFlow()

    private fun publish(): BroadcastReceivedUiState =
        broadcastReceivedState(session, recipient.peerId, formatters)
}


// ---------------------------------------------------------------- state builders

/**
 * The five screens' states, built from a session and nothing else.
 *
 * These are top-level functions rather than private methods of each view model for the same
 * reason the duplex session has one: a screen test needs a state it can construct, and a
 * completion screen's partial case — a broadcast that finished with a failure and a skip —
 * only exists if a state can be built from a session that has one. Every screen draws exactly
 * what these return, so a test that asserts over one of these is asserting over the screen.
 *
 * Each builder derives everything from the deliveries: no count, total, percentage or speed is
 * stored beside them, which is what makes a state that contradicts its own rows impossible to
 * write down.
 */

/** What the picker draws: a selection, the batch it would send, and whether discovery is live. */
internal fun broadcastPickState(
    selection: BroadcastSelection,
    batch: List<BroadcastFile>,
    formatters: MorseFormatters,
): BroadcastPickUiState = BroadcastPickUiState(
    selection = selection,
    batchFileCount = BroadcastMath.distinctFiles(batch).size,
    batchBytes = formatters.bytes(BroadcastMath.batchBytes(batch)),
    discoveryLive = FeatureReadiness.isAvailable(FeatureArea.LAN_TRANSPORT) ||
        FeatureReadiness.isAvailable(FeatureArea.NEARBY_TRANSPORT),
)

/** What the sender draws: the batch, nested by file, with one row per phone beneath it. */
internal fun broadcastSenderState(
    session: BroadcastSession,
    formatters: MorseFormatters,
    endConfirmationVisible: Boolean = false,
): BroadcastSenderUiState {
    val result = session.result
    return BroadcastSenderUiState(
        sessionId = session.id,
        files = session.uniqueFiles.map { file -> broadcastFileRow(session, file, formatters) },
        result = result,
        recipientCount = session.uniqueRecipients.size,
        combinedThroughput = formatters.speed(result.combinedThroughput),
        hasThroughput = result.combinedThroughput > 0L,
        tiles = BroadcastTiles(
            phones = session.uniqueRecipients.size.toString(),
            filesEach = session.uniqueFiles.size.toString(),
            toSend = formatters.bytes(session.bytesToSend),
            delivered = formatters.bytes(result.confirmedBytes),
        ),
        allAction = session.allAction,
        endConfirmationVisible = endConfirmationVisible,
    )
}

/** One file of the batch, and where each phone is with it. */
internal fun broadcastFileRow(
    session: BroadcastSession,
    file: BroadcastFile,
    formatters: MorseFormatters,
): BroadcastFileRow {
    val deliveries = session.deliveriesFor(file.id)
    return BroadcastFileRow(
        file = file,
        size = formatters.bytes(file.sizeBytes),
        deliveredPhones = deliveries.count { it.state == TransferState.DONE },
        totalPhones = deliveries.size,
        state = BroadcastGroupState.of(deliveries),
        recipients = deliveries.mapNotNull { delivery ->
            session.recipient(delivery.recipientId)?.let { recipient ->
                broadcastDeliveryRow(delivery, file, recipient, formatters)
            }
        },
    )
}

/** One phone's row beneath a file: its own bytes, its own percentage, its own controls. */
internal fun broadcastDeliveryRow(
    delivery: BroadcastDelivery,
    file: BroadcastFile,
    recipient: Peer,
    formatters: MorseFormatters,
): BroadcastDeliveryRow = BroadcastDeliveryRow(
    key = delivery.key,
    fileName = file.fileName,
    recipient = recipient,
    transferred = formatters.bytes(delivery.transferred),
    total = formatters.bytes(delivery.totalBytes),
    percent = if (delivery.transferred <= 0L) "" else formatters.percent(delivery.fraction),
    fraction = delivery.fraction,
    state = delivery.state,
    actions = delivery.actions,
)

/**
 * What one phone's receiving screen draws: that phone's deliveries, read as incoming.
 *
 * Only that phone's deliveries are here — the screen cannot show another phone's progress even
 * by accident, which is the structural half of "one recipient's trouble does not touch
 * another's".
 */
internal fun broadcastReceiverState(
    session: BroadcastSession,
    recipientId: String,
    formatters: MorseFormatters,
    endConfirmationVisible: Boolean = false,
): BroadcastReceiverUiState {
    val recipient = session.recipient(recipientId) ?: session.uniqueRecipients.first()
    val deliveries = session.deliveriesTo(recipient.peerId)
    val moving = deliveries.filter { it.state.isActive && it.speedBytesPerSecond > 0L }
    val average = if (moving.isEmpty()) 0L else moving.sumOf { it.speedBytesPerSecond } / moving.size
    return BroadcastReceiverUiState(
        sessionId = session.id,
        recipient = recipient,
        sender = session.sender,
        batchBytes = formatters.bytes(session.batchBytes),
        rows = deliveries.mapNotNull { delivery ->
            session.file(delivery.fileId)?.let { file ->
                rowTo(delivery.asItem(session.id, file, TransferDirection.INCOMING), formatters)
            }
        },
        result = session.resultFor(recipient.peerId),
        averageSpeed = formatters.speed(average),
        hasAverageSpeed = average > 0L,
        allAction = session.allActionFor(recipient.peerId),
        endConfirmationVisible = endConfirmationVisible,
    )
}

/** What the sender's completion draws: every phone, and what the batch added up to. */
internal fun broadcastSentState(
    session: BroadcastSession,
    formatters: MorseFormatters,
    cleared: Boolean = false,
): BroadcastSentUiState {
    val result = session.result
    return BroadcastSentUiState(
        result = result,
        recipients = if (cleared) {
            emptyList()
        } else {
            session.uniqueRecipients.map { recipient -> broadcastRecipientResult(session, recipient, formatters) }
        },
        recipientCount = session.uniqueRecipients.size,
        filesEach = session.uniqueFiles.size,
        deliveredBytes = formatters.bytes(result.confirmedBytes),
        allAction = session.allAction,
        cleared = cleared,
    )
}

/** One phone's outcome, as the sender's completion lists it. */
internal fun broadcastRecipientResult(
    session: BroadcastSession,
    recipient: Peer,
    formatters: MorseFormatters,
): BroadcastRecipientResultRow {
    val result = session.resultFor(recipient.peerId)
    return BroadcastRecipientResultRow(
        peer = recipient,
        delivered = result.delivered,
        expected = result.expected,
        bytes = formatters.bytes(result.confirmedBytes),
        failed = result.failed,
        skipped = result.skipped,
        cancelled = result.cancelled,
        mismatched = result.mismatched,
        state = session.stateFor(recipient.peerId),
    )
}

/** What one phone's completion draws: what arrived, and what the checksums said. */
internal fun broadcastReceivedState(
    session: BroadcastSession,
    recipientId: String,
    formatters: MorseFormatters,
): BroadcastReceivedUiState {
    val recipient = session.recipient(recipientId) ?: session.uniqueRecipients.first()
    val deliveries = session.deliveriesTo(recipient.peerId)
    val result = session.resultFor(recipient.peerId)
    return BroadcastReceivedUiState(
        sender = session.sender,
        recipient = recipient,
        rows = deliveries.mapNotNull { delivery ->
            session.file(delivery.fileId)?.let { file ->
                rowTo(delivery.asItem(session.id, file, TransferDirection.INCOMING), formatters)
            }
        },
        result = result,
        deliveredBytes = formatters.bytes(result.confirmedBytes),
        fileCount = deliveries.size,
        allAction = session.allActionFor(recipient.peerId),
    )
}
