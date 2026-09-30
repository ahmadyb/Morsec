package app.morsecode.ui.broadcast

import app.morsecode.core.model.MediaKind
import app.morsecode.core.model.Peer
import app.morsecode.ui.transfer.TransferActions
import app.morsecode.ui.transfer.TransferAllLabel
import app.morsecode.ui.transfer.TransferDirection
import app.morsecode.ui.transfer.TransferItem
import app.morsecode.ui.transfer.TransferProgress
import app.morsecode.ui.transfer.TransferRowAction
import app.morsecode.ui.transfer.TransferRules
import app.morsecode.ui.transfer.TransferState
import app.morsecode.ui.transfer.VerificationOutcome

/**
 * A broadcast: one batch of files fanned out to several phones at once.
 *
 * The master prompt's §5 "Broadcast" asks for something the duplex session is not: one batch,
 * N receivers, and one independent delivery session per receiver, so a slow or broken phone
 * cannot hold up the others. The models here are that idea written down — a [BroadcastFile]
 * that is sent once, a [Peer] per receiving phone, and a [BroadcastDelivery] for every
 * (file, phone) pair, which is the unit that actually progresses.
 *
 * Three properties are structural rather than promised, because they are the ones a
 * broadcast gets wrong in practice:
 *
 * - **Nothing is shared between recipients.** Every delivery is its own immutable value, and
 *   every transition returns a new session whose untouched deliveries are the *same objects*
 *   they were, so a phone that fails, slows down or is held cannot reach another phone's rows.
 * - **Nothing is counted twice.** Files and recipients are de-duplicated by id at every point
 *   the arithmetic happens, so a repeated id cannot inflate a batch size, a recipient count or
 *   a byte total.
 * - **Nothing is derived twice.** The per-delivery matrix is the transfer model's own
 *   ([TransferActions.of]), the transitions are the transfer rules', and every aggregate below
 *   is computed from the deliveries rather than stored beside them, which is what makes a
 *   contradictory "3 of 2 phones" impossible to write down.
 *
 * Everything here is a pure function of its arguments. No clock, no socket, no coroutine: the
 * fan-out itself is milestone 9's work, and until then these are the values the UI is built
 * and tested against.
 */

/** One file in a broadcast batch: sent once, received by every recipient. */
public data class BroadcastFile(
    public val id: String,
    public val fileName: String,
    public val kind: MediaKind,
    public val totalBytes: Long,
) {
    /** Never negative: a file smaller than nothing is a bad report, not a negative transfer. */
    public val sizeBytes: Long get() = totalBytes.coerceAtLeast(0L)
}

/** Identifies one delivery: this file, to that phone. */
public data class DeliveryKey(
    public val recipientId: String,
    public val fileId: String,
)

/**
 * One file's journey to one phone.
 *
 * The fields are the master prompt's minimum for a delivery — who, which file, how far, how
 * fast, what state, what the checksum said, and whether a retry is the engine's to offer —
 * and the state vocabulary is the transfer model's own [TransferState], because a delivery to
 * a phone *is* a transfer: it pauses, resumes, fails and verifies exactly like the duplex
 * rows do, and it must say so in the same words.
 *
 * [direction] is which end of the fan-out this copy is read from. The sending phone's session
 * has outgoing deliveries ([TransferState.SENDING] while moving); the receiving phone's view
 * of the same delivery is incoming ([TransferState.RECEIVING]), which is also what decides
 * where a resumed delivery goes back to.
 */
