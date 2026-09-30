package dev.kasoti.json

/**
 * A minimal JSON value model.
 *
 * `:core` is dependency-free (AGENTS.md §2) and the diary + sync wire formats are both
 * JSON (DESIGN.md §4, SYNC.md §3), so the project needs *some* JSON. Rather than adopt a
 * library for the handful of shapes we emit, this is a hand-written value tree plus a
 * recursive-descent parser ([JsonParser]) and a canonical writer ([CanonicalJson]).
 *
 * The scope is deliberately the whole of RFC 8259 for reading — escapes, integers, floats,
 * booleans, null, nested objects and arrays — and *nothing* else: no comments, no trailing
 * commas, no unquoted keys, no `NaN`/`Infinity`. Being strict is a security property here,
 * not a limitation, because a bundle that parses two different ways on two devices is a
 * bundle whose HMAC means two different things (SYNC.md §2).
 *
 * Two node types are worth calling out:
 *  - [F32] exists because binary32 has no exact decimal form. Canonical output uses the
 *    shortest text that round-trips through `Float`, which keeps vectors small and stable.
 *  - [Verbatim] lets already-canonical text be re-embedded without a reparse. It is a
 *    performance escape hatch and a foot-gun; prefer ordinary nodes unless you are splicing
 *    a canonical payload together.
 */
sealed interface JsonValue {

    data object Null : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    /** An integral JSON number, held as `Long` so big sequence numbers stay exact. */
    data class Num(val value: Long) : JsonValue

    /** A non-integral JSON number. Rejects NaN/±Infinity, which JSON cannot express. */
    data class Real(val value: Double) : JsonValue

    data class Str(val value: String) : JsonValue

    /**
     * A binary32 value.
     *
     * Canonical text is the shortest decimal that round-trips through `Float`. This is exact
     * on every target KASOTI ships (JVM and Android are both IEEE-754 binary32); it assumes
     * that, which is why [Num]/[Real] remain the types used for anything protocol-defined.
     */
    data class F32(val value: Float) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    /** Fields are keyed by `String`; the canonical writer sorts them by code unit. */
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue

    /**
     * Already-canonical JSON text, emitted byte-for-byte.
     *
     * Only ever construct this from [CanonicalJson.write] output (or from text you generated
     * yourself with it). Text that is *not* canonical will make a containing payload
     * non-canonical while still looking fine in a diff, which is exactly the class of bug
     * the canonical form exists to prevent.
     */
    data class Verbatim(val text: String) : JsonValue
}

/** Thrown for any input that is not well-formed JSON. Carries the byte offset for triage. */
class JsonSyntaxException(message: String, val offset: Int) :
    IllegalArgumentException("$message (offset $offset)")

// --- typed accessors -------------------------------------------------------------------
//
// Every accessor returns `null` for "absent or wrong type". A merge must not throw because
// one record in a 100k bundle has `"seq"` as a string — it must count it as rejected and
// carry on (SYNC.md §4).

fun JsonValue.Obj.string(key: String): String? = (fields[key] as? JsonValue.Str)?.value

fun JsonValue.Obj.number(key: String): Double? = when (val v = fields[key]) {
    is JsonValue.Num -> v.value.toDouble()
    is JsonValue.Real -> v.value
    is JsonValue.F32 -> v.value.toDouble()
    else -> null
}

fun JsonValue.Obj.float(key: String): Float? = when (val v = fields[key]) {
    is JsonValue.Num -> v.value.toFloat()
    is JsonValue.Real -> v.value.toFloat()
    is JsonValue.F32 -> v.value
    else -> null
}

fun JsonValue.Obj.long(key: String): Long? = when (val v = fields[key]) {
    is JsonValue.Num -> v.value
    // A `2.0` where an integer belongs is accepted: JSON has one number type and a sender
    // that lost its integer-ness must not be able to break the replay guard (invariant I2).
    is JsonValue.Real -> if (v.value == Math.floor(v.value) && v.value.isFinite()) v.value.toLong() else null
    is JsonValue.F32 -> {
        val d = v.value.toDouble()
        if (d == Math.floor(d) && d.isFinite()) d.toLong() else null
    }
    else -> null
}

fun JsonValue.Obj.array(key: String): List<JsonValue>? = (fields[key] as? JsonValue.Arr)?.items

fun JsonValue.Obj.obj(key: String): JsonValue.Obj? = fields[key] as? JsonValue.Obj

/** Convenience for building canonical objects inline. */
fun jsonObj(vararg pairs: Pair<String, JsonValue>): JsonValue.Obj =
    JsonValue.Obj(linkedMapOf(*pairs))

fun jsonArr(vararg items: JsonValue): JsonValue.Arr = JsonValue.Arr(items.toList())
