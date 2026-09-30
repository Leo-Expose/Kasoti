package dev.kasoti.evalmetrics

/**
 * A minimal JSON value tree for harness output.
 *
 * `:core` has no dependencies (AGENTS.md §2) and the eval harness has to write `metrics.json`
 * (EVAL.md §3). This exists so the harness output is produced by code that is unit-tested
 * against hostile strings, rather than by string concatenation in a CLI `main`.
 *
 * Scope is RFC 8259: objects, arrays, strings with escapes, numbers, booleans, null. No
 * comments, no trailing commas, no unquoted keys, no `NaN`/`Infinity` — the reader rejects
 * all of those, because a run directory that two tools parse differently is a run directory
 * whose numbers cannot be compared (EVAL.md §1).
 *
 * Numbers are held as [Double] and written in the shortest exact form: an integral value
 * below 2^53 is emitted without a decimal point, so a counter reads `50` and not `50.0`.
 * That is exact for every corpus size in EVAL.md §2.
 */
sealed interface MetricJson {
    data object Null : MetricJson

    data class Bool(val value: Boolean) : MetricJson

    data class Num(val value: Double) : MetricJson

    data class Str(val value: String) : MetricJson

    data class Arr(val items: List<MetricJson>) : MetricJson

    /** Field order is the insertion order, so a run's `metrics.json` diffs cleanly. */
    data class Obj(val fields: Map<String, MetricJson>) : MetricJson
}

/** Thrown for input that is not well-formed JSON. [offset] is a code-unit index. */
class MetricJsonException(message: String, val offset: Int) :
    IllegalArgumentException("$message (offset $offset)")

/** Convenience builders, so call sites read as data rather than as constructors. */
fun jsonOf(vararg pairs: Pair<String, MetricJson>): MetricJson.Obj =
    MetricJson.Obj(linkedMapOf(*pairs))

fun jsonArrayOf(items: List<MetricJson>): MetricJson.Arr = MetricJson.Arr(items)

/**
 * Serialises a [MetricJson] tree to text.
 *
 * Escaping is done per code unit with no regular expressions: the required escapes are the
 * two structural characters plus the five short forms, and every other C0 control character
 * goes out as `\u00XX`. Everything at or above U+0020 is emitted verbatim, so non-Latin
 * operator names and emoji survive a round trip instead of being mangled into `\u` soup.
 */
object MetricJsonWriter {

    fun write(value: MetricJson): String = buildString { emit(value, this) }

    /** A JSON string literal, quotes included. */
    fun quote(text: String): String = buildString { emitString(text, this) }

    private fun emit(value: MetricJson, out: StringBuilder) {
        when (value) {
            is MetricJson.Null -> out.append("null")
            is MetricJson.Bool -> out.append(if (value.value) "true" else "false")
            is MetricJson.Num -> out.append(number(value.value))
            is MetricJson.Str -> emitString(value.value, out)
            is MetricJson.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    emit(item, out)
                }
                out.append(']')
            }

            is MetricJson.Obj -> {
                out.append('{')
                var first = true
                for ((key, item) in value.fields) {
                    if (!first) out.append(',')
                    first = false
                    emitString(key, out)
                    out.append(':')
                    emit(item, out)
                }
                out.append('}')
            }
        }
    }

    private fun emitString(text: String, out: StringBuilder) {
        out.append('"')
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch == '\b' -> out.append("\\b")
                ch == '\u000C' -> out.append("\\f")
                ch < ' ' -> out.append(unicodeEscape(ch.code))
                else -> out.append(ch)
            }
            i++
        }
        out.append('"')
    }

    private fun unicodeEscape(code: Int): String = "\\u" + code.toString(16).padStart(4, '0')

    /** Shortest exact text: integral values lose the fraction, everything else round-trips. */
    private fun number(value: Double): String {
        require(value.isFinite()) { "JSON cannot represent $value" }
        return if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 9.007199254740992E15) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }
}

/**
 * Strict recursive-descent reader, the inverse of [MetricJsonWriter].
 *
 * Rejects leading zeroes, bare `+`, a missing integer part, hexadecimal floats and trailing
 * content, because each of those is a place where two readers can disagree about a byte
 * offset in a run manifest.
 */
object MetricJsonReader {

    fun parse(text: String): MetricJson {
        val cursor = Cursor(text)
        cursor.skipWhitespace()
        val value = cursor.value()
        cursor.skipWhitespace()
        if (!cursor.atEnd()) cursor.fail("trailing content after the top-level value")
        return value
    }

