package dev.kasoti.json

/**
 * A strict recursive-descent JSON parser (RFC 8259).
 *
 * Hand-written rather than regex-based on purpose. Regex JSON parsers are a well-known
 * source of acceptance bugs (trailing commas, `1.`, unterminated escapes, over-greedy
 * number capture), and this parser is the gate that decides whether a sync bundle is
 * well-formed *before* its HMAC is checked — a permissive parser here would let two devices
 * disagree about what a bundle says while both report "verified".
 *
 * Deliberate strictnesses, each of which is a hard rule in SYNC.md rather than a style choice:
 *  - **Duplicate object keys are rejected.** Two values for one key have no defined meaning,
 *    and picking either one is exactly how a "verified" bundle can carry a different payload
 *    from the one that was hashed.
 *  - **Nesting is depth-limited** ([MAX_DEPTH]). Untrusted input is expected here (a bundle
 *    arrives on a USB stick), and unbounded recursion on a crafted `[[[[…` is a stack
 *    overflow on a device that has no stack-overflow guard.
 *  - **NaN / Infinity / leading `+` / leading zeros / trailing commas are rejected.**
 *  - **Raw control characters inside strings are rejected**; they must be escaped.
 *
 * The parser is non-recursive only in the sense that it tracks depth explicitly; there is no
 * iterative fast path, because the shapes KASOTI stores are shallow.
 */
object JsonParser {

    /**
     * Maximum object/array nesting accepted. 64 is far above anything KASOTI writes (the
     * deepest real shape is a record inside `records` inside the envelope, i.e. 3) and far
     * below anything that could exhaust a device stack.
     */
    const val MAX_DEPTH = 64

    /** @throws JsonSyntaxException on any malformed input. */
    fun parse(text: String): JsonValue = Cursor(text).parseDocument()

    /** @return the parsed value, or `null` for malformed input. Prefer over [parse] at trust boundaries. */
    fun parseOrNull(text: String): JsonValue? = try {
        parse(text)
    } catch (_: JsonSyntaxException) {
        null
    }

    /** @return the parsed object, or `null` if [text] is malformed or is not an object. */
    fun parseObjectOrNull(text: String): JsonValue.Obj? = parseOrNull(text) as? JsonValue.Obj

    private class Cursor(private val text: String) {
        private var i = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = parseValue(0)
            skipWhitespace()
            if (i != text.length) fail("trailing content after the top-level value")
            return value
        }

        private fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("nesting deeper than $MAX_DEPTH")
            skipWhitespace()
            if (i >= text.length) fail("unexpected end of input")
            return when (val c = text[i]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> JsonValue.Str(parseString())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (c == '-' || c in '0'..'9') parseNumber() else fail("unexpected character '$c'")
            }
        }

        private fun parseObject(depth: Int): JsonValue.Obj {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                i++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("object keys must be quoted strings")
                val key = parseString()
                if (fields.containsKey(key)) fail("duplicate object key '$key'")
                skipWhitespace()
                expect(':')
                fields[key] = parseValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return JsonValue.Obj(fields)
                    }
                    else -> fail("expected ',' or '}' in object")
                }
            }
        }

        private fun parseArray(depth: Int): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                i++
                return JsonValue.Arr(items)
            }
            while (true) {
                items += parseValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return JsonValue.Arr(items)
                    }
                    else -> fail("expected ',' or ']' in array")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= text.length) fail("unterminated string")
                when (val c = text[i]) {
                    '"' -> {
                        i++
                        return sb.toString()
                    }
                    '\\' -> {
                        i++
                        if (i >= text.length) fail("unterminated escape")
                        when (val e = text[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> sb.append(parseUnicodeEscape())
                            else -> fail("invalid escape '\\$e'")
                        }
                        i++
                    }
                    else -> {
                        if (c < ' ') fail("raw control character U+%04X in string".format(c.code))
                        sb.append(c)
                        i++
                    }
                }
            }
        }

        private fun parseUnicodeEscape(): Char {
            if (i + 4 >= text.length) fail("truncated \\u escape")
            var value = 0
            for (k in 1..4) {
                val d = hexDigit(text[i + k])
                value = (value shl 4) or d
            }
            i += 4
            // Surrogates are not validated here: a well-formed string is a sequence of UTF-16
            // code units, and a lone surrogate is later rejected by UTF-8 encoding on the wire.
            return value.toChar()
        }

        private fun parseNumber(): JsonValue {
            val start = i
            if (peek() == '-') i++
            // int part: '0' alone, or a non-zero digit followed by digits.
            if (i >= text.length) fail("truncated number")
            if (text[i] == '0') {
                i++
            } else if (text[i] in '1'..'9') {
                while (i < text.length && text[i] in '0'..'9') i++
            } else {
                fail("number must start with a digit")
            }
            var integral = true
            if (i < text.length && text[i] == '.') {
                integral = false
                i++
                if (i >= text.length || text[i] !in '0'..'9') fail("digit required after '.'")
                while (i < text.length && text[i] in '0'..'9') i++
            }
            if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
                integral = false
                i++
                if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
                if (i >= text.length || text[i] !in '0'..'9') fail("digit required in exponent")
                while (i < text.length && text[i] in '0'..'9') i++
            }
            val raw = text.substring(start, i)
            return if (integral) {
                raw.toLongOrNull()?.let { JsonValue.Num(it) } ?: JsonValue.Real(raw.toDouble())
            } else {
                val d = raw.toDouble()
                if (!d.isFinite()) fail("number '$raw' is not finite")
                JsonValue.Real(d)
            }
        }

        private fun literal(text0: String, value: JsonValue): JsonValue {
            if (!text.startsWith(text0, i)) fail("expected '$text0'")
            i += text0.length
            return value
        }

        private fun peek(): Char = if (i < text.length) text[i] else fail("unexpected end of input")

        private fun expect(c: Char) {
            if (i >= text.length || text[i] != c) fail("expected '$c'")
            i++
        }

        private fun skipWhitespace() {
            while (i < text.length) {
                when (text[i]) {
                    ' ', '\t', '\n', '\r' -> i++
                    else -> return
                }
            }
        }

        private fun fail(message: String): Nothing = throw JsonSyntaxException(message, i)

        private fun hexDigit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> throw JsonSyntaxException("invalid hex digit '$c'", i)
        }
    }
}
