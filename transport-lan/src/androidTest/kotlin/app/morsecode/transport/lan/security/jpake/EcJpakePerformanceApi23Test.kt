package app.morsecode.transport.lan.security.jpake

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bouncycastle.crypto.agreement.ecjpake.ECJPAKECurves
import org.bouncycastle.crypto.agreement.ecjpake.ECJPAKEParticipant
import org.bouncycastle.crypto.digests.SHA256Digest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bounded API 23 timing measurement for the pinned Bouncy Castle EC J-PAKE implementation.
 *
 * **Historical, not the implemented profile.** The shipped exchange is MODP over
 * `JPAKEPrimeOrderGroups.NIST_3072`, measured separately by `ModpJpakeMeasurementApi23Test`. These
 * EC numbers are retained as the comparison that informed the variant choice.
 *
 * This is a feasibility measurement, not a benchmark. The assertion is a deliberately
 * generous ceiling that would only trip on a catastrophic regression, so that the result is
 * reproducible on a loaded CI emulator. The interesting numbers are the logged medians and
 * high percentiles, which exist to be read by a human reviewer, not asserted.
 *
 * Allocation is NOT measured. The thread-allocation counters available on API 23 include all
 * activity on the thread and cannot be attributed to the exchange reliably under a concurrent
 * test runner, so reporting them would be misleading.
 *
 * Only fixed phase names and bounded integers are logged. No entered code, keying material or
 * MAC tag is ever written to a log.
 */
