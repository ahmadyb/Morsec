package app.morsecode.transport.lan.payload

/*
 * The four TLS exporter labels the payload record layer is keyed from.
 *
 * WHY LABELS AND NOT ONE LONG OUTPUT
 *   The control layer already spends four labels for the same reason, and the reason is not style:
 *   splitting one exporter output in two makes both halves share the exporter's internal chaining,
 *   and reusing one output for two purposes makes the two purposes one key. Four labels is the
 *   smallest number that gives a key and an IV per direction with nothing shared.
 *
 * WHY THEY ARE DISTINCT FROM THE CONTROL LABELS
 *   AAD does not create separation. Two AEADs keyed alike with overlapping sequence ranges are a
 *   nonce reuse however different their headers are, and the two headers differ by exactly one byte
 *   of length, which is precisely the kind of difference a reader would not notice. The payload
 *   labels therefore carry a `PAYLOAD` segment the control labels do not have, so a collision is a
 *   string comparison that fails rather than an argument about whether it matters.
 *
 * WHY THEY END IN `V1`
 *   A label is a protocol constant: changing one changes every key derived from it, so it has to be
 *   versioned the same way the wire formats are. `V1` is the version of this schedule, not of the
 *   transport.
 *
 * THESE STRINGS ARE PINNED BYTES
 *   They cross into the TLS exporter as ASCII. A JVM test pins all four literally, and the boundary
 *   verifier reads them from this file and refuses a build where any of them equals a control,
 *   channel-binding or confirmation label.
 */
internal object LanPayloadExporterLabels {

    /** Payload key, initiator to responder. */
    const val C2S_KEY: String = "EXPORTER-MORSEC-PAYLOAD-C2S-KEY-V1"

    /** Payload nonce base, initiator to responder. */
    const val C2S_IV: String = "EXPORTER-MORSEC-PAYLOAD-C2S-IV-V1"

    /** Payload key, responder to initiator. */
    const val S2C_KEY: String = "EXPORTER-MORSEC-PAYLOAD-S2C-KEY-V1"

    /** Payload nonce base, responder to initiator. */
    const val S2C_IV: String = "EXPORTER-MORSEC-PAYLOAD-S2C-IV-V1"

    /**
     * All four, in derivation order.
     *
     * A list rather than four separate call sites, so a test can assert the set once and a verifier
     * can count it. Adding a fifth label means changing this list, which is the reviewable edit.
     */
    val ALL: List<String> = listOf(C2S_KEY, C2S_IV, S2C_KEY, S2C_IV)
}