public data class BroadcastDelivery(
    public val recipientId: String,
    public val fileId: String,
    public val totalBytes: Long,
    public val transferredBytes: Long = 0L,
    public val speedBytesPerSecond: Long = 0L,
    public val state: TransferState = TransferState.QUEUED,
    public val verification: VerificationOutcome = VerificationOutcome.PENDING,
    public val direction: TransferDirection = TransferDirection.OUTGOING,
    public val pauseEligible: Boolean = true,
    public val cancelEligible: Boolean = true,
    public val retryEligible: Boolean = true,
) {
    public val key: DeliveryKey get() = DeliveryKey(recipientId, fileId)

    /** Safe whenever it is read: a delivery cannot confirm more bytes than the file holds. */
    public val transferred: Long get() = transferredBytes.coerceIn(0L, totalBytes.coerceAtLeast(0L))

    /** How full this delivery's bar is, with a zero-byte file handled rather than divided by. */
    public val fraction: Float get() = TransferProgress.fraction(transferredBytes, totalBytes, state)

    /** What this delivery may offer: the same matrix every transfer row uses. */
    public val actions: TransferActions
        get() = TransferActions.of(state, pauseEligible, cancelEligible, retryEligible)

    /**
     * This delivery as the app's per-file transfer model, so the one set of rules can act on
     * it and the one row can draw it.
     *
     * [direction] decides which word "moving" uses: a delivery that is on its way to a phone
     * is [TransferState.SENDING] from the sending end and [TransferState.RECEIVING] from the
     * receiving one, and the difference is not cosmetic — it is where a resumed delivery
     * returns to.
     */
    public fun asItem(
        sessionId: String,
        file: BroadcastFile,
        direction: TransferDirection = this.direction,
    ): TransferItem {
        val moving = if (state.isActive) direction.activeState else state
        return TransferItem(
            id = "${recipientId}_${file.id}",
            sessionId = sessionId,
            direction = direction,
            fileName = file.fileName,
            kind = file.kind,
            totalBytes = file.sizeBytes,
            transferredBytes = transferredBytes,
            speedBytesPerSecond = speedBytesPerSecond,
            state = moving,
            verification = verification,
            pauseEligible = pauseEligible,
            cancelEligible = cancelEligible,
            retryEligible = retryEligible,
        )
    }

    /** The same delivery, with whatever the shared rules did to its equivalent item. */
    public fun from(item: TransferItem): BroadcastDelivery = copy(
        totalBytes = item.totalBytes,
        transferredBytes = item.transferredBytes,
        speedBytesPerSecond = item.speedBytesPerSecond,
        state = item.state,
        verification = item.verification,
        direction = item.direction,
    )

    public companion object {
        /** A delivery that has not started: nothing confirmed, no speed, waiting its turn. */
        public fun queued(
            recipientId: String,
            file: BroadcastFile,
            direction: TransferDirection = TransferDirection.OUTGOING,
        ): BroadcastDelivery = BroadcastDelivery(
            recipientId = recipientId,
            fileId = file.id,
            totalBytes = file.sizeBytes,
            direction = direction,
        )
    }
}

/**
 * How far a group of deliveries has got — one file across every phone, or one phone across
 * every file.
 *
 * The reference draws a per-file state word and, per file, a receiver row per phone; the same
 * question ("how is this group doing?") is asked of a file and of a recipient, so it is
 * answered once, here, from the deliveries themselves. The order of the questions is the
 * meaning: a group with anything delivered-but-unfinished is not finished, a group that is
 * entirely done is only "verified" when every checksum agreed, and a group that has stopped
 * everywhere without delivering is incomplete rather than still running.
 */
public enum class BroadcastGroupState(public val id: String) {
    /** Nothing has started anywhere in this group. */
    QUEUED("queued"),

    /** Something in this group is moving, or having its checksum compared. */
    IN_PROGRESS("sending"),

    /** Nothing is moving and something is held where it stopped. */
    HELD("paused"),

    /** Every delivery arrived; not every checksum has agreed. */
    COMPLETE("complete"),

    /** Every delivery arrived and every checksum agreed. */
    VERIFIED("verified"),

    /** Nothing is still running, and not everything arrived. */
    INCOMPLETE("incomplete"),
    ;

    /** Nothing further will happen without the user asking. */
    public val isFinished: Boolean get() = this == COMPLETE || this == VERIFIED || this == INCOMPLETE

    /** Everything arrived and every checksum agreed. */
    public val isSuccessful: Boolean get() = this == VERIFIED

    public companion object {
        public fun of(deliveries: List<BroadcastDelivery>): BroadcastGroupState {
            if (deliveries.isEmpty()) return QUEUED
            if (deliveries.all { it.state.isFinished }) {
                if (deliveries.any { it.state != TransferState.DONE }) return INCOMPLETE
                return if (deliveries.all { it.verification == VerificationOutcome.VERIFIED }) {
                    VERIFIED
                } else {
                    COMPLETE
                }
            }
            if (deliveries.any { it.state.isActive || it.state == TransferState.VERIFYING }) {
                return IN_PROGRESS
            }
            if (deliveries.any { it.state == TransferState.PAUSED }) return HELD
            return QUEUED
        }
    }
}

