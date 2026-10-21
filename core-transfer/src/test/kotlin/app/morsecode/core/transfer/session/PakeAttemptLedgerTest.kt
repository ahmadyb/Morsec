package app.morsecode.core.transfer.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-code PAKE attempt budget.
 *
 * The properties under test are the ones an attacker would otherwise have: the budget cannot be
 * extended, a fresh identifier cannot be reused, a local cancellation cannot spend the victim's
 * budget, and two exchanges cannot run against one code at the same time.
 *
 * These are behavioural tests of a pure reducer. Passing them says nothing about whether the
 * surrounding exchange is secure.
 */
public class PakeAttemptLedgerTest {

    @Test
    public fun `three charged failures exhaust the code and a fourth attempt is refused`() {
        val ledger = newLedger()
        val charges = (1..3).map { ordinal ->
            val attempt = requireNotNull(ledger.beginAttempt(entropy())) { "attempt $ordinal must start" }
            assertEquals(ordinal, attempt.ordinal)
            attempt.charge()
        }
        assertEquals(
            listOf(
                PakeAttemptCharge.CHARGED,
                PakeAttemptCharge.CHARGED,
                PakeAttemptCharge.EXHAUSTED,
            ),
            charges,
        )
        assertTrue(ledger.isExhausted())
        assertEquals(0, ledger.remaining())
        assertNull("a fourth attempt must be refused", ledger.beginAttempt(entropy()))
    }

    @Test
    public fun `a local stop does not spend the budget and does not renumber the next attempt`() {
        val ledger = newLedger()
        val first = requireNotNull(ledger.beginAttempt(entropy()))
        assertEquals(1, first.ordinal)
        assertTrue(first.abandon())

        // The next attempt keeps ordinal 1, because nothing was charged. Its identifier must differ.
        val second = requireNotNull(ledger.beginAttempt(entropy()))
        assertEquals(1, second.ordinal)
        assertNotEquals("an abandoned attempt's identifier must not be reused", first.id, second.id)
        assertEquals(3, ledger.remaining())
        assertEquals(0, ledger.attemptsCharged())
    }

    @Test
    public fun `every attempt gets a distinct identifier`() {
        val ledger = newLedger()
        val ids = (1..3).map {
            val attempt = requireNotNull(ledger.beginAttempt(entropy()))
            attempt.charge()
            attempt.id
        }
        assertEquals(3, ids.toSet().size)
    }

    @Test
    public fun `a second attempt cannot start while one is outstanding`() {
        val ledger = newLedger()
        assertNotNull(ledger.beginAttempt(entropy()))
        assertNull(
            "two live exchanges against one code are two guesses",
            ledger.beginAttempt(entropy()),
        )
    }

    @Test
    public fun `charging the same attempt twice spends one attempt of budget`() {
        val ledger = newLedger()
        val attempt = requireNotNull(ledger.beginAttempt(entropy()))
        assertEquals(PakeAttemptCharge.CHARGED, attempt.charge())
        assertEquals(PakeAttemptCharge.ALREADY_SETTLED, attempt.charge())
        assertEquals(1, ledger.attemptsCharged())
        assertEquals(2, ledger.remaining())
    }

    @Test
    public fun `a successful attempt settles once and does not spend budget`() {
        val ledger = newLedger()
        val attempt = requireNotNull(ledger.beginAttempt(entropy()))
        assertTrue(attempt.succeed())
        assertFalse("the second settle is a no-op", attempt.succeed())
        assertTrue(attempt.hasSucceeded())
        assertEquals(0, ledger.attemptsCharged())
        assertEquals(3, ledger.remaining())
    }

    @Test
    public fun `charging after a success is refused rather than counted`() {
        val ledger = newLedger()
        val attempt = requireNotNull(ledger.beginAttempt(entropy()))
        attempt.succeed()
        assertEquals(PakeAttemptCharge.ALREADY_SETTLED, attempt.charge())
        assertEquals(0, ledger.attemptsCharged())
    }

    @Test
    public fun `invalidate is idempotent and final`() {
        val ledger = newLedger()
        requireNotNull(ledger.beginAttempt(entropy()))
        ledger.invalidate()
        ledger.invalidate()
        assertTrue(ledger.isExhausted())
        assertEquals(0, ledger.remaining())
        assertNull(ledger.beginAttempt(entropy()))
    }

    @Test
    public fun `the attempt identifier is fixed width and redacted`() {
        val id = PakeAttemptId.generate(entropy())
        assertEquals(PakeAttemptId.BYTE_COUNT, id.bytes().size)
        assertEquals(PakeAttemptId.BYTE_COUNT * 2, id.hex().length)
        assertNull("a short identifier must be refused", PakeAttemptId.fromBytes(ByteArray(15)))
        assertNull(PakeAttemptId.fromBytes(ByteArray(17)))
        assertEquals("PakeAttemptId([redacted])", id.toString())
        assertEquals("PakeAttempt(ordinal=1, settled=false, ids=[redacted])", run {
            val attempt = requireNotNull(newLedger().beginAttempt(entropy()))
            attempt.toString().also { attempt.abandon() }
        })
    }

    @Test
    public fun `a code instance of the wrong width is refused`() {
        var threw = false
        try {
            PakeAttemptLedger(ByteArray(15))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    private fun newLedger(): PakeAttemptLedger = PakeAttemptLedger(ByteArray(16) { 0x5A })

    /**
     * A stand-in entropy source that yields a different block on every draw, the way a real one
     * does. A source that repeated itself would make the distinctness assertions look meaningful
     * while proving nothing: two draws would be equal no matter what the ledger did with them.
     */
    private fun entropy(): SecureEntropy = SecureEntropy { destination ->
        val draw = draws++
        destination.indices.forEach { destination[it] = (it * 7 + 1 + draw * 31).toByte() }
    }

    private var draws = 0
}
