package app.morsecode.ui.broadcast

import app.morsecode.core.model.Peer

/**
 * The phone picker's state: which discovered devices were offered, and which the user chose.
 *
 * Discovery itself is milestone 6's and 7's work, so the list here is handed in rather than
 * searched for; what this model owns is everything the picker decides. Three of those are
 * rules rather than preferences:
 *
 * - **Only phones and tablets can be chosen.** A broadcast fans a batch out to phones that
 *   install the app; a laptop on WebShare or a browser session is not a recipient. The rule is
 *   the peer model's own ([Peer.isBroadcastEligible]) and the list is filtered here, so a
 *   desktop cannot be selected, counted or started with even by a screen that offers it.
 * - **Two phones is the floor.** The master prompt says a broadcast addresses at least two,
 *   and [canStart] is the single answer every screen reads, so a button cannot offer a start
 *   the session model would refuse.
 * - **A repeated id is one device.** [chosen] is a set and [selectable] de-duplicates by id, so
 *   a transport that reports the same phone twice cannot inflate the recipient count or the
 *   bytes the batch will carry.
 */
public data class BroadcastSelection(
    /** What discovery found. The picker filters this; it never adds to it. */
    public val peers: List<Peer> = emptyList(),
    public val chosen: Set<String> = emptySet(),
) {
    /** The devices this screen may offer, once each, phones and tablets only. */
    public val selectable: List<Peer> get() = BroadcastMath.distinctRecipients(peers)

    /** The chosen devices, in the order they were discovered. */
    public val selected: List<Peer> get() = selectable.filter { it.peerId in chosen }

    public val selectedCount: Int get() = selected.size

    /** True once the batch could legally start. */
    public val canStart: Boolean get() = selectedCount >= BroadcastMath.MINIMUM_RECIPIENTS

    /** How many more phones are needed, for the sentence that explains the disabled button. */
    public val shortfall: Int get() = (BroadcastMath.MINIMUM_RECIPIENTS - selectedCount).coerceAtLeast(0)

    public fun isSelected(peerId: String): Boolean = chosen.contains(peerId)

    /**
     * Tap a device: choose it, or let it go.
     *
     * A device this screen may not offer is left alone rather than silently added — the model
     * refuses it wherever the tap came from.
     */
    public fun toggled(peerId: String): BroadcastSelection {
        if (selectable.none { it.peerId == peerId }) return this
        return copy(chosen = if (chosen.contains(peerId)) chosen - peerId else chosen + peerId)
    }

    /** The chosen ids as one saved-state token, so a recreated screen comes back the same. */
    public val chosenToken: String get() = chosen.sorted().joinToString(SEPARATOR)

    public companion object {
        private const val SEPARATOR = ","

        /**
         * The selection a screen restores from a saved token.
         *
         * Ids that are not in the offered list — a device that has since gone away, a token
         * written by an older build — are dropped rather than trusted: the restored state has
         * to be a state this screen could have produced.
         */
        public fun fromToken(peers: List<Peer>, token: String?): BroadcastSelection {
            val offered = BroadcastMath.distinctRecipients(peers).map { it.peerId }.toSet()
            val chosen = token.orEmpty()
                .split(SEPARATOR)
                .map { it.trim() }
                .filter { it.isNotEmpty() && offered.contains(it) }
                .toSet()
            return BroadcastSelection(peers = peers, chosen = chosen)
        }
    }
}
