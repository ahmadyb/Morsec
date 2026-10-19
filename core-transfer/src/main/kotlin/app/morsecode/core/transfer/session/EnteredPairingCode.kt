/*
 * The entered pairing code -- the only secret a human carries between two devices.
 *
 * Eight symbols over a 32-symbol alphabet is exactly 40 bits. That number is the whole online
 * guessing budget: with a hard limit of three entries per generated code, an attacker who reaches
 * the verifier has about a 2^-38 chance against one code. The limiter is a denial-of-service bound,
 * not authentication; the entropy is what does the work.
 *
 * WHY THIS IS NOT THE OLD SAS
 *   The previous mechanism *derived* five symbols from the TLS exporter and asked two humans to
 *   compare them. That is a comparison, and its 25 bits were the entire human contribution. This
 *   code is instead *typed* by a human, so it has to survive transcription: eight symbols, an
 *   alphabet with no ambiguous pairs, and a documented normalisation step.
 *
 * GENERATION IS UNBIASED BY CONSTRUCTION
 *   A uniform byte carries 8 bits. Because 32 divides 256 exactly, the low five bits of a uniform
 *   byte are uniform over 0..31 -- no rejection loop and no modulo reduction is needed, and none is
 *   used. A modulo-32 reduction of a *non*-uniform value would be biased; taking the low five bits
 *   of a genuinely uniform byte is not, and the difference is the reason this comment exists.
 *
 * WHAT THIS TYPE REFUSES TO DO
 *   It never logs, never persists, never renders itself through toString(), and never writes
 *   itself to a clipboard. displayText() is the single deliberate disclosure point.
 */

package app.morsecode.core.transfer.session

/**
 * A generated or entered eight-symbol code.
 *
 * The symbols are held in a mutable array so they can be wiped; [clearSensitive] is idempotent and
 * every accessor after it returns an empty or null result rather than stale material.
 */
public class EnteredPairingCode private constructor(symbols: CharArray) {
    private val values: CharArray = symbols.copyOf()
    private var cleared: Boolean = false

    init {
        require(values.size == SYMBOL_COUNT) { "a pairing code is exactly $SYMBOL_COUNT symbols" }
        require(values.all { it in ALPHABET }) { "pairing code contains a symbol outside the alphabet" }
    }

    /** Grouped 4-4 for reading aloud. The only API that reveals the code. */
    @Synchronized
    public fun displayText(): String {
        if (cleared) return ""
        val builder = StringBuilder(SYMBOL_COUNT + 1)
        for (index in 0 until SYMBOL_COUNT) {
            if (index == SYMBOL_COUNT / 2) builder.append('-')
            builder.append(values[index])
        }
        return builder.toString()
    }

    /**
     * A fresh copy for the key-agreement library, which takes `char[]` and wipes its own defensive
     * copy. The caller owns the returned array and must zero it after use.
     */
    @Synchronized
    public fun toPasswordChars(): CharArray =
        if (cleared) CharArray(0) else values.copyOf()

    /** Wipes the code and marks the object unusable. Safe to call more than once. */
    @Synchronized
    public fun clearSensitive() {
        values.fill('\u0000')
        cleared = true
    }

    @Synchronized
    public fun isCleared(): Boolean = cleared

    /** Redacted on purpose: this value must never reach a log line through string interpolation. */
    override fun toString(): String = "EnteredPairingCode([redacted])"

