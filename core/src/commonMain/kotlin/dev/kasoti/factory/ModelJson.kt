package dev.kasoti.factory

/**
 * A minimal JSON reader, written here rather than taken from a library.
 *
 * ## Why
 *
 * `:core` has exactly one dependency (`kotlinx-coroutines-core`) and this file is the reason that
 * is still true. The model artefact `svm_print_v1.json` is a flat list of numbers, and adding a
 * JSON library to read it would be a new dependency in the module that AGENTS.md §5 protects
 * hardest, on a platform both apps must build, to parse a shape that is seven fields and two
 * arrays. A 150-line reader is cheaper than the dependency and it lets the reader *refuse*
 * precisely rather than approximately — see [JsonValue.asObjectOrNull] and the strictness notes on
 * [JsonReader].
 *
 * ## What it accepts
 *
 * Strict JSON: no trailing commas, no comments, no unquoted keys, no single quotes, no `NaN` or
 * `Infinity`. Every one of those is accepted by some parser in the wild and by none of the two
 * implementations of this format on either side of the trainer, so accepting any of them would
 * mean a file this reads and a file `train_svm.py` does not are the same file. Unknown fields are
 * **kept**, not ignored — see [JsonObject.get] — because a model file that gains a field the
 * loader has not been taught about is a fact the loader needs to be able to report.
 *
 * Number parsing goes through [Double.toDouble] via [JsonNumber] and is then narrowed by the
 * caller, so a value that is finite as a `Double` but out of range for a `Float` becomes the
 * caller's problem to name rather than a silent infinity.
 */
internal sealed interface JsonValue {

    /** A JSON string, already unescaped. */
    @JvmInline
    value class Str(val value: String) : JsonValue

    @JvmInline
    value class Num(val value: Double) : JsonValue

    @JvmInline
    value class Bool(val value: Boolean) : JsonValue

    @JvmInline
    value class Arr(val items: List<JsonValue>) : JsonValue

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue

    val asObjectOrNull: Obj? get() = this as? Obj
    val asArrayOrNull: Arr? get() = this as? Arr
    val asStringOrNull: String? get() = (this as? Str)?.value
    val asNumberOrNull: Double? get() = (this as? Num)?.value
}

/** Thrown for input that is not strict JSON. Carries the offset so a bad file is diagnosable. */
class JsonSyntaxException(message: String, val offset: Int) :
    IllegalArgumentException("$message (at offset $offset)")

internal object Json {

    /**
     * @param maxDepth guards against a pathological or hostile file nesting arrays until the
     *   stack runs out. 32 is far beyond anything a model file needs — the real file nests twice.
     */
    fun parse(text: String, maxDepth: Int = 32): JsonValue {
        val reader = JsonReader(text, maxDepth)
        reader.skipWhitespace()
        val value = reader.readValue(0)
        reader.skipWhitespace()
        if (!reader.atEnd()) {
            reader.fail("trailing content after the top-level value")
        }
        return value
    }
}

private class JsonReader(private val text: String, private val maxDepth: Int) {

    private var index = 0

    fun atEnd(): Boolean = index >= text.length

    fun fail(message: String): Nothing = throw JsonSyntaxException(message, index)

    fun skipWhitespace() {
        while (index < text.length) {
            when (text[index]) {
                ' ', '\t', '\n', '\r' -> index++
                else -> return
            }
        }
    }

    fun readValue(depth: Int): JsonValue {
        if (depth > maxDepth) fail("nesting deeper than $maxDepth")
        skipWhitespace()
        if (atEnd()) fail("unexpected end of input")
        return when (val c = text[index]) {
            '{' -> readObject(depth)
            '[' -> readArray(depth)
            '"' -> JsonValue.Str(readString())
            't' -> readLiteral("true", JsonValue.Bool(true))
            'f' -> readLiteral("false", JsonValue.Bool(false))
            'n' -> readLiteral("null", NullValue)
            else ->
                if (c == '-' || c in '0'..'9') readNumber()
                else fail("unexpected character '$c'")
        }
    }

    private fun readObject(depth: Int): JsonValue {
        expect('{')
        // LinkedHashMap so a diagnostic that lists the keys the file *has* is in file order.
        val fields = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            return JsonValue.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("object key must be a quoted string")
            val key = readString()
            skipWhitespace()
            expect(':')
            val previous = fields.put(key, readValue(depth + 1))
            if (previous != null) fail("duplicate key '$key'")
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    return JsonValue.Obj(fields)
                }
                else -> fail("expected ',' or '}' in an object")
            }
        }
    }

    private fun readArray(depth: Int): JsonValue {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            return JsonValue.Arr(items)
        }
        while (true) {
            items += readValue(depth + 1)
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    return JsonValue.Arr(items)
                }
                else -> fail("expected ',' or ']' in an array")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            if (atEnd()) fail("unterminated string")
            when (val c = text[index++]) {
                '"' -> return out.toString()
                '\\' -> out.append(readEscape())
                else -> {
                    // Control characters are not allowed unescaped in JSON. Accepting them would
                    // make a file readable here and unreadable by the trainer.
                    if (c < ' ') fail("unescaped control character in a string")
                    out.append(c)
                }
            }
        }
    }

    private fun readEscape(): Char {
        if (atEnd()) fail("unterminated escape")
        return when (val c = text[index++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> ''
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (index + 4 > text.length) fail("truncated \\u escape")
                val hex = text.substring(index, index + 4)
                val code = hex.toIntOrNull(16) ?: fail("'$hex' is not a hex escape")
                index += 4
                code.toChar()
            }
            else -> fail("unknown escape '\\$c'")
        }
    }

    private fun readNumber(): JsonValue {
        val start = index
        if (peek() == '-') index++
        var digits = 0
        while (!atEnd() && text[index] in '0'..'9') {
            index++
            digits++
        }
        if (digits == 0) fail("a number needs at least one digit")
        if (!atEnd() && text[index] == '.') {
            index++
            var fraction = 0
            while (!atEnd() && text[index] in '0'..'9') {
                index++
                fraction++
            }
            if (fraction == 0) fail("a '.' needs a digit after it")
        }
        if (!atEnd() && (text[index] == 'e' || text[index] == 'E')) {
            index++
            if (!atEnd() && (text[index] == '+' || text[index] == '-')) index++
            var exponent = 0
            while (!atEnd() && text[index] in '0'..'9') {
                index++
                exponent++
            }
            if (exponent == 0) fail("an exponent needs a digit")
        }
        val slice = text.substring(start, index)
        // `NaN` and `Infinity` are not JSON and `toDouble` on the slice rejects both, which is
        // what we want: a weight of NaN would produce a model that scores silently and wrongly.
        return JsonValue.Num(slice.toDoubleOrNull() ?: fail("'$slice' is not a number"))
    }

    private fun readLiteral(literal: String, value: JsonValue): JsonValue {
        if (!text.startsWith(literal, index)) fail("expected '$literal'")
        index += literal.length
        return value
    }

    private fun peek(): Char {
        if (atEnd()) fail("unexpected end of input")
        return text[index]
    }

    private fun expect(c: Char) {
        skipWhitespace()
        if (atEnd() || text[index] != c) fail("expected '$c'")
        index++
    }
}

/** JSON `null`. Distinct from a missing field, which [SvmModelReader] reports as a read error. */
internal object NullValue : JsonValue