/** The numbers a broadcast's summary and completion screens print. */
public data class BroadcastResult(
    public val expected: Int,
    public val delivered: Int,
    public val verified: Int,
    public val mismatched: Int,
    public val failed: Int,
    public val cancelled: Int,
    public val skipped: Int,
    public val active: Int,
    public val queued: Int,
    public val paused: Int,
    public val verifying: Int,
    public val batchBytes: Long,
    public val expectedBytes: Long,
    public val confirmedBytes: Long,
    public val combinedThroughput: Long,
) {
    /** Deliveries still the engine's business: moving, waiting, held or being verified. */
    public val pending: Int get() = active + queued + paused + verifying

    /**
     * Finished, and only finished when nothing is pending.
     *
     * An empty broadcast is not complete: there is nothing to have finished.
     */
    public val complete: Boolean get() = expected > 0 && pending == 0

    /**
     * Fully successful: every expected delivery arrived *and* every checksum agreed.
     *
     * A delivered file whose checksum did not match, or was never compared, is not a success —
     * which is why this asks about verification rather than about delivery.
     */
    public val fullySuccessful: Boolean get() = complete && delivered == expected && verified == expected

    /** Everything that stopped without arriving, checksum mismatches included. */
    public val problems: Int get() = failed + cancelled + skipped + mismatched
}

/**
 * The arithmetic of a broadcast, as pure functions.
 *
 * Each one is here rather than inside a screen because each one has a way of being wrong that
 * nobody notices until a phone says "3 of 2": a batch counted twice because two files share an
 * id, a byte total multiplied by a duplicate recipient, a combined speed that includes a
 * paused phone, a percentage past 100%. The clamps are deliberate and stated.
 */
public object BroadcastMath {

    /** A phone broadcast goes to at least this many phones; fewer is not a broadcast. */
    public const val MINIMUM_RECIPIENTS: Int = 2

    /** Files by id, first one wins: a repeated id is one file, not two. */
    public fun distinctFiles(files: List<BroadcastFile>): List<BroadcastFile> = files.distinctBy { it.id }

    /** Recipients by id, first one wins, and only the phones a broadcast may address. */
    public fun distinctRecipients(recipients: List<Peer>): List<Peer> =
        recipients.filter { it.isBroadcastEligible }.distinctBy { it.peerId }

    /** The batch's size in bytes, counted once per file. */
    public fun batchBytes(files: List<BroadcastFile>): Long =
        distinctFiles(files).sumOf { it.sizeBytes }

    /**
     * What the whole fan-out has to carry: the batch, once per recipient.
     *
     * Clamped rather than wrapped: a sample that asks for more bytes than a Long holds gets the
     * largest total that is still a total, because a wrapped negative would read as "nothing
     * to send" on the screen.
     */
    public fun totalBytesToSend(batchBytes: Long, recipientCount: Int): Long {
        val bytes = batchBytes.coerceAtLeast(0L)
        val count = recipientCount.coerceAtLeast(0)
        if (bytes == 0L || count == 0) return 0L
        return if (count > Long.MAX_VALUE / bytes) Long.MAX_VALUE else bytes * count
    }

    /** Everything confirmed so far, added up over the deliveries given. */
    public fun confirmedBytes(deliveries: List<BroadcastDelivery>): Long =
        deliveries.sumOf { it.transferred }