    public companion object {
        /** Eight symbols of five bits each. Documented so a later change cannot shrink the budget. */
        public const val SYMBOL_COUNT: Int = 8

        public const val ENTROPY_BITS: Int = SYMBOL_COUNT * 5

        /**
         * Crockford base32: digits and letters without I, L, O and U.
         *
         * I/L/O are excluded because they are indistinguishable from 1 and 0 when read aloud or
         * written down; U is excluded by the alphabet's definition. Input normalisation maps the
         * excluded lookalikes back onto the symbols that are actually generated.
         */
        public const val ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        private const val ALPHABET_RADIX: Int = 32

        /**
         * Generates a code from the supplied entropy source.
         *
         * One byte yields one symbol: `byte and 0x1F`. Because 32 divides 256, every symbol is
         * equally likely for a uniform byte, so there is no modulo bias to correct.
         */
        public fun generate(entropy: SecureEntropy): EnteredPairingCode {
            val random = ByteArray(SYMBOL_COUNT)
            val symbols = CharArray(SYMBOL_COUNT)
            try {
                entropy.nextBytes(random)
                for (index in symbols.indices) {
                    symbols[index] = ALPHABET[random[index].toInt() and 0x1F]
                }
                return EnteredPairingCode(symbols)
            } finally {
                random.fill(0)
                symbols.fill('\u0000')
            }
        }

        /**
         * Normalises and parses human input, returning null when it cannot be a valid code.
         *
         * Normalisation is part of the contract, not a convenience: `o`/`O` become `0`, `i`/`I`
         * and `l`/`L` become `1`, lowercase becomes uppercase, and spaces or hyphens are ignored.
         * Anything else -- including `U`, which the alphabet excludes -- is a rejection rather than
         * a guess.
         */
        public fun parse(input: CharArray): EnteredPairingCode? {
            val symbols = CharArray(SYMBOL_COUNT)
            var count = 0
            var index = 0
            try {
                while (index < input.size) {
                    val raw = input[index]
                    index++
                    if (raw == ' ' || raw == '-' || raw == '\t') continue
                    val normalised = normalise(raw)
                    if (normalised == null) return null
                    if (count == SYMBOL_COUNT) return null
                    symbols[count] = normalised
                    count++
                }
                if (count != SYMBOL_COUNT) return null
                return EnteredPairingCode(symbols)
            } finally {
                symbols.fill('\u0000')
            }
        }

        /** Single-symbol normalisation, exposed so the rules can be tested directly. */
        public fun normalise(symbol: Char): Char? {
            var value = symbol
            if (value in 'a'..'z') value = value.uppercaseChar()
            return when {
                value in ALPHABET -> value
                value == 'O' -> '0'
                value == 'I' || value == 'L' -> '1'
                else -> null
            }
        }
    }
}

/**
 * Entry budget for one generated code.
 *
 * Three entries, then the code is dead and a fresh discovery plus a fresh code is required. The
 * budget is deliberately not resettable by re-displaying the same code: [reset] exists for a *new*
 * code only, and callers that reach for it to clear an exhaustion have reintroduced the attack it
 * is there to bound.
 *
 * This is process-local by construction. It resets when the process dies, which is documented
 * rather than hidden; at 40 bits the entropy is the bound and this is the throttle.
 */
public class EnteredCodeAttemptBudget(private val maxAttempts: Int = MAX_ENTERED_CODE_ATTEMPTS) {
    private var attempts: Int = 0
    private var invalidated: Boolean = false

    init {
        require(maxAttempts in 1..16) { "attempt budget out of range" }
    }

    /**
     * Claims one entry. Returns false once the budget is spent, and invalidates the code at that
     * point so a later call cannot succeed by arriving from a different code path.
     */
    @Synchronized
    public fun tryConsume(): Boolean {
        if (invalidated) return false
        if (attempts >= maxAttempts) {
            // Reaching for a fourth entry spends the code, not just the request: there is no path
            // from here back to a usable budget.
            invalidated = true
            return false
        }
        attempts++
        return true
    }

    @Synchronized
    public fun remaining(): Int = if (invalidated) 0 else (maxAttempts - attempts)

    @Synchronized
    public fun isExhausted(): Boolean = invalidated

    /** Only for a freshly generated code. Never for re-showing the previous one. */
    @Synchronized
    public fun reset() {
        attempts = 0
        invalidated = false
    }

    override fun toString(): String = "EnteredCodeAttemptBudget(remaining=${remaining()})"

    public companion object {
        /** Three entries per generated code. */
        public const val MAX_ENTERED_CODE_ATTEMPTS: Int = 3
    }
}
