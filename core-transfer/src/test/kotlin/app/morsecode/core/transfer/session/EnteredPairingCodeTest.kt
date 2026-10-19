package app.morsecode.core.transfer.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Entered-code contract tests: generation, normalisation, erasure and the entry budget. */
public class EnteredPairingCodeTest {

    private fun fixed(vararg values: Int): SecureEntropy = SecureEntropy { destination ->
        for (index in destination.indices) {
            destination[index] = values[index % values.size].toByte()
        }
    }

    @Test
    public fun `generation is deterministic for a fixed entropy source`() {
        val code = EnteredPairingCode.generate(fixed(0, 1, 2, 3, 4, 5, 6, 7))
        assertEquals("0123-4567", code.displayText())
    }

    @Test
    public fun `the full alphabet is reachable and every symbol is five bits`() {
        assertEquals(32, EnteredPairingCode.ALPHABET.length)
        assertEquals(8, EnteredPairingCode.SYMBOL_COUNT)
        assertEquals(40, EnteredPairingCode.ENTROPY_BITS)
        assertEquals(
            "the alphabet must contain no duplicates",
            EnteredPairingCode.ALPHABET.length,
            EnteredPairingCode.ALPHABET.toSet().size,
        )
        // Every generated symbol comes from the alphabet, and the ambiguous letters are absent
        // from it even though they are accepted on input.
        assertFalse(EnteredPairingCode.ALPHABET.contains('I'))
        assertFalse(EnteredPairingCode.ALPHABET.contains('L'))
        assertFalse(EnteredPairingCode.ALPHABET.contains('O'))
        assertFalse(EnteredPairingCode.ALPHABET.contains('U'))
        val code = EnteredPairingCode.generate(fixed(31))
        assertEquals("ZZZZ-ZZZZ", code.displayText())
    }

    @Test
    public fun `input is normalised as documented`() {
        val expected = "01234567"
        assertEquals(expected, parseText("0123-4567").displayText().replace("-", ""))
        assertEquals(expected, parseText("O123-4567").displayText().replace("-", ""))
        assertEquals(expected, parseText("o123 4567").displayText().replace("-", ""))
        assertEquals("11234567", parseText("I123-4567").displayText().replace("-", ""))
        assertEquals("11234567", parseText("L123-4567").displayText().replace("-", ""))
        assertEquals("ABCDEFGH", parseText("abcdefgh").displayText().replace("-", ""))
    }

    @Test
    public fun `invalid input is refused rather than guessed`() {
        assertNull(parseOrNull("U1234567"))
        assertNull(parseOrNull("0123-456"))
        assertNull(parseOrNull("0123-45678"))
        assertNull(parseOrNull(""))
        assertNull(parseOrNull("0123_4567"))
        // Seven symbols, so refused however it is punctuated.
        assertNull(parseOrNull("0123-45 7"))
        // Spaces and tabs are ignored anywhere by contract, so an interior space still parses
        // when the symbol count is right.
        assertEquals("01234567", parseText("0123-45 67").displayText().replace("-", ""))
        // One symbol too many is still a refusal, even when the extra one is harmless.
        assertNull(parseOrNull("0123-4567 8"))
    }

    @Test
    public fun `the code clears and stays cleared`() {
        val code = parseText("0123-4567")
        assertTrue(code.displayText().isNotEmpty())
        code.clearSensitive()
        assertTrue(code.isCleared())
        assertEquals("", code.displayText())
        assertEquals(0, code.toPasswordChars().size)
        code.clearSensitive()
        assertTrue(code.isCleared())
    }

    @Test
    public fun `neither the string form nor the budget leaks the code`() {
        val code = parseText("0123-4567")
        assertFalse(code.toString().contains("0123"))
        assertEquals("EnteredPairingCode([redacted])", code.toString())
        assertFalse(EnteredCodeAttemptBudget().toString().contains("code"))
    }

    @Test
    public fun `the password array is a copy that the caller owns`() {
        val code = parseText("0123-4567")
        val first = code.toPasswordChars()
        first.fill('\u0000')
        assertEquals("0123-4567", code.displayText())
        assertEquals(8, code.toPasswordChars().size)
    }

    @Test
    public fun `three entries are allowed and the fourth spends the code`() {
        val budget = EnteredCodeAttemptBudget()
        assertEquals(3, budget.remaining())
        assertTrue(budget.tryConsume())
        assertTrue(budget.tryConsume())
        assertTrue(budget.tryConsume())
        assertEquals(0, budget.remaining())
        assertFalse(budget.tryConsume())
        assertTrue(budget.isExhausted())
    }

    @Test
    public fun `only a fresh code resets the budget`() {
        val budget = EnteredCodeAttemptBudget()
        repeat(3) { assertTrue(budget.tryConsume()) }
        assertFalse(budget.tryConsume())
        assertTrue(budget.isExhausted())
        budget.reset()
        assertFalse(budget.isExhausted())
        assertEquals(3, budget.remaining())
    }

    private fun parseText(text: String): EnteredPairingCode {
        val parsed = EnteredPairingCode.parse(text.toCharArray())
        assertNotNull("expected `$text` to parse", parsed)
        return requireNotNull(parsed)
    }

    private fun parseOrNull(text: String): EnteredPairingCode? =
        EnteredPairingCode.parse(text.toCharArray())
}
