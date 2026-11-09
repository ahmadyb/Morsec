package app.morsecode.transport.lan.payload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The four exporter labels, pinned as bytes.
 *
 * A label is a protocol constant. Change one and every key derived from it changes, which means a
 * peer running the old build and a peer running the new one derive different keys and neither can
 * say why. That is why the strings are asserted literally here and read from the source by the
 * boundary verifier, instead of being compared against themselves.
 *
 * The collision assertions matter more than they look. The control layer already spends four labels
 * of the same shape, and the payload labels differ from them only by a `PAYLOAD` segment. A copy
 * and paste that forgot the segment would produce a build where control and payload share a key and
 * every structural test still passed.
 */
class LanPayloadExporterLabelsTest {

    private val controlLabels = listOf(
        "EXPORTER-MORSEC-C2S-KEY-V1",
        "EXPORTER-MORSEC-C2S-IV-V1",
        "EXPORTER-MORSEC-S2C-KEY-V1",
        "EXPORTER-MORSEC-S2C-IV-V1",
        "EXPORTER-MORSEC-PAIRING-PROOF-V1",
    )

    @Test fun `the four labels are these exact strings`() {
        assertEquals(
            listOf(
                "EXPORTER-MORSEC-PAYLOAD-C2S-KEY-V1",
                "EXPORTER-MORSEC-PAYLOAD-C2S-IV-V1",
                "EXPORTER-MORSEC-PAYLOAD-S2C-KEY-V1",
                "EXPORTER-MORSEC-PAYLOAD-S2C-IV-V1",
            ),
            LanPayloadExporterLabels.ALL,
        )
    }

    @Test fun `the labels are pinned as ASCII bytes`() {
        // The exporter takes a string and hands the provider its ASCII bytes; pinning the hex is
        // what makes a whitespace or case change visible rather than merely different.
        assertEquals(
            "4558504f525445522d4d4f525345432d5041594c4f41442d4332532d4b45592d5631",
            hex(LanPayloadExporterLabels.C2S_KEY),
        )
        assertEquals(
            "4558504f525445522d4d4f525345432d5041594c4f41442d4332532d49562d5631",
            hex(LanPayloadExporterLabels.C2S_IV),
        )
        assertEquals(
            "4558504f525445522d4d4f525345432d5041594c4f41442d5332432d4b45592d5631",
            hex(LanPayloadExporterLabels.S2C_KEY),
        )
        assertEquals(
            "4558504f525445522d4d4f525345432d5041594c4f41442d5332432d49562d5631",
            hex(LanPayloadExporterLabels.S2C_IV),
        )
        assertEquals(34, LanPayloadExporterLabels.C2S_KEY.toByteArray(Charsets.US_ASCII).size)
    }

    @Test fun `no two payload labels are equal`() {
        assertEquals(4, LanPayloadExporterLabels.ALL.toSet().size)
        assertEquals(4, LanPayloadExporterLabels.ALL.size)
    }

    @Test fun `no payload label collides with a control, proof or channel-binding label`() {
        for (payloadLabel in LanPayloadExporterLabels.ALL) {
            for (controlLabel in controlLabels) {
                assertFalse(
                    "the payload label $payloadLabel collides with $controlLabel",
                    payloadLabel == controlLabel,
                )
            }
        }
    }

    @Test fun `every label is inside the exporter alphabet`() {
        // EstablishedTlsSession.export refuses anything outside printable ASCII 0x21..0x7E, so a
        // label with a space or a control character would fail at the first derivation -- on the
        // device, mid-pairing, rather than here.
        for (label in LanPayloadExporterLabels.ALL) {
            assertTrue("$label is blank", label.isNotBlank())
            assertTrue(
                "$label contains a character the exporter refuses",
                label.all { it.code in 0x21..0x7E },
            )
            assertTrue("$label is not versioned: $label", label.endsWith("-V1"))
        }
    }

    @Test fun `each label names its direction and its purpose`() {
        assertTrue(LanPayloadExporterLabels.C2S_KEY.contains("-C2S-"))
        assertTrue(LanPayloadExporterLabels.C2S_IV.contains("-C2S-"))
        assertTrue(LanPayloadExporterLabels.S2C_KEY.contains("-S2C-"))
        assertTrue(LanPayloadExporterLabels.S2C_IV.contains("-S2C-"))
        assertTrue(LanPayloadExporterLabels.C2S_KEY.endsWith("-KEY-V1"))
        assertTrue(LanPayloadExporterLabels.S2C_KEY.endsWith("-KEY-V1"))
        assertTrue(LanPayloadExporterLabels.C2S_IV.endsWith("-IV-V1"))
        assertTrue(LanPayloadExporterLabels.S2C_IV.endsWith("-IV-V1"))
        // The direction and the purpose are what distinguish the four, so a pair that differed only
        // by case would not be a distinction on the wire.
        assertFalse(LanPayloadExporterLabels.C2S_KEY == LanPayloadExporterLabels.C2S_IV)
        assertFalse(LanPayloadExporterLabels.C2S_KEY == LanPayloadExporterLabels.S2C_KEY)
    }

    private fun hex(value: String): String {
        val builder = StringBuilder(value.length * 2)
        for (byte in value.toByteArray(Charsets.US_ASCII)) {
            val unsigned = byte.toInt() and 0xFF
            builder.append("0123456789abcdef"[unsigned ushr 4])
            builder.append("0123456789abcdef"[unsigned and 0x0F])
        }
        return builder.toString()
    }
}
