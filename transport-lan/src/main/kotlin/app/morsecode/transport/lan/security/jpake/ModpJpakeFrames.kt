/*
 * Fixed-width conversions between the J-PAKE library's integers and the MPS3 frame fields.
 *
 * WHY THIS FILE EXISTS
 *   The frame layer stores bytes; the library works in BigInteger. That boundary is where a
 *   project can silently change the meaning of a value, so every conversion goes through exactly
 *   one function here and every function has a defined refusal.
 *
 *   Measured, not assumed: the API 23 harness found that Bouncy Castle's own
 *   `BigIntegers.asUnsignedByteArray` throws on overflow but silently encodes -1 as 255. A helper
 *   that is convenient in one direction and wrong in the other is how a value stops being what it
 *   was, so the unsigned encoders below are written here rather than borrowed.
 *
 * WHAT "CANONICAL" MEANS HERE
 *   A received value is accepted only if it is the unique fixed-width encoding of a value in
 *   range. There is no reader that accepts a short encoding and pads it, and no writer that emits
 *   a width the reader would reject.
 *
 * STATUS
 *   Experimental and not independently cryptographically reviewed. The owner accepted that risk
 *   on 2026-10-19; see doc/security/owner-risk-waiver-2026-10-19.md. Range checks here bound what
 *   the library is asked to accept; they are not a proof of anything.
 */

package app.morsecode.transport.lan.security.jpake

import app.morsecode.core.transfer.session.JpakeMessageCodec
import java.math.BigInteger
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroup
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroups

/**
 * The one group this project runs, and the only place integers become wire bytes.
 *
 * NIST 3072-bit MODP: 384-byte elements, a 256-bit subgroup order, so 32-byte exponents. The
 * widths are taken from [JpakeMessageCodec] rather than restated, so the codec and the adapter
 * cannot drift apart.
 */
public object ModpJpakeFrames {

    /** The pinned group. Public parameters only; there is no secret in a group definition. */
    internal val GROUP: JPAKEPrimeOrderGroup = JPAKEPrimeOrderGroups.NIST_3072

    /** The 3072-bit modulus. An element must satisfy `2 <= value < p`. */
    public val MODULUS_P: BigInteger = GROUP.p

    /** The 256-bit subgroup order. A proof scalar must satisfy `0 <= value < q`. */
    public val SUBGROUP_ORDER_Q: BigInteger = GROUP.q

    /** The generator. */
    public val GENERATOR: BigInteger = GROUP.g

    public val ELEMENT_BYTES: Int = JpakeMessageCodec.ELEMENT_BYTES
    public val SCALAR_BYTES: Int = JpakeMessageCodec.SCALAR_BYTES
    public val TAG_BYTES: Int = JpakeMessageCodec.TAG_BYTES

    /**
     * Lowest accepted group element.
     *
     * The library's own check is `0 < gx < p` plus `gx^q mod p == 1`. This project refuses 0 and 1
     * as well: neither can be a useful element, and accepting them would mean handing the library
     * a value whose only possible outcome is a rejection.
     */
    private val MIN_ELEMENT: BigInteger = BigInteger.valueOf(2)

    private val BYTE_MASK: BigInteger = BigInteger.valueOf(0xFF)

    /**
     * Encodes a group element as exactly [ELEMENT_BYTES] big-endian bytes.
     *
     * Refuses, with null, anything the decoder would refuse: a value outside `[2, p)`. Encoder and
     * decoder enforce the same range on purpose -- an encoder that can emit a value its own peer
     * must reject is a bug waiting for a network round trip to expose it.
     */
    public fun encodeElement(value: BigInteger): ByteArray? =
        if (value < MIN_ELEMENT || value >= MODULUS_P) null
        else encodeUnsigned(value, ELEMENT_BYTES)

    /**
     * Decodes a group element, or null if the width is wrong or the value is outside `[2, p)`.
     *
     * Subgroup membership is not decided here: that needs an exponentiation, and the library does
     * it in `JPAKEUtil.validateZeroKnowledgeProof` after the value has been range-checked.
     */
    public fun decodeElement(bytes: ByteArray): BigInteger? {
        if (bytes.size != ELEMENT_BYTES) return null
        val value = BigInteger(1, bytes)
        return if (value >= MIN_ELEMENT && value < MODULUS_P) value else null
    }

