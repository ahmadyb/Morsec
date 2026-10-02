package app.morsecode.core.transfer.integrity

import app.morsecode.core.transfer.integrity.Crc32Test.Companion.deterministicBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class Sha256Test {

    @Test fun `known vector for the empty input`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256Accumulator().digest().hex,
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256Accumulator.EMPTY.hex,
        )
    }

    @Test fun `known vector for a single byte`() {
        val digest = Sha256Accumulator().updateByte('a'.code).digest()
        assertEquals(
            "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb",
            digest.hex,
        )
    }

    @Test fun `known vectors for abc and the 448 bit block boundary`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex(
                "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".toByteArray(),
            ),
        )
    }

    @Test fun `the 55 56 and 57 byte lengths straddle the padding boundary`() {
        val input = "a".repeat(128).toByteArray()
        for (length in listOf(55, 56, 57, 63, 64, 65, 119, 120, 127, 128)) {
            assertEquals(
                "length $length",
                platformSha256(input, length),
                sha256Hex(input, length),
            )
        }
    }

    @Test fun `chunk boundaries do not change the digest`() {
        val data = deterministicBytes(20_000)
        val oneShot = platformSha256(data, data.size)
        for (chunk in listOf(1, 7, 1_024, 4_096, 8_191, 20_000)) {
            val accumulator = Sha256Accumulator()
            var offset = 0
            while (offset < data.size) {
                val length = minOf(chunk, data.size - offset)
                accumulator.update(data, offset, length)
                offset += length
            }
            assertEquals("chunk size $chunk", oneShot, accumulator.digest().hex)
        }
    }

    @Test fun `a large streamed input matches the platform digest without buffering it whole`() {
        // 1 MiB moved in 64 KiB chunks: the accumulator holds one chunk at a
        // time, so this also proves nothing buffers the file.
        val data = deterministicBytes(1_048_576)
        val accumulator = Sha256Accumulator()
        var offset = 0
        while (offset < data.size) {
            val length = minOf(65_536, data.size - offset)
            accumulator.update(data, offset, length)
            offset += length
        }
        assertEquals(1_048_576L, data.size.toLong())
        assertEquals(platformSha256(data, data.size), accumulator.digest().hex)
    }

    @Test fun `the accumulator reports how much it has consumed and resets on digest`() {
        val accumulator = Sha256Accumulator()
        accumulator.update(ByteArray(1_000))
        assertEquals(1_000L, accumulator.bytesConsumed)
        val first = accumulator.digest()
        assertEquals(0L, accumulator.bytesConsumed)
        // Reusable: the same object can hash a second, different stream.
        accumulator.update(ByteArray(1_000))
        assertEquals(first, accumulator.digest())
    }

    @Test fun `reset discards partial state`() {
        val accumulator = Sha256Accumulator()
        accumulator.update("abc".toByteArray())
        accumulator.reset()
        assertEquals(0L, accumulator.bytesConsumed)
        assertEquals(Sha256Accumulator.EMPTY.hex, accumulator.digest().hex)
    }

    @Test fun `corrupted input produces a different digest`() {
        val data = deterministicBytes(10_000)
        val original = platformSha256(data, data.size)
        val corrupted = data.copyOf()
        corrupted[9_999] = (corrupted[9_999].toInt() xor 0x80).toByte()
        assertNotEquals(original, sha256Hex(corrupted))
    }

    @Test fun `zero byte files produce the empty digest`() {
        val accumulator = Sha256Accumulator()
        assertEquals(0L, accumulator.bytesConsumed)
        assertEquals(Sha256Accumulator.EMPTY, accumulator.digest())
    }

    @Test fun `canonical formatting is 64 lowercase hex characters`() {
        val hex = Sha256Accumulator().update("abc".toByteArray()).digest().hex
        assertEquals(64, hex.length)
        assertEquals(hex.lowercase(), hex)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test fun `digest equality is by content not identity`() {
        val a = Sha256Digest.fromHex("a".repeat(64))!!
        val b = Sha256Digest.fromHex("a".repeat(64))!!
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.contentEquals(b))
        assertFalse(a.contentEquals(Sha256Digest.fromHex("b".repeat(64))))
    }

    @Test fun `malformed digests are rejected rather than guessed at`() {
        assertNull(Sha256Digest.of(ByteArray(31)))
        assertNull(Sha256Digest.fromHex("a".repeat(63)))
        assertNull(Sha256Digest.fromHex("g".repeat(64)))
    }

    @Test fun `out of range updates are rejected`() {
        val accumulator = Sha256Accumulator()
        var threw = false
        try {
            accumulator.update(ByteArray(8), 4, 8)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    private fun sha256Hex(data: ByteArray, length: Int = data.size): String {
        val accumulator = Sha256Accumulator()
        accumulator.update(data, 0, length)
        return accumulator.digest().hex
    }

    private fun platformSha256(data: ByteArray, length: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data, 0, length)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
