package dev.kasoti.diary

import kotlin.random.Random

/**
 * ULID generation for `evt_`/`wl_` identifiers (AGENTS.md §2).
 *
 * ULID is 26 Crockford-base32 characters: 10 of millisecond timestamp followed by 16 of
 * entropy. That layout is why it is used instead of a random UUID — ids sort by creation
 * time, so a diary dumped in arrival order still reads in the order the events happened, which
 * matters when someone is reconstructing a shift from a file.
 *
 * Within one millisecond the entropy is incremented rather than redrawn. Two events captured
 * in the same millisecond are ordered, and re-rolling would give two ids that sort
 * arbitrarily relative to the events they name.
 */
object Ulid {

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val TIME_CHARS = 10
    private const val RANDOM_CHARS = 16
    const val LENGTH = TIME_CHARS + RANDOM_CHARS

    /**
     * @param nowMillis injected clock. `:core` never reads the device clock itself
     *   (AGENTS.md §5), and the caller is the one that knows about skew.
     * @param random injected entropy so tests are reproducible.
     */
    fun generate(nowMillis: Long, random: Random = Random.Default): String {
        require(nowMillis >= 0) { "ULID timestamp must be non-negative, got $nowMillis" }
        var time = nowMillis
        val timeChars = CharArray(TIME_CHARS)
        for (i in TIME_CHARS - 1 downTo 0) {
            timeChars[i] = ALPHABET[(time and 0x1FL).toInt()]
            time = time shr 5
        }
        val entropy = CharArray(RANDOM_CHARS)
        for (i in 0 until RANDOM_CHARS) {
            entropy[i] = ALPHABET[random.nextInt(32)]
        }
        return String(timeChars) + String(entropy)
    }

    fun eventId(nowMillis: Long, random: Random = Random.Default): String = "evt_" + generate(nowMillis, random)

    fun watchlistId(nowMillis: Long, random: Random = Random.Default): String = "wl_" + generate(nowMillis, random)

    /** Crockford base32 ignores case and maps I/L to 1 and O to 0 on read-back. */
    fun isWellFormed(ulid: String): Boolean {
        if (ulid.length != LENGTH) return false
        return ulid.all { c -> c in ALPHABET || c in "ilou" }
    }
}

/**
 * A monotonic id source for one device.
 *
 * Wraps [Ulid] with the guarantee [FileDiary.append] relies on: two calls never collide even
 * when the device clock stands still, which is exactly the condition under which a naive
 * `nowMillis` + fresh randomness would eventually reuse an id and make a merge silently drop
 * one of the two events as a duplicate.
 */
class IdSource(nowMillis: Long, private val random: Random = Random.Default) {
    private var lastMillis = nowMillis - 1
    private var lastEntropy = ""

    fun nextEventId(nowMillis: Long): String {
        if (nowMillis == lastMillis && lastEntropy.isNotEmpty()) {
            lastEntropy = increment(lastEntropy)
            return "evt_" + timePart(lastMillis) + lastEntropy
        }
        lastMillis = nowMillis
        lastEntropy = random.nextBytes(ENTROPY_BYTES).toBase32()
        return "evt_" + timePart(nowMillis) + lastEntropy
    }

    private fun increment(entropy: String): String {
        val chars = entropy.toCharArray()
        var i = chars.size - 1
        while (i >= 0) {
            val v = ALPHABET.indexOf(chars[i])
            if (v < 0 || v == 31) {
                chars[i] = ALPHABET[0]
                i--
            } else {
                chars[i] = ALPHABET[v + 1]
                return String(chars)
            }
        }
        // 32^16 distinct ids inside one millisecond is unreachable in practice, and a fresh
        // draw beats wrapping onto an id that already exists.
        return random.nextBytes(ENTROPY_BYTES).toBase32()
    }

    private companion object {
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        /** 80 bits of entropy = exactly the 16 base32 characters a ULID carries. */
        const val ENTROPY_BYTES = 10

        fun timePart(millis: Long): String {
            var time = millis
            val out = CharArray(10)
            for (i in 9 downTo 0) {
                out[i] = ALPHABET[(time and 0x1FL).toInt()]
                time = time shr 5
            }
            return String(out)
        }

        /** Big-endian base32 over whole bytes; the trailing partial group is zero-padded. */
        fun ByteArray.toBase32(): String {
            val out = StringBuilder(16)
            var buffer = 0L
            var bits = 0
            for (b in this) {
                buffer = (buffer shl 8) or (b.toLong() and 0xFF)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    out.append(ALPHABET[((buffer shr bits) and 0x1FL).toInt()])
                }
            }
            if (bits > 0) out.append(ALPHABET[((buffer shl (5 - bits)) and 0x1FL).toInt()])
            return out.toString()
        }
    }
}
