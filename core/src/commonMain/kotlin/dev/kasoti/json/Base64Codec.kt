package dev.kasoti.json

/**
 * Standard (RFC 4648 §4) Base64 with padding.
 *
 * SYNC.md §3 puts face embeddings on the wire as `base64(128×int8)`, which is 172 characters
 * per record. `:core` has no dependencies, so this is the encoder — it is a codec, not
 * cryptography, and it is the reason a QR watchlist pack stays inside its byte budget.
 *
 * Strict by design: [decodeOrNull] rejects non-alphabet characters, embedded whitespace,
 * wrong padding and impossible lengths. A lenient decoder is how a corrupted bundle becomes a
 * plausible-looking vector instead of a rejected file.
 */
object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val PAD = '='

    private val DECODE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
        table[PAD.code] = -2
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(n shr 18) and 0x3F])
            out.append(ALPHABET[(n shr 12) and 0x3F])
            out.append(ALPHABET[(n shr 6) and 0x3F])
            out.append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(n shr 18) and 0x3F])
                out.append(ALPHABET[(n shr 12) and 0x3F])
                out.append(PAD).append(PAD)
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(n shr 18) and 0x3F])
                out.append(ALPHABET[(n shr 12) and 0x3F])
                out.append(ALPHABET[(n shr 6) and 0x3F])
                out.append(PAD)
            }
        }
        return out.toString()
    }

    /** @return the decoded bytes, or `null` if [text] is not canonical padded Base64. */
    fun decodeOrNull(text: String): ByteArray? {
        if (text.isEmpty()) return ByteArray(0)
        if (text.length % 4 != 0) return null
        var padding = 0
        while (padding < 2 && text.length - 1 - padding >= 0 && text[text.length - 1 - padding] == PAD) padding++
        if (padding > 0 && text.length < 4) return null
        val dataChars = text.length - padding
        if (padding == 1 && dataChars % 4 != 3) return null
        if (padding == 2 && dataChars % 4 != 2) return null
        for (index in 0 until dataChars) {
            val c = text[index]
            if (c.code >= 128 || DECODE[c.code] < 0) return null
        }
        val out = ByteArray(text.length / 4 * 3 - padding)
        var outIndex = 0
        var buffer = 0
        var bits = 0
        for (index in 0 until dataChars) {
            buffer = (buffer shl 6) or DECODE[text[index].code]
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outIndex++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        // Leftover bits of a well-formed group are zero; non-zero means a non-canonical tail.
        if (bits > 0 && (buffer and ((1 shl bits) - 1)) != 0) return null
        return out
    }
}
