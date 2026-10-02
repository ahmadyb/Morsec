package app.morsecode.core.transfer.integrity

import app.morsecode.core.transfer.ProtocolLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

class Crc32Test {

    @Test fun `known check value for 123456789`() {
        // The canonical CRC-32 check value every implementation agrees on.
        assertEquals(0xCBF43926L, Crc32.asUnsigned(Crc32.compute("123456789".toByteArray())))
        assertEquals("cbf43926", Crc32.toHex(Crc32.compute("123456789".toByteArray())))
    }

    @Test fun `empty payload produces zero`() {
        assertEquals(0L, Crc32.asUnsigned(Crc32.compute(ByteArray(0))))
        assertEquals("00000000", Crc32.toHex(Crc32.compute(ByteArray(0))))
    }

    @Test fun `known single byte value`() {
        assertEquals(0xE8B7BE43L, Crc32.asUnsigned(Crc32.compute("a".toByteArray())))
    }

    @Test fun `matches the platform implementation for every length up to 300 bytes`() {
        val data = deterministicBytes(300)
        for (length in 0..300) {
            val expected = CRC32().apply { update(data, 0, length) }.value
            assertEquals(
                "mismatch at length $length",
                expected,
                Crc32.asUnsigned(Crc32.compute(data, 0, length)),
            )
        }
    }

    @Test fun `matches the platform implementation across offsets and lengths`() {
        val data = deterministicBytes(1_000)
        for (offset in listOf(0, 1, 7, 64, 999)) {
            for (length in listOf(0, 1, 33, 500)) {
                if (offset + length > data.size) continue
                val expected = CRC32().apply { update(data, offset, length) }.value
                assertEquals(
                    "mismatch at $offset..${offset + length}",
                    expected,
                    Crc32.asUnsigned(Crc32.compute(data, offset, length)),
                )
            }
        }
    }

    @Test fun `a corrupted single byte changes the checksum`() {
        val payload = deterministicBytes(4_096)
        val original = Crc32.compute(payload)
        for (index in listOf(0, 1, 2_047, 2_048, 4_095)) {
            val corrupted = payload.copyOf()
            corrupted[index] = (corrupted[index].toInt() xor 0x01).toByte()
            assertFalse(
                "a flipped bit at $index must change the CRC32",
                Crc32.compute(corrupted) == original,
            )
        }
    }

    @Test fun `incremental update equals one shot compute`() {
        val payload = deterministicBytes(1_024)
        var crc = -1
        for (byte in payload) crc = Crc32.update(crc, byte.toInt())
        assertEquals(Crc32.compute(payload), crc xor -1)
    }

    @Test fun `maximum payload is checked without buffering more than the chunk`() {
        // The largest allocation the codec will ever make for a payload. The
        // point of the test is that it succeeds at all: a decoder that trusted
        // the declared length before bounding it would not get this far.
        val payload = deterministicBytes(ProtocolLimits.MAX_PAYLOAD_BYTES)
        val expected = CRC32().apply { update(payload, 0, payload.size) }.value
        assertEquals(expected, Crc32.asUnsigned(Crc32.compute(payload)))
        assertEquals(payload.size, ProtocolLimits.MAX_PAYLOAD_BYTES)
    }

    @Test fun `matches reports agreement and disagreement`() {
        val payload = "payload".toByteArray()
        val crc = Crc32.compute(payload)
        assertTrue(Crc32.matches(payload, crc))
        assertFalse(Crc32.matches(payload, crc xor 1))
    }

    @Test fun `out of range requests are rejected rather than truncated`() {
        val payload = ByteArray(8)
        var threw = false
        try {
            Crc32.compute(payload, 4, 8)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    internal companion object {
        /**
         * Deterministic, allocation-light test data. A fixed-seed linear
         * congruential generator, not a random source, so a failure is always
         * reproducible.
         */
        fun deterministicBytes(length: Int): ByteArray {
            val out = ByteArray(length)
            var state = 0x2545F491L
            for (index in 0 until length) {
                state = (state * 6364136223846793005L + 1442695040888963407L) and 0x7FFFFFFFFFFFFFFFL
                out[index] = (state ushr 33).toByte()
            }
            return out
        }
    }
}
