package dev.kasoti.desktop

import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * `evt_<ulid>` / `case_<ulid>` identifiers (AGENTS.md §2).
 *
 * A ULID rather than a counter, for the reason AGENTS.md gives: a sequential id alone is
 * guessable and, worse, two post machines each starting at 1 produce colliding ids. The
 * 48-bit millisecond prefix plus 80 bits of [SecureRandom] makes that impossible in
 * practice, and the time prefix keeps the ids roughly sortable — which matters when an
 * investigator is reading a case list in a hurry.
 *
 * Crockford base32: no `I`, `L`, `O` or `U`, so an id read aloud or copied off a screen
 * does not acquire a different value on the way.
 */
object Ids {

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val ULID_LENGTH = 26

    private val random = SecureRandom()

    fun ulid(atMillis: Long = System.currentTimeMillis()): String {
        val out = CharArray(ULID_LENGTH)
        var timestamp = atMillis
        for (i in TIME_CHARS - 1 downTo 0) {
            out[i] = ALPHABET[(timestamp and 0x1F).toInt()]
            timestamp = timestamp shr 5
        }
        val entropy = ByteArray(ENTROPY_BYTES)
        random.nextBytes(entropy)
        var bitBuffer = 0L
        var bitCount = 0
        var outIndex = TIME_CHARS
        for (byte in entropy) {
            bitBuffer = (bitBuffer shl 8) or (byte.toLong() and 0xFF)
            bitCount += 8
            while (bitCount >= 5 && outIndex < ULID_LENGTH) {
                out[outIndex++] = ALPHABET[((bitBuffer shr (bitCount - 5)) and 0x1F).toInt()]
                bitCount -= 5
            }
        }
        return String(out)
    }

    fun caseId(atMillis: Long = System.currentTimeMillis()): String = "case_${ulid(atMillis)}"

    fun eventId(atMillis: Long = System.currentTimeMillis()): String = "evt_${ulid(atMillis)}"

    private const val TIME_CHARS = 10
    private const val ENTROPY_BYTES = 10
}

/**
 * The console's clock, injected rather than read at the call site.
 *
 * AGENTS.md §5 forbids `System.currentTimeMillis()` for a security decision without a skew
 * note, and `:core`'s `DateLogic.evaluate` takes `today` from its caller for the same
 * reason. Every timestamp the console writes therefore goes through here, so that one
 * object owns the "where did this time come from" question and can print the answer.
 */
class ConsoleClock(private val source: String = SOURCE_SYSTEM) {

    fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    fun nowIso(): String = iso(this.now())

    fun millis(): Long = now().toEpochMilli()

    /** The provenance line printed next to any decision. */
    fun provenance(): String =
        "$source: ${nowIso()} (skew against the reference post is flagged at sync — SYNC.md §2)"

    fun iso(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)

    companion object {
        const val SOURCE_SYSTEM = "system clock"
    }
}
