package app.morsecode.ui.transfer

/**
 * The two approved ways of reading one duplex session.
 *
 * The master prompt asks for two symmetrical views — "Sending + receiving" and
 * "Receiving + sending back" — of what is one session, and this is the whole of the
 * difference between them: which section comes first, and therefore which direction the
 * user is standing in. Everything below the headers is the same session, the same rows and
 * the same controls, which is why there is one screen here and not two that drift.
 *
 * The first name in the id is the direction whose section leads, and reading it back is
 * what the route carries (`session/sending`, `session/receiving`).
 */
public enum class TransferLayout(
    public val id: String,
    /**
     * The directions the batch card reports on, in the order it prints them.
     *
     * The reference puts the outbound batch line under the outbound rows on one view, and
     * both lines together at the foot of the other. Stating it here rather than in the
     * screen keeps the card's contents and the view model's summaries reading from one
     * source, so neither can quietly report on a direction the other does not.
     */
    public val summaryDirections: List<TransferDirection>,
) {
    /** "Sending + receiving": the outbound section leads, and its batch line follows it. */
    SENDING_FIRST("sending", listOf(TransferDirection.OUTGOING)),

    /** "Receiving + sending back": the inbound section leads, and both batch lines close the screen. */
    RECEIVING_FIRST("receiving", listOf(TransferDirection.INCOMING, TransferDirection.OUTGOING)),
    ;

    public companion object {
        /**
         * The layout a route token names.
         *
         * A token that names neither layout — a stale deep link, a hand-typed route — gets
         * the outbound view rather than a crash or an empty screen, the way an unreadable
         * sort token gets the app's default order.
         */
        public fun fromId(id: String?): TransferLayout = entries.firstOrNull { it.id == id } ?: SENDING_FIRST
    }
}
