package app.morsecode.core.storage.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The free-space preflight, including the three ways it is allowed to be unsure.
 *
 * The point of these tests is that "accepted" is not a synonym for "guaranteed". Each acceptance
 * carries the reason it was reached, and a caller that needs certainty can ask for
 * `KNOWN_CAPACITY` specifically. What a caller must not do is treat `UNKNOWN_CAPACITY` as a pass it
 * can rely on -- which is why it is a distinct value rather than a boolean.
 */
class DestinationCapacityTest {

    private fun probe(bytes: Long?): FreeSpaceProbe = FreeSpaceProbe { bytes }

    @Test(timeout = 30_000L)
    fun knownCapacityBelowTheRequirementRefusesEarly() {
        val decision = DestinationCapacity.preflight(probe(1_000L), remainingBytes = 4_000L)
        assertTrue(decision is CapacityDecision.Refuse)
        val error = (decision as CapacityDecision.Refuse).error
        assertEquals(TransferStorageErrorCategory.INSUFFICIENT_SPACE, error.category)
        assertEquals(4_000L, (error as TransferStorageError.InsufficientSpace).requiredBytes)
        assertEquals(1_000L, error.availableBytes)
    }

    @Test(timeout = 30_000L)
    fun knownCapacityExactlyEqualToTheRequirementIsAccepted() {
        val decision = DestinationCapacity.preflight(probe(4_000L), remainingBytes = 4_000L)
        assertTrue(">= is a fit", decision is CapacityDecision.Accept)
        assertEquals(
            CapacityAcceptance.KNOWN_CAPACITY,
            (decision as CapacityDecision.Accept).reason,
        )
    }

    @Test(timeout = 30_000L)
    fun unknownCapacityIsNotAnErrorButIsNotAcknowledgedAsCertainty() {
        val decision = DestinationCapacity.preflight(probe(null), remainingBytes = 10L * 1024 * 1024)
        assertTrue(decision is CapacityDecision.Accept)
        assertEquals(
            "the caller can tell this was a guess, not a measurement",
            CapacityAcceptance.UNKNOWN_CAPACITY,
            (decision as CapacityDecision.Accept).reason,
        )
    }

    @Test(timeout = 30_000L)
    fun anAbsentProbeBehavesLikeAnUnknownCapacity() {
        val decision = DestinationCapacity.preflight(null, remainingBytes = 1L)
        assertEquals(
            CapacityAcceptance.UNKNOWN_CAPACITY,
            (decision as CapacityDecision.Accept).reason,
        )
    }

    @Test(timeout = 30_000L)
    fun anUnknownLengthSourceIsExemptFromTheCheck() {
        // A streamed source has no declared length. It must not be refused for lacking one, and it
        // must not be reported as having been checked against capacity.
        val decision = DestinationCapacity.preflight(probe(0L), remainingBytes = null)
        assertTrue(decision is CapacityDecision.Accept)
        assertEquals(
            CapacityAcceptance.UNKNOWN_REQUIREMENT,
            (decision as CapacityDecision.Accept).reason,
        )
    }

    @Test(timeout = 30_000L)
    fun aNegativeAvailableFigureIsTreatedAsUnknownRatherThanFull() {
        // Some providers report -1 for "no idea". Treating that as zero would refuse everything.
        val decision = DestinationCapacity.preflight(probe(-1L), remainingBytes = 8L)
        assertEquals(
            CapacityAcceptance.UNKNOWN_CAPACITY,
            (decision as CapacityDecision.Accept).reason,
        )
    }

    @Test(timeout = 30_000L)
    fun theDefaultMarginIsZeroSoARightSizedTransferIsNotRefused() {
        assertEquals(0L, DestinationCapacity.DEFAULT_SAFETY_MARGIN_BYTES)
        val decision = DestinationCapacity.preflight(probe(100L), remainingBytes = 100L)
        assertEquals(
            "no hidden reserve: a transfer that exactly fits is accepted",
            CapacityAcceptance.KNOWN_CAPACITY,
            (decision as CapacityDecision.Accept).reason,
        )
    }

    @Test(timeout = 30_000L)
    fun aCallerChosenMarginIsAppliedAndCountsTowardTheRequirement() {
        val refused = DestinationCapacity.preflight(probe(100L), remainingBytes = 100L, safetyMarginBytes = 1L)
        assertTrue(refused is CapacityDecision.Refuse)
        assertEquals(
            101L,
            ((refused as CapacityDecision.Refuse).error as TransferStorageError.InsufficientSpace).requiredBytes,
        )

        val allowed = DestinationCapacity.preflight(probe(101L), remainingBytes = 100L, safetyMarginBytes = 1L)
        assertEquals(
            CapacityAcceptance.KNOWN_CAPACITY,
            (allowed as CapacityDecision.Accept).reason,
        )
    }

    @Test(timeout = 30_000L)
    fun theRequirementSaturatesInsteadOfOverflowingToANegative() {
        val decision = DestinationCapacity.preflight(
            probe(Long.MAX_VALUE),
            remainingBytes = Long.MAX_VALUE - 1L,
            safetyMarginBytes = 10L,
        )
        // A naive `+` would wrap to a small positive number and then wrongly accept a real shortage.
        assertEquals(
            CapacityAcceptance.KNOWN_CAPACITY,
            (decision as CapacityDecision.Accept).reason,
        )
        val refusal = DestinationCapacity.preflight(
            probe(Long.MAX_VALUE - 1L),
            remainingBytes = Long.MAX_VALUE - 1L,
            safetyMarginBytes = 10L,
        )
        assertTrue("the saturated requirement must still refuse", refusal is CapacityDecision.Refuse)
        assertEquals(
            Long.MAX_VALUE,
            ((refusal as CapacityDecision.Refuse).error as TransferStorageError.InsufficientSpace).requiredBytes,
        )
    }

    @Test(timeout = 30_000L)
    fun aNegativeRequirementOrMarginIsAProgrammingError() {
        assertTrue(
            runCatching { DestinationCapacity.preflight(probe(1L), remainingBytes = -1L) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { DestinationCapacity.preflight(probe(1L), 1L, safetyMarginBytes = -1L) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test(timeout = 30_000L)
    fun theRefusalDoesNotQuoteAnyLocation() {
        val decision = DestinationCapacity.preflight(probe(1L), remainingBytes = 2L)
        val message = (decision as CapacityDecision.Refuse).error.toString()
        assertEquals(
            "a shortfall reports counts, never a location",
            "insufficient_space:Not enough space: 2 bytes needed, 1 available",
            message,
        )
        assertTrue(!message.contains("/") && !message.contains("content://"))
    }
}
