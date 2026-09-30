package dev.kasoti.fusion

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Fixed-point rendering for evidence references and harness tables.
 *
 * `String.format` is JVM-only and `Double.toString` spells exponents differently on the two
 * `:core` targets, so neither is usable here. Evidence refs are read by humans during
 * incident review and quoted into tickets, which is why a value like `0.30000001192092896`
 * is unacceptable: the ref has to be greppable and stable across platforms.
 */
object NumberFormat {

    /** @param decimals digits after the point, 0..9. */
    fun fixed(value: Double, decimals: Int = 3): String {
        require(decimals in 0..9) { "decimals out of range: $decimals" }
        require(value.isFinite()) { "cannot render a non-finite value: $value" }
        val scale = 10.0.pow(decimals).toLong()
        val scaled = (abs(value) * scale).roundToLong()
        val whole = scaled / scale
        val fraction = if (decimals == 0) {
            ""
        } else {
            "." + (scaled % scale).toString().padStart(decimals, '0')
        }
        return (if (value < 0) "-" else "") + whole + fraction
    }

    /** Percentage with one decimal — the form every table in EVAL.md uses. */
    fun percent(ratio: Double, decimals: Int = 1): String =
        if (!ratio.isFinite()) "n/a" else fixed(ratio * 100.0, decimals) + "%"
}
