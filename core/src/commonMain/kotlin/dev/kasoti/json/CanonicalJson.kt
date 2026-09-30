package dev.kasoti.json

/**
 * The canonical JSON form: UTF-8, object keys sorted, no insignificant whitespace.
 *
 * This is the single serialisation that KASOTI hashes and signs (SYNC.md §2). Two devices
 * that see byte-identical data must produce byte-identical text, otherwise the sync HMAC and
 * the audit chain hash are computed over something the other device never sees, and every
 * comparison fails for reasons no one can debug from a field report.
 *
 * Rules, all of which are observable and therefore testable:
 *  - Object keys are sorted by UTF-16 code unit ([String.compareTo]), the ordering JCS/RFC 8785
 *    mandates. Kotlin's natural string ordering *is* code-unit ordering, so `toSortedMap()`
 *    is the whole rule — there is no locale-sensitive step to get wrong.
 *  - Array order is preserved; it is data, not presentation.
 *  - Only `"`, `\` and `U+0000..U+001F` are escaped (`\b \f \n \r \t`, else `\u00XX`). `/` is
 *    left alone and non-ASCII is emitted as raw UTF-8, per SYNC.md's "UTF-8" wording.
 *  - [JsonValue.F32] prints the shortest text that round-trips through binary32.
 *  - [JsonValue.Real] prints the shortest round-tripping double.
 *  - [JsonValue.Verbatim] is copied through unchanged; see its KDoc for the obligation that
 *    puts on the caller.
 *
 * `dev.kasoti.audit` carries its own hand-built canonical string for decision records. That
 * duplication is deliberate and out of scope here: the audit chain predates this writer and
 * changing its byte layout would invalidate every stored chain hash.
 */
object CanonicalJson {

    /** @return the canonical text for [value]. */
    fun write(value: JsonValue): String = buildString { appendTo(this, value) }

    /** @return the canonical UTF-8 bytes for [value] — what actually gets hashed or signed. */
    fun writeUtf8(value: JsonValue): ByteArray = write(value).toByteArray(Charsets.UTF_8)

    private fun appendTo(out: StringBuilder, value: JsonValue) {
        when (value) {
            is JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            is JsonValue.Num -> out.append(value.value.toString())
            is JsonValue.Real -> out.append(doubleText(value.value))
            is JsonValue.F32 -> out.append(value.value.toString())
            is JsonValue.Str -> appendString(out, value.value)
            is JsonValue.Verbatim -> out.append(value.text)
            is JsonValue.Arr -> {
                out.append('[')
                for ((index, item) in value.items.withIndex()) {
                    if (index > 0) out.append(',')
                    appendTo(out, item)
                }
                out.append(']')
            }
            is JsonValue.Obj -> {
                out.append('{')
                var first = true
                for (key in value.fields.keys.sorted()) {
                    if (!first) out.append(',')
                    first = false
                    appendString(out, key)
                    out.append(':')
                    appendTo(out, value.fields.getValue(key))
                }
                out.append('}')
            }
        }
    }

    private fun appendString(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append(unicodeEscape(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    /** `Double.toString` is already shortest-round-trip; only non-finite needs guarding. */
    private fun doubleText(value: Double): String {
        require(value.isFinite()) { "JSON cannot represent $value" }
        return value.toString()
    }

    private const val HEX = "0123456789abcdef"

    /**
     * Always four hex digits.
     *
     * A short escape such as `\u00` is not a JSON escape at all — it would swallow the next
     * character of the string and turn a NUL into `nul`. Cheap to get wrong, impossible to
     * notice by eye, so it is written out rather than assembled from two nibbles.
     */
    private fun unicodeEscape(code: Int): String = buildString(6) {
        append("\\u")
        for (shift in intArrayOf(12, 8, 4, 0)) append(HEX[(code shr shift) and 0xF])
    }

    /**
     * @return the canonical text of an already-canonical fragment, for embedding without a
     *   reparse. Only pass output of this writer.
     */
    fun verbatim(canonicalText: String): JsonValue = JsonValue.Verbatim(canonicalText)
}