    /** Encodes an exponent modulo the subgroup order as exactly [SCALAR_BYTES] bytes, in `[0, q)`. */
    public fun encodeScalar(value: BigInteger): ByteArray? =
        if (value.signum() < 0 || value >= SUBGROUP_ORDER_Q) null
        else encodeUnsigned(value, SCALAR_BYTES)

    /**
     * Decodes a proof scalar, or null if the width is wrong or the value is outside `[0, q)`.
     *
     * Zero is accepted. A proof scalar is `v - x*h mod n`, which is legitimately zero for some
     * proofs, so refusing it would reject honest exchanges.
     */
    public fun decodeScalar(bytes: ByteArray): BigInteger? {
        if (bytes.size != SCALAR_BYTES) return null
        val value = BigInteger(1, bytes)
        return if (value < SUBGROUP_ORDER_Q) value else null
    }

    /**
     * Encodes the round-three confirmation tag as exactly [TAG_BYTES] two's-complement bytes.
     *
     * THE ONE PLACE `toByteArray()` IS CORRECT
     *   Errata R-3 forbids `BigInteger.toByteArray()` for wire values because it is variable
     *   width and signed. This value is different: the library returns the MAC as
     *   `new BigInteger(hmacOutput)` -- the *signed* reading of 32 HMAC bytes -- and reads it back
     *   with the signed `BigInteger(byte[])` constructor. Its Javadoc says so explicitly: a tag is
     *   negative about half the time and must be encoded two's complement and sign-extended.
     *
     *   So the variable-width signed form is converted here, immediately, into a *fixed* 32-byte
     *   field. No caller ever sees the variable-width form, and the width on the wire is constant.
     *   Using an unsigned encoder instead -- which is what the API 23 harness did at first -- makes
     *   confirmation fail roughly half the time, and it fails at the last round, after everything
     *   else has passed.
     *
     * Returns null if the value does not fit in 32 signed bytes, which cannot happen for a
     * well-formed tag and is treated as an internal error by the caller.
     */
    public fun encodeTag(value: BigInteger): ByteArray? {
        val minimal = value.toByteArray()
        if (minimal.size > TAG_BYTES) return null
        val padding: Byte = if (value.signum() < 0) 0xFF.toByte() else 0x00
        val out = ByteArray(TAG_BYTES) { padding }
        System.arraycopy(minimal, 0, out, TAG_BYTES - minimal.size, minimal.size)
        return out
    }

    /**
     * Decodes a round-three tag, or null if the width is not exactly [TAG_BYTES].
     *
     * Width is the only structural rule, and it is enough. Two's complement at a fixed width is a
     * bijection between the 32-byte strings and the values it can denote: unlike a variable-width
     * encoding, no two byte strings denote the same number, and none is a padded variant of
     * another. An earlier draft of this function round-tripped the value to "reject non-canonical
     * encodings"; that check was a tautology, because sign-extension to a fixed width is
     * idempotent. It is gone rather than kept as decoration.
     *
     * The practical consequence is the one that matters: the bytes a peer sends are exactly the
     * bytes the sender's HMAC produced, and `encodeTag(decodeTag(bytes))` returns them unchanged.
     */
    public fun decodeTag(bytes: ByteArray): BigInteger? =
        if (bytes.size == TAG_BYTES) BigInteger(bytes) else null

    /**
     * Writes an unsigned integer into a fixed width, refusing anything that does not fit.
     *
     * Written out rather than delegated so the refusal rules are visible: a negative value is a
     * null, a value wider than the field is a null, and a value that fits is zero-padded on the
     * left. Every branch is exercised by the unit tests.
     */
    private fun encodeUnsigned(value: BigInteger, width: Int): ByteArray? {
        if (value.signum() < 0) return null
        if (value.bitLength() > width * Byte.SIZE_BITS) return null
        val out = ByteArray(width)
        var remaining = value
        var index = width - 1
        while (remaining.signum() != 0) {
            out[index] = remaining.and(BYTE_MASK).toByte()
            remaining = remaining.shiftRight(Byte.SIZE_BITS)
            index--
        }
        return out
    }
}