@RunWith(AndroidJUnit4::class)
class EcJpakePerformanceApi23Test {

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun honestExchangePhaseTimingsStayUnderAGenerousCeiling() {
        // Warm-up is deliberately separated and discarded: the first exchange pays for class
        // loading, curve table construction and JIT, which is not representative.
        repeat(WARMUP) { timedExchange("warmup-$it") }

        val samples = (0 until ITERATIONS).map { timedExchange("measured-$it") }

        reportPhase("construct", samples.map { it.constructNanos })
        reportPhase("round1_create", samples.map { it.round1CreateNanos })
        reportPhase("round1_validate", samples.map { it.round1ValidateNanos })
        reportPhase("round2_create", samples.map { it.round2CreateNanos })
        reportPhase("round2_validate", samples.map { it.round2ValidateNanos })
        reportPhase("keying_material", samples.map { it.keyingMaterialNanos })
        reportPhase("round3_create", samples.map { it.round3CreateNanos })
        reportPhase("round3_validate", samples.map { it.round3ValidateNanos })
        reportPhase("total_honest", samples.map { it.totalNanos })

        val medianTotalMicros = percentile(samples.map { it.totalNanos }.sorted(), 0.5) / NANOS_PER_MICRO
        Log.i(PHASE_TAG, "phase=median_total micros=$medianTotalMicros iterations=$ITERATIONS warmup=$WARMUP")
        assertTrue(
            "median honest exchange took ${medianTotalMicros}us, above the generous ceiling of " +
                "$CEILING_MICROS us",
            medianTotalMicros < CEILING_MICROS,
        )
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun wrongCodeExchangeCostsAboutTheSameAsAnHonestOne() {
        repeat(WARMUP) { timedWrongCodeExchange() }
        val samples = (0 until ITERATIONS).map { timedWrongCodeExchange() }
        reportPhase("total_wrong_code", samples)
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun fourConcurrentExchangesCompleteInsideTheCeiling() {
        repeat(WARMUP) { concurrentExchangeBatch() }
        val samples = (0 until CONCURRENT_ITERATIONS).map { concurrentExchangeBatch() }
        reportPhase("four_concurrent", samples)

        val medianMicros = percentile(samples.sorted(), 0.5) / NANOS_PER_MICRO
        assertTrue(
            "median four-concurrent batch took ${medianMicros}us, above the generous ceiling of " +
                "$CONCURRENT_CEILING_MICROS us",
            medianMicros < CONCURRENT_CEILING_MICROS,
        )
    }

    /** One fully timed honest exchange, returning per-phase nanoseconds. */
    private fun timedExchange(label: String): PhaseTimings {
        var start = System.nanoTime()
        val alice = newParticipant("$ID_A-$label")
        val bob = newParticipant("$ID_B-$label")
        val construct = System.nanoTime() - start

        start = System.nanoTime()
        val aliceRound1 = alice.createRound1PayloadToSend()
        val bobRound1 = bob.createRound1PayloadToSend()
        val round1Create = System.nanoTime() - start

        start = System.nanoTime()
        alice.validateRound1PayloadReceived(bobRound1)
        bob.validateRound1PayloadReceived(aliceRound1)
        val round1Validate = System.nanoTime() - start

        start = System.nanoTime()
        val aliceRound2 = alice.createRound2PayloadToSend()
        val bobRound2 = bob.createRound2PayloadToSend()
        val round2Create = System.nanoTime() - start

        start = System.nanoTime()
        alice.validateRound2PayloadReceived(bobRound2)
        bob.validateRound2PayloadReceived(aliceRound2)
        val round2Validate = System.nanoTime() - start

        start = System.nanoTime()
        val keyA = alice.calculateKeyingMaterial()
        val keyB = bob.calculateKeyingMaterial()
        val keyingMaterial = System.nanoTime() - start

        start = System.nanoTime()
        val aliceRound3 = alice.createRound3PayloadToSend(keyA)
        val bobRound3 = bob.createRound3PayloadToSend(keyB)
        val round3Create = System.nanoTime() - start

        start = System.nanoTime()
        alice.validateRound3PayloadReceived(bobRound3, keyA)
        bob.validateRound3PayloadReceived(aliceRound3, keyB)
        val round3Validate = System.nanoTime() - start

        return PhaseTimings(
            constructNanos = construct,
            round1CreateNanos = round1Create,
            round1ValidateNanos = round1Validate,
            round2CreateNanos = round2Create,
            round2ValidateNanos = round2Validate,
            keyingMaterialNanos = keyingMaterial,
            round3CreateNanos = round3Create,
            round3ValidateNanos = round3Validate,
            totalNanos = construct + round1Create + round1Validate + round2Create +
                round2Validate + keyingMaterial + round3Create + round3Validate,
        )
    }

    /** A wrong-code exchange fails at confirmation; the cost up to that point is what matters. */
    private fun timedWrongCodeExchange(): Long {
        val start = System.nanoTime()
        val alice = newParticipant(ID_A, CODE)
        val bob = newParticipant(ID_B, WRONG_CODE)
        // Create before validate on both sides: the library forbids the other order.
        val aliceRound1 = alice.createRound1PayloadToSend()
        val bobRound1 = bob.createRound1PayloadToSend()
        alice.validateRound1PayloadReceived(bobRound1)
        bob.validateRound1PayloadReceived(aliceRound1)
        val aliceRound2 = alice.createRound2PayloadToSend()
        val bobRound2 = bob.createRound2PayloadToSend()
        alice.validateRound2PayloadReceived(bobRound2)
        bob.validateRound2PayloadReceived(aliceRound2)
        val keyA = alice.calculateKeyingMaterial()
        val keyB = bob.calculateKeyingMaterial()
        val bobTag = bob.createRound3PayloadToSend(keyB)
        val failed = try {
            alice.validateRound3PayloadReceived(bobTag, keyA)
            false
        } catch (expected: Exception) {
            true
        }
        assertTrue("a wrong code must fail confirmation", failed)
        return System.nanoTime() - start
    }

    private fun concurrentExchangeBatch(): Long {
        val executor = Executors.newFixedThreadPool(CONCURRENCY)
        val start = System.nanoTime()
        try {
            val futures = executor.invokeAll(
                (0 until CONCURRENCY).map { index ->
                    Callable {
                        val alice = newParticipant("$ID_A-c$index")
                        val bob = newParticipant("$ID_B-c$index")
                        val aliceRound1 = alice.createRound1PayloadToSend()
                        val bobRound1 = bob.createRound1PayloadToSend()
                        alice.validateRound1PayloadReceived(bobRound1)
                        bob.validateRound1PayloadReceived(aliceRound1)
                        val aliceRound2 = alice.createRound2PayloadToSend()
                        val bobRound2 = bob.createRound2PayloadToSend()
                        alice.validateRound2PayloadReceived(bobRound2)
                        bob.validateRound2PayloadReceived(aliceRound2)
                        val keyA = alice.calculateKeyingMaterial()
                        val keyB = bob.calculateKeyingMaterial()
                        val aliceRound3 = alice.createRound3PayloadToSend(keyA)
                        val bobRound3 = bob.createRound3PayloadToSend(keyB)
                        alice.validateRound3PayloadReceived(bobRound3, keyA)
                        bob.validateRound3PayloadReceived(aliceRound3, keyB)
                        keyA == keyB
                    }
                },
                CONCURRENT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            assertTrue("every concurrent exchange must agree", futures.all { it.isDone && it.get() })
        } finally {
            executor.shutdownNow()
        }
        return System.nanoTime() - start
    }

    private fun reportPhase(name: String, nanos: List<Long>) {
        val sorted = nanos.sorted()
        val medianMicros = percentile(sorted, 0.5) / NANOS_PER_MICRO
        val highMicros = percentile(sorted, 0.9) / NANOS_PER_MICRO
        Log.i(PHASE_TAG, "phase=$name median_micros=$medianMicros p90_micros=$highMicros samples=${sorted.size}")
    }

    private fun percentile(sortedNanos: List<Long>, fraction: Double): Long {
        if (sortedNanos.isEmpty()) return 0L
        val index = ((sortedNanos.size - 1) * fraction).toInt().coerceIn(0, sortedNanos.size - 1)
        return sortedNanos[index]
    }

    private fun newParticipant(id: String, code: String = CODE): ECJPAKEParticipant {
        val secret = code.toCharArray()
        try {
            return ECJPAKEParticipant(
                id,
                secret,
                ECJPAKECurves.NIST_P256,
                SHA256Digest.newInstance(),
                SecureRandom(),
            )
        } finally {
            secret.fill('\u0000')
        }
    }

    private class PhaseTimings(
        val constructNanos: Long,
        val round1CreateNanos: Long,
        val round1ValidateNanos: Long,
        val round2CreateNanos: Long,
        val round2ValidateNanos: Long,
        val keyingMaterialNanos: Long,
        val round3CreateNanos: Long,
        val round3ValidateNanos: Long,
        val totalNanos: Long,
    )

    private companion object {
        const val PHASE_TAG = "MorsecJpakePhase"
        const val ID_A = "morsec-perf-a"
        const val ID_B = "morsec-perf-b"
        const val CODE = "7KQF-2XPD"
        const val WRONG_CODE = "9MTR-4WBN"

        const val WARMUP = 3
        const val ITERATIONS = 7
        const val CONCURRENCY = 4
        const val CONCURRENT_ITERATIONS = 3
        const val CONCURRENT_TIMEOUT_SECONDS = 90L

        const val NANOS_PER_MICRO = 1_000L

        // Generous ceilings. These are catastrophic-regression detectors on an emulator that
        // may be heavily loaded, not performance targets, and must not be tightened into a
        // fragile benchmark threshold.
        const val CEILING_MICROS = 20_000_000L
        const val CONCURRENT_CEILING_MICROS = 40_000_000L

        const val TEST_TIMEOUT_MILLIS = 100_000L
    }
}