    /** @return the parsed tree, or `null` when the text is not well-formed. */
    fun parseOrNull(text: String): MetricJson? = try {
        parse(text)
    } catch (_: MetricJsonException) {
        null
    }

    private class Cursor(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun fail(message: String): Nothing = throw MetricJsonException(message, index)

        fun skipWhitespace() {
            while (index < text.length) {
                when (text[index]) {
                    ' ', '\t', '\n', '\r' -> index++
                    else -> return
                }
            }
        }

        fun value(): MetricJson {
            if (atEnd()) fail("expected a value")
            return when (text[index]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> MetricJson.Str(string())
                't' -> literal("true", MetricJson.Bool(true))
                'f' -> literal("false", MetricJson.Bool(false))
                'n' -> literal("null", MetricJson.Null)
                else -> number()
            }
        }

        private fun obj(): MetricJson {
            index++ // '{'
            val fields = LinkedHashMap<String, MetricJson>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return MetricJson.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("object keys must be quoted strings")
                val key = string()
                skipWhitespace()
                if (peek() != ':') fail("expected ':' after the key")
                index++
                skipWhitespace()
                fields[key] = value()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return MetricJson.Obj(fields)
                    }

                    else -> fail("expected ',' or '}' in an object")
                }
            }
        }

        private fun arr(): MetricJson {
            index++ // '['
            val items = ArrayList<MetricJson>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return MetricJson.Arr(items)
            }
            while (true) {
                skipWhitespace()
                items += value()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return MetricJson.Arr(items)
                    }

                    else -> fail("expected ',' or ']' in an array")
                }
            }
        }

        private fun peek(): Char = if (atEnd()) fail("unexpected end of input") else text[index]

        private fun literal(word: String, value: MetricJson): MetricJson {
            if (!text.startsWith(word, index)) fail("expected $word")
            index += word.length
            return value
        }

        private fun string(): String {
            index++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                when (val ch = text[index]) {
                    '"' -> {
                        index++
                        return out.toString()
                    }

                    '\\' -> {
                        index++
                        out.append(escape())
                    }

                    else -> {
                        if (ch < ' ') fail("unescaped control character in a string")
                        out.append(ch)
                        index++
                    }
                }
            }
        }

        private fun escape(): Char {
            if (atEnd()) fail("unterminated escape")
            val ch = text[index]
            index++
            return when (ch) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> unicodeEscape()
                else -> {
                    index--
                    fail("unsupported escape '\\$ch'")
                }
            }
        }

        private fun unicodeEscape(): Char {
            if (index + 4 > text.length) fail("truncated \\u escape")
            val digits = text.substring(index, index + 4)
            val code = digits.toIntOrNull(radix = 16) ?: fail("bad hex in \\u escape")
            index += 4
            return code.toChar()
        }

        private fun number(): MetricJson {
            val start = index
            if (peek() == '-') index++
            integerPart()
            if (!atEnd() && text[index] == '.') {
                index++
                fractionPart()
            }
            if (!atEnd() && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (!atEnd() && (text[index] == '+' || text[index] == '-')) index++
                exponentPart()
            }
            val text0 = text.substring(start, index)
            val value = text0.toDoubleOrNull() ?: fail("not a number: $text0")
            return MetricJson.Num(value)
        }

        private fun integerPart() {
            if (atEnd() || text[index] !in '0'..'9') fail("expected a digit")
            if (text[index] == '0') {
                index++
                if (!atEnd() && text[index] in '0'..'9') fail("leading zero in a number")
                return
            }
            while (!atEnd() && text[index] in '0'..'9') index++
        }

        private fun fractionPart() {
            if (atEnd() || text[index] !in '0'..'9') fail("expected a digit after '.'")
            while (!atEnd() && text[index] in '0'..'9') index++
        }

        private fun exponentPart() {
            if (atEnd() || text[index] !in '0'..'9') fail("expected a digit in the exponent")
            while (!atEnd() && text[index] in '0'..'9') index++
        }
    }
}

// --- typed accessors ---------------------------------------------------------------------

fun MetricJson.Obj.opt(key: String): MetricJson? = fields[key]

fun MetricJson.Obj.string(key: String): String? = (fields[key] as? MetricJson.Str)?.value

fun MetricJson.Obj.bool(key: String): Boolean? = (fields[key] as? MetricJson.Bool)?.value

fun MetricJson.Obj.number(key: String): Double? = (fields[key] as? MetricJson.Num)?.value

fun MetricJson.Obj.array(key: String): List<MetricJson>? = (fields[key] as? MetricJson.Arr)?.items

fun MetricJson.Obj.obj(key: String): MetricJson.Obj? = fields[key] as? MetricJson.Obj
