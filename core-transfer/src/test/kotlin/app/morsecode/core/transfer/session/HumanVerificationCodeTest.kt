package app.morsecode.core.transfer.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the short authentication string.
 *
 * The expected codes below were derived by hand from the bit layout, not by running this
 * implementation and pasting its output. That matters: a vector produced by the code under test
 * would still pass if the code were wrong.
 *
 * Layout: 25 bits taken as bits 24..17 of byte 0, 16..9 of byte 1, 8..1 of byte 2 and bit 7 of
 * byte 3, rendered most-significant-symbol first over the 32-symbol alphabet
 * "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".
 *
 *   AB CD EF 80 -> 10101011 11001101 11101111 1 -> 10101 01111 00110 11110 11111
 *               -> indices 21, 15, 6, 30, 31 -> P H 8 Y Z
 *   00 00 00 00 -> all-zero value            -> 2 2 2 2 2
 *   FF FF FF FF -> value 2^25 - 1            -> Z Z Z Z Z
 */
class HumanVerificationCodeTest {

    private fun code(vararg bytes: Int): HumanVerificationCode =
        HumanVerificationCode.fromExporterMaterial(ByteArray(32) { index ->
            if (index < bytes.size) bytes[index].toByte() else 0
        })

    @Test
    fun `alphabet is exactly 32 unambiguous symbols`() {
        val alphabet = HumanVerificationCode.SAS_ALPHABET
        assertEquals(32, alphabet.length)
        assertEquals("alphabet must not repeat a symbol", 32, alphabet.toSet().size)
        for (ambiguous in listOf('0', 'O', '1', 'I')) {
            assertFalse("alphabet must exclude $ambiguous", ambiguous in alphabet)
        }
        assertEquals(5, HumanVerificationCode.SAS_SYMBOL_COUNT)
        assertEquals(25, HumanVerificationCode.SAS_ENTROPY_BITS)
        assertEquals(
            HumanVerificationCode.SAS_SYMBOL_COUNT * 5,
            HumanVerificationCode.SAS_ENTROPY_BITS,
        )
    }

    @Test
    fun `hand derived vectors are reproduced`() {
        assertEquals("PH8YZ", code(0xAB, 0xCD, 0xEF, 0x80).displayText())
        assertEquals("22222", code(0x00, 0x00, 0x00, 0x00).displayText())
        assertEquals("ZZZZZ", code(0xFF, 0xFF, 0xFF, 0xFF).displayText())
    }

    @Test
    fun `every code is five alphabet symbols`() {
        var seed = 0x12345678L
        repeat(500) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val bytes = ByteArray(32) { index -> ((seed ushr (index % 8) * 8) and 0xFF).toByte() }
            val text = HumanVerificationCode.fromExporterMaterial(bytes).displayText()
            assertEquals(5, text.length)
            assertTrue("unexpected symbol in $text", text.all { it in HumanVerificationCode.SAS_ALPHABET })
            assertTrue("no ambiguous symbol may appear in $text", text.none { it in "0O1I" })
        }
    }

    @Test
    fun `the full 25 bit budget is used`() {
        // 2^25 - 1 renders as all-last-symbol, so no bit above 25 can be in play.
        assertEquals("ZZZZZ", code(0xFF, 0xFF, 0xFF, 0xFF).displayText())
        // Flipping only the most significant bit of byte 0 must change the first symbol.
        val high = code(0x80, 0x00, 0x00, 0x00).displayText()
        val low = code(0x00, 0x00, 0x00, 0x00).displayText()
        assertNotEquals(high, low)
        // 0x80 << 17 == 2^24, whose most significant base-32 symbol is alphabet[16] == 'J'.
        assertEquals("J2222", high)
        assertEquals("22222", low)
    }

    @Test
    fun `only the documented bits of the fourth byte are consumed`() {
        // Bit 7 of byte 3 is the 25th bit; bits 6..0 must not affect the code.
        val withBit = code(0x00, 0x00, 0x00, 0x80).displayText()
        val withoutBit = code(0x00, 0x00, 0x00, 0x7F).displayText()
        assertNotEquals(withBit, withoutBit)
        assertEquals(withBit, code(0x00, 0x00, 0x00, 0x81).displayText())
        assertEquals(withoutBit, code(0x00, 0x00, 0x00, 0x3F).displayText())
    }

    @Test
    fun `too-short exporter material is rejected`() {
        var threw = false
        try {
            HumanVerificationCode.fromExporterMaterial(ByteArray(3))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("three bytes cannot carry 25 bits", threw)
    }

    @Test
    fun `exporter material is wiped whether conversion succeeds or fails`() {
        val ok = ByteArray(32) { 0x41 }
        HumanVerificationCode.fromExporterMaterial(ok)
        assertTrue("material must be zeroed after a successful conversion", ok.all { it == 0.toByte() })

        val short = ByteArray(3) { 0x41 }
        try {
            HumanVerificationCode.fromExporterMaterial(short)
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertTrue("material must be zeroed after a failed conversion", short.all { it == 0.toByte() })
    }

    @Test
    fun `the code is redacted and cleared`() {
        val proof = code(0xAB, 0xCD, 0xEF, 0x80)
        assertEquals("HumanVerificationCode([redacted])", proof.toString())
        assertEquals("PH8YZ", proof.displayText())
        proof.clearSensitive()
        assertEquals("clearing must make the code unreadable", "", proof.displayText())
        assertEquals("HumanVerificationCode([redacted])", proof.toString())
    }

    @Test
    fun `distinct exporter prefixes give distinct codes`() {
        val seen = HashSet<String>()
        for (first in 0..255) {
            seen.add(code(first, 0x00, 0x00, 0x00).displayText())
        }
        // Byte 0 contributes 8 of the 25 bits, so all 256 prefixes must be distinguishable.
        assertEquals(256, seen.size)
    }
}