    /**
     * A share of a whole, as the bar and the percentage both read it: never negative, never
     * more than all of it, and zero when there is no whole to have a share of.
     */
    public fun fraction(confirmedBytes: Long, totalBytes: Long): Float {
        if (totalBytes <= 0L) return 0f
        return (confirmedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /**
     * The combined throughput: the speeds of the deliveries that are actually moving.
     *
     * Queued, held, failed, delivered and verifying deliveries contribute nothing, so a phone
     * that is held does not make the batch look faster than the bytes arriving justify. A
     * delivery that is moving but reports no speed is skipped rather than counted as zero,
     * because zero is a measurement and nothing is not.
     */
    public fun combinedThroughput(deliveries: List<BroadcastDelivery>): Long =
        deliveries.filter { it.state.isActive && it.speedBytesPerSecond > 0L }
            .sumOf { it.speedBytesPerSecond }

    /** Every count the summary and completion screens print, from the deliveries themselves. */
    public fun result(
        deliveries: List<BroadcastDelivery>,
        batchBytes: Long,
        recipientCount: Int,
    ): BroadcastResult {
        fun count(predicate: (BroadcastDelivery) -> Boolean): Int = deliveries.count(predicate)
        return BroadcastResult(
            expected = deliveries.size,
            delivered = count { it.state == TransferState.DONE },
            verified = count {
                it.state == TransferState.DONE && it.verification == VerificationOutcome.VERIFIED
            },
            mismatched = count {
                it.state == TransferState.DONE && it.verification == VerificationOutcome.MISMATCH
            },
            failed = count { it.state == TransferState.FAILED },
            cancelled = count { it.state == TransferState.CANCELLED },
            skipped = count { it.state == TransferState.SKIPPED },
            active = count { it.state.isActive },
            queued = count { it.state == TransferState.QUEUED },
            paused = count { it.state == TransferState.PAUSED },
            verifying = count { it.state == TransferState.VERIFYING },
            batchBytes = batchBytes.coerceAtLeast(0L),
            expectedBytes = totalBytesToSend(batchBytes, recipientCount),
            confirmedBytes = confirmedBytes(deliveries),
            combinedThroughput = combinedThroughput(deliveries),
        )
    }
}

/**
 * A broadcast session, as one immutable value.
 *
 * The deliveries are keyed by (recipient, file) and every question is answered from that map
 * and the two lists, so there is nowhere for a second copy of a count to live and disagree
 * with the first. [deliveries] may be handed in incomplete or with irrelevant keys: unknown
 * keys are ignored, and an expected pair with nothing recorded yet reads as a queued delivery
 * rather than as a missing one, which is what stops "no record" from meaning "not required".
 */
public data class BroadcastSession(
    public val id: String,
    /** The phone fanning the batch out: the identity a receiver sees on its own screen. */
    public val sender: Peer,
    public val files: List<BroadcastFile> = emptyList(),
    public val recipients: List<Peer> = emptyList(),
    public val deliveries: Map<DeliveryKey, BroadcastDelivery> = emptyMap(),
) {
    /** The batch's files, counted once each. */
    public val uniqueFiles: List<BroadcastFile> get() = BroadcastMath.distinctFiles(files)

    /** The phones this broadcast addresses, counted once each. */
    public val uniqueRecipients: List<Peer> get() = BroadcastMath.distinctRecipients(recipients)

    /** Every pair this broadcast owes: one delivery per file per phone. */
    public val expected: List<DeliveryKey>
        get() = uniqueFiles.flatMap { file ->
            uniqueRecipients.map { recipient -> DeliveryKey(recipient.peerId, file.id) }
        }

    public fun file(fileId: String): BroadcastFile? = uniqueFiles.firstOrNull { it.id == fileId }

    public fun recipient(recipientId: String): Peer? =
        uniqueRecipients.firstOrNull { it.peerId == recipientId }

    /**
     * The delivery for one pair, or null when that pair is not part of this broadcast.
     *
     * A pair that belongs here but has nothing recorded yet reads as queued: a broadcast that
     * has been started owes every pair, and treating "not started" as "not required" is how a
     * batch comes to claim it is complete with files nobody sent.
     */
    public fun delivery(key: DeliveryKey): BroadcastDelivery? {
        val file = file(key.fileId) ?: return null
        if (recipient(key.recipientId) == null) return null
        return deliveries[key] ?: BroadcastDelivery.queued(key.recipientId, file)
    }

    /** One file's deliveries, in recipient order. */
    public fun deliveriesFor(fileId: String): List<BroadcastDelivery> =
        uniqueRecipients.mapNotNull { delivery(DeliveryKey(it.peerId, fileId)) }

    /** One recipient's deliveries, in file order. */
    public fun deliveriesTo(recipientId: String): List<BroadcastDelivery> =
        uniqueFiles.mapNotNull { delivery(DeliveryKey(recipientId, it.id)) }

    /** Every delivery this broadcast owes, in file-then-recipient order. */
    public val allDeliveries: List<BroadcastDelivery>
        get() = expected.mapNotNull { delivery(it) }

    /** The delivery list with [delivery] recorded, when its pair belongs to this broadcast. */
    public fun withDelivery(delivery: BroadcastDelivery): BroadcastSession {
        if (!expected.contains(delivery.key)) return this
        return copy(deliveries = deliveries + (delivery.key to delivery))
    }

    public fun withDeliveries(replacements: List<BroadcastDelivery>): BroadcastSession =
        replacements.fold(this) { session, delivery -> session.withDelivery(delivery) }

    /** How far one file has got, across every phone it was sent to. */
    public fun state(fileId: String): BroadcastGroupState = BroadcastGroupState.of(deliveriesFor(fileId))

    /** How far one phone has got, across every file in the batch. */
    public fun stateFor(recipientId: String): BroadcastGroupState = BroadcastGroupState.of(deliveriesTo(recipientId))

    /** The batch: the files a broadcast carries. */
    public val batchBytes: Long get() = BroadcastMath.batchBytes(uniqueFiles)

    /** What the whole fan-out has to carry: the batch, once per phone. */
    public val bytesToSend: Long get() = BroadcastMath.totalBytesToSend(batchBytes, uniqueRecipients.size)

    /** How far one phone has got through the batch, as the number its percentage prints. */
    public fun fractionFor(recipientId: String): Float {
        val confirmed = BroadcastMath.confirmedBytes(deliveriesTo(recipientId))
        return BroadcastMath.fraction(confirmed, batchBytes)
    }

    /** Every count the sender-side screens print. */
    public val result: BroadcastResult
        get() = BroadcastMath.result(allDeliveries, batchBytes, uniqueRecipients.size)

    /** The same counts for one phone, which is what that phone's own screen prints. */
    public fun resultFor(recipientId: String): BroadcastResult =
        BroadcastMath.result(deliveriesTo(recipientId), batchBytes, 1)

    /** The one whole-session control's label and enabled state, over every phone. */
    public val allAction: BroadcastAllAction get() = BroadcastAllAction.of(allDeliveries)

    /** The same control as one phone's screen offers it: over that phone's deliveries only. */
    public fun allActionFor(recipientId: String): BroadcastAllAction =
        BroadcastAllAction.of(deliveriesTo(recipientId))

    public companion object {
        /**
         * Start a broadcast, or return null because this one cannot legally start.
         *
         * The rules the master prompt states: a phone broadcast addresses at least two phones,
         * a device that is not a phone or tablet is not a recipient, and a batch with nothing
         * in it is not a batch. Every caller gets the same answer, so a screen cannot offer a
         * start the model would refuse.
         */
        public fun start(
            id: String,
            sender: Peer,
            files: List<BroadcastFile>,
            recipients: List<Peer>,
        ): BroadcastSession? {
            val phones = BroadcastMath.distinctRecipients(recipients)
            if (phones.size < BroadcastMath.MINIMUM_RECIPIENTS) return null
            if (BroadcastMath.distinctFiles(files).isEmpty()) return null
            return BroadcastSession(
                id = id,
                sender = sender,
                files = BroadcastMath.distinctFiles(files),
                recipients = phones,
            )
        }

        /** Whether a selection of phones could start a broadcast at all. */
        public fun canStart(recipients: List<Peer>): Boolean =
            BroadcastMath.distinctRecipients(recipients).size >= BroadcastMath.MINIMUM_RECIPIENTS
    }
}

/**
 * The action bar's single whole-session control, for a broadcast.
 *
 * The same decision the duplex session's control makes, over the deliveries this screen is
 * showing: resume when something is held and nothing is moving, pause otherwise, and disabled
 * when there is nothing the label could act on. Label and behaviour are read from this one
 * value, so a control that says Resume all cannot be one that pauses.
 */
public data class BroadcastAllAction(
    public val label: TransferAllLabel,
    public val enabled: Boolean,
) {
    public companion object {
        public fun of(deliveries: List<BroadcastDelivery>): BroadcastAllAction {
            val paused = deliveries.count { it.state == TransferState.PAUSED }
            val pausable = deliveries.count { it.state.isPausable && it.pauseEligible }
            val resumeInstead = paused > 0 && deliveries.none { it.state.isActive }
            return BroadcastAllAction(
                label = if (resumeInstead) TransferAllLabel.RESUME_ALL else TransferAllLabel.PAUSE_ALL,
                enabled = if (resumeInstead) paused > 0 else pausable > 0,
            )
        }
    }
}

/**
 * Every transition a broadcast screen can perform, as pure functions.
 *
 * Each one acts on a set of deliveries — the whole fan-out, or one phone's share of it — and
 * leaves every other delivery exactly as it was, object for object. That is what makes
 * "a failed recipient does not fail a healthy one" and "a paused recipient does not pause a
 * healthy one" properties of the code rather than promises in a comment, and it is what the
 * tests assert by identity.
 *
 * The transitions themselves are the transfer rules': a delivery is converted to the app's
 * per-file item, handed to [TransferRules], and converted back. There is no second matrix.
 */
public object BroadcastRules {

    /** One delivery's answer to one control. */
    public fun apply(
        session: BroadcastSession,
        key: DeliveryKey,
        action: TransferRowAction,
    ): BroadcastSession {
        val file = session.file(key.fileId) ?: return session
        val delivery = session.delivery(key) ?: return session
        return session.withDelivery(act(session, delivery, file, action))
    }

    /** Every unfinished delivery of the whole fan-out stops; the rest are left alone. */
    public fun end(session: BroadcastSession, recipientId: String? = null): BroadcastSession =
        over(session, recipientId) { delivery, file ->
            val item = delivery.asItem(session.id, file)
            delivery.from(TransferRules.end(item))
        }

    /** Hold everything that can be held. */
    public fun pauseAll(session: BroadcastSession, recipientId: String? = null): BroadcastSession =
        over(session, recipientId) { delivery, file ->
            act(session, delivery, file, TransferRowAction.PAUSE)
        }

    /** Set everything held going again. */
    public fun resumeAll(session: BroadcastSession, recipientId: String? = null): BroadcastSession =
        over(session, recipientId) { delivery, file ->
            act(session, delivery, file, TransferRowAction.RESUME)
        }

    /** What the one whole-session control does, given what it says. */
    public fun toggleAll(session: BroadcastSession, recipientId: String? = null): BroadcastSession =
        if (actionFor(session, recipientId).label == TransferAllLabel.RESUME_ALL) {
            resumeAll(session, recipientId)
        } else {
            pauseAll(session, recipientId)
        }

    /** The control as the screen scoped to [recipientId] shows it. */
    public fun actionFor(session: BroadcastSession, recipientId: String? = null): BroadcastAllAction =
        if (recipientId == null) session.allAction else session.allActionFor(recipientId)

    /**
     * Remove the deliveries that arrived, and only those.
     *
     * A failed, cancelled or skipped delivery stays: it is something the user may still want to
     * act on, and clearing delivered work is not the same instruction as clearing everything
     * that stopped. Scoped to one phone when the screen is that phone's, so clearing what has
     * arrived on one receiver cannot reach another receiver's rows.
     */
    public fun clearCompleted(session: BroadcastSession, recipientId: String? = null): BroadcastSession {
        // Driven from the pairs this broadcast owes, so a key the session cannot rebuild from
        // its own files and recipients is left exactly as it was rather than quietly dropped.
        val removable = session.expected.filter { key ->
            val inScope = recipientId == null || key.recipientId == recipientId
            inScope && session.deliveries[key]?.state == TransferState.DONE
        }.toSet()
        if (removable.isEmpty()) return session
        return session.copy(deliveries = session.deliveries - removable)
    }

    /** Every delivery in scope, with [transition] applied to each. */
    private fun over(
        session: BroadcastSession,
        recipientId: String?,
        transition: (BroadcastDelivery, BroadcastFile) -> BroadcastDelivery,
    ): BroadcastSession {
        val targets = session.uniqueFiles.flatMap { file ->
            session.uniqueRecipients
                .filter { recipientId == null || it.peerId == recipientId }
                .mapNotNull { recipient ->
                    val delivery = session.delivery(DeliveryKey(recipient.peerId, file.id))
                    if (delivery == null) null else delivery to file
                }
        }
        return session.withDeliveries(targets.map { (delivery, file) -> transition(delivery, file) })
    }

    /** One delivery's control, through the shared per-file rules. */
    private fun act(
        session: BroadcastSession,
        delivery: BroadcastDelivery,
        file: BroadcastFile,
        action: TransferRowAction,
    ): BroadcastDelivery {
        val item = delivery.asItem(session.id, file)
        return delivery.from(TransferRules.apply(item, action))
    }
}
